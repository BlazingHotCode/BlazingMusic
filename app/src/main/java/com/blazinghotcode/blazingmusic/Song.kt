package com.blazinghotcode.blazingmusic

/** Normalized audio item model read from MediaStore and used across playback/UI. */
data class Song (
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val dateAddedSeconds: Long,
    val path: String,
    val albumArtUri: String?,
    val sourceVideoId: String? = null
)
