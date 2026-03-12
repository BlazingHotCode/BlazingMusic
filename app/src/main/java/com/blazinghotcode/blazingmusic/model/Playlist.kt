package com.blazinghotcode.blazingmusic

object PlaylistSystem {
    const val LOCAL_MUSIC_ID: Long = -1L
    const val LOCAL_MUSIC_NAME: String = "Local music"
    const val YOUTUBE_LIKED_MUSIC_ID: Long = -2L
    const val YOUTUBE_LIKED_MUSIC_NAME: String = "Liked Music"
    const val YOUTUBE_LIKED_MUSIC_BROWSE_ID: String = "LM"
}

/** Persisted playlist model containing ordered song paths. */
data class Playlist(
    val id: Long,
    val name: String,
    val songPaths: List<String>,
    val songs: List<Song> = emptyList(),
    val coverArtUri: String? = null,
    val remoteBrowseId: String? = null,
    val remoteBrowseType: YouTubeItemType = YouTubeItemType.UNKNOWN
)

fun Playlist.isLocalMusicSystemPlaylist(): Boolean = id == PlaylistSystem.LOCAL_MUSIC_ID

fun Playlist.isYouTubeLikedSystemPlaylist(): Boolean = id == PlaylistSystem.YOUTUBE_LIKED_MUSIC_ID

fun Playlist.isRemoteSystemPlaylist(): Boolean = remoteBrowseId != null

fun Playlist.isEditablePlaylist(): Boolean = id > 0L && !isRemoteSystemPlaylist()
