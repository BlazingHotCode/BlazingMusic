package com.blazinghotcode.blazingmusic

/**
 * Pure transition helpers used by [MusicViewModel] playback navigation logic.
 * Keeping this side-effect free makes playback behavior easy to unit test.
 */
object PlaybackTransitionLogic {

    sealed class NextAction {
        data class PlayAt(val index: Int) : NextAction()
        data object StopAtQueueEnd : NextAction()
    }

    sealed class PreviousAction {
        data object RestartCurrent : PreviousAction()
        data class PlayAt(val index: Int) : PreviousAction()
    }

    data class ManualNextRepeatPlan(
        val wrapAround: Boolean,
        val nextRepeatMode: Int
    )

    fun resolveManualNextRepeatPlan(repeatMode: Int): ManualNextRepeatPlan {
        val normalized = repeatMode.coerceIn(0, 2)
        return ManualNextRepeatPlan(
            wrapAround = normalized == 1 || normalized == 2,
            nextRepeatMode = if (normalized == 2) 0 else normalized
        )
    }

    fun nextRepeatMode(currentMode: Int): Int {
        val normalized = currentMode.coerceIn(0, 2)
        return (normalized + 1) % 3
    }

    fun resolveNextAction(
        queueSize: Int,
        currentIndex: Int,
        wrapAround: Boolean
    ): NextAction? {
        if (queueSize <= 0) return null
        val nextIndex = if (currentIndex == -1) {
            0
        } else {
            currentIndex + 1
        }
        if (nextIndex >= queueSize) {
            return if (wrapAround) NextAction.PlayAt(0) else NextAction.StopAtQueueEnd
        }
        return NextAction.PlayAt(nextIndex)
    }

    fun resolvePreviousAction(
        queueSize: Int,
        currentIndex: Int,
        canWrapToEnd: Boolean,
        currentPositionMs: Long,
        restartThresholdMs: Long
    ): PreviousAction? {
        if (queueSize <= 0) return null
        val hasValidCurrent = currentIndex in 0 until queueSize
        if (hasValidCurrent && currentPositionMs > restartThresholdMs) {
            return PreviousAction.RestartCurrent
        }
        val previousIndex = if (currentIndex == -1) {
            if (canWrapToEnd) queueSize - 1 else 0
        } else if (currentIndex > 0) {
            currentIndex - 1
        } else {
            if (canWrapToEnd) queueSize - 1 else 0
        }
        return PreviousAction.PlayAt(previousIndex)
    }

    fun <T> buildShuffledUpcomingQueue(
        queue: List<T>,
        currentIndex: Int,
        shuffle: (List<T>) -> List<T> = { it.shuffled() }
    ): List<T> {
        if (queue.isEmpty()) return queue
        if (currentIndex !in queue.indices) return shuffle(queue)

        val playedAndCurrent = queue.subList(0, currentIndex + 1)
        val upcoming = queue.subList(currentIndex + 1, queue.size)
        if (upcoming.size <= 1) return queue
        return playedAndCurrent + shuffle(upcoming)
    }
}

