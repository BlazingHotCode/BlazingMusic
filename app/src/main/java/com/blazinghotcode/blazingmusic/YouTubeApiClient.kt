package com.blazinghotcode.blazingmusic

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
    suspend fun searchMusicVideos(query: String, maxResults: Int = 20): List<YouTubeVideo> {
        if (apiKey.isBlank()) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = URL(
                "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet" +
                    "&type=video" +
                    "&videoCategoryId=10" +
                    "&maxResults=${maxResults.coerceIn(1, 50)}" +
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
                parseSearchResults(payload)
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
            val title = snippet.optString("title", "Untitled")
            val channelTitle = snippet.optString("channelTitle", "Unknown channel")
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
}

