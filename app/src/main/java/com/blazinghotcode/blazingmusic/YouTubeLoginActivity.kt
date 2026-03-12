package com.blazinghotcode.blazingmusic

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class YouTubeLoginActivity : AppCompatActivity() {
    private lateinit var btnBack: ImageButton
    private lateinit var tvLoginStatus: TextView
    private lateinit var webViewLogin: WebView

    private val apiClient by lazy { YouTubeApiClient(applicationContext) }
    private var visitorData: String = ""
    private var dataSyncId: String = ""
    private var completedLogin = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_login)

        btnBack = findViewById(R.id.btnBack)
        tvLoginStatus = findViewById(R.id.tvLoginStatus)
        webViewLogin = findViewById(R.id.webViewLogin)

        btnBack.setOnClickListener { handleBackPress() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webViewLogin, true)

        webViewLogin.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webViewLogin.addJavascriptInterface(LoginJavascriptBridge(), "Android")
        webViewLogin.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                tvLoginStatus.text = when {
                    url.isNullOrBlank() -> "Loading sign-in page..."
                    url.startsWith("https://accounts.google.com") -> "Sign in with your Google account"
                    url.startsWith("https://music.youtube.com") -> "Finishing YouTube Music login..."
                    else -> "Loading..."
                }
                view.loadUrl("javascript:Android.onRetrieveVisitorData(window.yt && window.yt.config_ ? window.yt.config_.VISITOR_DATA : '')")
                view.loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt && window.yt.config_ ? window.yt.config_.DATASYNC_ID : '')")

                if (url?.startsWith("https://music.youtube.com") == true && !completedLogin) {
                    val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                    if (cookie.isBlank()) return
                    completedLogin = true
                    completeLogin(cookie)
                }
            }
        }
        webViewLogin.loadUrl(LOGIN_URL)
    }

    private fun handleBackPress() {
        if (webViewLogin.canGoBack()) {
            webViewLogin.goBack()
        } else {
            finish()
        }
    }

    private fun completeLogin(cookie: String) {
        tvLoginStatus.text = "Saving login..."
        val normalizedDataSyncId = dataSyncId.substringBefore("||").trim()
        lifecycleScope.launch {
            val profile = runCatching {
                apiClient.fetchAccountProfile(cookie, visitorData, normalizedDataSyncId)
            }.getOrNull()

            YouTubeAccountStore.save(
                context = this@YouTubeLoginActivity,
                cookie = cookie,
                visitorData = visitorData,
                dataSyncId = normalizedDataSyncId,
                accountName = profile?.name.orEmpty(),
                avatarUrl = profile?.avatarUrl.orEmpty()
            )
            Toast.makeText(this@YouTubeLoginActivity, "YouTube account connected", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@YouTubeLoginActivity, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
    }

    override fun onDestroy() {
        webViewLogin.removeJavascriptInterface("Android")
        webViewLogin.stopLoading()
        webViewLogin.destroy()
        super.onDestroy()
    }

    private inner class LoginJavascriptBridge {
        @JavascriptInterface
        fun onRetrieveVisitorData(value: String?) {
            visitorData = value.orEmpty()
        }

        @JavascriptInterface
        fun onRetrieveDataSyncId(value: String?) {
            dataSyncId = value.orEmpty()
        }
    }

    private companion object {
        const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    }
}
