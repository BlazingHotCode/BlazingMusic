package com.blazinghotcode.blazingmusic

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.PopupMenu
import android.widget.Toast
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Full-screen player dialog with gesture-based dismiss and queue handoff controls. */
class FullPlayerDialogFragment : DialogFragment(R.layout.fragment_full_player) {

    companion object {
        const val TAG = "FullPlayerDialog"
        private const val MENU_SONG_RADIO_UP_NEXT = 1
        private const val MENU_SHUFFLE_UPCOMING = 2
        private const val MENU_CLEAR_UPCOMING = 3
    }

    private val viewModel: MusicViewModel by activityViewModels()

    private lateinit var btnClose: ImageButton
    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnQueue: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var fullPlayerRoot: View
    private lateinit var topGestureZone: View
    private lateinit var bottomGestureZone: View

    private var isCurrentlyPlaying = false
    private var shouldRestartQueue = false
    private var gestureStartY = 0f
    private var isDragClosing = false
    private var isQueueDragInProgress = false
    private var closeVelocityTracker: VelocityTracker? = null
    private var queueVelocityTracker: VelocityTracker? = null
    private var radioJob: Job? = null
    private val apiClient by lazy { YouTubeApiClient() }
    private var queueSnapshot: List<Song> = emptyList()
    private var queueIndexSnapshot: Int = -1

    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekbarRunnable = object : Runnable {
        override fun run() {
            if (!isAdded) return
            val position = viewModel.getCurrentPosition()
            seekBar.progress = position.toInt()
            tvCurrentTime.text = formatDuration(position)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_BlazingMusic_FullScreenDialog)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupControls()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateSeekbarRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateSeekbarRunnable)
    }

    private fun bindViews(root: View) {
        fullPlayerRoot = root.findViewById(R.id.fullPlayerRoot)
        topGestureZone = root.findViewById(R.id.topGestureZone)
        btnClose = root.findViewById(R.id.btnClose)
        ivAlbumArt = root.findViewById(R.id.ivAlbumArt)
        tvSongTitle = root.findViewById(R.id.tvSongTitle)
        tvSongTitle.isSelected = true
        tvArtist = root.findViewById(R.id.tvArtist)
        seekBar = root.findViewById(R.id.seekBar)
        tvCurrentTime = root.findViewById(R.id.tvCurrentTime)
        tvTotalTime = root.findViewById(R.id.tvTotalTime)
        btnShuffle = root.findViewById(R.id.btnShuffle)
        btnPrevious = root.findViewById(R.id.btnPrevious)
        btnPlayPause = root.findViewById(R.id.btnPlayPause)
        btnNext = root.findViewById(R.id.btnNext)
        btnRepeat = root.findViewById(R.id.btnRepeat)
        btnQueue = root.findViewById(R.id.btnQueue)
        btnMore = root.findViewById(R.id.btnMore)
        bottomGestureZone = root.findViewById(R.id.bottomGestureZone)
    }

    private fun setupControls() {
        btnClose.setOnClickListener { dismiss() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.seekTo(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        btnPlayPause.setOnClickListener {
            if (shouldRestartQueue) {
                viewModel.restartQueueFromBeginning()
            } else {
                viewModel.playPause()
            }
        }
        btnPrevious.setOnClickListener { viewModel.playPrevious() }
        btnNext.setOnClickListener { viewModel.playNext() }
        btnShuffle.setOnClickListener { viewModel.toggleShuffle() }
        btnRepeat.setOnClickListener { viewModel.toggleRepeat() }
        btnQueue.setOnClickListener { openQueueSheet() }
        btnMore.setOnClickListener { showOverflowMenu() }
        setupDragToDismiss()
        setupSwipeUpToQueue()
    }

    private fun setupDragToDismiss() {
        val dismissDragListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartY = event.y
                    isDragClosing = false
                    closeVelocityTracker?.recycle()
                    closeVelocityTracker = VelocityTracker.obtain().apply {
                        addMovement(event)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    closeVelocityTracker?.addMovement(event)
                    val deltaY = event.y - gestureStartY
                    if (deltaY > 0f) {
                        isDragClosing = true
                        applyDragProgress(deltaY)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    closeVelocityTracker?.addMovement(event)
                    closeVelocityTracker?.computeCurrentVelocity(1000)
                    val deltaY = event.y - gestureStartY
                    val velocityPxPerSec = closeVelocityTracker?.yVelocity ?: 0f
                    val closeThreshold = dp(120f)
                    val fastCloseDistance = dp(16f)
                    val fastCloseVelocity = dp(520f)
                    val shouldClose = deltaY > closeThreshold ||
                        (deltaY > fastCloseDistance && velocityPxPerSec > fastCloseVelocity)
                    closeVelocityTracker?.recycle()
                    closeVelocityTracker = null

                    if (shouldClose) {
                        animateDismissDownAndClose()
                        true
                    } else if (isDragClosing) {
                        animateBackToRest()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    closeVelocityTracker?.recycle()
                    closeVelocityTracker = null
                    if (isDragClosing) {
                        animateBackToRest()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        topGestureZone.setOnTouchListener(dismissDragListener)
        ivAlbumArt.setOnTouchListener(dismissDragListener)
    }

    private fun setupSwipeUpToQueue() {
        bottomGestureZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartY = event.y
                    isQueueDragInProgress = false
                    queueVelocityTracker?.recycle()
                    queueVelocityTracker = VelocityTracker.obtain().apply {
                        addMovement(event)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    queueVelocityTracker?.addMovement(event)
                    val deltaY = event.y - gestureStartY
                    if (deltaY < 0f) {
                        val host = activity as? MainActivity
                        if (!isQueueDragInProgress) {
                            host?.beginQueueSheetDrag()
                            isQueueDragInProgress = true
                        }
                        host?.updateQueueSheetDrag((-deltaY).coerceAtLeast(0f))
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    queueVelocityTracker?.addMovement(event)
                    queueVelocityTracker?.computeCurrentVelocity(1000)
                    val deltaY = event.y - gestureStartY
                    val velocityPxPerSec = queueVelocityTracker?.yVelocity ?: 0f
                    val fastOpenDistance = dp(14f)
                    val fastOpenVelocity = dp(520f)
                    val shouldOpen = deltaY < -dp(70f) ||
                        (deltaY < -fastOpenDistance && velocityPxPerSec < -fastOpenVelocity)
                    queueVelocityTracker?.recycle()
                    queueVelocityTracker = null
                    if (isQueueDragInProgress) {
                        (activity as? MainActivity)?.endQueueSheetDrag(shouldOpen)
                        isQueueDragInProgress = false
                        true
                    } else if (shouldOpen) {
                        openQueueSheet()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    queueVelocityTracker?.recycle()
                    queueVelocityTracker = null
                    if (isQueueDragInProgress) {
                        (activity as? MainActivity)?.endQueueSheetDrag(false)
                        isQueueDragInProgress = false
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

    }

    private fun applyDragProgress(translationY: Float) {
        fullPlayerRoot.translationY = translationY
        val alphaLoss = kotlin.math.min(0.45f, kotlin.math.abs(translationY) / (fullPlayerRoot.height * 1.1f))
        fullPlayerRoot.alpha = 1f - alphaLoss
    }

    private fun animateBackToRest() {
        isDragClosing = false
        fullPlayerRoot.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(180L)
            .start()
    }

    private fun animateDismissDownAndClose() {
        isDragClosing = false
        fullPlayerRoot.animate()
            .translationY(fullPlayerRoot.height.toFloat())
            .alpha(0.65f)
            .setDuration(170L)
            .withEndAction { dismiss() }
            .start()
    }

    private fun openQueueSheet() {
        val host = activity as? MainActivity ?: return
        host.window?.decorView?.post { host.showQueueSheet() }
    }

    private fun observeViewModel() {
        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            if (song == null) {
                dismissAllowingStateLoss()
                return@observe
            }
            tvSongTitle.text = song.title
            tvSongTitle.isSelected = true
            tvArtist.text = song.artist
            song.albumArtUri?.let { uri ->
                ivAlbumArt.load(uri) {
                    crossfade(true)
                    placeholder(R.drawable.ml_library_music)
                    error(R.drawable.ml_library_music)
                    transformations(RoundedCornersTransformation(28f))
                }
            } ?: ivAlbumArt.setImageResource(R.drawable.ml_library_music)
        }

        viewModel.isPlaying.observe(viewLifecycleOwner) {
            isCurrentlyPlaying = it
            updatePrimaryControlButton()
        }

        viewModel.shouldRestartQueue.observe(viewLifecycleOwner) {
            shouldRestartQueue = it
            updatePrimaryControlButton()
        }

        viewModel.duration.observe(viewLifecycleOwner) { duration ->
            seekBar.max = duration.toInt().coerceAtLeast(0)
            tvTotalTime.text = formatDuration(duration)
        }

        viewModel.isShuffleEnabled.observe(viewLifecycleOwner) { enabled ->
            updateShuffleUi(enabled)
        }

        viewModel.repeatMode.observe(viewLifecycleOwner) { mode ->
            updateRepeatUi(mode)
        }

        viewModel.queue.observe(viewLifecycleOwner) { queueSnapshot = it }
        viewModel.currentQueueIndex.observe(viewLifecycleOwner) { queueIndexSnapshot = it }
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(requireContext(), btnMore)
        popup.menu.add(0, MENU_SONG_RADIO_UP_NEXT, 0, "Song radio (Up next)")
        popup.menu.add(0, MENU_SHUFFLE_UPCOMING, 1, "Shuffle upcoming")
        popup.menu.add(0, MENU_CLEAR_UPCOMING, 2, "Clear upcoming")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SONG_RADIO_UP_NEXT -> {
                    startSongRadioUpNext()
                    true
                }
                MENU_SHUFFLE_UPCOMING -> {
                    shuffleUpcomingQueue()
                    true
                }
                MENU_CLEAR_UPCOMING -> {
                    clearUpcomingQueue()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun startSongRadioUpNext() {
        val currentSong = viewModel.currentSong.value ?: run {
            showToast("Nothing is currently playing")
            return
        }
        val seedVideoId = currentSong.sourceVideoId ?: run {
            showToast("Song radio is available for YouTube tracks")
            return
        }

        radioJob?.cancel()
        radioJob = viewLifecycleOwner.lifecycleScope.launch {
            showToast("Building song radio...")
            val candidates = runCatching {
                apiClient.fetchRadioCandidates(
                    videoId = seedVideoId,
                    playlistId = currentSong.sourcePlaylistId,
                    playlistSetVideoId = currentSong.sourcePlaylistSetVideoId,
                    params = currentSong.sourceParams,
                    index = currentSong.sourceIndex,
                    fallbackQuery = "${currentSong.artist} ${currentSong.title}",
                    maxResults = 90
                )
            }
                .getOrDefault(emptyList())
                .asSequence()
                .filter { it.videoId != seedVideoId }
                .filter(::isRadioSongCandidate)
                .distinctBy { it.videoId ?: it.id }
                .toList()

            if (candidates.isEmpty()) {
                showToast("No radio recommendations found")
                return@launch
            }

            val resolved = mutableListOf<Song>()
            for (candidate in candidates) {
                val playable = resolvePlayableRadioSong(candidate) ?: continue
                resolved += playable
            }

            if (resolved.isEmpty()) {
                showToast("Unable to build playable radio queue")
                return@launch
            }

            viewModel.replaceUpcomingQueue(resolved)
            showToast("Song radio ready (${resolved.size} up next)")
        }
    }

    private fun shuffleUpcomingQueue() {
        if (queueIndexSnapshot !in queueSnapshot.indices) {
            showToast("No active queue")
            return
        }
        val upcoming = queueSnapshot.drop(queueIndexSnapshot + 1)
        if (upcoming.isEmpty()) {
            showToast("No upcoming songs to shuffle")
            return
        }
        viewModel.replaceUpcomingQueue(upcoming.shuffled())
        showToast("Upcoming queue shuffled")
    }

    private fun clearUpcomingQueue() {
        if (queueIndexSnapshot !in queueSnapshot.indices) {
            showToast("No active queue")
            return
        }
        val hadUpcoming = queueSnapshot.size > queueIndexSnapshot + 1
        viewModel.replaceUpcomingQueue(emptyList())
        if (hadUpcoming) {
            showToast("Cleared upcoming queue")
        } else {
            showToast("No upcoming songs to clear")
        }
    }

    private fun isRadioSongCandidate(item: YouTubeVideo): Boolean {
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

    private suspend fun resolvePlayableRadioSong(item: YouTubeVideo): Song? {
        val videoId = item.videoId ?: return null
        val streamUrl = runCatching { apiClient.resolveAudioStreamUrl(videoId) }.getOrNull() ?: return null
        val playable = runCatching { apiClient.isStreamPlayable(streamUrl) }.getOrDefault(false)
        if (!playable) return null
        return Song(
            id = (item.id.hashCode().toLong() and 0x7fffffffL) + 10_000_000_000L,
            title = item.title,
            artist = item.channelTitle.ifBlank { "YouTube Music" },
            album = "YouTube",
            duration = 0L,
            dateAddedSeconds = System.currentTimeMillis() / 1000,
            path = streamUrl,
            albumArtUri = YouTubeThumbnailUtils.toPlaybackArtworkUrl(item.thumbnailUrl, item.videoId),
            sourceVideoId = item.videoId,
            sourcePlaylistId = item.sourcePlaylistId,
            sourcePlaylistSetVideoId = item.sourcePlaylistSetVideoId,
            sourceParams = item.sourceParams,
            sourceIndex = item.sourceIndex
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun updatePrimaryControlButton() {
        if (shouldRestartQueue) {
            btnPlayPause.setImageResource(R.drawable.ml_replay)
            return
        }
        btnPlayPause.setImageResource(if (isCurrentlyPlaying) R.drawable.ml_pause else R.drawable.ml_play)
    }

    private fun updateShuffleUi(isEnabled: Boolean) {
        btnShuffle.setImageResource(if (isEnabled) R.drawable.ml_shuffle_on else R.drawable.ml_shuffle)
        val tint = if (isEnabled) R.color.accent_lavender else R.color.text_secondary
        ImageViewCompat.setImageTintList(
            btnShuffle,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tint))
        )
    }

    private fun updateRepeatUi(mode: Int) {
        val icon = when (mode) {
            1 -> R.drawable.ml_repeat_on
            2 -> R.drawable.ml_repeat_one_on
            else -> R.drawable.ml_repeat
        }
        btnRepeat.setImageResource(icon)
        val tint = if (mode == 0) R.color.text_secondary else R.color.accent_lavender
        ImageViewCompat.setImageTintList(
            btnRepeat,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tint))
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val minutes = (safeDuration / 1000) / 60
        val seconds = (safeDuration / 1000) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

}
