package com.blazinghotcode.blazingmusic

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Embedded YouTube tab screen shown inside MainActivity.
 */
class YouTubeSearchFragment : Fragment() {
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var etQuery: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var chipGroupSearchFilters: ChipGroup
    private lateinit var tvRecentSearchesLabel: TextView
    private lateinit var chipGroupRecentSearches: ChipGroup
    private lateinit var rvResults: RecyclerView
    private lateinit var tvState: TextView
    private lateinit var browseHeaderContainer: View
    private lateinit var ivBrowseHeaderArt: ImageView
    private lateinit var tvBrowseHeaderTitle: TextView
    private lateinit var tvBrowseHeaderSubtitle: TextView
    private lateinit var adapter: YouTubeSearchAdapter

    private val apiClient by lazy { YouTubeApiClient(requireContext().applicationContext) }
    private var activeJob: Job? = null
    private var inputJob: Job? = null
    private var selectedFilter: YouTubeSearchFilter = YouTubeSearchFilter.ALL
    private var latestSuggestions: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_youtube_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedFilter = YouTubeSearchFilter.defaultFromResources(resources)
        bindViews(view)
        applyHeaderInsets(view)
        setupList()
        setupActions()
        showState("Search YouTube music sources.")
        val initialQuery = arguments?.getString(ARG_INITIAL_QUERY).orEmpty().trim()
        if (initialQuery.isNotEmpty()) {
            etQuery.setText(initialQuery)
            etQuery.setSelection(initialQuery.length)
            runSearch(triggeredByUser = true)
        }
    }

    override fun onDestroyView() {
        activeJob?.cancel()
        inputJob?.cancel()
        super.onDestroyView()
    }

    fun handleBackNavigation(): Boolean {
        val hasQuery = etQuery.text?.toString().orEmpty().isNotBlank()
        val hasResults = adapter.itemCount > 0
        val hasSuggestions = chipGroupRecentSearches.childCount > 0
        if (!hasQuery && !hasResults && !hasSuggestions) return false
        clearSearchSurface()
        return true
    }

    private fun bindViews(view: View) {
        btnBack = view.findViewById(R.id.btnBack)
        tvTitle = view.findViewById(R.id.tvTitle)
        etQuery = view.findViewById(R.id.etYouTubeSearch)
        btnSearch = view.findViewById(R.id.btnRunYouTubeSearch)
        chipGroupSearchFilters = view.findViewById(R.id.chipGroupSearchFilters)
        tvRecentSearchesLabel = view.findViewById(R.id.tvRecentSearchesLabel)
        chipGroupRecentSearches = view.findViewById(R.id.chipGroupRecentSearches)
        rvResults = view.findViewById(R.id.rvYouTubeResults)
        tvState = view.findViewById(R.id.tvYouTubeState)
        browseHeaderContainer = view.findViewById(R.id.browseHeaderContainer)
        ivBrowseHeaderArt = view.findViewById(R.id.ivBrowseHeaderArt)
        tvBrowseHeaderTitle = view.findViewById(R.id.tvBrowseHeaderTitle)
        tvBrowseHeaderSubtitle = view.findViewById(R.id.tvBrowseHeaderSubtitle)
        applyHeaderTitleSizing(tvTitle)
        bindFilterChips()
        bindRecentSearchChips()
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
        btnSearch.setOnClickListener { runSearch(triggeredByUser = true) }
        etQuery.setOnEditorActionListener { _, _, _ ->
            runSearch(triggeredByUser = true)
            true
        }
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                scheduleReactiveSearch()
            }
        })
        etQuery.requestFocus()
    }

    private fun scheduleReactiveSearch() {
        inputJob?.cancel()
        activeJob?.cancel()
        val query = etQuery.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            clearSearchSurface(preserveQuery = false)
            return
        }
        inputJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(260L)
            loadSuggestions(query)
        }
    }

    private fun loadSuggestions(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            latestSuggestions = emptyList()
            bindRecentSearchChips()
            return
        }
        updateBrowseUiState()
        adapter.clear()
        showState("Loading suggestions...")
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = runCatching {
                apiClient.searchSuggestions(trimmed)
            }.getOrDefault(emptyList())
            latestSuggestions = suggestions
            bindRecentSearchChips()
            showState(
                if (suggestions.isEmpty()) {
                    "Press search to see full results."
                } else {
                    "Tap a suggestion or press search."
                }
            )
            updateSuggestionVisibility(trimmed)
        }
    }

    private fun runSearch(triggeredByUser: Boolean) {
        val query = etQuery.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            clearSearchSurface(preserveQuery = false)
            return
        }

        updateBrowseUiState()
        inputJob?.cancel()
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            showState("Searching...")
            val results = runCatching {
                apiClient.searchMusicVideos(
                    query,
                    maxResults = if (selectedFilter == YouTubeSearchFilter.ALL) 36 else 28,
                    filter = selectedFilter
                )
            }.getOrElse {
                emptyList()
            }
            latestSuggestions = emptyList()
            adapter.submitResults(results, grouped = selectedFilter == YouTubeSearchFilter.ALL)
            YouTubeSearchHistoryStore.save(requireContext(), query)
            bindRecentSearchChips()
            if (results.isEmpty()) {
                showState("No results found.")
            } else {
                val prefix = if (triggeredByUser) "Showing" else "Updated"
                showState("$prefix ${selectedFilter.displayName.lowercase()} results.")
            }
            updateSuggestionVisibility(query)
        }
    }

    private fun bindFilterChips() {
        chipGroupSearchFilters.removeAllViews()
        YouTubeSearchFilter.entries.forEach { filter ->
            val chip = buildChip(filter.displayName, isCheckable = true)
            chip.isChecked = filter == selectedFilter
            chip.setOnClickListener {
                if (selectedFilter == filter) return@setOnClickListener
                selectedFilter = filter
                refreshFilterChipChecks()
                if (etQuery.text?.toString().orEmpty().trim().isNotEmpty()) {
                    runSearch(triggeredByUser = false)
                } else {
                    showState("Filter: ${selectedFilter.displayName}")
                }
            }
            chipGroupSearchFilters.addView(chip)
        }
    }

    private fun bindRecentSearchChips() {
        val context = context ?: return
        val query = etQuery.text?.toString().orEmpty().trim()
        val chipValues = if (query.isBlank()) {
            tvRecentSearchesLabel.text = "Recent searches"
            YouTubeSearchHistoryStore.load(context)
        } else {
            tvRecentSearchesLabel.text = "Suggestions"
            latestSuggestions
        }
        chipGroupRecentSearches.removeAllViews()
        chipValues.forEach { value ->
            val chip = buildChip(value, isCheckable = false)
            chip.setOnClickListener {
                etQuery.setText(value)
                etQuery.setSelection(value.length)
                runSearch(triggeredByUser = true)
            }
            chipGroupRecentSearches.addView(chip)
        }
        updateSuggestionVisibility(query)
    }

    private fun refreshFilterChipChecks() {
        for (index in 0 until chipGroupSearchFilters.childCount) {
            val chip = chipGroupSearchFilters.getChildAt(index) as? Chip ?: continue
            chip.isChecked = chip.text.toString() == selectedFilter.displayName
        }
    }

    private fun updateSuggestionVisibility(query: String) {
        val show = chipGroupRecentSearches.childCount > 0
        tvRecentSearchesLabel.visibility = if (show) View.VISIBLE else View.GONE
        chipGroupRecentSearches.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun buildChip(label: String, isCheckable: Boolean): Chip {
        return Chip(requireContext()).apply {
            text = label
            this.isCheckable = isCheckable
            isClickable = true
            setCheckedIconVisible(false)
            setEnsureMinTouchTargetSize(false)
        }
    }

    private fun clearSearchSurface(preserveQuery: Boolean = false) {
        inputJob?.cancel()
        activeJob?.cancel()
        if (!preserveQuery) {
            etQuery.text?.clear()
        }
        latestSuggestions = emptyList()
        adapter.clear()
        updateBrowseUiState()
        bindRecentSearchChips()
        showState("Search YouTube music sources.")
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
        chipGroupSearchFilters.visibility = View.VISIBLE
        browseHeaderContainer.visibility = View.GONE
        updateSuggestionVisibility(etQuery.text?.toString().orEmpty())
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

            var announced = false
            val resolved = resolveRadioSongsFast(candidates) { snapshot ->
                host.replaceUpcomingQueue(snapshot)
                if (!announced && snapshot.size >= RADIO_INITIAL_READY) {
                    showToast("Radio ready (${snapshot.size} up next)")
                    showState("Radio updating...")
                    announced = true
                }
            }
            if (resolved.isEmpty()) {
                showToast("No radio recommendations found")
                showState("No radio recommendations found.")
                return@launch
            }
            host.replaceUpcomingQueue(resolved)
            if (!announced) {
                showToast("Radio ready (${resolved.size} up next)")
            }
            showState("Radio ready (${resolved.size} up next).")
        }
    }

    private suspend fun resolvePlayableSong(item: YouTubeVideo): Song? {
        val videoId = item.videoId ?: return null
        val streamUrl = runCatching { apiClient.resolveAudioStreamUrl(videoId) }.getOrNull() ?: return null
        val playable = runCatching { apiClient.isStreamPlayable(streamUrl) }.getOrDefault(false)
        if (!playable) return null
        return item.toSong(streamUrl)
    }

    private suspend fun resolveRadioSongsFast(
        items: List<YouTubeVideo>,
        onProgress: ((List<Song>) -> Unit)? = null
    ): List<Song> = coroutineScope {
        val chunks = items
            .take(RADIO_CANDIDATE_LIMIT)
            .mapIndexed { index, item -> index to item }
            .chunked(RADIO_RESOLVE_PARALLELISM)

        val collected = mutableListOf<Pair<Int, Song>>()
        val seen = hashSetOf<String>()
        var publishedCount = 0

        for (chunk in chunks) {
            val resolvedBatch = chunk
                .map { (index, item) ->
                    async(Dispatchers.IO) {
                        val videoId = item.videoId ?: return@async null
                        val streamUrl = withTimeoutOrNull<String?>(RADIO_RESOLVE_TIMEOUT_MS) {
                            runCatching { apiClient.resolveAudioStreamUrlFast(videoId) }.getOrNull()
                        } ?: return@async null
                        val playable = runCatching { apiClient.isStreamPlayable(streamUrl) }.getOrDefault(false)
                        if (!playable) return@async null
                        index to item.toSong(streamUrl)
                    }
                }
                .awaitAll()
                .filterNotNull()

            resolvedBatch.forEach { (index, song) ->
                val key = song.sourceVideoId?.takeIf { it.isNotBlank() } ?: song.path
                if (seen.add(key)) {
                    collected += index to song
                }
            }

            val snapshot = collected
                .sortedBy { it.first }
                .map { it.second }
                .take(RADIO_TARGET_SIZE)

            if (snapshot.size >= RADIO_INITIAL_READY && snapshot.size > publishedCount) {
                onProgress?.invoke(snapshot)
                publishedCount = snapshot.size
            }
            if (snapshot.size >= RADIO_TARGET_SIZE) break
        }

        val final = collected
            .sortedBy { it.first }
            .map { it.second }
            .take(RADIO_TARGET_SIZE)

        if (final.size > publishedCount) {
            onProgress?.invoke(final)
        }
        final
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

    companion object {
        private const val ARG_INITIAL_QUERY = "initial_query"
        private const val MENU_PLAY_NOW = 1
        private const val MENU_PLAY_NEXT = 2
        private const val MENU_ADD_QUEUE = 3
        private const val MENU_SONG_RADIO_UP_NEXT = 4
        private const val MENU_OPEN_ALBUM = 5
        private const val RADIO_INITIAL_READY = 10
        private const val RADIO_TARGET_SIZE = 30
        private const val RADIO_CANDIDATE_LIMIT = 60
        private const val RADIO_RESOLVE_PARALLELISM = 6
        private const val RADIO_RESOLVE_TIMEOUT_MS = 6000L

        fun newInstance(initialQuery: String? = null): YouTubeSearchFragment {
            return YouTubeSearchFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_INITIAL_QUERY, initialQuery)
                }
            }
        }
    }

}
