package com.blazinghotcode.blazingmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player

class PlaybackNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val player = SharedPlayer.getOrCreate(context.applicationContext)
        when (action) {
            PlaybackNotificationManager.ACTION_PREVIOUS -> {
                if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            }
            PlaybackNotificationManager.ACTION_PLAY_PAUSE -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    when (player.playbackState) {
                        Player.STATE_IDLE -> {
                            player.prepare()
                            player.play()
                        }
                        Player.STATE_ENDED -> {
                            val index = player.currentMediaItemIndex
                            if (index != -1) {
                                player.seekToDefaultPosition(index)
                            } else if (player.mediaItemCount > 0) {
                                player.seekToDefaultPosition(0)
                            }
                            player.play()
                        }
                        else -> player.play()
                    }
                }
            }
            PlaybackNotificationManager.ACTION_NEXT -> {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
            PlaybackNotificationManager.ACTION_SEEK_BACK -> {
                val seekTo = (player.currentPosition - 10_000L).coerceAtLeast(0L)
                player.seekTo(seekTo)
            }
            PlaybackNotificationManager.ACTION_SEEK_FORWARD -> {
                val seekTo = (player.currentPosition + 10_000L).coerceAtLeast(0L)
                player.seekTo(seekTo)
            }
        }
    }
}
