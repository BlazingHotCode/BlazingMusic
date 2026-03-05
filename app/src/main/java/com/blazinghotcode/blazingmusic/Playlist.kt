package com.blazinghotcode.blazingmusic

/** Persisted playlist model containing ordered song paths. */
data class Playlist(
    val id: Long,
    val name: String,
    val songPaths: List<String>
)
