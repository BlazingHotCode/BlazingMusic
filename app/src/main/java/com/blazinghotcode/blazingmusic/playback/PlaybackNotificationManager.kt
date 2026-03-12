package com.blazinghotcode.blazingmusic

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/** Builds/updates a MediaStyle playback notification with transport + seek actions. */
class PlaybackNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "playback_controls"
        private const val NOTIFICATION_ID = 1100

        const val ACTION_PREVIOUS = "com.blazinghotcode.blazingmusic.action.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.blazinghotcode.blazingmusic.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.blazinghotcode.blazingmusic.action.NEXT"
        const val ACTION_SEEK_BACK = "com.blazinghotcode.blazingmusic.action.SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.blazinghotcode.blazingmusic.action.SEEK_FORWARD"
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val mediaSession = MediaSessionCompat(context, "BlazingMusicNotificationSession")

    init {
        createChannelIfNeeded()
        mediaSession.isActive = true
    }

    fun showOrUpdate(
        song: Song,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        if (!canPostNotifications()) return

        updateMediaSessionState(song, isPlaying, positionMs, durationMs)

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ml_library_music)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(song.album)
            .setContentIntent(openAppIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_rew,
                "Back 10s",
                actionPendingIntent(ACTION_SEEK_BACK)
            )
            .addAction(
                R.drawable.ml_skip_previous,
                "Previous",
                actionPendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                if (isPlaying) R.drawable.ml_pause else R.drawable.ml_play,
                if (isPlaying) "Pause" else "Play",
                actionPendingIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(
                R.drawable.ml_skip_next,
                "Next",
                actionPendingIntent(ACTION_NEXT)
            )
            .addAction(
                android.R.drawable.ic_media_ff,
                "Forward 10s",
                actionPendingIntent(ACTION_SEEK_FORWARD)
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun release() {
        cancel()
        mediaSession.release()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(context, PlaybackNotificationReceiver::class.java)
            .setAction(action)
            .setPackage(context.packageName)
        val requestCode = action.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateMediaSessionState(
        song: Song,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        val clampedPosition = positionMs.coerceAtLeast(0L)
        val playbackActions =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_FAST_FORWARD

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(playbackActions)
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    clampedPosition,
                    if (isPlaying) 1f else 0f
                )
                .build()
        )

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
                .build()
        )
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback controls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media playback controls"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
