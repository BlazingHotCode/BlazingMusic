package com.blazinghotcode.blazingmusic

data class Playlist(
    val id: Long,
    val name: String,
    val songPaths: List<String>
)
