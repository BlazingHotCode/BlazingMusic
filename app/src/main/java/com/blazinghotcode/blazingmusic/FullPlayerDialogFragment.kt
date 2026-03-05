package com.blazinghotcode.blazingmusic

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import coil.load
import coil.transform.RoundedCornersTransformation

class FullPlayerDialogFragment : DialogFragment(R.layout.fragment_full_player) {

    companion object {
        const val TAG = "FullPlayerDialog"
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
