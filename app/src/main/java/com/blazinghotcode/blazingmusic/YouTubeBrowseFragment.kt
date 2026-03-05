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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Dedicated destination page for a YouTube artist/album/playlist.
 * Header/about sections are rendered as the first RecyclerView item so scrolling is natural.
 */
class YouTubeBrowseFragment : Fragment() {
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var rvBrowseResults: RecyclerView
    private lateinit var adapter: YouTubeBrowseAdapter

    private val apiClient by lazy { YouTubeApiClient() }
    private var activeJob: Job? = null

    private var browseId: String = ""
    private var browseParams: String? = null
    private var browseTitle: String = ""
    private var browseSubtitle: String = ""
    private var browseThumb: String? = null
    private var browseType: YouTubeItemType = YouTubeItemType.UNKNOWN
    private var loadedItems: List<YouTubeVideo> = emptyList()
    private var cachedQueueSignature: String? = null
    private var cachedResolvedQueue: List<Song> = emptyList()
    private var artistInfo: YouTubeArtistInfo? = null
    private var stateMessage: String = ""

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
        rvBrowseResults.layoutManager = LinearLayoutManager(requireContext())
        rvBrowseResults.adapter = adapter
    }

    private fun setupActions() {
        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
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

            loadedItems = page.items
            artistInfo = page.artistInfo
            adapter.submit(loadedItems)

            if (loadedItems.isEmpty()) {
                showState("No items found.")
            } else {
                showState("Browsing ${browseTypeLabel()}.")
            }
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

            val sourceSignature = playableItems.joinToString("|") { it.id }
            val resolvedSongs = if (cachedQueueSignature == sourceSignature && cachedResolvedQueue.isNotEmpty()) {
                cachedResolvedQueue
            } else {
                val resolved = resolveSongsInParallel(playableItems)
                cachedQueueSignature = sourceSignature
                cachedResolvedQueue = resolved
                resolved
            }
            if (resolvedSongs.isEmpty()) {
                showState("Unable to resolve playable tracks from this page.")
                showToast("Could not start playback")
                return@launch
            }

            val finalQueue = if (shuffle) {
                resolvedSongs.shuffled(Random(System.currentTimeMillis()))
            } else {
                resolvedSongs
            }
            (activity as? MainActivity)?.playTemporaryQueue(finalQueue, 0)
            showState(if (shuffle) "Starting shuffled playback..." else "Starting playback...")
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
                showState("Playing now. Building shuffle queue 0/$total...")

                remainingItems.forEach { item ->
                    loaded += 1
                    val playableSong = resolvePlayableSong(item)
                    if (playableSong != null) {
                        playableLoaded += 1
                        (activity as? MainActivity)?.appendSongsToCurrentQueue(listOf(playableSong))
                    }
                    showState("Playing now. Building shuffle queue $loaded/$total...")
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
                    .filter(::isArtistShuffleSongCandidate)
                    .filter { it.videoId != seed.videoId }
                    .distinctBy { it.videoId ?: it.id }
                    .toList()

                if (candidates.isEmpty()) {
                    showState("Radio started. No extra recommendations yet.")
                    return@radioBuild
                }

                var loaded = 0
                var added = 0
                val total = candidates.size
                candidates.forEach { candidate ->
                    loaded += 1
                    val playable = resolvePlayableSong(candidate)
                    if (playable != null) {
                        added += 1
                        (activity as? MainActivity)?.appendSongsToCurrentQueue(listOf(playable))
                    }
                    showState("Building radio $loaded/$total...")
                }
                showState("Radio ready. Added $added recommendations.")
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

            val resolvedSongs = resolveSongsInParallel(albumItems)
            if (resolvedSongs.isEmpty()) {
                showToast("Could not start album playback")
                showState("Unable to resolve playable album songs.")
                return@launch
            }

            val startIndex = albumItems.indexOfFirst { it.videoId == tappedItem.videoId }
                .coerceAtLeast(0)
                .coerceAtMost(resolvedSongs.lastIndex)
            (activity as? MainActivity)?.playTemporaryQueue(resolvedSongs, startIndex)
            showState("Starting album playback...")
        }
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
        if (item.type != YouTubeItemType.ALBUM) return
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_OPEN_ALBUM, 0, "Open album")
        popup.setOnMenuItemClickListener { menu ->
            when (menu.itemId) {
                MENU_OPEN_ALBUM -> {
                    if (!item.browseId.isNullOrBlank()) openNestedBrowse(item) else showToast("Album page unavailable")
                    true
                }
                else -> false
            }
        }
        popup.show()
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
                    subtitle = browseTitle,
                    thumbUrl = browseThumb,
                    type = sectionType
                )
            )
            .addToBackStack("youtube_browse")
            .commit()
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

    companion object {
        private const val MENU_OPEN_ALBUM = 1
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
