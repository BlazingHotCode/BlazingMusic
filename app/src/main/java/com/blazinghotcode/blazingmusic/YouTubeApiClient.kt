package com.blazinghotcode.blazingmusic

import android.util.Log
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube Music InnerTube search client (Metrolist-style WEB_REMIX search).
 */
class YouTubeApiClient(
    private val apiKey: String = ""
) {
    suspend fun searchMusicVideos(
        query: String,
        maxResults: Int = 20,
        songsOnly: Boolean = true
    ): List<YouTubeVideo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            val url = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            val requestBody = buildSearchBody(query = trimmed, songsOnly = songsOnly)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
                setRequestProperty("User-Agent", USER_AGENT_WEB)
                setRequestProperty("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                setRequestProperty("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
            }
            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(requestBody)
                }
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    Log.w(TAG, "InnerTube search failed code=${connection.responseCode}")
                    connection.errorStream ?: return@withContext emptyList()
                }
                val payload = stream.bufferedReader().use { it.readText() }
                parseSearchResults(payload).take(maxResults.coerceIn(1, 50))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun buildSearchBody(query: String, songsOnly: Boolean): String {
        val body = JSONObject()
        val context = JSONObject()
        val client = JSONObject()
            .put("clientName", WEB_REMIX_CLIENT_NAME)
            .put("clientVersion", WEB_REMIX_CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "US")
        context.put("client", client)
        body.put("context", context)
        body.put("query", query)
        if (songsOnly) {
            body.put("params", FILTER_SONG)
        }
        return body.toString()
    }

    private fun parseSearchResults(jsonText: String): List<YouTubeVideo> {
        val root = JSONObject(jsonText)
        val renderers = mutableListOf<JSONObject>()
        collectMusicResponsiveRenderers(root, renderers)
        if (renderers.isEmpty()) return emptyList()

        val videos = mutableListOf<YouTubeVideo>()
        val seenIds = HashSet<String>()
        for (renderer in renderers) {
            val videoId = extractVideoId(renderer)
            if (videoId.isEmpty()) continue
            if (!seenIds.add(videoId)) continue

            val title = extractTitle(renderer).ifBlank { "Untitled" }
            val channelTitle = extractArtistOrChannel(renderer).ifBlank { "Unknown channel" }
            val thumbnailUrl = extractThumbnail(renderer)
            videos.add(
                YouTubeVideo(
                    id = videoId,
                    title = decodeHtmlEntities(title),
                    channelTitle = decodeHtmlEntities(channelTitle),
                    thumbnailUrl = thumbnailUrl
                )
            )
        }
        return videos
    }

    private fun collectMusicResponsiveRenderers(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let(out::add)
                val iterator = node.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    collectMusicResponsiveRenderers(node.opt(key), out)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectMusicResponsiveRenderers(node.opt(index), out)
                }
            }
        }
    }

    private fun extractVideoId(renderer: JSONObject): String {
        val playlistItemData = renderer.optJSONObject("playlistItemData")
        val playlistVideoId = playlistItemData?.optString("videoId", "").orEmpty().trim()
        if (playlistVideoId.isNotEmpty()) return playlistVideoId

        val overlayVideoId = renderer
            .optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId", "")
            .orEmpty()
            .trim()
        if (overlayVideoId.isNotEmpty()) return overlayVideoId

        return renderer
            .optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId", "")
            .orEmpty()
            .trim()
    }

    private fun extractTitle(renderer: JSONObject): String {
        return renderer
            .optJSONArray("flexColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.let(::joinRunsText)
            .orEmpty()
    }

    private fun extractArtistOrChannel(renderer: JSONObject): String {
        val secondRuns = renderer
            .optJSONArray("flexColumns")
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?: return ""

        // Prefer explicit artist page links, matching YT Music shelf semantics.
        for (i in 0 until secondRuns.length()) {
            val run = secondRuns.optJSONObject(i) ?: continue
            val text = run.optString("text", "").trim()
            if (text.isEmpty() || isSeparator(text) || looksLikeDuration(text)) continue
            val pageType = run
                .optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType", "")
                .orEmpty()
            if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                return text
            }
        }

        for (i in 0 until secondRuns.length()) {
            val text = secondRuns.optJSONObject(i)?.optString("text", "").orEmpty().trim()
            if (text.isNotEmpty() && !isSeparator(text) && !looksLikeDuration(text)) return text
        }
        return ""
    }

    private fun extractThumbnail(renderer: JSONObject): String? {
        val thumbs = renderer
            .optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: return null
        for (i in thumbs.length() - 1 downTo 0) {
            val url = thumbs.optJSONObject(i)?.optString("url", "").orEmpty().trim()
            if (url.isNotEmpty()) return url
        }
        return null
    }

    private fun joinRunsText(runs: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val part = runs.optJSONObject(i)?.optString("text", "").orEmpty()
            sb.append(part)
        }
        return sb.toString().trim()
    }

    private fun isSeparator(value: String): Boolean {
        return value == "•" || value == "·" || value == "|" || value == "-" || value == "• " || value == " · "
    }

    private fun looksLikeDuration(value: String): Boolean {
        return value.matches(Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"))
    }

    private fun decodeHtmlEntities(raw: String): String {
        if (raw.isBlank()) return raw
        return HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    private companion object {
        const val TAG = "YouTubeApiClient"
        const val WEB_REMIX_CLIENT_ID = "67"
        const val WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
        const val WEB_REMIX_CLIENT_VERSION = "1.20260213.01.00"
        const val FILTER_SONG = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val USER_AGENT_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
