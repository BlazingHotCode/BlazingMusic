package com.blazinghotcode.blazingmusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground MediaSession service for background playback and system media controls.
 */
class MusicService : MediaSessionService() {

    companion object {
        private const val CHANNEL_ID = "music_player"
        private const val NOTIFICATION_ID = 1101
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createPlaybackChannelIfNeeded()
        startForegroundBootstrap()

        // Clear legacy custom notification if it exists.
        NotificationManagerCompat.from(this).cancel(1100)

        setMediaNotificationProvider(
            BlazingMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.app_name
            ).apply {
                setSmallIcon(R.drawable.ic_stat_blazing_music)
            }
        )

        val player = SharedPlayer.getOrCreate(this)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(MediaSessionCallback())
            .build()
    }

    private class MediaSessionCallback : MediaSession.Callback

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    // Retain explicit support for custom seek actions from existing pending intents.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player
        when (intent?.action) {
            PlaybackNotificationManager.ACTION_SEEK_BACK -> {
                player?.let {
                    val seekTo = (it.currentPosition - 10_000L).coerceAtLeast(0L)
                    it.seekTo(seekTo)
                }
            }
            PlaybackNotificationManager.ACTION_SEEK_FORWARD -> {
                player?.let {
                    val seekTo = (it.currentPosition + 10_000L).coerceAtLeast(0L)
                    it.seekTo(seekTo)
                }
            }
            PlaybackNotificationManager.ACTION_PREVIOUS -> {
                if (player?.hasPreviousMediaItem() == true) player.seekToPreviousMediaItem()
            }
            PlaybackNotificationManager.ACTION_NEXT -> {
                if (player?.hasNextMediaItem() == true) player.seekToNextMediaItem()
            }
            PlaybackNotificationManager.ACTION_PLAY_PAUSE -> {
                player?.let { currentPlayer ->
                    if (currentPlayer.isPlaying) {
                        currentPlayer.pause()
                    } else {
                        when (currentPlayer.playbackState) {
                            Player.STATE_IDLE -> {
                                currentPlayer.prepare()
                                currentPlayer.play()
                            }
                            Player.STATE_ENDED -> {
                                val index = currentPlayer.currentMediaItemIndex
                                if (index != -1) {
                                    currentPlayer.seekToDefaultPosition(index)
                                } else if (currentPlayer.mediaItemCount > 0) {
                                    currentPlayer.seekToDefaultPosition(0)
                                }
                                currentPlayer.play()
                            }
                            else -> currentPlayer.play()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        SharedPlayer.clear()
        super.onDestroy()
    }

    private fun createPlaybackChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundBootstrap() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blazing_music)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("")
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
