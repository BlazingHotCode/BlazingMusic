package com.blazinghotcode.blazingmusic

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import coil.load
import coil.transform.RoundedCornersTransformation

/**
 * Settings screen for playback defaults and audio focus behavior.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var switchDefaultShuffle: SwitchCompat
    private lateinit var groupRepeat: RadioGroup
    private lateinit var radioRepeatOff: RadioButton
    private lateinit var radioRepeatAll: RadioButton
    private lateinit var radioRepeatOne: RadioButton
    private lateinit var switchHandleAudioFocus: SwitchCompat
    private lateinit var switchPauseOnNoisy: SwitchCompat
    private lateinit var ivAccountAvatar: ImageView
    private lateinit var tvAccountStatus: TextView
    private lateinit var tvAccountSummary: TextView
    private lateinit var accountSurfaceRow: View
    private lateinit var btnAccountHistory: Button
    private lateinit var btnAccountLibrary: Button
    private lateinit var btnAccountLogin: Button
    private lateinit var btnAccountLogout: Button
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
        ivAccountAvatar = findViewById(R.id.ivAccountAvatar)
        tvAccountStatus = findViewById(R.id.tvAccountStatus)
        tvAccountSummary = findViewById(R.id.tvAccountSummary)
        accountSurfaceRow = findViewById(R.id.accountSurfaceRow)
        btnAccountHistory = findViewById(R.id.btnAccountHistory)
        btnAccountLibrary = findViewById(R.id.btnAccountLibrary)
        btnAccountLogin = findViewById(R.id.btnAccountLogin)
        btnAccountLogout = findViewById(R.id.btnAccountLogout)
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
        btnAccountLogin.setOnClickListener {
            startActivity(Intent(this, YouTubeLoginActivity::class.java))
        }
        btnAccountLogout.setOnClickListener {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
            YouTubeAccountStore.clear(this)
            refreshAccountUi()
            Toast.makeText(this, "YouTube account removed", Toast.LENGTH_SHORT).show()
        }
        btnAccountHistory.setOnClickListener {
            startActivity(YouTubeAccountWebActivity.intent(this, "History", HISTORY_URL))
        }
        btnAccountLibrary.setOnClickListener {
            startActivity(YouTubeAccountWebActivity.intent(this, "Library", LIBRARY_URL))
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
        refreshAccountUi()
    }

    private fun refreshAccountUi() {
        val account = YouTubeAccountStore.read(this)
        val isLoggedIn = account.isLoggedIn
        account.avatarUrl.takeIf { it.isNotBlank() }?.let { avatarUrl ->
            ivAccountAvatar.load(avatarUrl) {
                crossfade(true)
                transformations(RoundedCornersTransformation(999f))
            }
        } ?: ivAccountAvatar.setImageResource(R.drawable.ml_library_music)
        tvAccountStatus.text = if (isLoggedIn) {
            account.accountName.ifBlank { "Logged in" }
        } else {
            "Guest"
        }
        tvAccountSummary.text = if (isLoggedIn) {
            buildString {
                append("Using stored YouTube Music auth for browse/search requests")
                if (account.visitorData.isNotBlank()) append(" • visitor data ready")
                if (account.dataSyncId.isNotBlank()) append(" • sync id ready")
            }
        } else {
            "Sign in with your Metrolist YouTube auth values for better private/library access."
        }
        accountSurfaceRow.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        btnAccountLogin.text = if (isLoggedIn) "Re-login" else "Google login"
        btnAccountLogout.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
    }

    private companion object {
        const val HISTORY_URL = "https://music.youtube.com/history"
        const val LIBRARY_URL = "https://music.youtube.com/library/playlists"
    }
}

