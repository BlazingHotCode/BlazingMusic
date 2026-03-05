package com.blazinghotcode.blazingmusic

import android.os.Bundle
import android.os.Build
import android.text.Layout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Embedded YouTube tab screen shown inside MainActivity.
 */
class YouTubeSearchFragment : Fragment() {
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_youtube_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        applyHeaderInsets(view)
        setupList()
        setupActions()
        showState("Search YouTube music sources.")
    }

    override fun onDestroyView() {
        activeJob?.cancel()
        super.onDestroyView()
    }

    fun handleBackNavigation(): Boolean {
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

    private fun bindViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        tvTitle = view.findViewById(R.id.tvTitle)
        etQuery = view.findViewById(R.id.etYouTubeSearch)
        btnSearch = view.findViewById(R.id.btnRunYouTubeSearch)
        switchSongsOnly = view.findViewById(R.id.switchSongsOnly)
        rvResults = view.findViewById(R.id.rvYouTubeResults)
        tvState = view.findViewById(R.id.tvYouTubeState)
        applyHeaderTitleSizing(tvTitle)
    }

    private fun applyHeaderTitleSizing(titleView: TextView) {
        val text = titleView.text?.toString().orEmpty().trim()
        val isSingleWord = text.isNotEmpty() && text.none { it.isWhitespace() }
        titleView.maxLines = if (isSingleWord) 1 else 2
        titleView.ellipsize = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            titleView.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            titleView.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
    }

    private fun applyHeaderInsets(root: View) {
        val topGap = dp(6)
        val back = btnBack
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (back.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = topInset + topGap
                back.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupList() {
        adapter = YouTubeSearchAdapter { item -> onItemClicked(item) }
        rvResults.layoutManager = LinearLayoutManager(requireContext())
        rvResults.adapter = adapter
    }

    private fun setupActions() {
        btnBack.setOnClickListener {
            if (!handleBackNavigation()) {
                (activity as? MainActivity)?.openHomeTab()
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
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
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
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
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
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
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
            (activity as? MainActivity)?.playTemporaryQueue(listOf(playableSong), 0)
            showState("Starting playback...")
        }
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
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showState(message: String) {
        tvState.text = message
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private data class BrowseRequest(
        val browseId: String,
        val params: String?,
        val title: String
    )
}
