package com.blazinghotcode.blazingmusic

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Small bridge for delivering playback actions across app/service timing gaps.
 * Uses [PlaybackActionStore] so actions survive process/lifecycle timing races.
 */
object PlaybackCommandBus {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ((String) -> Unit)? = null

    fun register(context: Context, callback: (String) -> Unit) {
        this.callback = callback
        val pending = PlaybackActionStore.consume(context)
        if (pending != null) {
            PlaybackActionStore.clear(context)
            mainHandler.post { callback(pending) }
        }
    }

    fun unregister() {
        callback = null
    }

    fun dispatch(context: Context, action: String) {
        PlaybackActionStore.publish(context, action)
        val target = callback ?: return
        val pending = PlaybackActionStore.consume(context) ?: return
        PlaybackActionStore.clear(context)
        mainHandler.post { target(pending) }
    }
}
