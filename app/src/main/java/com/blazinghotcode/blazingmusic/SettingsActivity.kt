package com.blazinghotcode.blazingmusic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

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
    private lateinit var tvAccountStatus: TextView
    private lateinit var tvAccountSummary: TextView
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
        tvAccountStatus = findViewById(R.id.tvAccountStatus)
        tvAccountSummary = findViewById(R.id.tvAccountSummary)
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
        btnAccountLogin.setOnClickListener { showAccountLoginDialog() }
        btnAccountLogout.setOnClickListener {
            YouTubeAccountStore.clear(this)
            refreshAccountUi()
            Toast.makeText(this, "YouTube account removed", Toast.LENGTH_SHORT).show()
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
        btnAccountLogin.text = if (isLoggedIn) "Edit login" else "Add login"
        btnAccountLogout.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
    }

    private fun showAccountLoginDialog() {
        val existing = YouTubeAccountStore.read(this)
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_youtube_account_login, null, false)
        val etAccountName = content.findViewById<EditText>(R.id.etAccountName)
        val etVisitorData = content.findViewById<EditText>(R.id.etVisitorData)
        val etDataSyncId = content.findViewById<EditText>(R.id.etDataSyncId)
        val etCookie = content.findViewById<EditText>(R.id.etCookie)

        etAccountName.setText(existing.accountName)
        etVisitorData.setText(existing.visitorData)
        etDataSyncId.setText(existing.dataSyncId)
        etCookie.setText(existing.cookie)

        AlertDialog.Builder(this)
            .setTitle(if (existing.isLoggedIn) "Edit YouTube account" else "Add YouTube account")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val cookie = etCookie.text?.toString().orEmpty().trim()
                        if (cookie.isBlank()) {
                            etCookie.error = "Cookie is required"
                            return@setOnClickListener
                        }
                        YouTubeAccountStore.save(
                            context = this,
                            cookie = cookie,
                            visitorData = etVisitorData.text?.toString().orEmpty(),
                            dataSyncId = etDataSyncId.text?.toString().orEmpty(),
                            accountName = etAccountName.text?.toString().orEmpty()
                        )
                        refreshAccountUi()
                        Toast.makeText(this, "YouTube account saved", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }
}

