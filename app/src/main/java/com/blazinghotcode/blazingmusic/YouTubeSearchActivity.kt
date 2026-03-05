package com.blazinghotcode.blazingmusic

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * YouTube Music search/browse screen with in-app audio playback handoff to MainActivity.
 */
class YouTubeSearchActivity : AppCompatActivity() {
    private lateinit var btnBack: ImageButton
    private lateinit var etQuery: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var tvFilterLabel: TextView
    private lateinit var btnSearchFilter: Button
    private lateinit var rvResults: RecyclerView
    private lateinit var tvState: TextView
    private lateinit var browseHeaderContainer: View
    private lateinit var ivBrowseHeaderArt: ImageView
    private lateinit var tvBrowseHeaderTitle: TextView
    private lateinit var tvBrowseHeaderSubtitle: TextView
    private lateinit var adapter: YouTubeSearchAdapter

    private val apiClient by lazy { YouTubeApiClient(applicationContext) }
    private var activeJob: Job? = null

    private var lastQuery: String = ""
    private var selectedFilter: YouTubeSearchFilter = YouTubeSearchFilter.ALL
    private var lastSearchResults: List<YouTubeVideo> = emptyList()
    private val browseStack = mutableListOf<BrowseRequest>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_search)
        selectedFilter = YouTubeSearchFilter.defaultFromResources(resources)

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
        tvFilterLabel = findViewById(R.id.tvSongsOnlyLabel)
        btnSearchFilter = findViewById(R.id.btnSearchFilter)
        rvResults = findViewById(R.id.rvYouTubeResults)
        tvState = findViewById(R.id.tvYouTubeState)
        browseHeaderContainer = findViewById(R.id.browseHeaderContainer)
        ivBrowseHeaderArt = findViewById(R.id.ivBrowseHeaderArt)
        tvBrowseHeaderTitle = findViewById(R.id.tvBrowseHeaderTitle)
        tvBrowseHeaderSubtitle = findViewById(R.id.tvBrowseHeaderSubtitle)
        btnSearchFilter.text = selectedFilter.displayName
        updateBrowseUiState()
    }

    private fun setupList() {
        adapter = YouTubeSearchAdapter(
            onVideoClick = { item -> onItemClicked(item) },
            onItemMenuClick = { item, anchor -> showItemMenu(item, anchor) }
        )
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
        btnSearchFilter.setOnClickListener { showFilterMenu() }
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
        updateBrowseUiState()
        activeJob?.cancel()
        activeJob = lifecycleScope.launch {
            showState("Searching...")
            val results = runCatching {
                apiClient.searchMusicVideos(query, filter = selectedFilter)
            }.getOrElse {
                emptyList()
            }
            lastSearchResults = results
            adapter.submitList(results)
            if (results.isEmpty()) {
                showState("No results found.")
            } else {
                showState("Showing ${selectedFilter.displayName.lowercase()} results.")
            }
        }
    }

    private fun showFilterMenu() {
        if (browseStack.isNotEmpty()) return
        val popup = PopupMenu(this, btnSearchFilter)
        YouTubeSearchFilter.entries.forEachIndexed { index, filter ->
            popup.menu.add(0, index, index, filter.displayName)
        }
        popup.setOnMenuItemClickListener { item ->
            val chosen = YouTubeSearchFilter.entries.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            selectedFilter = chosen
            btnSearchFilter.text = selectedFilter.displayName
            showState("Filter: ${selectedFilter.displayName}")
            true
        }
        popup.show()
    }

    private fun onItemClicked(item: YouTubeVideo) {
        when {
            !item.videoId.isNullOrBlank() -> playInApp(item)
            !item.browseId.isNullOrBlank() -> openBrowse(item)
            else -> showToast("This item cannot be opened yet")
        }
    }

    private fun showItemMenu(item: YouTubeVideo, anchor: View) {
        val popup = PopupMenu(this, anchor)
        when (item.type) {
            YouTubeItemType.SONG -> popup.menu.add(0, 1, 0, "Play now")
            YouTubeItemType.ALBUM -> {
                popup.menu.add(0, 2, 0, "Open album")
                popup.menu.add(0, 3, 1, "Play now")
            }
            else -> return
        }
        popup.setOnMenuItemClickListener { menu ->
            when (menu.itemId) {
                1 -> {
                    playInApp(item)
                    true
                }
                2 -> {
                    if (!item.browseId.isNullOrBlank()) openBrowse(item) else showToast("Album page unavailable")
                    true
                }
                3 -> {
                    playInApp(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openBrowse(item: YouTubeVideo) {
        val browseId = item.browseId ?: return
        val request = BrowseRequest(
            browseId = browseId,
            params = item.browseParams,
            title = item.title,
            subtitle = item.channelTitle,
            thumbnailUrl = item.thumbnailUrl,
            itemType = item.type
        )
        browseStack.add(request)
        loadBrowse(request)
        updateBrowseUiState()
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
                showState("Browsing ${request.itemTypeLabel()}.")
            }
        }
    }

    private fun updateBrowseUiState() {
        val current = browseStack.lastOrNull()
        val inBrowseMode = current != null
        etQuery.visibility = if (inBrowseMode) View.GONE else View.VISIBLE
        btnSearch.visibility = if (inBrowseMode) View.GONE else View.VISIBLE
        tvFilterLabel.visibility = if (inBrowseMode) View.GONE else View.VISIBLE
        btnSearchFilter.visibility = if (inBrowseMode) View.GONE else View.VISIBLE
        browseHeaderContainer.visibility = if (inBrowseMode) View.VISIBLE else View.GONE

        if (current != null) {
            tvBrowseHeaderTitle.text = current.title
            val subtitle = buildString {
                append(current.itemTypeLabel())
                if (current.subtitle.isNotBlank()) {
                    append(" • ")
                    append(current.subtitle)
                }
            }
            tvBrowseHeaderSubtitle.text = subtitle
            val artworkUrl = YouTubeThumbnailUtils.toPlaybackArtworkUrl(current.thumbnailUrl, null)
            if (artworkUrl != null) {
                ivBrowseHeaderArt.load(artworkUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ml_library_music)
                    error(R.drawable.ml_library_music)
                    transformations(RoundedCornersTransformation(16f))
                }
            } else {
                ivBrowseHeaderArt.setImageResource(R.drawable.ml_library_music)
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
            updateBrowseUiState()
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
            sourceVideoId = videoId,
            sourcePlaylistId = sourcePlaylistId,
            sourcePlaylistSetVideoId = sourcePlaylistSetVideoId,
            sourceParams = sourceParams,
            sourceIndex = sourceIndex,
            albumArtUri = YouTubeThumbnailUtils.toPlaybackArtworkUrl(thumbnailUrl, videoId)
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
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val itemType: YouTubeItemType
    ) {
        fun itemTypeLabel(): String = when (itemType) {
            YouTubeItemType.ARTIST -> "artist"
            YouTubeItemType.ALBUM -> "album"
            YouTubeItemType.PLAYLIST -> "playlist"
            YouTubeItemType.SONG -> "song"
            YouTubeItemType.VIDEO -> "video"
            YouTubeItemType.UNKNOWN -> "collection"
        }
    }
}
