package com.blazinghotcode.blazingmusic

/**
 * Structured browse payload so UI can render richer pages (sections + artist metadata).
 */
data class YouTubeBrowsePage(
    val items: List<YouTubeVideo>,
    val artistInfo: YouTubeArtistInfo? = null,
    val continuation: String? = null
)

data class YouTubeArtistInfo(
    val description: String? = null,
    val subscriberCount: String? = null,
    val monthlyListeners: String? = null
)

