package com.blazinghotcode.blazingmusic

import android.content.Context
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import coil.load
import coil.transform.RoundedCornersTransformation
import kotlin.math.abs
import kotlin.math.min

object PlaybackTimeFormatter {
    fun formatDuration(durationMs: Long): String {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val minutes = (safeDuration / 1000) / 60
        val seconds = (safeDuration / 1000) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}

object PlaybackControlUi {
    fun bindPrimaryControl(
        button: ImageButton,
        isPlaying: Boolean,
        shouldRestartQueue: Boolean,
        labels: PrimaryControlLabels? = null
    ) {
        val icon = primaryControlIcon(isPlaying, shouldRestartQueue)
        button.setImageResource(icon)
        labels?.let {
            button.contentDescription = when {
                shouldRestartQueue -> it.restart
                isPlaying -> it.pause
                else -> it.play
            }
        }
    }

    fun bindShuffleControl(
        context: Context,
        button: ImageButton,
        isEnabled: Boolean,
        labels: ToggleLabels? = null
    ) {
        button.setImageResource(shuffleIcon(isEnabled))
        ImageViewCompat.setImageTintList(
            button,
            ColorStateList.valueOf(ContextCompat.getColor(context, shuffleTint(isEnabled)))
        )
        labels?.let {
            button.contentDescription = if (isEnabled) it.on else it.off
        }
    }

    fun bindRepeatControl(
        context: Context,
        button: ImageButton,
        mode: Int,
        labels: RepeatLabels? = null
    ) {
        button.setImageResource(repeatIcon(mode))
        ImageViewCompat.setImageTintList(
            button,
            ColorStateList.valueOf(ContextCompat.getColor(context, repeatTint(mode)))
        )
        labels?.let {
            button.contentDescription = when (mode) {
                1 -> it.repeatAll
                2 -> it.repeatOne
                else -> it.off
            }
        }
    }

    fun bindLikeControl(
        context: Context,
        button: ImageButton,
        isLiked: Boolean,
        isVisible: Boolean
    ) {
        button.visibility = if (isVisible) View.VISIBLE else View.GONE
        if (!isVisible) return
        button.setImageResource(if (isLiked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        ImageViewCompat.setImageTintList(
            button,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (isLiked) R.color.accent_lavender else R.color.text_secondary
                )
            )
        )
        button.contentDescription = if (isLiked) "Unlike song" else "Like song"
    }

    @DrawableRes
    private fun primaryControlIcon(isPlaying: Boolean, shouldRestartQueue: Boolean): Int {
        if (shouldRestartQueue) return R.drawable.ml_replay
        return if (isPlaying) R.drawable.ml_pause else R.drawable.ml_play
    }

    @DrawableRes
    private fun shuffleIcon(isEnabled: Boolean): Int {
        return if (isEnabled) R.drawable.ml_shuffle_on else R.drawable.ml_shuffle
    }

    @ColorRes
    private fun shuffleTint(isEnabled: Boolean): Int {
        return if (isEnabled) R.color.accent_lavender else R.color.text_secondary
    }

    @DrawableRes
    private fun repeatIcon(mode: Int): Int {
        return when (mode) {
            1 -> R.drawable.ml_repeat_on
            2 -> R.drawable.ml_repeat_one_on
            else -> R.drawable.ml_repeat
        }
    }

    @ColorRes
    private fun repeatTint(mode: Int): Int {
        return if (mode == 0) R.color.text_secondary else R.color.accent_lavender
    }

    data class PrimaryControlLabels(
        val play: String,
        val pause: String,
        val restart: String
    )

    data class ToggleLabels(
        val off: String,
        val on: String
    )

    data class RepeatLabels(
        val off: String,
        val repeatAll: String,
        val repeatOne: String
    )
}

object MiniPlayerSongUi {
    fun bindSong(
        titleView: android.widget.TextView,
        artistView: android.widget.TextView,
        albumArtView: android.widget.ImageView,
        song: Song
    ) {
        titleView.text = song.title
        titleView.isSelected = true
        artistView.text = song.artist
        song.albumArtUri?.let { uri ->
            albumArtView.load(uri) {
                crossfade(true)
                placeholder(R.drawable.ml_library_music)
                error(R.drawable.ml_library_music)
                transformations(RoundedCornersTransformation(20f))
            }
        } ?: albumArtView.setImageResource(R.drawable.ml_library_music)
    }
}

class MiniPlayerExpandGestureController(
    private val playerLayout: View,
    private val toPx: (Float) -> Float,
    private val onOpen: () -> Unit
) {
    private var startX = 0f
    private var startY = 0f
    private var dragActive = false
    private var velocityTracker: VelocityTracker? = null

    fun attach() {
        val touchSlop = ViewConfiguration.get(playerLayout.context).scaledTouchSlop.toFloat()
        playerLayout.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    dragActive = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val deltaX = event.rawX - startX
                    val deltaY = event.rawY - startY
                    val verticalDominant = abs(deltaY) > abs(deltaX)
                    if (deltaY < 0f && verticalDominant) {
                        dragActive = true
                        applyDrag(deltaY)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val deltaX = event.rawX - startX
                    val deltaY = event.rawY - startY
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    val dragOpenDistance = toPx(90f)
                    val fastOpenDistance = toPx(16f)
                    val fastOpenVelocity = toPx(520f)
                    val verticalDominant = abs(deltaY) > abs(deltaX)
                    val shouldOpenFromDrag = verticalDominant && (
                        deltaY < -dragOpenDistance ||
                            (deltaY < -fastOpenDistance && velocityY < -fastOpenVelocity)
                        )
                    val isTap = abs(deltaY) < touchSlop && abs(deltaX) < touchSlop

                    when {
                        shouldOpenFromDrag || isTap -> {
                            animateToRest()
                            onOpen()
                            true
                        }

                        dragActive -> {
                            animateToRest()
                            true
                        }

                        else -> false
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (dragActive) {
                        animateToRest()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun applyDrag(deltaY: Float) {
        val clamped = deltaY.coerceAtMost(0f).coerceAtLeast(-toPx(56f))
        playerLayout.translationY = clamped
        val alphaLoss = min(0.18f, abs(clamped) / toPx(220f))
        playerLayout.alpha = 1f - alphaLoss
    }

    private fun animateToRest() {
        dragActive = false
        playerLayout.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(160L)
            .start()
    }
}
