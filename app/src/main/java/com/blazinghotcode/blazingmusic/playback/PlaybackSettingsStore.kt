package com.blazinghotcode.blazingmusic

import android.content.Context

data class PlaybackSettings(
    val defaultShuffleEnabled: Boolean,
    val defaultRepeatMode: Int,
    val handleAudioFocus: Boolean,
    val pauseOnNoisyOutput: Boolean
)

/**
 * SharedPreferences-backed storage for user-configurable playback defaults.
 */
object PlaybackSettingsStore {
    private const val PREFS_NAME = "blazing_music_settings"
    private const val KEY_DEFAULT_SHUFFLE = "default_shuffle"
    private const val KEY_DEFAULT_REPEAT_MODE = "default_repeat_mode"
    private const val KEY_HANDLE_AUDIO_FOCUS = "handle_audio_focus"
    private const val KEY_PAUSE_ON_NOISY_OUTPUT = "pause_on_noisy_output"

    private const val DEFAULT_REPEAT_MODE = 0

    fun read(context: Context): PlaybackSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PlaybackSettings(
            defaultShuffleEnabled = prefs.getBoolean(KEY_DEFAULT_SHUFFLE, false),
            defaultRepeatMode = prefs.getInt(KEY_DEFAULT_REPEAT_MODE, DEFAULT_REPEAT_MODE).coerceIn(0, 2),
            handleAudioFocus = prefs.getBoolean(KEY_HANDLE_AUDIO_FOCUS, true),
            pauseOnNoisyOutput = prefs.getBoolean(KEY_PAUSE_ON_NOISY_OUTPUT, true)
        )
    }

    fun updateDefaultShuffleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEFAULT_SHUFFLE, enabled)
            .apply()
    }

    fun updateDefaultRepeatMode(context: Context, repeatMode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DEFAULT_REPEAT_MODE, repeatMode.coerceIn(0, 2))
            .apply()
    }

    fun updateHandleAudioFocus(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HANDLE_AUDIO_FOCUS, enabled)
            .apply()
    }

    fun updatePauseOnNoisyOutput(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAUSE_ON_NOISY_OUTPUT, enabled)
            .apply()
    }
}

