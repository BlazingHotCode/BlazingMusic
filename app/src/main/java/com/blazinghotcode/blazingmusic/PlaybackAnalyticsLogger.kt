package com.blazinghotcode.blazingmusic

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Central analytics/logging hooks for playback diagnostics.
 *
 * Current implementation logs to Logcat. Replace/extend these methods with
 * your analytics backend if needed.
 */
object PlaybackAnalyticsLogger {
    private const val TAG = "PlaybackAnalytics"
    private const val MAX_RECENT_EVENTS = 20
    private val recentEvents = ArrayDeque<String>(MAX_RECENT_EVENTS)
    private val timestampFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun recentEventsSnapshot(): List<String> = recentEvents.toList()

    fun logPlaybackError(
        error: PlaybackException,
        song: Song?,
        queueIndex: Int,
        queueSize: Int
    ) {
        val message =
            "playback_error code=${error.errorCode} message=${error.message} " +
                "songPath=${song?.path ?: "none"} queueIndex=$queueIndex queueSize=$queueSize"
        recordRecentEvent(message)
        Log.e(TAG, message, error)
    }

    fun logSkip(
        action: String,
        source: String,
        fromSong: Song?,
        toSong: Song?,
        fromIndex: Int,
        toIndex: Int
    ) {
        val message =
            "skip action=$action source=$source " +
                "fromIndex=$fromIndex toIndex=$toIndex " +
                "fromPath=${fromSong?.path ?: "none"} toPath=${toSong?.path ?: "none"}"
        recordRecentEvent(message)
        Log.i(TAG, message)
    }

    fun logTransition(
        reason: Int,
        fromSong: Song?,
        toSong: Song?,
        toIndex: Int
    ) {
        val reasonLabel = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
            else -> "unknown_$reason"
        }
        val message =
            "transition reason=$reasonLabel toIndex=$toIndex " +
                "fromPath=${fromSong?.path ?: "none"} toPath=${toSong?.path ?: "none"}"
        recordRecentEvent(message)
        Log.d(TAG, message)
    }

    @Synchronized
    private fun recordRecentEvent(message: String) {
        if (recentEvents.size >= MAX_RECENT_EVENTS) {
            recentEvents.removeFirst()
        }
        val timestamp = timestampFormatter.format(Date())
        recentEvents.addLast("[$timestamp] $message")
    }
}
