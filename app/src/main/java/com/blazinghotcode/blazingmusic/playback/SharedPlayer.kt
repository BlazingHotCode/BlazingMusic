package com.blazinghotcode.blazingmusic

import android.content.Context
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Singleton holder for the shared ExoPlayer instance used by UI and service. */
object SharedPlayer {
    @Volatile
    private var player: ExoPlayer? = null

    @Synchronized
    fun getOrCreate(context: Context): ExoPlayer {
        return player ?: ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        STARTUP_MIN_BUFFER_MS,
                        STARTUP_MAX_BUFFER_MS,
                        STARTUP_PLAYBACK_BUFFER_MS,
                        STARTUP_REBUFFER_BUFFER_MS
                    )
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context.applicationContext).setDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(
                            mapOf(
                                "Referer" to "https://music.youtube.com/",
                                "Origin" to "https://music.youtube.com"
                            )
                        )
                        .setUserAgent(YOUTUBE_ANDROID_USER_AGENT)
                )
            )
            .build()
            .also {
            player = it
        }
    }

    @Synchronized
    fun clear() {
        player = null
    }

    private const val YOUTUBE_ANDROID_USER_AGENT =
        "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
    private const val STARTUP_MIN_BUFFER_MS = 30_000
    private const val STARTUP_MAX_BUFFER_MS = 120_000
    private const val STARTUP_PLAYBACK_BUFFER_MS = 20_000
    private const val STARTUP_REBUFFER_BUFFER_MS = 25_000
}
