package com.blazinghotcode.blazingmusic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Dedicated destination page for a YouTube artist/album/playlist, similar to Metrolist browse pages.
 */
class YouTubeBrowseFragment : Fragment() {
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var ivArtistHeroArt: ImageView
    private lateinit var tvArtistHeroTitle: TextView
    private lateinit var tvArtistHeroSubtitle: TextView
    private lateinit var artistActionRow: View
    private lateinit var btnArtistRadio: View
    private lateinit var btnArtistShuffle: View
    private lateinit var ivBrowseHeaderArt: ImageView
    private lateinit var tvBrowseHeaderTitle: TextView
    private lateinit var tvBrowseHeaderSubtitle: TextView
    private lateinit var btnPlayAll: View
    private lateinit var btnShuffleAll: View
    private lateinit var tvState: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        browseId = args.getString(ARG_BROWSE_ID).orEmpty()
        browseParams = args.getString(ARG_BROWSE_PARAMS)
        browseTitle = args.getString(ARG_BROWSE_TITLE).orEmpty()
        browseSubtitle = args.getString(ARG_BROWSE_SUBTITLE).orEmpty()
        browseThumb = args.getString(ARG_BROWSE_THUMB)
        browseType = YouTubeItemType.entries.getOrNull(args.getInt(ARG_BROWSE_TYPE_ORDINAL, YouTubeItemType.UNKNOWN.ordinal))
            ?: YouTubeItemType.UNKNOWN
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
        setupHeader()
        setupActions()
        loadBrowse()
    }

    override fun onDestroyView() {
        activeJob?.cancel()
        super.onDestroyView()
    }

    private fun bindViews(root: View) {
        btnBack = root.findViewById(R.id.btnBack)
        tvTitle = root.findViewById(R.id.tvTitle)
        ivArtistHeroArt = root.findViewById(R.id.ivArtistHeroArt)
        tvArtistHeroTitle = root.findViewById(R.id.tvArtistHeroTitle)
        tvArtistHeroSubtitle = root.findViewById(R.id.tvArtistHeroSubtitle)
        artistActionRow = root.findViewById(R.id.artistActionRow)
        btnArtistRadio = root.findViewById(R.id.btnArtistRadio)
        btnArtistShuffle = root.findViewById(R.id.btnArtistShuffle)
        ivBrowseHeaderArt = root.findViewById(R.id.ivBrowseHeaderArt)
        tvBrowseHeaderTitle = root.findViewById(R.id.tvBrowseHeaderTitle)
        tvBrowseHeaderSubtitle = root.findViewById(R.id.tvBrowseHeaderSubtitle)
        btnPlayAll = root.findViewById(R.id.btnPlayAll)
        btnShuffleAll = root.findViewById(R.id.btnShuffleAll)
        tvState = root.findViewById(R.id.tvState)
        rvBrowseResults = root.findViewById(R.id.rvBrowseResults)
    }

    private fun setupList() {
        adapter = YouTubeBrowseAdapter { item -> onItemClicked(item) }
        rvBrowseResults.layoutManager = LinearLayoutManager(requireContext())
        rvBrowseResults.adapter = adapter
    }

    private fun setupHeader() {
        tvTitle.text = browseTypeLabelCapitalized()
        applyHeaderMode()
        tvBrowseHeaderTitle.text = browseTitle
        tvArtistHeroTitle.text = browseTitle
        val subtitle = buildString {
            append(browseTypeLabel())
            if (browseSubtitle.isNotBlank()) {
                append(" • ")
                append(browseSubtitle)
            }
        }
        tvBrowseHeaderSubtitle.text = subtitle
        tvArtistHeroSubtitle.text = subtitle
        val artworkUrl = YouTubeThumbnailUtils.toPlaybackArtworkUrl(browseThumb, null)
        if (artworkUrl != null) {
            ivBrowseHeaderArt.load(artworkUrl) {
                crossfade(true)
                placeholder(R.drawable.ml_library_music)
                error(R.drawable.ml_library_music)
                transformations(RoundedCornersTransformation(16f))
            }
            ivArtistHeroArt.load(artworkUrl) {
                crossfade(true)
                placeholder(R.drawable.ml_library_music)
                error(R.drawable.ml_library_music)
                transformations(RoundedCornersTransformation(20f))
            }
        } else {
            ivBrowseHeaderArt.setImageResource(R.drawable.ml_library_music)
            ivArtistHeroArt.setImageResource(R.drawable.ml_library_music)
        }
    }

    private fun applyHeaderMode() {
        val isArtist = browseType == YouTubeItemType.ARTIST
        ivArtistHeroArt.visibility = if (isArtist) View.VISIBLE else View.GONE
        tvArtistHeroTitle.visibility = if (isArtist) View.VISIBLE else View.GONE
        tvArtistHeroSubtitle.visibility = if (isArtist) View.VISIBLE else View.GONE
        artistActionRow.visibility = if (isArtist) View.VISIBLE else View.GONE

        ivBrowseHeaderArt.visibility = if (isArtist) View.GONE else View.VISIBLE
        tvBrowseHeaderTitle.visibility = if (isArtist) View.GONE else View.VISIBLE
        tvBrowseHeaderSubtitle.visibility = if (isArtist) View.GONE else View.VISIBLE
        btnPlayAll.visibility = if (isArtist) View.GONE else View.VISIBLE
        btnShuffleAll.visibility = if (isArtist) View.GONE else View.VISIBLE
    }

    private fun setupActions() {
        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        btnPlayAll.setOnClickListener { playBrowseItems(shuffle = false) }
        btnShuffleAll.setOnClickListener { playBrowseItems(shuffle = true) }
        btnArtistRadio.setOnClickListener { playBrowseItems(shuffle = false) }
        btnArtistShuffle.setOnClickListener { playBrowseItems(shuffle = true) }
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
            val results = runCatching {
                apiClient.browseCollection(browseId, browseParams)
            }.getOrElse {
                emptyList()
            }
            loadedItems = results
            adapter.submit(results)
            val playableCount = results.count { !it.videoId.isNullOrBlank() }
            btnPlayAll.isEnabled = playableCount > 0
            btnShuffleAll.isEnabled = playableCount > 1
            btnArtistRadio.isEnabled = playableCount > 0
            btnArtistShuffle.isEnabled = playableCount > 1
            if (results.isEmpty()) {
                showState("No items found.")
            } else {
                showState("Browsing ${browseTypeLabel()}.")
            }
        }
    }

    private fun playBrowseItems(shuffle: Boolean) {
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

    private suspend fun resolveSongsInParallel(items: List<YouTubeVideo>): List<Song> = coroutineScope {
        val useAlbumArtwork = browseType == YouTubeItemType.ALBUM
        items.mapIndexed { index, item ->
            async {
                val videoId = item.videoId ?: return@async null
                val streamUrl = runCatching { apiClient.resolveAudioStreamUrl(videoId) }.getOrNull()
                    ?: return@async null
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
            !item.videoId.isNullOrBlank() -> playInApp(item)
            !item.browseId.isNullOrBlank() -> openNestedBrowse(item)
            else -> showToast("This item cannot be opened yet")
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
            albumArtUri = YouTubeThumbnailUtils.toPlaybackArtworkUrl(
                rawUrl = forcedArtwork ?: thumbnailUrl,
                videoId = videoId
            )
        )
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
        tvState.text = message
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
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
            val fragment = YouTubeBrowseFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_BROWSE_ID, browseId)
                putString(ARG_BROWSE_PARAMS, browseParams)
                putString(ARG_BROWSE_TITLE, title)
                putString(ARG_BROWSE_SUBTITLE, subtitle)
                putString(ARG_BROWSE_THUMB, thumbUrl)
                putInt(ARG_BROWSE_TYPE_ORDINAL, type.ordinal)
            }
            return fragment
        }
    }
}
