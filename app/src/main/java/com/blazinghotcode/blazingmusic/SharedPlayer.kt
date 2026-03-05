package com.blazinghotcode.blazingmusic

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

object SharedPlayer {
    @Volatile
    private var player: ExoPlayer? = null

    @Synchronized
    fun getOrCreate(context: Context): ExoPlayer {
        return player ?: ExoPlayer.Builder(context.applicationContext).build().also {
            player = it
        }
    }

    @Synchronized
    fun clear() {
        player = null
    }
}
