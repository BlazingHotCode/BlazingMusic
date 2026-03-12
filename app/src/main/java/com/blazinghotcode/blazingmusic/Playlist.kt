package com.blazinghotcode.blazingmusic

object PlaylistSystem {
    const val LOCAL_MUSIC_ID: Long = -1L
    const val LOCAL_MUSIC_NAME: String = "Local music"
    const val ACCOUNT_HISTORY_ID: Long = -2L
    const val ACCOUNT_PLAYLISTS_ID: Long = -3L
    const val ACCOUNT_ALBUMS_ID: Long = -4L
    const val ACCOUNT_ARTISTS_ID: Long = -5L
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
