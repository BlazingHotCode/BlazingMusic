package com.blazinghotcode.blazingmusic

import android.content.Context

object PlaybackActionStore {
    private const val PREFS_NAME = "blazing_music_notification_actions"
    private const val KEY_ACTION = "action"
    private const val KEY_TOKEN = "token"

    fun publish(context: Context, action: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACTION, action)
            .putLong(KEY_TOKEN, System.nanoTime())
            .apply()
    }

    fun consume(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTION, null)
    }

    fun token(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_TOKEN, 0L)
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_ACTION).apply()
    }
}
