package org.jellyfin.androidtv.ui.playback.danmaku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 御坂弹幕服务（Misaka Danmaku Server）客户端。
 *
 * [apiBaseUrl] 需要指向 misaka 的 `/api/v1/{token}` 根路径。
 * 播放时优先用文件名调用 `POST /api/v2/match`，失败后再按番剧名检索本地弹幕库。
 */
class MisakaClient(okHttpClient: OkHttpClient) {

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    data class AnimeResult(
        val animeId: Long,
        val animeTitle: String,
        val typeDescription: String,
        val type: String,
        val episodes: List<EpisodeResult>,
    )

    data class EpisodeResult(
        val episodeId: Long,
        val episodeTitle: String,
    )

    data class MatchResult(
        val episodeId: Long,
        val animeTitle: String,
        val episodeTitle: String,
    )

    /**
     * 服务版本，用于探活。对应 `GET /api/v2/version`。
     */
    suspend fun getVersion(apiBaseUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val json = requestJson(buildUrl(apiBaseUrl, "/api/v2/version"))
            requireSuccess(json)
            json.optString("version").takeIf(String::isNotBlank)
        }
            .onFailure { e -> Timber.w(e, "Misaka version check failed") }
            .getOrNull()
    }

    /**
     * 按文件名匹配剧集，对应 `POST /api/v2/match`。
     */
    suspend fun match(
        apiBaseUrl: String,
        fileName: String,
        fileSize: Long? = null,
        videoDurationSeconds: Double? = null,
    ): MatchResult? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("fileName", fileName)
            .put("matchMode", "fileNameOnly")
        fileSize?.takeIf { it > 0 }?.let { payload.put("fileSize", it) }
        videoDurationSeconds?.takeIf { it > 0 }?.let { payload.put("videoDuration", it) }

        val json = requestJson(
            url = buildUrl(apiBaseUrl, "/api/v2/match"),
            body = payload.toString().toRequestBody(JSON_MEDIA_TYPE),
        )
        if (isNotFound(json)) return@withContext null
        requireSuccess(json)
        if (!json.optBoolean("isMatched", false)) return@withContext null

        val matches = json.optJSONArray("matches") ?: return@withContext null
        for (i in 0 until matches.length()) {
            val match = matches.optJSONObject(i) ?: continue
            val episodeId = match.optLong("episodeId")
            if (episodeId <= 0) continue
            return@withContext MatchResult(
                episodeId = episodeId,
                animeTitle = match.optString("animeTitle"),
                episodeTitle = match.optString("episodeTitle"),
            )
        }
        null
    }

    /**
     * 按番剧名称搜索本地弹幕库中的剧集。
     */
    suspend fun searchEpisodes(apiBaseUrl: String, animeName: String): List<AnimeResult> = withContext(Dispatchers.IO) {
        val encodedName = URLEncoder.encode(animeName, Charsets.UTF_8.name())
        val url = buildUrl(apiBaseUrl, "/api/v2/search/episodes?anime=$encodedName")
        val json = requestJson(url)
        if (isNotFound(json)) return@withContext emptyList()
        requireSuccess(json)
        val animes = json.optJSONArray("animes") ?: return@withContext emptyList()

        buildList {
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i) ?: continue
                val episodesJson = anime.optJSONArray("episodes") ?: continue
                val episodes = buildList {
                    for (j in 0 until episodesJson.length()) {
                        val episode = episodesJson.optJSONObject(j) ?: continue
                        add(
                            EpisodeResult(
                                episodeId = episode.optLong("episodeId"),
                                episodeTitle = episode.optString("episodeTitle"),
                            ),
                        )
                    }
                }
                add(
                    AnimeResult(
                        animeId = anime.optLong("animeId"),
                        animeTitle = anime.optString("animeTitle"),
                        typeDescription = anime.optString("typeDescription"),
                        type = anime.optString("type"),
                        episodes = episodes,
                    ),
                )
            }
        }
    }

    /**
     * 获取指定剧集的弹幕（含第三方关联源）
     */
    suspend fun getComments(apiBaseUrl: String, episodeId: Long, chConvert: Int): List<DanmakuComment> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(
                apiBaseUrl,
                "/api/v2/comment/$episodeId?withRelated=true&chConvert=${chConvert.coerceIn(0, 2)}",
            )
            val json = requestJson(url)
            requireSuccess(json)
            val comments = json.optJSONArray("comments") ?: return@withContext emptyList()

            buildList {
                for (i in 0 until comments.length()) {
                    val comment = comments.optJSONObject(i) ?: continue
                    val text = comment.optString("m").replace(LIKE_COUNT_REGEX, "").trim()
                    if (text.isEmpty()) continue
                    val parsed = DanmakuComment.fromDandanplay(
                        p = comment.optString("p"),
                        text = text,
                    )
                    if (parsed != null) add(parsed)
                }
            }
        }

    private fun buildUrl(apiBaseUrl: String, path: String): String {
        val base = apiBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw IOException("Misaka API base URL is empty")
        return base + path
    }

    private fun requireSuccess(json: JSONObject) {
        val success = if (json.has("success")) json.optBoolean("success") else true
        val errorCode = json.optInt("errorCode", 0)
        if (success && errorCode == 0) return

        val message = json.optString("errorMessage").ifBlank { "request failed" }
        throw IOException("Misaka API error $errorCode: $message")
    }

    /** 未收录的作品/剧集按「没有结果」处理，让上层继续走其他匹配回退。 */
    private fun isNotFound(json: JSONObject): Boolean = json.optInt("errorCode", 0) == 1002

    private fun requestJson(url: String, body: RequestBody? = null): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { body?.let(::post) }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Misaka HTTP ${response.code}: ${text.take(200)}")
            }
            return runCatching { JSONObject(text) }
                .getOrElse { e -> throw IOException("Misaka returned invalid JSON", e) }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 45L
        private const val WRITE_TIMEOUT_SECONDS = 15L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** misaka 会在正文末尾附加点赞数，如 "好！ 🤍 14" */
        private val LIKE_COUNT_REGEX = Regex("""\s*🤍\s*\d+\s*$""")
    }
}
