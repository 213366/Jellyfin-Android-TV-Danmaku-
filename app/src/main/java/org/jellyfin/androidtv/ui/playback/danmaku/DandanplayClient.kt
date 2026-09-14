package org.jellyfin.androidtv.ui.playback.danmaku

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * dandanplay 弹幕 API 客户端，同时支持 jellyfin-plugin-danmu 的服务端 XML 弹幕接口。
 */
class DandanplayClient(okHttpClient: OkHttpClient) {

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    /**
     * 按番剧名称搜索剧集
     */
    suspend fun searchEpisodes(apiBaseUrl: String, animeName: String): List<AnimeResult> = withContext(Dispatchers.IO) {
        val encodedName = URLEncoder.encode(animeName, Charsets.UTF_8.name())
        val url = "$apiBaseUrl/api/v2/search/episodes?anime=$encodedName"
        val json = JSONObject(requestString(url))
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
            val url = "$apiBaseUrl/api/v2/comment/$episodeId?withRelated=true&chConvert=$chConvert"
            val json = JSONObject(requestString(url))
            val comments = json.optJSONArray("comments") ?: return@withContext emptyList()

            buildList {
                for (i in 0 until comments.length()) {
                    val comment = comments.optJSONObject(i) ?: continue
                    val parsed = DanmakuComment.fromDandanplay(
                        p = comment.optString("p"),
                        text = comment.optString("m"),
                    )
                    if (parsed != null) add(parsed)
                }
            }
        }

    /**
     * 从 jellyfin-plugin-danmu 服务端插件获取 XML 弹幕
     */
    suspend fun getPluginXmlComments(
        serverBaseUrl: String,
        itemId: String,
        accessToken: String?,
    ): List<DanmakuComment>? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(serverBaseUrl.trimEnd('/'))
            append("/api/danmu/")
            append(itemId)
            append("/raw")
            if (!accessToken.isNullOrEmpty()) {
                append("?api_key=")
                append(accessToken)
            }
        }
        val xmlText = runCatching { requestString(url) }
            .onFailure { e -> Timber.d(e, "Failed to load XML danmaku from server plugin") }
            .getOrNull()
            ?.takeIf { text -> text.isNotBlank() }
            ?: return@withContext null

        runCatching { parseXmlComments(xmlText) }
            .onFailure { e -> Timber.w(e, "Failed to parse XML danmaku") }
            .getOrNull()
            ?.takeIf { comments -> comments.isNotEmpty() }
    }

    @Suppress("MagicNumber", "NestedBlockDepth")
    private fun parseXmlComments(xmlText: String): List<DanmakuComment> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlText))

        val result = mutableListOf<DanmakuComment>()
        var currentP: String? = null
        val textBuilder = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "d") {
                    currentP = parser.getAttributeValue(null, "p")
                    textBuilder.setLength(0)
                }
                XmlPullParser.TEXT -> if (currentP != null) {
                    textBuilder.append(parser.text)
                }
                XmlPullParser.END_TAG -> if (parser.name == "d") {
                    val p = currentP
                    if (p != null) {
                        val parts = p.split(',')
                        if (parts.size >= 4) {
                            val sender = parts.getOrElse(6) { "" }
                            val converted = "${parts[0]},${parts[1]},${parts[3]},$sender"
                            DanmakuComment.fromDandanplay(converted, textBuilder.toString())?.let(result::add)
                        }
                    }
                    currentP = null
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun requestString(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/xml, */*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP code ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 20L
    }
}
