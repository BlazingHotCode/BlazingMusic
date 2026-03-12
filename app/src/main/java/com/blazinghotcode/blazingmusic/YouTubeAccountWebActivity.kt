package com.blazinghotcode.blazingmusic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class YouTubeAccountWebActivity : AppCompatActivity() {
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var webViewPage: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_account_web)

        btnBack = findViewById(R.id.btnBack)
        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        webViewPage = findViewById(R.id.webViewPage)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "YouTube" }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { DEFAULT_URL }
        tvTitle.text = title

        btnBack.setOnClickListener { handleBackPress() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        webViewPage.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webViewPage.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                tvStatus.text = url?.removePrefix("https://") ?: "Ready"
            }
        }
        webViewPage.loadUrl(url)
    }

    private fun handleBackPress() {
        if (webViewPage.canGoBack()) {
            webViewPage.goBack()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        webViewPage.stopLoading()
        webViewPage.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val DEFAULT_URL = "https://music.youtube.com/"

        fun intent(context: Context, title: String, url: String): Intent {
            return Intent(context, YouTubeAccountWebActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_URL, url)
        }
    }
}
