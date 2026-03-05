package com.blazinghotcode.blazingmusic

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal YouTube Data API v3 client for searching music videos.
 */
class YouTubeApiClient(
    private val apiKey: String
) {
    suspend fun searchMusicVideos(
        query: String,
        maxResults: Int = 20,
        songsOnly: Boolean = true
    ): List<YouTubeVideo> {
        if (apiKey.isBlank()) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            val queryText = if (songsOnly) "$trimmed official audio" else trimmed
            val encodedQuery = URLEncoder.encode(queryText, "UTF-8")
            val requested = if (songsOnly) (maxResults * 3).coerceIn(1, 50) else maxResults.coerceIn(1, 50)
            val categoryParam = if (songsOnly) "&videoCategoryId=10" else ""
            val musicTopicParam = if (songsOnly) "&topicId=%2Fm%2F04rlf" else ""
            val url = URL(
                "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet" +
                    "&type=video" +
                    categoryParam +
                    musicTopicParam +
                    "&maxResults=$requested" +
                    "&q=$encodedQuery" +
                    "&key=$apiKey"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: return@withContext emptyList()
                }
                val payload = stream.bufferedReader().use { it.readText() }
                val parsed = parseSearchResults(payload)
                if (!songsOnly) {
                    parsed.take(maxResults.coerceIn(1, 50))
                } else {
                    val scored = parsed
                        .map { it to songLikelihoodScore(it) }
                        .filter { (_, score) -> score > 0 }
                        .sortedByDescending { (_, score) -> score }
                        .map { (video, _) -> video }

                    if (scored.isNotEmpty()) {
                        scored.take(maxResults.coerceIn(1, 50))
                    } else {
                        // Soft fallback: if strict song scoring finds nothing, still prefer release-like sources.
                        parsed
                            .sortedByDescending { songLikelihoodScore(it) }
                            .take(maxResults.coerceIn(1, 50))
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseSearchResults(jsonText: String): List<YouTubeVideo> {
        val root = JSONObject(jsonText)
        val items = root.optJSONArray("items") ?: return emptyList()
        val videos = mutableListOf<YouTubeVideo>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val idObject = item.optJSONObject("id") ?: continue
            val videoId = idObject.optString("videoId", "").trim()
            if (videoId.isEmpty()) continue
            val snippet = item.optJSONObject("snippet") ?: continue
            val title = decodeHtmlEntities(snippet.optString("title", "Untitled"))
            val channelTitle = decodeHtmlEntities(snippet.optString("channelTitle", "Unknown channel"))
            val thumbnails = snippet.optJSONObject("thumbnails")
            val thumbnailUrl =
                thumbnails?.optJSONObject("high")?.optString("url")
                    ?: thumbnails?.optJSONObject("medium")?.optString("url")
                    ?: thumbnails?.optJSONObject("default")?.optString("url")
            videos.add(
                YouTubeVideo(
                    id = videoId,
                    title = title,
                    channelTitle = channelTitle,
                    thumbnailUrl = thumbnailUrl
                )
            )
        }
        return videos
    }

    private fun songLikelihoodScore(video: YouTubeVideo): Int {
        val channel = video.channelTitle.lowercase()
        val title = video.title.lowercase()
        var score = 0

        // Positive release/audio signals.
        if (channel.endsWith(" - topic")) score += 120
        if (channel.contains("release")) score += 80
        if (title.contains("provided to youtube by")) score += 70
        if (title.contains("official audio")) score += 60
        if (title.contains("audio")) score += 15
        if (title.contains("single version")) score += 25

        // Strong negative signals for non-song uploads.
        if (title.contains("reaction")) score -= 120
        if (title.contains("tutorial")) score -= 120
        if (title.contains("interview")) score -= 120
        if (title.contains("podcast")) score -= 100
        if (title.contains("vlog")) score -= 100
        if (title.contains("teaser")) score -= 90
        if (title.contains("trailer")) score -= 90
        if (title.contains("live")) score -= 80
        if (title.contains("concert")) score -= 80
        if (title.contains("cover")) score -= 70
        if (title.contains("karaoke")) score -= 70
        if (title.contains("music video")) score -= 55
        if (title.contains("official video")) score -= 55
        if (title.contains("lyric video")) score -= 45
        if (title.contains("shorts")) score -= 45
        if (title.contains("mix")) score -= 30

        return score
    }

    private fun decodeHtmlEntities(raw: String): String {
        if (raw.isBlank()) return raw
        return HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
