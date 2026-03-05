package com.blazinghotcode.blazingmusic

import android.os.Bundle
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen for playback defaults and audio focus behavior.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var switchDefaultShuffle: Switch
    private lateinit var groupRepeat: RadioGroup
    private lateinit var radioRepeatOff: RadioButton
    private lateinit var radioRepeatAll: RadioButton
    private lateinit var radioRepeatOne: RadioButton
    private lateinit var switchHandleAudioFocus: Switch
    private lateinit var switchPauseOnNoisy: Switch
    private lateinit var btnBack: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()
        bindActions()
        loadSettings()
    }

    private fun bindViews() {
        switchDefaultShuffle = findViewById(R.id.switchDefaultShuffle)
        groupRepeat = findViewById(R.id.groupRepeatMode)
        radioRepeatOff = findViewById(R.id.radioRepeatOff)
        radioRepeatAll = findViewById(R.id.radioRepeatAll)
        radioRepeatOne = findViewById(R.id.radioRepeatOne)
        switchHandleAudioFocus = findViewById(R.id.switchHandleAudioFocus)
        switchPauseOnNoisy = findViewById(R.id.switchPauseOnNoisy)
        btnBack = findViewById(R.id.btnBack)
    }

    private fun bindActions() {
        btnBack.setOnClickListener { finish() }

        switchDefaultShuffle.setOnCheckedChangeListener { _, isChecked ->
            PlaybackSettingsStore.updateDefaultShuffleEnabled(this, isChecked)
        }
        groupRepeat.setOnCheckedChangeListener { _, checkedId ->
            val repeatMode = when (checkedId) {
                R.id.radioRepeatAll -> 1
                R.id.radioRepeatOne -> 2
                else -> 0
            }
            PlaybackSettingsStore.updateDefaultRepeatMode(this, repeatMode)
        }
        switchHandleAudioFocus.setOnCheckedChangeListener { _, isChecked ->
            PlaybackSettingsStore.updateHandleAudioFocus(this, isChecked)
        }
        switchPauseOnNoisy.setOnCheckedChangeListener { _, isChecked ->
            PlaybackSettingsStore.updatePauseOnNoisyOutput(this, isChecked)
        }
    }

    private fun loadSettings() {
        val settings = PlaybackSettingsStore.read(this)
        switchDefaultShuffle.isChecked = settings.defaultShuffleEnabled
        when (settings.defaultRepeatMode) {
            1 -> radioRepeatAll.isChecked = true
            2 -> radioRepeatOne.isChecked = true
            else -> radioRepeatOff.isChecked = true
        }
        switchHandleAudioFocus.isChecked = settings.handleAudioFocus
        switchPauseOnNoisy.isChecked = settings.pauseOnNoisyOutput
    }
}

