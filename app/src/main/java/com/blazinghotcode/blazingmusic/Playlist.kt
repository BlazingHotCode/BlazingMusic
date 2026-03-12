package com.blazinghotcode.blazingmusic

object PlaylistSystem {
    const val LOCAL_MUSIC_ID: Long = -1L
    const val LOCAL_MUSIC_NAME: String = "Local music"
}

/** Persisted playlist model containing ordered song paths. */
data class Playlist(
    val id: Long,
    val name: String,
    val songPaths: List<String>,
    val songs: List<Song> = emptyList(),
    val remoteBrowseId: String? = null,
    val remoteBrowseType: YouTubeItemType = YouTubeItemType.UNKNOWN
)

fun Playlist.isLocalMusicSystemPlaylist(): Boolean = id == PlaylistSystem.LOCAL_MUSIC_ID

fun Playlist.isRemoteSystemPlaylist(): Boolean = remoteBrowseId != null

fun Playlist.isEditablePlaylist(): Boolean = !isLocalMusicSystemPlaylist() && !isRemoteSystemPlaylist()
