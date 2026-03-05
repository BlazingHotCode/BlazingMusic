package com.blazinghotcode.blazingmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionLogicTest {

    @Test
    fun resolveManualNextRepeatPlan_repeatOneWrapsAndClearsToOff() {
        val plan = PlaybackTransitionLogic.resolveManualNextRepeatPlan(repeatMode = 2)
        assertTrue(plan.wrapAround)
        assertEquals(0, plan.nextRepeatMode)
    }

    @Test
    fun resolveManualNextRepeatPlan_repeatAllWrapsAndStaysRepeatAll() {
        val plan = PlaybackTransitionLogic.resolveManualNextRepeatPlan(repeatMode = 1)
        assertTrue(plan.wrapAround)
        assertEquals(1, plan.nextRepeatMode)
    }

    @Test
    fun nextRepeatMode_cyclesOffAllOneOff() {
        assertEquals(1, PlaybackTransitionLogic.nextRepeatMode(0))
        assertEquals(2, PlaybackTransitionLogic.nextRepeatMode(1))
        assertEquals(0, PlaybackTransitionLogic.nextRepeatMode(2))
    }

    @Test
    fun resolveNextAction_endWithoutWrapStops() {
        val action = PlaybackTransitionLogic.resolveNextAction(
            queueSize = 3,
            currentIndex = 2,
            wrapAround = false
        )
        assertEquals(PlaybackTransitionLogic.NextAction.StopAtQueueEnd, action)
    }

    @Test
    fun resolveNextAction_endWithWrapStartsFromZero() {
        val action = PlaybackTransitionLogic.resolveNextAction(
            queueSize = 3,
            currentIndex = 2,
            wrapAround = true
        )
        assertEquals(PlaybackTransitionLogic.NextAction.PlayAt(0), action)
    }

    @Test
    fun resolveNextAction_withoutCurrentStartsAtZero() {
        val action = PlaybackTransitionLogic.resolveNextAction(
            queueSize = 3,
            currentIndex = -1,
            wrapAround = false
        )
        assertEquals(PlaybackTransitionLogic.NextAction.PlayAt(0), action)
    }

    @Test
    fun resolvePreviousAction_pastThresholdRestartsCurrent() {
        val action = PlaybackTransitionLogic.resolvePreviousAction(
            queueSize = 4,
            currentIndex = 1,
            canWrapToEnd = false,
            currentPositionMs = 6_000L,
            restartThresholdMs = 4_000L
        )
        assertEquals(PlaybackTransitionLogic.PreviousAction.RestartCurrent, action)
    }

    @Test
    fun resolvePreviousAction_atStartNoWrapStaysOnFirst() {
        val action = PlaybackTransitionLogic.resolvePreviousAction(
            queueSize = 4,
            currentIndex = 0,
            canWrapToEnd = false,
            currentPositionMs = 500L,
            restartThresholdMs = 4_000L
        )
        assertEquals(PlaybackTransitionLogic.PreviousAction.PlayAt(0), action)
    }

    @Test
    fun resolvePreviousAction_atStartWithWrapMovesToLast() {
        val action = PlaybackTransitionLogic.resolvePreviousAction(
            queueSize = 4,
            currentIndex = 0,
            canWrapToEnd = true,
            currentPositionMs = 500L,
            restartThresholdMs = 4_000L
        )
        assertEquals(PlaybackTransitionLogic.PreviousAction.PlayAt(3), action)
    }

    @Test
    fun resolvePreviousAction_noCurrentWithWrapMovesToLast() {
        val action = PlaybackTransitionLogic.resolvePreviousAction(
            queueSize = 4,
            currentIndex = -1,
            canWrapToEnd = true,
            currentPositionMs = 0L,
            restartThresholdMs = 4_000L
        )
        assertEquals(PlaybackTransitionLogic.PreviousAction.PlayAt(3), action)
    }

    @Test
    fun buildShuffledUpcomingQueue_keepsPlayedPrefixUntouched() {
        val queue = listOf(1, 2, 3, 4, 5, 6)
        val result = PlaybackTransitionLogic.buildShuffledUpcomingQueue(
            queue = queue,
            currentIndex = 2
        )

        assertEquals(listOf(1, 2, 3), result.take(3))
        assertEquals(queue.size, result.size)
        assertEquals(queue.toSet(), result.toSet())
    }

    @Test
    fun buildShuffledUpcomingQueue_invalidCurrentShufflesWholeQueueMembership() {
        val queue = listOf(1, 2, 3, 4)
        val result = PlaybackTransitionLogic.buildShuffledUpcomingQueue(
            queue = queue,
            currentIndex = -1
        )

        assertEquals(queue.size, result.size)
        assertEquals(queue.toSet(), result.toSet())
    }
}

