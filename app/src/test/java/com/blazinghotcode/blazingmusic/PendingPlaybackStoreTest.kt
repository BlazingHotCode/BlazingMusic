package com.blazinghotcode.blazingmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPlaybackStoreTest {

    private fun song(id: Long): Song {
        return Song(
            id = id,
            title = "Song $id",
            artist = "Artist",
            album = "Album",
            duration = 1_000L,
            dateAddedSeconds = 0L,
            path = "path-$id",
            albumArtUri = null
        )
    }

    @Test
    fun consume_whenEmpty_returnsNull() {
        PendingPlaybackStore.consume()
        assertNull(PendingPlaybackStore.consume())
    }

    @Test
    fun putThenConsume_returnsStoredQueueAndIndex() {
        val queue = listOf(song(1), song(2), song(3))
        PendingPlaybackStore.put(queue, 2)

        val consumed = PendingPlaybackStore.consume()

        assertEquals(queue to 2, consumed)
    }

    @Test
    fun consume_clearsStore() {
        PendingPlaybackStore.put(listOf(song(7)), 0)
        PendingPlaybackStore.consume()

        val secondConsume = PendingPlaybackStore.consume()

        assertNull(secondConsume)
    }

    @Test
    fun put_overwritesPreviousPendingQueue() {
        PendingPlaybackStore.put(listOf(song(1), song(2)), 1)
        PendingPlaybackStore.put(listOf(song(9)), 0)

        val consumed = PendingPlaybackStore.consume()

        assertEquals(listOf(song(9)) to 0, consumed)
    }
}

