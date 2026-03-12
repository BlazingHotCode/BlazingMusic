package com.blazinghotcode.blazingmusic

enum class YouTubeItemType {
    SONG,
    VIDEO,
    ARTIST,
    ALBUM,
    PLAYLIST,
    UNKNOWN
}

data class YouTubeVideo(
    val id: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String?,
    val sectionTitle: String? = null,
    val sectionBrowseId: String? = null,
    val sectionBrowseParams: String? = null,
    val type: YouTubeItemType = YouTubeItemType.UNKNOWN,
    val videoId: String? = null,
    val browseId: String? = null,
    val browseParams: String? = null,
    val sourcePlaylistId: String? = null,
    val sourcePlaylistSetVideoId: String? = null,
    val sourceParams: String? = null,
    val sourceIndex: Int? = null
)
