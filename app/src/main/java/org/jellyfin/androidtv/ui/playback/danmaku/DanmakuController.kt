package org.jellyfin.androidtv.ui.playback.danmaku

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import android.app.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.DialogDanmakuSettingsBinding
import org.jellyfin.androidtv.util.toast
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

/**
 * 弹幕功能编排：匹配、拉取、过滤处理、设置菜单。
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class DanmakuController(
    private val context: Context,
    private val danmakuView: DanmakuView,
    private val scope: CoroutineScope,
) : KoinComponent {

    private val preferences: DanmakuPreferences by inject()
    private val client: DandanplayClient by inject()
    private val apiClient: ApiClient = get()

    private var currentItemId: UUID? = null
    private var currentItem: BaseItemDto? = null
    private var animeKey: String? = null
    private var episodeKey: String? = null
    private var episodeIndex: Int = 1
    private var rawComments: List<DanmakuComment> = emptyList()
    private var currentMatch: SavedDanmakuMatch? = null
    private var loadJob: Job? = null
    private var settingsDialog: Dialog? = null

    init {
        applyViewConfig()
        danmakuView.danmakuVisible = preferences.enabled
    }

    // ---- 生命周期入口 ----

    /**
     * Android TV 版使用 [BaseItemDto] 作为当前播放项
     */
    fun onMediaItemChanged(item: BaseItemDto?) {
        if (item == null) return
        val newItemId = item.id
        if (newItemId == currentItemId) return

        loadJob?.cancel()
        currentItemId = newItemId
        currentItem = item
        rawComments = emptyList()
        currentMatch = null
        danmakuView.clear()

        animeKey = (item.seasonId ?: item.id).toString()
        episodeIndex = item.indexNumber ?: 1
        episodeKey = "${animeKey}_$episodeIndex"

        applyViewConfig()
        danmakuView.danmakuVisible = preferences.enabled

        if (preferences.enabled) {
            reload(showToast = false)
        }
    }

    fun destroy() {
        loadJob?.cancel()
        settingsDialog?.dismiss()
        settingsDialog = null
    }

    // ---- 加载流程 ----

    private fun reload(showToast: Boolean = true) {
        val itemId = currentItemId ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                if (preferences.useXmlDanmaku) {
                    val baseUrl = apiClient.baseUrl
                    if (baseUrl != null) {
                        val xmlComments = client.getPluginXmlComments(
                            serverBaseUrl = baseUrl,
                            itemId = itemId.toString(),
                            accessToken = apiClient.accessToken,
                        )
                        if (!xmlComments.isNullOrEmpty()) {
                            rawComments = xmlComments
                            currentMatch = SavedDanmakuMatch(
                                episodeId = -1,
                                animeTitle = context.getString(R.string.danmaku_source_server_xml),
                                episodeTitle = "",
                            )
                            applyProcessedComments()
                            notifyLoaded()
                            return@launch
                        }
                    }
                }

                val match = autoMatch()
                if (match == null) {
                    if (showToast) context.toast(R.string.danmaku_no_match)
                    return@launch
                }
                currentMatch = match
                rawComments = client.getComments(preferences.apiBaseUrl, match.episodeId, preferences.chConvert)
                applyProcessedComments()
                notifyLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load danmaku")
                if (showToast) context.toast(R.string.danmaku_load_failed)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun autoMatch(): SavedDanmakuMatch? {
        val episodeKey = episodeKey ?: return null
        val animeKey = animeKey ?: return null
        val item = currentItem ?: return null

        preferences.getSavedEpisode(episodeKey)?.let { saved -> return saved }

        val animeName = buildAnimeName(item) ?: return null
        // 回退0：手动匹配过本番后，用记忆的番剧标题再搜一次。
        // 某些标题带季号/别名时按原名搜不到（如"正相反的你和我2"需搜"相反的你"），
        // 手动匹配保存的标题是已验证可搜到的关键词，之后本季剧集都能自动匹配。
        val savedAnime = preferences.getSavedAnime(animeKey)
        var results = client.searchEpisodes(preferences.apiBaseUrl, animeName)
        if (results.isEmpty() && savedAnime != null && savedAnime.second != animeName) {
            results = client.searchEpisodes(preferences.apiBaseUrl, savedAnime.second)
        }

        if (results.isEmpty()) {
            val originalTitle = fetchOriginalTitle(item.seriesId ?: item.id)
            if (!originalTitle.isNullOrBlank() && originalTitle != animeName) {
                results = client.searchEpisodes(preferences.apiBaseUrl, originalTitle)
            }
        }

        val season = item.parentIndexNumber ?: 1
        if (results.isEmpty() || (results.size > 1 && season > 1 && !results.any { anime ->
            matchesSeason(anime.animeTitle, season)
        })) {
            val seriesOnly = item.seriesName ?: item.name
            if (!seriesOnly.isNullOrBlank() && seriesOnly != animeName) {
                val fallbackResults = client.searchEpisodes(preferences.apiBaseUrl, seriesOnly)
                if (fallbackResults.isNotEmpty()) {
                    results = (results.toList() + fallbackResults.toList())
                        .distinctBy { anime -> anime.animeId }
                }
            }
        }

        if (results.isEmpty()) return null

        val animeIdx = savedAnime
            ?.let { (animeId, _) -> results.indexOfFirst { anime -> anime.animeId == animeId } }
            ?.takeIf { idx -> idx >= 0 }
            ?: selectBestAnime(results, item, animeName)
        if (animeIdx == null) return null
        val anime = results[animeIdx]
        if (anime.episodes.isEmpty()) return null

        val standardEps = filterStandardEpisodes(anime.episodes)
        val mainEpisodes = if (standardEps.isNotEmpty()) standardEps else anime.episodes

        // 精确匹配优先：按剧集标题里的集号（第N话）直接匹配当前集号，
        // 避免弹幕库缺集/编号不连续时位置换算偏移（与 Jellyfin 的 SxxExx 集号对齐）
        val exactEpisode = mainEpisodes.firstOrNull { ep ->
            EPISODE_NUMBER_REGEX.find(ep.episodeTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() == episodeIndex
        }
        if (exactEpisode != null) {
            val exactMatch = SavedDanmakuMatch(
                episodeId = exactEpisode.episodeId,
                animeTitle = anime.animeTitle,
                episodeTitle = exactEpisode.episodeTitle,
            )
            preferences.saveEpisode(episodeKey, exactMatch)
            return exactMatch
        }

        // 位置换算回退：弹幕库不从第 1 话开始等情况（与 ede.js 一致）
        val firstStandard = findFirstStandardEpisode(mainEpisodes)
        val initialEp = firstStandard
            ?.let { EPISODE_NUMBER_REGEX.find(it.episodeTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: 1
        val epIdx = if (episodeIndex < initialEp) episodeIndex - 1 else episodeIndex - initialEp
        val episode = mainEpisodes.getOrNull(epIdx) ?: return null

        val match = SavedDanmakuMatch(
            episodeId = episode.episodeId,
            animeTitle = anime.animeTitle,
            episodeTitle = episode.episodeTitle,
        )
        preferences.saveEpisode(episodeKey, match)
        return match
    }

    private fun buildAnimeName(item: BaseItemDto): String? {
        var name = item.seriesName ?: item.name ?: return null
        val season = item.parentIndexNumber
        if (season != null && season > 1) {
            name += " $season"
        }
        return name
    }

    @Suppress("ReturnCount")
    private fun selectBestAnime(
        results: List<DandanplayClient.AnimeResult>,
        item: BaseItemDto,
        searchKey: String,
    ): Int? {
        if (results.size == 1) return 0

        val season = item.parentIndexNumber ?: 1
        val episodeIndex = item.indexNumber ?: 1

        if (season > 1) {
            val seasonMatchIdx = results.indexOfFirst { anime ->
                matchesSeason(anime.animeTitle, season)
            }
            if (seasonMatchIdx >= 0) return seasonMatchIdx
        }

        val scores = results.mapIndexed { idx, anime ->
            val standardEps = filterStandardEpisodes(anime.episodes)
            val allEps = if (standardEps.isNotEmpty()) standardEps else anime.episodes
            val firstEp = findFirstStandardEpisode(allEps)
            val initialEp = firstEp
                ?.let { EPISODE_NUMBER_REGEX.find(it.episodeTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: 1
            val maxEp = initialEp + allEps.size - 1
            val coversEpisode = episodeIndex in initialEp..maxEp

            var score = 100000
            if (coversEpisode) score -= 50000
            if (anime.type == "tvseries") score -= 10000
            if (season == 1 && !hasSeasonIndicator(anime.animeTitle)) score -= 5000

            if (anime.animeTitle.contains(searchKey, ignoreCase = true) ||
                searchKey.contains(anime.animeTitle, ignoreCase = true)) {
                score -= 20000
            }

            score += anime.animeTitle.length
            idx to score
        }

        return scores.minByOrNull { it.second }?.first
    }

    private fun matchesSeason(title: String, season: Int): Boolean {
        val patterns = listOf(
            "第${season}季", "第 ${season} 季", "${season}期", "第${season}期",
            "Season $season", " $season",
        )
        return patterns.any { pattern ->
            title.contains(pattern, ignoreCase = true)
        }
    }

    private fun hasSeasonIndicator(title: String): Boolean {
        return SEASON_INDICATOR_REGEX.containsMatchIn(title)
    }

    private suspend fun fetchOriginalTitle(seriesId: UUID?): String? {
        if (seriesId == null) return null
        return runCatching {
            withContext(Dispatchers.IO) {
                apiClient.userLibraryApi.getItem(itemId = seriesId).content.originalTitle
            }
        }.getOrNull()
    }

    private fun notifyLoaded() {
        val match = currentMatch ?: return
        val title = listOf(match.animeTitle, match.episodeTitle)
            .filter(String::isNotBlank)
            .joinToString(" - ")
        context.toast(context.getString(R.string.danmaku_loaded_toast, rawComments.size, title))
    }

    // ---- 弹幕预处理 ----

    private fun applyProcessedComments() {
        danmakuView.setComments(processComments())
    }

    private fun processComments(): List<DanmakuComment> {
        val sourceFilter = preferences.sourceFilter
        val modeFilter = preferences.modeFilter
        val offset = episodeKey?.let(preferences::getOffset) ?: 0.0

        val seen = HashSet<String>(rawComments.size)
        val filtered = rawComments
            .filter { comment ->
                seen.add("${comment.timeSeconds},${comment.mode},${comment.colorRgb}|${comment.text}") &&
                    !isSourceBlocked(comment.source, sourceFilter) &&
                    !isModeBlocked(comment.mode, modeFilter)
            }
            .sortedBy(DanmakuComment::timeSeconds)

        val densityLimited = when {
            preferences.densityLimit > 0 -> applyDensityLimit(filtered, preferences.densityLimit)
            else -> filtered
        }

        return when {
            offset != 0.0 -> densityLimited.map { comment ->
                comment.copy(timeSeconds = comment.timeSeconds + offset)
            }
            else -> densityLimited
        }
    }

    private fun isSourceBlocked(source: DanmakuSource, filter: Int): Boolean = when (source) {
        DanmakuSource.BILIBILI -> filter and DanmakuPreferences.SOURCE_FILTER_BILIBILI != 0
        DanmakuSource.GAMER -> filter and DanmakuPreferences.SOURCE_FILTER_GAMER != 0
        DanmakuSource.DANDAN -> filter and DanmakuPreferences.SOURCE_FILTER_DANDAN != 0
        DanmakuSource.OTHER -> filter and DanmakuPreferences.SOURCE_FILTER_OTHER != 0
    }

    private fun isModeBlocked(mode: DanmakuMode, filter: Int): Boolean = when (mode) {
        DanmakuMode.BOTTOM -> filter and DanmakuPreferences.MODE_FILTER_BOTTOM != 0
        DanmakuMode.TOP -> filter and DanmakuPreferences.MODE_FILTER_TOP != 0
        DanmakuMode.SCROLL -> filter and DanmakuPreferences.MODE_FILTER_SCROLL != 0
    }

    @Suppress("MagicNumber")
    private fun applyDensityLimit(comments: List<DanmakuComment>, level: Int): List<DanmakuComment> {
        val metrics = context.resources.displayMetrics
        val width = danmakuView.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = danmakuView.height.takeIf { it > 0 } ?: metrics.heightPixels
        val fontPx = preferences.fontSizeSp * metrics.scaledDensity
        val speedPx = preferences.speed * metrics.density

        val durationSec = max(1.0, ceil(width / speedPx.toDouble()))
        val lines = max(1, (height * preferences.heightRatio / (fontPx * 1.35f)).toInt() - 1)
        val scrollLimit = (9 - level * 2) * lines
        val verticalLimit = max(lines - 1, 1)

        val scrollBuckets = HashMap<Long, Int>()
        val verticalBuckets = HashMap<Long, Int>()

        return comments.filter { comment ->
            val bucket = ceil(comment.timeSeconds / durationSec).toLong()
            when (comment.mode) {
                DanmakuMode.SCROLL -> {
                    val count = (scrollBuckets[bucket] ?: 0) + 1
                    scrollBuckets[bucket] = count
                    count <= scrollLimit
                }
                else -> {
                    val count = (verticalBuckets[bucket] ?: 0) + 1
                    verticalBuckets[bucket] = count
                    count <= verticalLimit
                }
            }
        }
    }

    private fun applyViewConfig() {
        danmakuView.applyConfig(
            DanmakuView.Config(
                opacity = preferences.opacity,
                fontSizeSp = preferences.fontSizeSp,
                speedDpPerSecond = preferences.speed,
                heightRatio = preferences.heightRatio,
                antiOverlap = preferences.antiOverlap,
            ),
        )
    }

    // ---- 手动匹配 ----

    private fun showManualMatchDialog() {
        val editText = EditText(context).apply {
            setText(currentItem?.let(::buildAnimeName).orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.danmaku_search_title)
            .setView(editText)
            .setPositiveButton(R.string.danmaku_search_action) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) searchAndSelect(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun searchAndSelect(name: String) {
        scope.launch {
            try {
                val results = client.searchEpisodes(preferences.apiBaseUrl, name)
                if (results.isEmpty()) {
                    context.toast(R.string.danmaku_no_match)
                    return@launch
                }
                showAnimeSelectDialog(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Danmaku search failed")
                context.toast(R.string.danmaku_load_failed)
            }
        }
    }

    private fun showAnimeSelectDialog(results: List<DandanplayClient.AnimeResult>) {
        val titles = results
            .map { anime -> "${anime.animeTitle}（${anime.typeDescription}）" }
            .toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.danmaku_select_anime)
            .setItems(titles) { _, which -> showEpisodeSelectDialog(results[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEpisodeSelectDialog(anime: DandanplayClient.AnimeResult) {
        if (anime.episodes.isEmpty()) {
            context.toast(R.string.danmaku_no_match)
            return
        }
        val titles = anime.episodes.map(DandanplayClient.EpisodeResult::episodeTitle).toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.danmaku_select_episode)
            .setItems(titles) { _, which -> onManualMatchSelected(anime, anime.episodes[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onManualMatchSelected(anime: DandanplayClient.AnimeResult, episode: DandanplayClient.EpisodeResult) {
        val animeKey = animeKey ?: return
        val episodeKey = episodeKey ?: return
        preferences.saveAnime(animeKey, anime.animeId, anime.animeTitle)
        val match = SavedDanmakuMatch(
            episodeId = episode.episodeId,
            animeTitle = anime.animeTitle,
            episodeTitle = episode.episodeTitle,
        )
        preferences.saveEpisode(episodeKey, match)
        currentMatch = match

        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                rawComments = client.getComments(preferences.apiBaseUrl, match.episodeId, preferences.chConvert)
                applyProcessedComments()
                notifyLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load danmaku")
                context.toast(R.string.danmaku_load_failed)
            }
        }
    }

    // ---- 设置菜单 ----

    @SuppressLint("SetTextI18n")
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun showSettings(onDismissed: (() -> Unit)? = null) {
        settingsDialog?.dismiss()

        val binding = DialogDanmakuSettingsBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context)
        dialog.setContentView(binding.root)
        settingsDialog = dialog

        val initialChConvert = preferences.chConvert
        val initialUseXml = preferences.useXmlDanmaku
        val initialCustomApi = preferences.customApiBaseUrl
        val initialSourceFilter = preferences.sourceFilter
        val initialModeFilter = preferences.modeFilter
        val initialDensity = preferences.densityLimit
        val initialOffset = episodeKey?.let(preferences::getOffset) ?: 0.0

        with(binding) {
            danmakuEnabledSwitch.isChecked = preferences.enabled
            danmakuEnabledSwitch.setOnCheckedChangeListener { _, checked ->
                preferences.enabled = checked
                danmakuView.danmakuVisible = checked
                if (checked && rawComments.isEmpty() && currentItemId != null) {
                    reload()
                }
            }

            val match = currentMatch
            danmakuMatchInfo.text = when {
                match != null -> context.getString(
                    R.string.danmaku_match_info,
                    listOf(match.animeTitle, match.episodeTitle).filter(String::isNotBlank).joinToString(" - "),
                )
                else -> context.getString(R.string.danmaku_match_none)
            }
            danmakuMatchButton.setOnClickListener {
                dialog.dismiss()
                showManualMatchDialog()
            }
            danmakuReloadButton.setOnClickListener {
                dialog.dismiss()
                reload()
            }

            danmakuOffsetValue.setText(initialOffset.toString())
            danmakuOffsetMinus.setOnClickListener {
                val value = danmakuOffsetValue.text.toString().toDoubleOrNull() ?: 0.0
                danmakuOffsetValue.setText((value - OFFSET_STEP_SECONDS).toString())
            }
            danmakuOffsetPlus.setOnClickListener {
                val value = danmakuOffsetValue.text.toString().toDoubleOrNull() ?: 0.0
                danmakuOffsetValue.setText((value + OFFSET_STEP_SECONDS).toString())
            }

            setupSeekBar(
                seekBar = danmakuOpacitySeekbar,
                min = MIN_OPACITY_PERCENT,
                max = MAX_PERCENT,
                value = (preferences.opacity * MAX_PERCENT).toInt(),
                updateLabel = { value ->
                    danmakuOpacityLabel.text = context.getString(R.string.danmaku_opacity_label, value)
                },
            ) { value ->
                preferences.opacity = value / MAX_PERCENT.toFloat()
                applyViewConfig()
            }
            setupSeekBar(
                seekBar = danmakuFontSizeSeekbar,
                min = DanmakuPreferences.MIN_FONT_SIZE,
                max = DanmakuPreferences.MAX_FONT_SIZE,
                value = preferences.fontSizeSp,
                updateLabel = { value ->
                    danmakuFontSizeLabel.text = context.getString(R.string.danmaku_font_size_label, value)
                },
            ) { value ->
                preferences.fontSizeSp = value
                applyViewConfig()
            }
            setupSeekBar(
                seekBar = danmakuSpeedSeekbar,
                min = DanmakuPreferences.MIN_SPEED,
                max = DanmakuPreferences.MAX_SPEED,
                value = preferences.speed,
                updateLabel = { value ->
                    danmakuSpeedLabel.text = context.getString(R.string.danmaku_speed_label, value)
                },
            ) { value ->
                preferences.speed = value
                applyViewConfig()
            }
            setupSeekBar(
                seekBar = danmakuHeightSeekbar,
                min = MIN_HEIGHT_PERCENT,
                max = MAX_PERCENT,
                value = (preferences.heightRatio * MAX_PERCENT).toInt(),
                updateLabel = { value ->
                    danmakuHeightLabel.text = context.getString(R.string.danmaku_display_area_label, value)
                },
            ) { value ->
                preferences.heightRatio = value / MAX_PERCENT.toFloat()
                applyViewConfig()
            }

            danmakuModeScroll.isChecked = preferences.modeFilter and DanmakuPreferences.MODE_FILTER_SCROLL == 0
            danmakuModeTop.isChecked = preferences.modeFilter and DanmakuPreferences.MODE_FILTER_TOP == 0
            danmakuModeBottom.isChecked = preferences.modeFilter and DanmakuPreferences.MODE_FILTER_BOTTOM == 0

            danmakuSourceBilibili.isChecked = preferences.sourceFilter and DanmakuPreferences.SOURCE_FILTER_BILIBILI == 0
            danmakuSourceGamer.isChecked = preferences.sourceFilter and DanmakuPreferences.SOURCE_FILTER_GAMER == 0
            danmakuSourceDandan.isChecked = preferences.sourceFilter and DanmakuPreferences.SOURCE_FILTER_DANDAN == 0
            danmakuSourceOther.isChecked = preferences.sourceFilter and DanmakuPreferences.SOURCE_FILTER_OTHER == 0

            val densityButtons = listOf(danmakuDensity0, danmakuDensity1, danmakuDensity2, danmakuDensity3)
            densityButtons.getOrNull(preferences.densityLimit)?.isChecked = true

            val chButtons = listOf(danmakuCh0, danmakuCh1, danmakuCh2)
            chButtons.getOrNull(preferences.chConvert)?.isChecked = true

            danmakuAntiOverlapSwitch.isChecked = preferences.antiOverlap
            danmakuUseXmlSwitch.isChecked = preferences.useXmlDanmaku
            danmakuCustomApiInput.setText(preferences.customApiBaseUrl)

            dialog.setOnDismissListener {
                settingsDialog = null

                var modeFilter = 0
                if (!danmakuModeBottom.isChecked) modeFilter = modeFilter or DanmakuPreferences.MODE_FILTER_BOTTOM
                if (!danmakuModeTop.isChecked) modeFilter = modeFilter or DanmakuPreferences.MODE_FILTER_TOP
                if (!danmakuModeScroll.isChecked) modeFilter = modeFilter or DanmakuPreferences.MODE_FILTER_SCROLL
                preferences.modeFilter = modeFilter

                var sourceFilter = 0
                if (!danmakuSourceBilibili.isChecked) {
                    sourceFilter = sourceFilter or DanmakuPreferences.SOURCE_FILTER_BILIBILI
                }
                if (!danmakuSourceGamer.isChecked) sourceFilter = sourceFilter or DanmakuPreferences.SOURCE_FILTER_GAMER
                if (!danmakuSourceDandan.isChecked) {
                    sourceFilter = sourceFilter or DanmakuPreferences.SOURCE_FILTER_DANDAN
                }
                if (!danmakuSourceOther.isChecked) sourceFilter = sourceFilter or DanmakuPreferences.SOURCE_FILTER_OTHER
                preferences.sourceFilter = sourceFilter

                preferences.densityLimit = densityButtons.indexOfFirst { button -> button.isChecked }.coerceAtLeast(0)
                preferences.chConvert = chButtons.indexOfFirst { button -> button.isChecked }.coerceAtLeast(0)
                preferences.antiOverlap = danmakuAntiOverlapSwitch.isChecked
                preferences.useXmlDanmaku = danmakuUseXmlSwitch.isChecked
                preferences.customApiBaseUrl = danmakuCustomApiInput.text.toString()

                val offset = danmakuOffsetValue.text.toString().toDoubleOrNull() ?: initialOffset
                episodeKey?.let { key -> preferences.setOffset(key, offset) }

                applyViewConfig()

                val needsRefetch = preferences.chConvert != initialChConvert ||
                    preferences.useXmlDanmaku != initialUseXml ||
                    preferences.customApiBaseUrl != initialCustomApi
                val needsReprocess = preferences.sourceFilter != initialSourceFilter ||
                    preferences.modeFilter != initialModeFilter ||
                    preferences.densityLimit != initialDensity ||
                    offset != initialOffset

                when {
                    needsRefetch && preferences.enabled -> reload()
                    needsReprocess && rawComments.isNotEmpty() -> applyProcessedComments()
                }

                onDismissed?.invoke()
            }
        }

        dialog.show()
    }

    private fun setupSeekBar(
        seekBar: SeekBar,
        min: Int,
        max: Int,
        value: Int,
        updateLabel: (Int) -> Unit,
        onValueChanged: (Int) -> Unit,
    ) {
        seekBar.max = max - min
        seekBar.progress = (value - min).coerceIn(0, max - min)
        updateLabel(value)
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    updateLabel(progress + min)
                    if (fromUser) onValueChanged(progress + min)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
    }

    companion object {
        private val EPISODE_NUMBER_REGEX = Regex("""第\s*(\d+)\s*[话話集]""")
        private val SEASON_INDICATOR_REGEX = Regex("""第\s*\d+\s*季|\d+期|Season\s+\d+""", RegexOption.IGNORE_CASE)
        private const val OFFSET_STEP_SECONDS = 0.5
        private const val MAX_PERCENT = 100
        private const val MIN_OPACITY_PERCENT = 10
        private const val MIN_HEIGHT_PERCENT = 10
    }

    private fun findFirstStandardEpisode(episodes: List<DandanplayClient.EpisodeResult>): DandanplayClient.EpisodeResult? {
        return episodes.firstOrNull { ep ->
            EPISODE_NUMBER_REGEX.containsMatchIn(ep.episodeTitle)
        }
    }

    private fun filterStandardEpisodes(episodes: List<DandanplayClient.EpisodeResult>): List<DandanplayClient.EpisodeResult> {
        return episodes.filter { ep ->
            EPISODE_NUMBER_REGEX.containsMatchIn(ep.episodeTitle)
        }
    }
}
