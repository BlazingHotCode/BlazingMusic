package com.blazinghotcode.blazingmusic

/**
 * Small process-local bridge used to hand a prepared queue from secondary screens
 * (e.g. YouTube search/browse) to MainActivity playback.
 */
object PendingPlaybackStore {
    private val lock = Any()
    private var queue: List<Song>? = null
    private var startIndex: Int = 0

    fun put(pendingQueue: List<Song>, pendingStartIndex: Int) {
        synchronized(lock) {
            queue = pendingQueue
            startIndex = pendingStartIndex
        }
    }

    fun consume(): Pair<List<Song>, Int>? {
        synchronized(lock) {
            val currentQueue = queue ?: return null
            val index = startIndex
            queue = null
            startIndex = 0
            return currentQueue to index
        }
    }
}
