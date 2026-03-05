package com.blazinghotcode.blazingmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Search YouTube Music sources (InnerTube) and hand off to YouTube Music app.
 * This activity intentionally does not support in-app or browser video playback.
 */
class YouTubeSearchActivity : AppCompatActivity() {
    private lateinit var btnBack: ImageButton
    private lateinit var etQuery: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var switchSongsOnly: Switch
    private lateinit var rvResults: RecyclerView
    private lateinit var tvState: TextView
    private lateinit var adapter: YouTubeSearchAdapter

    private val apiClient by lazy { YouTubeApiClient() }
    private var activeSearchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_search)

        bindViews()
        setupList()
        setupActions()
        showState("Search YouTube music sources.")
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        etQuery = findViewById(R.id.etYouTubeSearch)
        btnSearch = findViewById(R.id.btnRunYouTubeSearch)
        switchSongsOnly = findViewById(R.id.switchSongsOnly)
        rvResults = findViewById(R.id.rvYouTubeResults)
        tvState = findViewById(R.id.tvYouTubeState)
    }

    private fun setupList() {
        adapter = YouTubeSearchAdapter { video ->
            openInYouTubeMusic(video.id)
        }
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter
    }

    private fun setupActions() {
        btnBack.setOnClickListener { finish() }
        btnSearch.setOnClickListener { runSearch() }
        switchSongsOnly.setOnCheckedChangeListener { _, _ ->
            val mode = if (switchSongsOnly.isChecked) "Songs-only filter enabled." else "Showing broader video results."
            showState(mode)
        }
        etQuery.setOnEditorActionListener { _, _, _ ->
            runSearch()
            true
        }
    }

    private fun runSearch() {
        val query = etQuery.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            showState("Type a song/artist to search.")
            return
        }
        activeSearchJob?.cancel()
        activeSearchJob = lifecycleScope.launch {
            showState("Searching...")
            val songsOnly = switchSongsOnly.isChecked
            val results = runCatching {
                apiClient.searchMusicVideos(query, songsOnly = songsOnly)
            }.getOrElse {
                emptyList()
            }
            adapter.submitList(results)
            if (results.isEmpty()) {
                showState("No results found.")
            } else {
                showState("Tap a result to open in YouTube Music.")
            }
        }
    }

    private fun openInYouTubeMusic(videoId: String) {
        val musicIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://music.youtube.com/watch?v=$videoId")
        ).apply {
            `package` = "com.google.android.apps.youtube.music"
        }
        if (musicIntent.resolveActivity(packageManager) == null) {
            Toast.makeText(
                this,
                "YouTube Music app is required for playback handoff.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        runCatching { startActivity(musicIntent) }.onFailure {
            Toast.makeText(this, "Unable to open YouTube Music", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showState(message: String) {
        tvState.text = message
    }
}
