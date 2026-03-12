package com.blazinghotcode.blazingmusic

/**
 * Utilities for normalizing YouTube thumbnail URLs for consistent and sharper playback artwork.
 * Mirrors Metrolist's resize strategy for `lh3.googleusercontent.com` URLs.
 */
object YouTubeThumbnailUtils {
    private val googleThumbRegex = Regex("https://lh3\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*")
    private val yt3ThumbRegex = Regex("https://yt3\\.ggpht\\.com/.*=s(\\d+).*")

    fun toPlaybackArtworkUrl(rawUrl: String?, videoId: String?): String? {
        val normalized = rawUrl?.trim().orEmpty().ifBlank { null }
        val resized = normalized?.resize(width = 1200, height = 1200)
        if (!resized.isNullOrBlank()) return resized
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun String.resize(width: Int? = null, height: Int? = null): String {
        if (width == null && height == null) return this
        googleThumbRegex.matchEntire(this)?.groupValues?.let { group ->
            val sourceWidth = group[1].toIntOrNull() ?: return@let
            val sourceHeight = group[2].toIntOrNull() ?: return@let
            var outputWidth = width
            var outputHeight = height
            if (outputWidth != null && outputHeight == null && sourceWidth > 0) {
                outputHeight = (outputWidth * sourceHeight) / sourceWidth
            }
            if (outputWidth == null && outputHeight != null && sourceHeight > 0) {
                outputWidth = (outputHeight * sourceWidth) / sourceHeight
            }
            if (outputWidth != null && outputHeight != null) {
                return "${substringBefore("=w")}=w$outputWidth-h$outputHeight-p-l90-rj"
            }
        }

        if (yt3ThumbRegex.matches(this)) {
            val size = width ?: height
            if (size != null) return "$this-s$size"
        }
        return this
    }
}
