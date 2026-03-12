package com.blazinghotcode.blazingmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Dedicated destination page for a YouTube artist/album/playlist.
 * Header/about sections are rendered as the first RecyclerView item so scrolling is natural.
 */
class YouTubeBrowseFragment : Fragment() {
    private lateinit var btnBack: ImageButton
    private lateinit var btnPageOptions: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var rvBrowseResults: RecyclerView
    private lateinit var adapter: YouTubeBrowseAdapter

    private val apiClient by lazy { YouTubeApiClient(requireContext().applicationContext) }
    private val musicViewModel: MusicViewModel by activityViewModels()
    private var activeJob: Job? = null

    private var browseId: String = ""
    private var browseParams: String? = null
    private var browseTitle: String = ""
    private var browseSubtitle: String = ""
    private var browseThumb: String? = null
    private var browseType: YouTubeItemType = YouTubeItemType.UNKNOWN
    private var loadedItems: List<YouTubeVideo> = emptyList()
    private var remoteItems: List<YouTubeVideo> = emptyList()
    private var cachedQueueSignature: String? = null
    private var cachedResolvedQueue: List<Song> = emptyList()
    private var artistInfo: YouTubeArtistInfo? = null
    private var stateMessage: String = ""
    private var continuationToken: String? = null
    private var isLoadingMore = false
    private var playlistSortMode: PlaylistSortMode = PlaylistSortMode.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        browseId = args.getString(ARG_BROWSE_ID).orEmpty()
        browseParams = args.getString(ARG_BROWSE_PARAMS)
        browseTitle = args.getString(ARG_BROWSE_TITLE).orEmpty()
        browseSubtitle = args.getString(ARG_BROWSE_SUBTITLE).orEmpty()
        browseThumb = args.getString(ARG_BROWSE_THUMB)
        browseType = YouTubeItemType.entries.getOrNull(
            args.getInt(ARG_BROWSE_TYPE_ORDINAL, YouTubeItemType.UNKNOWN.ordinal)
        ) ?: YouTubeItemType.UNKNOWN
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_youtube_browse, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupList()
        setupActions()
        refreshHeader()
        loadBrowse()
    }

    override fun onDestroyView() {
        activeJob?.cancel()
        super.onDestroyView()
    }

    private fun bindViews(root: View) {
        btnBack = root.findViewById(R.id.btnBack)
        btnPageOptions = root.findViewById(R.id.btnPageOptions)
        tvTitle = root.findViewById(R.id.tvTitle)
        rvBrowseResults = root.findViewById(R.id.rvBrowseResults)
        tvTitle.text = browseTypeLabelCapitalized()
    }

    private fun setupList() {
        adapter = YouTubeBrowseAdapter(
            onItemClick = { item -> onItemClicked(item) },
            onItemMenuClick = { item, anchor -> showItemMenu(item, anchor) },
            onPlayAllClick = { shuffle -> playBrowseItems(shuffle) },
            onStartRadioClick = { startRadioFromCurrentContext() },
            onArtistOptionsClick = { showArtistPageOptionsDialog() },
            onSectionSeeAllClick = { sectionTitle, sectionBrowseId, sectionBrowseParams ->
                openSectionSeeAll(sectionTitle, sectionBrowseId, sectionBrowseParams)
            }
        )
        adapter.setHideItemThumbnails(browseType == YouTubeItemType.ALBUM)
        adapter.setAllowSongItemMenu(browseType == YouTubeItemType.PLAYLIST)
        musicViewModel.currentSong.observe(viewLifecycleOwner) { song ->
            adapter.setCurrentSong(song)
        }
        rvBrowseResults.layoutManager = LinearLayoutManager(requireContext())
        rvBrowseResults.adapter = adapter
        rvBrowseResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore || continuationToken.isNullOrBlank()) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val total = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= total - 6) {
                    loadMoreBrowse()
                }
            }
        })
    }

    private fun setupActions() {
        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        btnPageOptions.visibility = if (browseType == YouTubeItemType.PLAYLIST) View.VISIBLE else View.GONE
        btnPageOptions.setOnClickListener { showPlaylistPageOptions(it) }
    }

    private fun loadBrowse() {
        if (browseId.isBlank()) {
            showState("Unable to open this page.")
            return
        }
        cachedQueueSignature = null
        cachedResolvedQueue = emptyList()
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            showState("Loading ${browseTypeLabel()}...")
            val page = runCatching {
                apiClient.browseCollectionPage(browseId, browseParams)
            }.getOrNull() ?: YouTubeBrowsePage(emptyList())

            remoteItems = sanitizeRemoteItems(page.items)
            loadedItems = applyPlaylistSort(remoteItems)
            artistInfo = page.artistInfo
            continuationToken = page.continuation
            hydrateBrowseChrome()
            adapter.submit(loadedItems)

            if (loadedItems.isEmpty()) {
                showState("No items found.")
            } else {
                val personalized = if (YouTubeAccountStore.read(requireContext()).isLoggedIn) " for your account" else ""
                showState("Browsing ${browseTypeLabel()}$personalized.")
            }
        }
    }

    private fun loadMoreBrowse() {
        val continuation = continuationToken ?: return
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            val page = runCatching {
                apiClient.browseContinuationPage(continuation, maxResults = 120)
            }.getOrNull()
            isLoadingMore = false
            if (page == null || page.items.isEmpty()) {
                continuationToken = null
                return@launch
            }
            continuationToken = page.continuation
            remoteItems = sanitizeRemoteItems(remoteItems + page.items)
                .distinctBy { it.videoId ?: it.id }
            loadedItems = applyPlaylistSort(remoteItems)
            hydrateBrowseChrome()
            adapter.submit(loadedItems)
            showState("Loaded ${loadedItems.size} ${browseTypeLabel()} items.")
        }
    }

    private fun playBrowseItems(shuffle: Boolean) {
        if (browseType == YouTubeItemType.ARTIST && shuffle) {
            playArtistShuffleFast()
            return
        }
        val playableItems = loadedItems.filter { !it.videoId.isNullOrBlank() }
        if (playableItems.isEmpty()) {
            showToast("No playable songs found on this page")
            return
        }
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            SharedPlayer.getOrCreate(requireContext()).pause()
            showState("Loading queue...")

            val modePrefix = if (shuffle) "shuffle" else "ordered"
            val sourceSignature = "$modePrefix|" + playableItems.joinToString("|") { it.id }
            val usedCache = cachedQueueSignature == sourceSignature && cachedResolvedQueue.isNotEmpty()
            val resolvedSongs = if (usedCache) {
                cachedResolvedQueue
            } else {
                val orderedItems = if (shuffle) {
                    playableItems.shuffled(Random(System.currentTimeMillis()))
                } else {
                    playableItems
                }
                val resolved = buildQueueProgressively(
                    orderedItems = orderedItems,
                    sourceSignature = sourceSignature,
                    loadingLabel = "queue"
                )
                cachedQueueSignature = sourceSignature
                cachedResolvedQueue = resolved
                resolved
            }
            if (resolvedSongs.isEmpty()) {
                showState("Unable to resolve playable tracks from this page.")
                showToast("Could not start playback")
                return@launch
            }

            if (usedCache) {
                (activity as? MainActivity)?.playTemporaryQueue(resolvedSongs, 0)
                showState("Queue ready.")
            }
        }
    }

    private fun playArtistShuffleFast() {
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            SharedPlayer.getOrCreate(requireContext()).pause()
            showState("Starting artist shuffle...")

            val songItems = fetchAllArtistShuffleCandidates()
            if (songItems.isEmpty()) {
                showState("No playable songs found on this artist page")
                showToast("No playable songs found on this artist page")
                return@launch
            }

            val randomized = songItems.shuffled(Random(System.currentTimeMillis()))
            val firstResolved = resolveFirstPlayableSong(randomized)
            if (firstResolved == null) {
                showState("Unable to resolve playable songs from this artist.")
                showToast("Could not start shuffled playback")
                return@launch
            }

            val (firstItem, firstSong) = firstResolved
            (activity as? MainActivity)?.playTemporaryQueue(listOf(firstSong), 0)
            showState("Playing random artist song...")

            val remainingItems = randomized.filterNot {
                it.videoId == firstItem.videoId && it.id == firstItem.id
            }
            if (remainingItems.isEmpty()) {
                showState("Artist shuffle ready.")
                return@launch
            }

            // Build the rest of the shuffle queue in the background and append progressively
            // so playback continues immediately while the queue is still loading.
            launch {
                val total = remainingItems.size
                var loaded = 0
                var playableLoaded = 0
                val appendBuffer = mutableListOf<Song>()
                showState("Playing now. Building shuffle queue 0/$total...")

                remainingItems.forEach { item ->
                    loaded += 1
                    val playableSong = resolvePlayableSong(item)
                    if (playableSong != null) {
                        playableLoaded += 1
                        appendBuffer += playableSong
                        if (appendBuffer.size >= QUEUE_APPEND_BATCH_SIZE) {
                            (activity as? MainActivity)?.appendSongsToCurrentQueue(appendBuffer.toList())
                            appendBuffer.clear()
                        }
                    }
                    showState("Playing now. Building shuffle queue $loaded/$total...")
                }

                if (appendBuffer.isNotEmpty()) {
                    (activity as? MainActivity)?.appendSongsToCurrentQueue(appendBuffer.toList())
                }

                showState("Artist shuffle ready. Added $playableLoaded songs.")
            }
        }
    }

    private fun startRadioFromCurrentContext() {
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            SharedPlayer.getOrCreate(requireContext()).pause()
            showState("Starting radio...")

            val seed = findRadioSeedItem()
            if (seed == null) {
                showState("No song available for radio.")
                showToast("No song available for radio")
                return@launch
            }

            val firstSong = resolvePlayableSong(seed)
            if (firstSong == null) {
                showState("Unable to start radio from this item.")
                showToast("Could not start radio")
                return@launch
            }

            (activity as? MainActivity)?.playTemporaryQueue(listOf(firstSong), 0)
            showState("Playing radio seed...")

            launch radioBuild@{
                val seedVideoId = seed.videoId ?: return@radioBuild
                val candidates = runCatching {
                    apiClient.fetchRadioCandidates(
                        videoId = seedVideoId,
                        playlistId = seed.sourcePlaylistId,
                        playlistSetVideoId = seed.sourcePlaylistSetVideoId,
                        params = seed.sourceParams,
                        index = seed.sourceIndex,
                        fallbackQuery = "${seed.channelTitle} ${seed.title}",
                        maxResults = 80
                    )
                }
                    .getOrDefault(emptyList())
                    .asSequence()
                    .distinctBy { it.videoId ?: it.id }
                    .toList()

                if (candidates.isEmpty()) {
                    showState("Radio started. No extra recommendations yet.")
                    return@radioBuild
                }

                showState("Building radio recommendations...")
                var firstPublished = false
                val resolved = resolveRadioSongsFast(candidates) { snapshot ->
                    (activity as? MainActivity)?.replaceUpcomingQueue(snapshot)
                    if (!firstPublished && snapshot.size >= RADIO_INITIAL_READY) {
                        showState("Radio started. Loaded ${snapshot.size} recommendations.")
                        firstPublished = true
                    } else {
                        showState("Radio updating (${snapshot.size})...")
                    }
                }

                if (resolved.isEmpty()) {
                    showState("Radio started. No extra recommendations yet.")
                    return@radioBuild
                }

                (activity as? MainActivity)?.replaceUpcomingQueue(resolved)
                showState("Radio ready. Added ${resolved.size} recommendations.")
            }
        }
    }

    private suspend fun findRadioSeedItem(): YouTubeVideo? {
        val currentPageSeed = loadedItems.firstOrNull(::isArtistShuffleSongCandidate)
        if (currentPageSeed != null) return currentPageSeed

        if (browseType == YouTubeItemType.ARTIST) {
            val expanded = fetchAllArtistShuffleCandidates()
            return expanded.firstOrNull()
        }
        return null
    }

    private suspend fun fetchAllArtistShuffleCandidates(): List<YouTubeVideo> {
        val endpointKeys = linkedSetOf<Pair<String, String?>>()
        loadedItems.forEach { item ->
            val section = item.sectionTitle.orEmpty()
            if (
                section.contains("song", ignoreCase = true) &&
                !item.sectionBrowseId.isNullOrBlank()
            ) {
                endpointKeys += item.sectionBrowseId.orEmpty() to item.sectionBrowseParams
            }
        }

        val fetched = mutableListOf<YouTubeVideo>()
        endpointKeys.forEach { (browseId, browseParams) ->
            val sectionItems = runCatching {
                apiClient.browseCollection(
                    browseId = browseId,
                    params = browseParams,
                    maxResults = 2000
                )
            }.getOrDefault(emptyList())
            fetched += sectionItems
        }

        val source = if (fetched.isNotEmpty()) fetched else loadedItems
        return source
            .asSequence()
            .filter(::isArtistShuffleSongCandidate)
            .distinctBy { it.videoId ?: it.id }
            .toList()
    }

    private fun isArtistShuffleSongCandidate(item: YouTubeVideo): Boolean {
        if (item.videoId.isNullOrBlank()) return false
        if (item.type != YouTubeItemType.SONG) return false
        val section = item.sectionTitle.orEmpty()
        if (section.contains("video", ignoreCase = true)) return false
        val text = "${item.title} ${item.channelTitle}".lowercase()
        val blockedHints = listOf(
            "official music video",
            "music video",
            "lyric video",
            "lyrics video",
            "visualizer"
        )
        return blockedHints.none { hint -> text.contains(hint) }
    }

    private suspend fun resolvePlayableSong(item: YouTubeVideo): Song? {
        val videoId = item.videoId ?: return null
        val streamUrl = runCatching { apiClient.resolveAudioStreamUrl(videoId) }.getOrNull() ?: return null
        val streamPlayable = runCatching { apiClient.isStreamPlayable(streamUrl) }.getOrDefault(false)
        if (!streamPlayable) return null
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

    private suspend fun resolveFirstPlayableSong(items: List<YouTubeVideo>): Pair<YouTubeVideo, Song>? {
        for (item in items) {
            val song = resolvePlayableSong(item) ?: continue
            return item to song
        }
        return null
    }

    private suspend fun resolveSongsInParallel(items: List<YouTubeVideo>): List<Song> = coroutineScope {
        val useAlbumArtwork = browseType == YouTubeItemType.ALBUM
        items.mapIndexed { index, item ->
            async {
                val videoId = item.videoId ?: return@async null
                val streamUrl = runCatching { apiClient.resolveAudioStreamUrl(videoId) }.getOrNull() ?: return@async null
                val streamPlayable = runCatching { apiClient.isStreamPlayable(streamUrl) }.getOrDefault(false)
                if (!streamPlayable) return@async null
                val forcedArtwork = if (useAlbumArtwork) browseThumb else null
                index to item.toSong(streamUrl, forcedArtwork)
            }
        }.awaitAll()
            .filterNotNull()
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun onItemClicked(item: YouTubeVideo) {
        when {
            !item.videoId.isNullOrBlank() && browseType == YouTubeItemType.ALBUM -> playAlbumFromTappedSong(item)
            !item.videoId.isNullOrBlank() -> playInApp(item)
            !item.browseId.isNullOrBlank() -> openNestedBrowse(item)
            else -> showToast("This item cannot be opened yet")
        }
    }

    private fun playAlbumFromTappedSong(tappedItem: YouTubeVideo) {
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            SharedPlayer.getOrCreate(requireContext()).pause()
            showState("Loading album queue...")

            val albumItems = albumSongItemsForQueue(tappedItem)
            if (albumItems.isEmpty()) {
                showToast("No playable album songs found")
                showState("No playable album songs found.")
                return@launch
            }

            val tappedIndex = albumItems.indexOfFirst { it.videoId == tappedItem.videoId }.coerceAtLeast(0)
            val orderedItems = albumItems.drop(tappedIndex) + albumItems.take(tappedIndex)
            val sourceSignature = orderedItems.joinToString("|") { it.id }
            val resolvedSongs = buildQueueProgressively(
                orderedItems = orderedItems,
                sourceSignature = sourceSignature,
                loadingLabel = "album queue"
            )
            if (resolvedSongs.isEmpty()) {
                showToast("Could not start album playback")
                showState("Unable to resolve playable album songs.")
                return@launch
            }
            showState("Album queue ready.")
        }
    }

    private suspend fun buildQueueProgressively(
        orderedItems: List<YouTubeVideo>,
        sourceSignature: String,
        loadingLabel: String
    ): List<Song> {
        val firstResolved = resolveFirstPlayableSong(orderedItems) ?: return emptyList()
        val (firstItem, firstSong) = firstResolved
        val builtQueue = mutableListOf(firstSong)
        (activity as? MainActivity)?.playTemporaryQueue(builtQueue, 0)

        val remaining = orderedItems.filterNot {
            it.videoId == firstItem.videoId && it.id == firstItem.id
        }
        if (remaining.isEmpty()) return builtQueue

        var processed = 0
        val total = remaining.size
        for (chunk in remaining.chunked(QUEUE_APPEND_BATCH_SIZE)) {
            val resolvedChunk = resolveSongsInParallel(chunk)
            processed += chunk.size
            if (resolvedChunk.isNotEmpty()) {
                builtQueue += resolvedChunk
                (activity as? MainActivity)?.appendSongsToCurrentQueue(resolvedChunk)
            }
            showState("Playing now. Building $loadingLabel $processed/$total...")
        }

        cachedQueueSignature = sourceSignature
        cachedResolvedQueue = builtQueue.toList()
        return builtQueue
    }

    private fun albumSongItemsForQueue(tappedItem: YouTubeVideo): List<YouTubeVideo> {
        val queued = loadedItems
            .asSequence()
            .filter(::isArtistShuffleSongCandidate)
            .distinctBy { it.videoId ?: it.id }
            .toMutableList()

        val hasTapped = queued.any { it.videoId == tappedItem.videoId }
        if (!hasTapped) {
            queued.add(0, tappedItem)
        }
        return queued
    }

    private fun showItemMenu(item: YouTubeVideo, anchor: View) {
        if (browseType == YouTubeItemType.PLAYLIST && item.type == YouTubeItemType.SONG) {
            showPlaylistSongMenu(item, anchor)
            return
        }
        val popup = PopupMenu(requireContext(), anchor)
        when (item.type) {
            YouTubeItemType.ALBUM -> popup.menu.add(0, MENU_OPEN_BROWSE, 0, "Open album")
            YouTubeItemType.ARTIST -> popup.menu.add(0, MENU_OPEN_BROWSE, 0, "Open artist")
            YouTubeItemType.PLAYLIST -> popup.menu.add(0, MENU_OPEN_BROWSE, 0, "Open playlist")
            else -> return
        }
        popup.setOnMenuItemClickListener { menu ->
            when (menu.itemId) {
                MENU_OPEN_BROWSE -> {
                    if (!item.browseId.isNullOrBlank()) openNestedBrowse(item) else showToast("Page unavailable")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showPlaylistSongMenu(item: YouTubeVideo, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_PLAY_NEXT, 0, "Play next")
            menu.add(0, MENU_ADD_TO_QUEUE, 1, "Add to queue")
            menu.add(0, MENU_ADD_TO_PLAYLIST, 2, "Add to playlist")
            val canLike = !item.videoId.isNullOrBlank() &&
                YouTubeAccountStore.read(requireContext().applicationContext).isLoggedIn
            if (canLike) {
                menu.add(
                    0,
                    MENU_TOGGLE_LIKE,
                    3,
                    if (musicViewModel.isVideoLiked(item.videoId)) "Unlike" else "Like"
                )
            }
            menu.add(0, MENU_REMOVE_FROM_PLAYLIST, 4, "Remove from playlist")
            if (playlistSortMode == PlaylistSortMode.DEFAULT) {
                menu.add(0, MENU_MOVE_UP, 5, "Move up")
                menu.add(0, MENU_MOVE_DOWN, 6, "Move down")
            }
            setOnMenuItemClickListener { selected ->
                when (selected.itemId) {
                    MENU_PLAY_NEXT -> {
                        enqueuePlaylistSong(item, playNext = true)
                        true
                    }
                    MENU_ADD_TO_QUEUE -> {
                        enqueuePlaylistSong(item, playNext = false)
                        true
                    }
                    MENU_ADD_TO_PLAYLIST -> {
                        addPlaylistSongToPlaylist(item)
                        true
                    }
                    MENU_TOGGLE_LIKE -> {
                        togglePlaylistSongLike(item)
                        true
                    }
                    MENU_REMOVE_FROM_PLAYLIST -> {
                        removeSongFromCurrentPlaylist(item)
                        true
                    }
                    MENU_MOVE_UP -> {
                        moveSongInCurrentPlaylist(item, up = true)
                        true
                    }
                    MENU_MOVE_DOWN -> {
                        moveSongInCurrentPlaylist(item, up = false)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun togglePlaylistSongLike(item: YouTubeVideo) {
        val videoId = item.videoId
        if (videoId.isNullOrBlank()) {
            showToast("This track cannot be liked")
            return
        }
        val willLike = !musicViewModel.isVideoLiked(videoId)
        if (!willLike) {
            val success = musicViewModel.setSongLiked(
                Song(
                    id = videoId.hashCode().toLong(),
                    title = item.title,
                    artist = item.channelTitle.ifBlank { "YouTube Music" },
                    album = "YouTube Music",
                    duration = 0L,
                    dateAddedSeconds = 0L,
                    path = "https://music.youtube.com/watch?v=$videoId",
                    albumArtUri = item.thumbnailUrl,
                    sourceVideoId = videoId
                ),
                liked = false
            )
            if (success) showToast("Removed from Liked Music") else showToast("Sign in to like YouTube songs")
            return
        }

        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            val song = resolvePlayableSong(item)
            if (song == null) {
                showToast("Could not resolve this track")
                return@launch
            }
            val success = musicViewModel.setSongLiked(song, liked = true)
            if (success) showToast("Added to Liked Music") else showToast("Sign in to like YouTube songs")
        }
    }

    private fun addPlaylistSongToPlaylist(item: YouTubeVideo) {
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            val song = resolvePlayableSong(item)
            if (song == null) {
                showToast("Could not resolve this track")
                return@launch
            }
            (activity as? MainActivity)?.openAddToPlaylistDialog(song)
        }
    }

    private fun enqueuePlaylistSong(item: YouTubeVideo, playNext: Boolean) {
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            val song = resolvePlayableSong(item)
            if (song == null) {
                showToast("Could not resolve this track")
                return@launch
            }
            val main = activity as? MainActivity ?: return@launch
            if (playNext) {
                main.addSongToPlayNext(song)
                showToast("Queued to play next")
            } else {
                main.addSongToCurrentQueue(song)
                showToast("Added to queue")
            }
        }
    }

    private fun removeSongFromCurrentPlaylist(item: YouTubeVideo) {
        val playlistId = browseId.takeIf { it.isNotBlank() }
        val videoId = item.videoId
        val setVideoId = item.sourcePlaylistSetVideoId
        if (playlistId.isNullOrBlank() || videoId.isNullOrBlank() || setVideoId.isNullOrBlank()) {
            showToast("This playlist item cannot be edited")
            return
        }

        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            showState("Removing song from playlist...")
            val removed = runCatching {
                apiClient.removeFromPlaylist(
                    playlistId = playlistId,
                    videoId = videoId,
                    setVideoId = setVideoId
                )
            }.getOrDefault(false)
            if (!removed) {
                showState("Failed to update playlist.")
                showToast("Could not remove song")
                return@launch
            }

            val updated = remoteItems.toMutableList()
            val removeIndex = updated.indexOfFirst {
                it.sourcePlaylistSetVideoId == setVideoId ||
                    (it.videoId == videoId && it.id == item.id)
            }
            if (removeIndex in updated.indices) {
                updated.removeAt(removeIndex)
            }
            remoteItems = updated
            loadedItems = applyPlaylistSort(remoteItems)
            adapter.submit(loadedItems)
            showState("Song removed from playlist.")
            showToast("Removed from playlist")
        }
    }

    private fun moveSongInCurrentPlaylist(item: YouTubeVideo, up: Boolean) {
        if (playlistSortMode != PlaylistSortMode.DEFAULT) {
            showToast("Switch to YouTube order before syncing reorder changes")
            return
        }
        val playlistId = browseId.takeIf { it.isNotBlank() }
        val currentSetVideoId = item.sourcePlaylistSetVideoId
        if (playlistId.isNullOrBlank() || currentSetVideoId.isNullOrBlank()) {
            showToast("This playlist item cannot be reordered")
            return
        }

        val currentIndex = remoteItems.indexOfFirst { it.sourcePlaylistSetVideoId == currentSetVideoId }
        if (currentIndex !in remoteItems.indices) return
        val targetIndex = if (up) currentIndex - 1 else currentIndex + 1
        if (targetIndex !in remoteItems.indices) return

        val successorSetVideoId = if (up) {
            remoteItems[targetIndex].sourcePlaylistSetVideoId
        } else {
            remoteItems.getOrNull(targetIndex + 1)?.sourcePlaylistSetVideoId
        }

        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            showState("Updating playlist order...")
            val moved = runCatching {
                apiClient.moveSongInPlaylist(
                    playlistId = playlistId,
                    setVideoId = currentSetVideoId,
                    successorSetVideoId = successorSetVideoId
                )
            }.getOrDefault(false)
            if (!moved) {
                showState("Failed to update playlist order.")
                showToast("Could not reorder song")
                return@launch
            }

            val mutable = remoteItems.toMutableList()
            val movedSong = mutable.removeAt(currentIndex)
            val insertAt = if (up) {
                targetIndex
            } else {
                (targetIndex + 1).coerceAtMost(mutable.size)
            }
            mutable.add(insertAt, movedSong)
            remoteItems = mutable
            loadedItems = applyPlaylistSort(remoteItems)
            adapter.submit(loadedItems)
            showState("Playlist order updated.")
            showToast("Playlist reordered")
        }
    }

    private fun openNestedBrowse(item: YouTubeVideo) {
        val id = item.browseId ?: return
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            .replace(
                R.id.youtubeContainer,
                newInstance(
                    browseId = id,
                    browseParams = item.browseParams,
                    title = item.title,
                    subtitle = item.channelTitle,
                    thumbUrl = item.thumbnailUrl,
                    type = item.type
                )
            )
            .addToBackStack("youtube_browse")
            .commit()
    }

    private fun openSectionSeeAll(sectionTitle: String, sectionBrowseId: String, sectionBrowseParams: String?) {
        val normalized = sectionTitle.lowercase()
        val sectionThumb = loadedItems.firstOrNull {
            it.sectionTitle.equals(sectionTitle, ignoreCase = true) && !it.thumbnailUrl.isNullOrBlank()
        }?.thumbnailUrl ?: browseThumb
        val sectionSubtitle = buildString {
            append(browseTitle)
            val relatedCount = loadedItems.count { it.sectionTitle.equals(sectionTitle, ignoreCase = true) }
            if (relatedCount > 0) {
                append(" • ")
                append("$relatedCount items")
            }
        }
        val sectionType = when {
            normalized.contains("album") || normalized.contains("single") || normalized.contains("ep") -> YouTubeItemType.ALBUM
            normalized.contains("artist") -> YouTubeItemType.ARTIST
            normalized.contains("playlist") -> YouTubeItemType.PLAYLIST
            normalized.contains("song") -> YouTubeItemType.SONG
            else -> YouTubeItemType.UNKNOWN
        }
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            .replace(
                R.id.youtubeContainer,
                newInstance(
                    browseId = sectionBrowseId,
                    browseParams = sectionBrowseParams,
                    title = sectionTitle,
                    subtitle = sectionSubtitle,
                    thumbUrl = sectionThumb,
                    type = sectionType
                )
            )
            .addToBackStack("youtube_browse")
            .commit()
    }

    private fun showPlaylistPageOptions(anchor: View) {
        if (browseType != YouTubeItemType.PLAYLIST) return
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_SORT_DEFAULT, 0, "Sort: YouTube order")
            menu.add(0, MENU_SORT_TITLE, 1, "Sort: Title (local)")
            menu.add(0, MENU_SORT_ARTIST, 2, "Sort: Artist (local)")
            setOnMenuItemClickListener { item ->
                val nextSort = when (item.itemId) {
                    MENU_SORT_DEFAULT -> PlaylistSortMode.DEFAULT
                    MENU_SORT_TITLE -> PlaylistSortMode.TITLE
                    MENU_SORT_ARTIST -> PlaylistSortMode.ARTIST
                    else -> return@setOnMenuItemClickListener false
                }
                if (playlistSortMode == nextSort) return@setOnMenuItemClickListener true
                playlistSortMode = nextSort
                loadedItems = applyPlaylistSort(remoteItems)
                adapter.submit(loadedItems)
                showState(
                    when (playlistSortMode) {
                        PlaylistSortMode.DEFAULT -> "Playlist sorted by YouTube order."
                        PlaylistSortMode.TITLE -> "Playlist sorted by title locally only."
                        PlaylistSortMode.ARTIST -> "Playlist sorted by artist locally only."
                    }
                )
                true
            }
            show()
        }
    }

    private fun applyPlaylistSort(items: List<YouTubeVideo>): List<YouTubeVideo> {
        if (browseType != YouTubeItemType.PLAYLIST || items.isEmpty()) return items
        return when (playlistSortMode) {
            PlaylistSortMode.DEFAULT -> items
            PlaylistSortMode.TITLE -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            PlaylistSortMode.ARTIST -> items.sortedWith(
                Comparator { first, second ->
                    val artistComparison = String.CASE_INSENSITIVE_ORDER.compare(
                        first.channelTitle,
                        second.channelTitle
                    )
                    if (artistComparison != 0) {
                        artistComparison
                    } else {
                        String.CASE_INSENSITIVE_ORDER.compare(first.title, second.title)
                    }
                }
            )
        }
    }

    private fun sanitizeRemoteItems(items: List<YouTubeVideo>): List<YouTubeVideo> {
        if (browseId != YouTubeAccountSurfaces.PLAYLISTS_BROWSE_ID) return items
        return items.filterNot {
            it.type == YouTubeItemType.PLAYLIST &&
                run {
                    val normalizedBrowseId = it.browseId?.removePrefix("VL")?.trim()?.uppercase()
                    val normalizedTitle = it.title.trim().lowercase()
                    normalizedBrowseId in setOf("LM", "SE") ||
                        normalizedTitle == "liked music" ||
                        normalizedTitle == "liked songs"
                }
        }
    }

    private fun hydrateBrowseChrome() {
        if (browseThumb.isNullOrBlank()) {
            browseThumb = loadedItems.firstNotNullOfOrNull { it.thumbnailUrl }
        }
        if (browseSubtitle.isBlank()) {
            browseSubtitle = when (browseType) {
                YouTubeItemType.ARTIST -> listOfNotNull(
                    artistInfo?.monthlyListeners?.takeIf { it.isNotBlank() },
                    artistInfo?.subscriberCount?.takeIf { it.isNotBlank() }
                ).joinToString(" • ").ifBlank { loadedItems.firstOrNull()?.channelTitle.orEmpty() }
                else -> loadedItems.firstOrNull()?.channelTitle.orEmpty()
            }
        }
        if (browseTitle.isBlank()) {
            browseTitle = loadedItems.firstOrNull()?.sectionTitle
                ?: loadedItems.firstOrNull()?.title
                ?: browseTypeLabelCapitalized()
        }
        refreshHeader()
    }

    private fun playInApp(item: YouTubeVideo) {
        val videoId = item.videoId ?: return
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            showState("Resolving audio stream...")
            val streamUrl = runCatching { apiClient.resolveAudioStreamUrl(videoId) }.getOrNull()
            if (streamUrl.isNullOrBlank()) {
                showState("Unable to resolve playable audio stream for this item.")
                showToast("Could not start playback for this track")
                return@launch
            }
            val streamPlayable = runCatching { apiClient.isStreamPlayable(streamUrl) }.getOrDefault(false)
            if (!streamPlayable) {
                showState("In-app stream blocked. Opening YouTube Music fallback.")
                openInYouTubeMusic(videoId)
                return@launch
            }

            val forcedArtwork = if (browseType == YouTubeItemType.ALBUM) browseThumb else null
            val playableSong = item.toSong(streamUrl, forcedArtwork)
            (activity as? MainActivity)?.playTemporaryQueue(listOf(playableSong), 0)
            showState("Starting playback...")
        }
    }

    private fun YouTubeVideo.toSong(streamUrl: String, forcedArtwork: String? = null): Song {
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
            albumArtUri = YouTubeThumbnailUtils.toPlaybackArtworkUrl(
                rawUrl = forcedArtwork ?: thumbnailUrl,
                videoId = videoId
            )
        )
    }

    private fun openInYouTubeMusic(videoId: String) {
        val context = context ?: return
        val musicIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$videoId")).apply {
            `package` = "com.google.android.apps.youtube.music"
        }
        if (musicIntent.resolveActivity(context.packageManager) == null) {
            showToast("YouTube Music app not installed")
            return
        }
        runCatching { startActivity(musicIntent) }.onFailure { showToast("Unable to open YouTube Music") }
    }

    private fun browseTypeLabel(): String = when (browseType) {
        YouTubeItemType.ARTIST -> "artist"
        YouTubeItemType.ALBUM -> "album"
        YouTubeItemType.PLAYLIST -> "playlist"
        YouTubeItemType.SONG -> "song"
        YouTubeItemType.VIDEO -> "video"
        YouTubeItemType.UNKNOWN -> "collection"
    }

    private fun browseTypeLabelCapitalized(): String = browseTypeLabel().replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
    }

    private fun showState(message: String) {
        stateMessage = message
        refreshHeader()
    }

    private fun refreshHeader() {
        val playableCount = loadedItems.count { !it.videoId.isNullOrBlank() }
        val subtitle = buildString {
            append(browseTypeLabel())
            if (browseSubtitle.isNotBlank()) {
                append(" • ")
                append(browseSubtitle)
            }
        }
        adapter.setHeader(
            YouTubeBrowseAdapter.HeaderModel(
                browseType = browseType,
                title = browseTitle,
                subtitle = subtitle,
                artworkUrl = YouTubeThumbnailUtils.toPlaybackArtworkUrl(browseThumb, null),
                stateMessage = stateMessage,
                artistInfo = artistInfo,
                showArtistDescription = artistPageSetting(KEY_SHOW_ARTIST_DESCRIPTION, true),
                showArtistSubscribers = artistPageSetting(KEY_SHOW_ARTIST_SUBSCRIBERS, true),
                showArtistMonthlyListeners = artistPageSetting(KEY_SHOW_ARTIST_MONTHLY_LISTENERS, true),
                canPlay = playableCount > 0,
                canShuffle = playableCount > 1
            )
        )
    }

    private fun showArtistPageOptionsDialog() {
        if (browseType != YouTubeItemType.ARTIST) return
        val labels = arrayOf("Show description", "Show subscriber count", "Show monthly listeners")
        val checked = booleanArrayOf(
            artistPageSetting(KEY_SHOW_ARTIST_DESCRIPTION, true),
            artistPageSetting(KEY_SHOW_ARTIST_SUBSCRIBERS, true),
            artistPageSetting(KEY_SHOW_ARTIST_MONTHLY_LISTENERS, true)
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Artist page options")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Apply") { _, _ ->
                setArtistPageSetting(KEY_SHOW_ARTIST_DESCRIPTION, checked[0])
                setArtistPageSetting(KEY_SHOW_ARTIST_SUBSCRIBERS, checked[1])
                setArtistPageSetting(KEY_SHOW_ARTIST_MONTHLY_LISTENERS, checked[2])
                refreshHeader()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun artistPageSetting(key: String, defaultValue: Boolean): Boolean {
        return requireContext().getSharedPreferences(ARTIST_PAGE_PREFS, 0).getBoolean(key, defaultValue)
    }

    private fun setArtistPageSetting(key: String, value: Boolean) {
        requireContext().getSharedPreferences(ARTIST_PAGE_PREFS, 0).edit().putBoolean(key, value).apply()
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private enum class PlaylistSortMode {
        DEFAULT,
        TITLE,
        ARTIST
    }

    companion object {
        private const val MENU_OPEN_BROWSE = 1
        private const val MENU_PLAY_NEXT = 2
        private const val MENU_ADD_TO_QUEUE = 3
        private const val MENU_ADD_TO_PLAYLIST = 4
        private const val MENU_REMOVE_FROM_PLAYLIST = 5
        private const val MENU_TOGGLE_LIKE = 6
        private const val MENU_MOVE_UP = 7
        private const val MENU_MOVE_DOWN = 8
        private const val MENU_SORT_DEFAULT = 9
        private const val MENU_SORT_TITLE = 10
        private const val MENU_SORT_ARTIST = 11
        private const val QUEUE_APPEND_BATCH_SIZE = 8
        private const val RADIO_INITIAL_READY = 10
        private const val RADIO_TARGET_SIZE = 30
        private const val RADIO_CANDIDATE_LIMIT = 60
        private const val RADIO_RESOLVE_PARALLELISM = 6
        private const val RADIO_RESOLVE_TIMEOUT_MS = 6000L
        private const val ARTIST_PAGE_PREFS = "blazing_music_artist_page_prefs"
        private const val KEY_SHOW_ARTIST_DESCRIPTION = "show_artist_description"
        private const val KEY_SHOW_ARTIST_SUBSCRIBERS = "show_artist_subscriber_count"
        private const val KEY_SHOW_ARTIST_MONTHLY_LISTENERS = "show_artist_monthly_listeners"
        private const val ARG_BROWSE_ID = "arg_browse_id"
        private const val ARG_BROWSE_PARAMS = "arg_browse_params"
        private const val ARG_BROWSE_TITLE = "arg_browse_title"
        private const val ARG_BROWSE_SUBTITLE = "arg_browse_subtitle"
        private const val ARG_BROWSE_THUMB = "arg_browse_thumb"
        private const val ARG_BROWSE_TYPE_ORDINAL = "arg_browse_type_ordinal"

        fun newInstance(
            browseId: String,
            browseParams: String?,
            title: String,
            subtitle: String,
            thumbUrl: String?,
            type: YouTubeItemType
        ): YouTubeBrowseFragment {
            return YouTubeBrowseFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_BROWSE_ID, browseId)
                    putString(ARG_BROWSE_PARAMS, browseParams)
                    putString(ARG_BROWSE_TITLE, title)
                    putString(ARG_BROWSE_SUBTITLE, subtitle)
                    putString(ARG_BROWSE_THUMB, thumbUrl)
                    putInt(ARG_BROWSE_TYPE_ORDINAL, type.ordinal)
                }
            }
        }
    }
}
