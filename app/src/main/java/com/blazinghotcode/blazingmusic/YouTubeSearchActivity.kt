package com.blazinghotcode.blazingmusic

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * YouTube Music search/browse screen with in-app audio playback handoff to MainActivity.
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
    private var activeJob: Job? = null

    private var lastQuery: String = ""
    private var lastSongsOnly: Boolean = true
    private var lastSearchResults: List<YouTubeVideo> = emptyList()
    private val browseStack = mutableListOf<BrowseRequest>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_search)

        bindViews()
        setupList()
        setupActions()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBackNavigation()) {
                    finish()
                }
            }
        })

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
        adapter = YouTubeSearchAdapter { item -> onItemClicked(item) }
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter
    }

    private fun setupActions() {
        btnBack.setOnClickListener {
            if (!handleBackNavigation()) {
                finish()
            }
        }
        btnSearch.setOnClickListener { runSearch() }
        switchSongsOnly.setOnCheckedChangeListener { _, _ ->
            if (browseStack.isNotEmpty()) return@setOnCheckedChangeListener
            val mode = if (switchSongsOnly.isChecked) {
                "Songs-only filter enabled."
            } else {
                "Showing broader music results."
            }
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

        browseStack.clear()
        lastQuery = query
        lastSongsOnly = switchSongsOnly.isChecked
        activeJob?.cancel()
        activeJob = lifecycleScope.launch {
            showState("Searching...")
            val results = runCatching {
                apiClient.searchMusicVideos(query, songsOnly = lastSongsOnly)
            }.getOrElse {
                emptyList()
            }
            lastSearchResults = results
            adapter.submitList(results)
            if (results.isEmpty()) {
                showState("No results found.")
            } else {
                showState("Tap songs/videos to play in app, or open artists/albums/playlists.")
            }
        }
    }

    private fun onItemClicked(item: YouTubeVideo) {
        when {
            !item.videoId.isNullOrBlank() -> playInApp(item)
            !item.browseId.isNullOrBlank() -> openBrowse(item)
            else -> showToast("This item cannot be opened yet")
        }
    }

    private fun openBrowse(item: YouTubeVideo) {
        val browseId = item.browseId ?: return
        val request = BrowseRequest(
            browseId = browseId,
            params = item.browseParams,
            title = item.title
        )
        browseStack.add(request)
        loadBrowse(request)
    }

    private fun loadBrowse(request: BrowseRequest) {
        activeJob?.cancel()
        activeJob = lifecycleScope.launch {
            showState("Loading ${request.title}...")
            val results = runCatching {
                apiClient.browseCollection(request.browseId, request.params)
            }.getOrElse {
                emptyList()
            }
            adapter.submitList(results)
            if (results.isEmpty()) {
                showState("No items found in ${request.title}.")
            } else {
                showState("${request.title}: tap songs/videos to play, tap collections to drill down.")
            }
        }
    }

    private fun playInApp(item: YouTubeVideo) {
        val videoId = item.videoId ?: return
        activeJob?.cancel()
        activeJob = lifecycleScope.launch {
            showState("Resolving audio stream...")
            val streamUrl = runCatching {
                apiClient.resolveAudioStreamUrl(videoId)
            }.getOrNull()

            if (streamUrl.isNullOrBlank()) {
                showState("Unable to resolve playable audio stream for this item.")
                showToast("Could not start playback for this track")
                return@launch
            }

            val playableSong = item.toSong(streamUrl)
            PendingPlaybackStore.put(listOf(playableSong), 0)
            startActivity(
                Intent(this@YouTubeSearchActivity, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_PLAY_PENDING_QUEUE
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            showState("Starting playback...")
            finish()
        }
    }

    private fun handleBackNavigation(): Boolean {
        if (browseStack.isEmpty()) return false
        browseStack.removeLastOrNull()
        if (browseStack.isEmpty()) {
            adapter.submitList(lastSearchResults)
            showState("Back to search results for \"$lastQuery\".")
        } else {
            loadBrowse(browseStack.last())
        }
        return true
    }

    private fun YouTubeVideo.toSong(streamUrl: String): Song {
        val stableId = (id.hashCode().toLong() and 0x7fffffffL) + 10_000_000_000L
        val normalizedArtist = channelTitle.ifBlank { "YouTube Music" }
        return Song(
            id = stableId,
            title = title,
            artist = normalizedArtist,
            album = "YouTube",
            duration = 0L,
            dateAddedSeconds = System.currentTimeMillis() / 1000,
            path = streamUrl,
            albumArtUri = thumbnailUrl
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showState(message: String) {
        tvState.text = message
    }

    private data class BrowseRequest(
        val browseId: String,
        val params: String?,
        val title: String
    )
}
