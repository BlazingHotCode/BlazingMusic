package com.blazinghotcode.blazingmusic

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Embedded YouTube tab screen shown inside MainActivity.
 */
class YouTubeSearchFragment : Fragment() {
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
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

    private val apiClient by lazy { YouTubeApiClient() }
    private var activeJob: Job? = null
    private var selectedFilter: YouTubeSearchFilter = YouTubeSearchFilter.ALL

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
        return false
    }

    private fun bindViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        tvTitle = view.findViewById(R.id.tvTitle)
        etQuery = view.findViewById(R.id.etYouTubeSearch)
        btnSearch = view.findViewById(R.id.btnRunYouTubeSearch)
        tvFilterLabel = view.findViewById(R.id.tvSongsOnlyLabel)
        btnSearchFilter = view.findViewById(R.id.btnSearchFilter)
        rvResults = view.findViewById(R.id.rvYouTubeResults)
        tvState = view.findViewById(R.id.tvYouTubeState)
        browseHeaderContainer = view.findViewById(R.id.browseHeaderContainer)
        ivBrowseHeaderArt = view.findViewById(R.id.ivBrowseHeaderArt)
        tvBrowseHeaderTitle = view.findViewById(R.id.tvBrowseHeaderTitle)
        tvBrowseHeaderSubtitle = view.findViewById(R.id.tvBrowseHeaderSubtitle)
        applyHeaderTitleSizing(tvTitle)
        updateFilterButtonText()
        updateBrowseUiState()
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
        adapter = YouTubeSearchAdapter(
            onVideoClick = { item -> onItemClicked(item) },
            onItemMenuClick = { item, anchor -> showItemMenu(item, anchor) }
        )
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

        updateBrowseUiState()
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            showState("Searching...")
            val results = runCatching {
                apiClient.searchMusicVideos(query, filter = selectedFilter)
            }.getOrElse {
                emptyList()
            }
            adapter.submitList(results)
            if (results.isEmpty()) {
                showState("No results found.")
            } else {
                showState("Showing ${selectedFilter.displayName.lowercase()} results.")
            }
        }
    }

    private fun showFilterMenu() {
        val popup = PopupMenu(requireContext(), btnSearchFilter)
        YouTubeSearchFilter.entries.forEachIndexed { index, filter ->
            popup.menu.add(0, index, index, filter.displayName)
        }
        popup.setOnMenuItemClickListener { item ->
            val chosen = YouTubeSearchFilter.entries.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            selectedFilter = chosen
            updateFilterButtonText()
            showState("Filter: ${selectedFilter.displayName}")
            true
        }
        popup.show()
    }

    private fun updateFilterButtonText() {
        btnSearchFilter.text = selectedFilter.displayName
    }

    private fun onItemClicked(item: YouTubeVideo) {
        when {
            !item.videoId.isNullOrBlank() -> playInApp(item)
            !item.browseId.isNullOrBlank() -> openBrowse(item)
            else -> showToast("This item cannot be opened yet")
        }
    }

    private fun openBrowse(item: YouTubeVideo) {
        (activity as? MainActivity)?.openYouTubeBrowse(item)
    }

    private fun showItemMenu(item: YouTubeVideo, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        when (item.type) {
            YouTubeItemType.SONG -> {
                popup.menu.add(0, MENU_PLAY_NOW, 0, "Play now")
                popup.menu.add(0, MENU_PLAY_NEXT, 1, "Play next")
                popup.menu.add(0, MENU_ADD_QUEUE, 2, "Add to queue")
                popup.menu.add(0, MENU_SONG_RADIO_UP_NEXT, 3, "Song radio (Up next)")
            }
            YouTubeItemType.ALBUM -> {
                popup.menu.add(0, MENU_OPEN_ALBUM, 0, "Open album")
            }
            else -> return
        }
        popup.setOnMenuItemClickListener { menu ->
            when (menu.itemId) {
                MENU_PLAY_NOW -> {
                    playInApp(item)
                    true
                }
                MENU_PLAY_NEXT -> {
                    enqueueSong(item, playNext = true)
                    true
                }
                MENU_ADD_QUEUE -> {
                    enqueueSong(item, playNext = false)
                    true
                }
                MENU_SONG_RADIO_UP_NEXT -> {
                    startSongRadioUpNext(item)
                    true
                }
                MENU_OPEN_ALBUM -> {
                    if (!item.browseId.isNullOrBlank()) openBrowse(item) else showToast("Album page unavailable")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun updateBrowseUiState() {
        etQuery.visibility = View.VISIBLE
        btnSearch.visibility = View.VISIBLE
        tvFilterLabel.visibility = View.VISIBLE
        btnSearchFilter.visibility = View.VISIBLE
        browseHeaderContainer.visibility = View.GONE
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
            val streamPlayable = runCatching {
                apiClient.isStreamPlayable(streamUrl)
            }.getOrDefault(false)
            if (!streamPlayable) {
                showState("In-app stream blocked. Opening YouTube Music fallback.")
                openInYouTubeMusic(videoId)
                return@launch
            }

            val playableSong = item.toSong(streamUrl)
            (activity as? MainActivity)?.playTemporaryQueue(listOf(playableSong), 0)
            showState("Starting playback...")
        }
    }

    private fun enqueueSong(item: YouTubeVideo, playNext: Boolean) {
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            val song = resolvePlayableSong(item) ?: run {
                showToast("Could not resolve this song")
                return@launch
            }
            val host = activity as? MainActivity ?: return@launch
            if (playNext) {
                host.addSongToPlayNext(song)
                showToast("Added to play next")
            } else {
                host.addSongToCurrentQueue(song)
                showToast("Added to queue")
            }
        }
    }

    private fun startSongRadioUpNext(seedItem: YouTubeVideo) {
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            val seedVideoId = seedItem.videoId ?: run {
                showToast("Radio unavailable for this item")
                return@launch
            }
            val host = activity as? MainActivity ?: return@launch
            showState("Building song radio...")

            val candidates = runCatching {
                apiClient.fetchRadioCandidates(
                    videoId = seedVideoId,
                    playlistId = seedItem.sourcePlaylistId,
                    playlistSetVideoId = seedItem.sourcePlaylistSetVideoId,
                    params = seedItem.sourceParams,
                    index = seedItem.sourceIndex,
                    fallbackQuery = "${seedItem.channelTitle} ${seedItem.title}",
                    maxResults = 80
                )
            }
                .getOrDefault(emptyList())
                .asSequence()
                .distinctBy { it.videoId ?: it.id }
                .toList()

            val resolved = mutableListOf<Song>()
            resolved += resolveRadioSongsFast(candidates)
            if (resolved.isEmpty()) {
                showToast("No radio recommendations found")
                showState("No radio recommendations found.")
                return@launch
            }
            host.replaceUpcomingQueue(resolved)
            showToast("Radio ready (${resolved.size} up next)")
            showState("Radio ready.")
        }
    }

    private suspend fun resolvePlayableSong(item: YouTubeVideo): Song? {
        val videoId = item.videoId ?: return null
        val streamUrl = runCatching { apiClient.resolveAudioStreamUrl(videoId) }.getOrNull() ?: return null
        val playable = runCatching { apiClient.isStreamPlayable(streamUrl) }.getOrDefault(false)
        if (!playable) return null
        return item.toSong(streamUrl)
    }

    private suspend fun resolveRadioSongsFast(items: List<YouTubeVideo>): List<Song> = coroutineScope {
        val semaphore = Semaphore(RADIO_RESOLVE_PARALLELISM)
        items
            .take(RADIO_CANDIDATE_LIMIT)
            .mapIndexed { index, item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val videoId = item.videoId ?: return@withPermit null
                        val streamUrl = runCatching { apiClient.resolveAudioStreamUrl(videoId) }.getOrNull() ?: return@withPermit null
                        index to item.toSong(streamUrl)
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .sortedBy { it.first }
            .map { it.second }
            .take(RADIO_TARGET_SIZE)
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
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun openInYouTubeMusic(videoId: String) {
        val context = context ?: return
        val musicIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://music.youtube.com/watch?v=$videoId")
        ).apply {
            `package` = "com.google.android.apps.youtube.music"
        }
        if (musicIntent.resolveActivity(context.packageManager) == null) {
            showToast("YouTube Music app not installed")
            return
        }
        runCatching { startActivity(musicIntent) }
            .onFailure { showToast("Unable to open YouTube Music") }
    }

    private fun showState(message: String) {
        tvState.text = message
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private companion object {
        private const val MENU_PLAY_NOW = 1
        private const val MENU_PLAY_NEXT = 2
        private const val MENU_ADD_QUEUE = 3
        private const val MENU_SONG_RADIO_UP_NEXT = 4
        private const val MENU_OPEN_ALBUM = 5
        private const val RADIO_TARGET_SIZE = 30
        private const val RADIO_CANDIDATE_LIMIT = 60
        private const val RADIO_RESOLVE_PARALLELISM = 6
    }

}
