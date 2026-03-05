package com.blazinghotcode.blazingmusic

import android.util.Log
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * YouTube Music InnerTube client inspired by Metrolist's WEB_REMIX flow.
 * Supports search, browse navigation (artist/album/playlist), and stream URL resolution.
 */
class YouTubeApiClient {
    suspend fun searchMusicVideos(
        query: String,
        maxResults: Int = 20,
        filter: YouTubeSearchFilter = YouTubeSearchFilter.ALL
    ): List<YouTubeVideo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val body = JSONObject()
            .put("context", contextObject())
            .put("query", trimmed)
            .apply {
                filter.params?.let { put("params", it) }
            }

        return post("search", body)
            ?.let { parseShelfItems(it).take(maxResults.coerceIn(1, 100)) }
            .orEmpty()
    }

    suspend fun browseCollection(
        browseId: String,
        params: String? = null,
        maxResults: Int = 80
    ): List<YouTubeVideo> {
        if (browseId.isBlank()) return emptyList()
        val body = JSONObject()
            .put("context", contextObject())
            .put("browseId", browseId)
            .apply {
                if (!params.isNullOrBlank()) put("params", params)
            }

        return post("browse", body)
            ?.let { parseShelfItems(it).take(maxResults.coerceIn(1, 300)) }
            .orEmpty()
    }

    suspend fun resolveAudioStreamUrl(videoId: String): String? {
        if (videoId.isBlank()) return null
        YouTubeStreamResolver.resolveBestAudioUrl(videoId)?.let { extracted ->
            Log.i(TAG, "Resolved stream with NewPipe extractor for $videoId")
            return extracted
        }

        val body = JSONObject()
            .put("context", contextObjectAndroid())
            .put("videoId", videoId)
            .put("playbackContext", JSONObject().put(
                "contentPlaybackContext",
                JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
            ))
            .put("racyCheckOk", true)
            .put("contentCheckOk", true)

        val response = post(
            path = "player",
            body = body,
            clientName = ANDROID_CLIENT_NAME,
            clientVersion = ANDROID_CLIENT_VERSION,
            clientId = ANDROID_CLIENT_ID,
            userAgent = USER_AGENT_ANDROID
        ) ?: return null
        val playability = response.optJSONObject("playabilityStatus")?.optString("status", "UNKNOWN").orEmpty()
        if (playability != "OK") {
            Log.w(TAG, "Player resolve not playable for $videoId: $playability")
            return null
        }
        val streamingData = response.optJSONObject("streamingData") ?: return null
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
        val formats = streamingData.optJSONArray("formats") ?: JSONArray()

        val chosen = pickBestAudioFormat(adaptiveFormats, preferMimePrefix = "audio/mp4")
            ?: pickBestAudioFormat(formats, preferMimePrefix = "audio/mp4")
            ?: pickBestAudioFormat(adaptiveFormats)
            ?: pickBestAudioFormat(formats)
            ?: return null

        val directUrl = chosen.optString("url", "").trim()
        if (directUrl.isNotEmpty()) return directUrl

        val cipher = chosen.optString("signatureCipher", "").trim()
        if (cipher.isNotEmpty()) {
            val params = parseUrlEncodedParams(cipher)
            val cipherUrl = params["url"].orEmpty().trim()
            if (cipherUrl.isNotEmpty()) {
                // Some formats are already playable without deciphering `s`; use when available.
                return cipherUrl
            }
        }

        return null
    }

    suspend fun isStreamPlayable(url: String): Boolean {
        if (url.isBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", USER_AGENT_ANDROID)
                    setRequestProperty("Range", "bytes=0-1")
                    instanceFollowRedirects = true
                }
                try {
                    val code = connection.responseCode
                    code in 200..399
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
        }
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject? {
        return post(
            path = path,
            body = body,
            clientName = WEB_REMIX_CLIENT_NAME,
            clientVersion = WEB_REMIX_CLIENT_VERSION,
            clientId = WEB_REMIX_CLIENT_ID,
            userAgent = USER_AGENT_WEB
        )
    }

    private suspend fun post(
        path: String,
        body: JSONObject,
        clientName: String,
        clientVersion: String,
        clientId: String,
        userAgent: String
    ): JSONObject? {
        return withContext(Dispatchers.IO) {
            val url = URL("$INNER_TUBE_BASE/$path?prettyPrint=false")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("X-YouTube-Client-Name", clientId)
                setRequestProperty("X-YouTube-Client-Version", clientVersion)
            }
            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    Log.w(TAG, "InnerTube $path failed code=${connection.responseCode}")
                    connection.errorStream ?: return@withContext null
                }
                val payload = stream.bufferedReader().use { it.readText() }
                JSONObject(payload)
            } catch (error: Throwable) {
                Log.w(TAG, "InnerTube $path request failed", error)
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun contextObject(): JSONObject {
        return JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", WEB_REMIX_CLIENT_NAME)
                .put("clientVersion", WEB_REMIX_CLIENT_VERSION)
                .put("hl", "en")
                .put("gl", "US")
        )
    }

    private fun contextObjectAndroid(): JSONObject {
        return JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", ANDROID_CLIENT_NAME)
                .put("clientVersion", ANDROID_CLIENT_VERSION)
                .put("androidSdkVersion", 35)
                .put("hl", "en")
                .put("gl", "US")
        )
    }

    private fun parseShelfItems(root: JSONObject): List<YouTubeVideo> {
        val out = mutableListOf<YouTubeVideo>()
        val seen = HashSet<String>()
        collectShelfRenderers(root) { renderer, shelfTitle ->
            val item = mapRendererToItem(renderer, shelfTitle) ?: return@collectShelfRenderers
            if (seen.add(item.id)) out += item
        }
        return out
    }

    private fun collectShelfRenderers(root: JSONObject, block: (JSONObject, String?) -> Unit) {
        // Primary response shape for search/browse shelves.
        val contents = root
            .optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        if (contents != null) {
            for (i in 0 until contents.length()) {
                val shelf = contents.optJSONObject(i)?.optJSONObject("musicShelfRenderer") ?: continue
                val shelfTitle = shelf.optJSONObject("title")?.optJSONArray("runs")?.let(::joinRunsText)
                val shelfItems = shelf.optJSONArray("contents") ?: continue
                for (j in 0 until shelfItems.length()) {
                    val renderer = shelfItems.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    block(renderer, shelfTitle)
                }
            }
            return
        }

        // Browse/continuation shapes can be nested, so fall back to recursive walk.
        collectRenderersRecursively(root, null, block)
    }

    private fun collectRenderersRecursively(node: Any?, shelfTitle: String?, block: (JSONObject, String?) -> Unit) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicShelfRenderer")?.let { shelf ->
                    val title = shelf.optJSONObject("title")?.optJSONArray("runs")?.let(::joinRunsText)
                    val shelfItems = shelf.optJSONArray("contents")
                    if (shelfItems != null) {
                        for (j in 0 until shelfItems.length()) {
                            val renderer = shelfItems.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            block(renderer, title)
                        }
                    }
                }

                node.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                    block(renderer, shelfTitle)
                }

                val iterator = node.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    collectRenderersRecursively(node.opt(key), shelfTitle, block)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectRenderersRecursively(node.opt(i), shelfTitle, block)
                }
            }
        }
    }

    private fun mapRendererToItem(renderer: JSONObject, shelfTitle: String?): YouTubeVideo? {
        val titleRuns = renderer
            .optJSONArray("flexColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        val title = titleRuns?.let(::joinRunsText)?.takeIf { it.isNotBlank() }
            ?: return null

        val secondRuns = renderer
            .optJSONArray("flexColumns")
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        val videoId = extractVideoId(renderer).ifBlank { null }
        val browseEndpoint = extractBrowseEndpoint(renderer, titleRuns)
        val browseId = browseEndpoint.first
        val browseParams = browseEndpoint.second

        val type = detectType(renderer, shelfTitle, videoId != null)
        val subtitle = extractSubtitle(secondRuns, type)
        val thumb = extractThumbnail(renderer)

        val stableId = when {
            videoId != null -> "video:$videoId"
            !browseId.isNullOrBlank() -> "browse:$browseId:${browseParams.orEmpty()}"
            else -> "title:${title.lowercase()}:${subtitle.lowercase()}"
        }

        return YouTubeVideo(
            id = stableId,
            title = decodeHtmlEntities(title),
            channelTitle = decodeHtmlEntities(subtitle),
            thumbnailUrl = thumb,
            sectionTitle = shelfTitle?.let(::decodeHtmlEntities),
            type = type,
            videoId = videoId,
            browseId = browseId,
            browseParams = browseParams
        )
    }

    private fun detectType(renderer: JSONObject, shelfTitle: String?, hasVideoId: Boolean): YouTubeItemType {
        val pageType = renderer
            .optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType", "")
            .orEmpty()

        if (hasVideoId) {
            val mvType = renderer.optString("musicVideoType", "")
            if (mvType == "MUSIC_VIDEO_TYPE_OMV") return YouTubeItemType.VIDEO
            if (mvType == "MUSIC_VIDEO_TYPE_ATV") return YouTubeItemType.SONG
            return when {
                shelfTitle?.contains("video", ignoreCase = true) == true -> YouTubeItemType.VIDEO
                else -> YouTubeItemType.SONG
            }
        }

        return when (pageType) {
            "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> YouTubeItemType.ARTIST
            "MUSIC_PAGE_TYPE_ALBUM" -> YouTubeItemType.ALBUM
            "MUSIC_PAGE_TYPE_PLAYLIST", "MUSIC_PAGE_TYPE_LIBRARY_PLAYLIST" -> YouTubeItemType.PLAYLIST
            else -> when {
                shelfTitle?.contains("artist", ignoreCase = true) == true -> YouTubeItemType.ARTIST
                shelfTitle?.contains("album", ignoreCase = true) == true -> YouTubeItemType.ALBUM
                shelfTitle?.contains("playlist", ignoreCase = true) == true -> YouTubeItemType.PLAYLIST
                else -> YouTubeItemType.UNKNOWN
            }
        }
    }

    private fun extractSubtitle(secondRuns: JSONArray?, type: YouTubeItemType): String {
        if (secondRuns == null) {
            return when (type) {
                YouTubeItemType.ARTIST -> "Artist"
                YouTubeItemType.ALBUM -> "Album"
                YouTubeItemType.PLAYLIST -> "Playlist"
                YouTubeItemType.SONG -> "Song"
                YouTubeItemType.VIDEO -> "Video"
                else -> "YouTube Music"
            }
        }

        val cleaned = mutableListOf<String>()
        for (i in 0 until secondRuns.length()) {
            val text = secondRuns.optJSONObject(i)?.optString("text", "")?.trim().orEmpty()
            if (text.isEmpty() || isSeparator(text) || looksLikeDuration(text)) continue
            cleaned += text
        }
        if (cleaned.isEmpty()) return "YouTube Music"
        return cleaned.joinToString(" • ")
    }

    private fun extractVideoId(renderer: JSONObject): String {
        val playlistVideoId = renderer
            .optJSONObject("playlistItemData")
            ?.optString("videoId", "")
            .orEmpty()
            .trim()
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

    private fun extractBrowseEndpoint(renderer: JSONObject, titleRuns: JSONArray?): Pair<String?, String?> {
        val fromTitle = titleRuns
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
        if (fromTitle != null) {
            val id = fromTitle.optString("browseId", "").trim()
            if (id.isNotEmpty()) return id to fromTitle.optString("params", "").trim().ifEmpty { null }
        }

        val direct = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        if (direct != null) {
            val id = direct.optString("browseId", "").trim()
            if (id.isNotEmpty()) return id to direct.optString("params", "").trim().ifEmpty { null }
        }

        return null to null
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

    private fun pickBestAudioFormat(
        formats: JSONArray,
        preferMimePrefix: String? = null
    ): JSONObject? {
        var best: JSONObject? = null
        var bestBitrate = -1
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType", "")
            if (!mimeType.startsWith("audio/")) continue
            if (preferMimePrefix != null && !mimeType.startsWith(preferMimePrefix)) continue
            val bitrate = format.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                best = format
                bestBitrate = bitrate
            }
        }
        return best
    }

    private fun parseUrlEncodedParams(value: String): Map<String, String> {
        return value.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                key to v
            }
            .toMap()
    }

    private fun joinRunsText(runs: JSONArray): String {
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            builder.append(runs.optJSONObject(i)?.optString("text", "").orEmpty())
        }
        return builder.toString().trim()
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
        private const val TAG = "YouTubeApiClient"
        private const val INNER_TUBE_BASE = "https://music.youtube.com/youtubei/v1"
        private const val WEB_REMIX_CLIENT_ID = "67"
        private const val WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
        private const val WEB_REMIX_CLIENT_VERSION = "1.20260213.01.00"
        private const val ANDROID_CLIENT_ID = "3"
        private const val ANDROID_CLIENT_NAME = "ANDROID"
        private const val ANDROID_CLIENT_VERSION = "21.03.38"
        private const val USER_AGENT_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private const val USER_AGENT_ANDROID =
            "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
    }
}
