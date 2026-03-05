package com.blazinghotcode.blazingmusic

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Media3 notification provider override for title/ticker presentation. */
@UnstableApi
class BlazingMediaNotificationProvider(
    context: Context,
    notificationIdProvider: DefaultMediaNotificationProvider.NotificationIdProvider,
    channelId: String,
    channelNameResourceId: Int
) : DefaultMediaNotificationProvider(
    context,
    notificationIdProvider,
    channelId,
    channelNameResourceId
) {

    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence {
        return metadata.title
            ?: metadata.displayTitle
            ?: metadata.artist
            ?: metadata.albumTitle
            ?: "BlazingMusic"
    }

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory
    ): IntArray {
        val compact = super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
        val tickerText = getNotificationContentTitle(mediaSession.player.mediaMetadata)
        builder.setTicker(tickerText)
        return compact
    }
}
