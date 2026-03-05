package com.blazinghotcode.blazingmusic

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central playback/state coordinator for songs, queue, player controls, and playlists.
 * Owns persistence for queue/index/position and exposes UI-observable state via LiveData.
 */
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "blazing_music_prefs"
        private const val KEY_QUEUE_IDS = "queue_ids"
        private const val KEY_QUEUE_PATHS = "queue_paths"
        private const val KEY_CURRENT_QUEUE_INDEX = "current_queue_index"
        private const val KEY_CURRENT_POSITION_MS = "current_position_ms"
        private const val KEY_PLAYLISTS_JSON = "playlists_json"
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 4_000L
        private const val TAG = "MusicViewModel"
    }

    private val repository = MusicRepository(application)
    private var exoPlayer: ExoPlayer? = null
    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> = _songs
    private val _libraryLoadState = MutableLiveData<LibraryLoadState>(LibraryLoadState.Loading)
    val libraryLoadState: LiveData<LibraryLoadState> = _libraryLoadState

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> = _currentSong

    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _shouldRestartQueue = MutableLiveData<Boolean>()
    val shouldRestartQueue: LiveData<Boolean> = _shouldRestartQueue

    private val _isShuffleEnabled = MutableLiveData<Boolean>()
    val isShuffleEnabled: LiveData<Boolean> = _isShuffleEnabled

    private val _repeatMode = MutableLiveData<Int>() // 0 = off, 1 = repeat all, 2 = repeat one
    val repeatMode: LiveData<Int> = _repeatMode

    private val _currentPosition = MutableLiveData<Long>()
    val currentPosition: LiveData<Long> = _currentPosition

    private val _duration = MutableLiveData<Long>()
    val duration: LiveData<Long> = _duration

    private val _queue = MutableLiveData<List<Song>>()
    val queue: LiveData<List<Song>> = _queue

    private val _currentQueueIndex = MutableLiveData<Int>()
    val currentQueueIndex: LiveData<Int> = _currentQueueIndex

    private val _playlists = MutableLiveData<List<Playlist>>()
    val playlists: LiveData<List<Playlist>> = _playlists

    private var normalQueue: List<Song> = emptyList()
    private var activeQueue: List<Song> = emptyList()
    private var currentQueueIndexInternal = -1
    private var playlistsInternal: List<Playlist> = emptyList()

    init {
        _isShuffleEnabled.value = false
        _repeatMode.value = 0
        _shouldRestartQueue.value = false
        _currentQueueIndex.value = -1
        _queue.value = emptyList()
        loadPersistedPlaylists()
        initializePlayer()
    }

    private fun initializePlayer() {
        val app = getApplication<Application>()
        app.startService(Intent(app, MusicService::class.java))
        exoPlayer = SharedPlayer.getOrCreate(app).apply {
            val player = this
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.postValue(playing)
                    refreshPlaybackNotification()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _duration.postValue(player.duration)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        handleTrackEnded()
                    }
                    refreshPlaybackNotification()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val path = mediaItem?.localConfiguration?.uri?.toString() ?: return
                    val index = activeQueue.indexOfFirst { it.path == path }
                    if (index !in activeQueue.indices) return
                    if (index != currentQueueIndexInternal) {
                        currentQueueIndexInternal = index
                        _currentQueueIndex.postValue(index)
                        _currentSong.postValue(activeQueue[index])
                        persistQueueState()
                    }
                    refreshPlaybackNotification()
                }
            })
        }
    }

    fun loadSongs() {
        viewModelScope.launch {
            _libraryLoadState.value = LibraryLoadState.Loading
            try {
                normalQueue = repository.getAllSongs()
                _songs.value = normalQueue
                _libraryLoadState.value = if (normalQueue.isEmpty()) {
                    LibraryLoadState.Empty
                } else {
                    LibraryLoadState.Content
                }
                val restored = restorePersistedQueue(normalQueue)
                activeQueue = restored.first ?: if (_isShuffleEnabled.value == true) {
                    buildShuffledQueue(normalQueue)
                } else {
                    normalQueue
                }
                _queue.value = activeQueue
                currentQueueIndexInternal = if (restored.second in activeQueue.indices) {
                    restored.second
                } else {
                    -1
                }
                _currentQueueIndex.value = currentQueueIndexInternal
                _currentSong.value = if (currentQueueIndexInternal in activeQueue.indices) {
                    activeQueue[currentQueueIndexInternal]
                } else {
                    null
                }
                if (currentQueueIndexInternal in activeQueue.indices) {
                    val restoredSong = activeQueue[currentQueueIndexInternal]
                    val restoredPositionMs = readPersistedPositionMs()
                    _shouldRestartQueue.value = false
                    prepareRestoredSong(restoredSong, restoredPositionMs)
                    persistQueueState(positionOverrideMs = restoredPositionMs)
                } else {
                    persistQueueState(positionOverrideMs = 0L)
                }
                refreshPlaybackNotification()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load songs", t)
                _songs.value = emptyList()
                _queue.value = emptyList()
                _currentQueueIndex.value = -1
                _currentSong.value = null
                _libraryLoadState.value = LibraryLoadState.Error(
                    t.message?.takeIf { it.isNotBlank() } ?: "Unable to load songs from device storage."
                )
                persistQueueState(positionOverrideMs = 0L)
            }
        }
    }

    fun playSong(song: Song) {
        // Song-list taps should always reset queue back to the full library.
        val fullLibrary = _songs.value ?: normalQueue
        playSongFromQueue(song, fullLibrary)
    }

    fun playSongFromQueue(song: Song, queueSource: List<Song>) {
        val baseQueue = queueSource.distinctBy { it.path }
        if (baseQueue.isEmpty()) return

        normalQueue = baseQueue
        activeQueue = if (_isShuffleEnabled.value == true) {
            buildShuffledQueue(normalQueue)
        } else {
            normalQueue
        }
        _queue.value = activeQueue

        val index = activeQueue.indexOfFirst { it.path == song.path }
        val resolvedIndex = if (index != -1) index else 0
        val songToPlay = activeQueue[resolvedIndex]
        currentQueueIndexInternal = resolvedIndex
        _currentQueueIndex.value = resolvedIndex
        _shouldRestartQueue.value = false
        _currentSong.value = songToPlay
        persistQueueState(positionOverrideMs = 0L)
        startPlayback()
    }

    private fun startPlayback() {
        exoPlayer?.let { player ->
            val mediaItems = activeQueue.map { mediaItemForSong(it) }
            val startIndex = currentQueueIndexInternal.coerceIn(0, mediaItems.lastIndex)
            player.setMediaItems(mediaItems, startIndex, 0L)
            player.prepare()
            player.play()
        }
    }

    fun playPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            persistPlaybackPosition()
            refreshPlaybackNotification()
        }
    }

    fun playNext() {
        val repeatMode = _repeatMode.value ?: 0
        val plan = PlaybackTransitionLogic.resolveManualNextRepeatPlan(repeatMode)
        playNextInternal(wrapAround = plan.wrapAround)
        _repeatMode.value = plan.nextRepeatMode
    }

    private fun playNextInternal(wrapAround: Boolean) {
        when (val action = PlaybackTransitionLogic.resolveNextAction(
            queueSize = activeQueue.size,
            currentIndex = currentQueueIndexInternal,
            wrapAround = wrapAround
        )) {
            null -> return
            is PlaybackTransitionLogic.NextAction.PlayAt -> playSongAt(action.index)
            PlaybackTransitionLogic.NextAction.StopAtQueueEnd -> {
                exoPlayer?.pause()
                _isPlaying.value = false
                _shouldRestartQueue.value = true
            }
        }
    }

    fun playPrevious() {
        if (activeQueue.isEmpty()) return
        val currentPosition = exoPlayer?.currentPosition ?: 0L
        val canWrapToEnd = (_repeatMode.value ?: 0) == 1 // Repeat all only
        when (val action = PlaybackTransitionLogic.resolvePreviousAction(
            queueSize = activeQueue.size,
            currentIndex = currentQueueIndexInternal,
            canWrapToEnd = canWrapToEnd,
            currentPositionMs = currentPosition,
            restartThresholdMs = PREVIOUS_RESTART_THRESHOLD_MS
        )) {
            null -> return
            PlaybackTransitionLogic.PreviousAction.RestartCurrent -> {
                exoPlayer?.seekTo(0L)
                persistQueueState(positionOverrideMs = 0L)
            }
            is PlaybackTransitionLogic.PreviousAction.PlayAt -> {
                playSongAt(action.index)
            }
        }
    }

    fun toggleShuffle() {
        val isEnabled = !(_isShuffleEnabled.value ?: false)
        _isShuffleEnabled.value = isEnabled

        val current = _currentSong.value
        activeQueue = if (isEnabled) {
            buildShuffledUpcomingQueue(activeQueue, currentQueueIndexInternal)
        } else {
            normalQueue
        }
        _queue.value = activeQueue
        currentQueueIndexInternal = current?.let { activeQueue.indexOf(it) } ?: -1
        _currentQueueIndex.value = currentQueueIndexInternal
        persistQueueState()
    }

    fun toggleRepeat() {
        val current = _repeatMode.value ?: 0
        val nextMode = PlaybackTransitionLogic.nextRepeatMode(current)
        _repeatMode.value = nextMode
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
    }

    fun seekTo(position: Long) {
        val clamped = position.coerceAtLeast(0L)
        exoPlayer?.seekTo(clamped)
        persistQueueState(positionOverrideMs = clamped)
        refreshPlaybackNotification()
    }

    fun seekBy(deltaMs: Long) {
        val nextPosition = (getCurrentPosition() + deltaMs).coerceAtLeast(0L)
        seekTo(nextPosition)
    }

    fun addSongToQueue(song: Song) {
        val mutableQueue = activeQueue.toMutableList()
        mutableQueue.add(song)
        applyQueueMutation(mutableQueue)
    }

    fun addSongToPlayNext(song: Song) {
        val mutableQueue = activeQueue.toMutableList()
        val insertAt = if (currentQueueIndexInternal == -1) {
            0
        } else {
            (currentQueueIndexInternal + 1).coerceAtMost(mutableQueue.size)
        }
        mutableQueue.add(insertAt, song)
        applyQueueMutation(mutableQueue)
    }

    fun playSongAt(index: Int) {
        if (index !in activeQueue.indices) return
        currentQueueIndexInternal = index
        _currentQueueIndex.value = index
        _shouldRestartQueue.value = false
        val selected = activeQueue[index]
        _currentSong.value = selected
        persistQueueState(positionOverrideMs = 0L)
        startPlayback()
    }

    fun restartQueueFromBeginning() {
        if (activeQueue.isEmpty()) return
        playSongAt(0)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in activeQueue.indices || toIndex !in activeQueue.indices || fromIndex == toIndex) {
            return
        }

        val mutableQueue = activeQueue.toMutableList()
        val movedSong = mutableQueue.removeAt(fromIndex)
        mutableQueue.add(toIndex, movedSong)

        val updatedCurrentIndex = when {
            currentQueueIndexInternal == fromIndex -> toIndex
            fromIndex < currentQueueIndexInternal && toIndex >= currentQueueIndexInternal -> currentQueueIndexInternal - 1
            fromIndex > currentQueueIndexInternal && toIndex <= currentQueueIndexInternal -> currentQueueIndexInternal + 1
            else -> currentQueueIndexInternal
        }
        applyQueueMutation(mutableQueue, updatedCurrentIndex)
    }

    fun removeQueueItem(index: Int) {
        if (index !in activeQueue.indices) return

        val mutableQueue = activeQueue.toMutableList()
        val removedSong = mutableQueue.removeAt(index)
        val updatedCurrentIndex = when {
            index < currentQueueIndexInternal -> currentQueueIndexInternal - 1
            index == currentQueueIndexInternal -> -1
            else -> currentQueueIndexInternal
        }
        applyQueueMutation(mutableQueue, updatedCurrentIndex)

        val current = _currentSong.value
        if (current?.id == removedSong.id) {
            if (activeQueue.isEmpty()) {
                exoPlayer?.stop()
                _currentSong.value = null
                _isPlaying.value = false
                currentQueueIndexInternal = -1
                _currentQueueIndex.value = -1
                persistQueueState(positionOverrideMs = 0L)
                refreshPlaybackNotification()
            } else {
                val fallbackIndex = index.coerceAtMost(activeQueue.lastIndex)
                playSongAt(fallbackIndex)
            }
        }
    }

    fun moveSongToPlayNext(index: Int) {
        if (index !in activeQueue.indices || activeQueue.size < 2) return
        val currentIndex = currentQueueIndexInternal
        if (currentIndex == -1 || index == currentIndex || index == currentIndex + 1) return

        val mutableQueue = activeQueue.toMutableList()
        val selected = mutableQueue.removeAt(index)
        var insertAt = currentIndex + 1
        if (index < insertAt) {
            insertAt -= 1
        }
        insertAt = insertAt.coerceIn(0, mutableQueue.size)
        mutableQueue.add(insertAt, selected)
        val updatedCurrentIndex = when {
            index < currentIndex && insertAt >= currentIndex -> currentIndex - 1
            index > currentIndex && insertAt <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        applyQueueMutation(mutableQueue, updatedCurrentIndex)
    }

    fun setQueueOrder(newQueue: List<Song>, newCurrentIndex: Int = currentQueueIndexInternal) {
        if (newQueue.isEmpty() || newQueue.size != activeQueue.size) return
        applyQueueMutation(newQueue.toMutableList(), newCurrentIndex)
    }

    fun createPlaylist(name: String): Playlist? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (playlistsInternal.any { it.name.equals(trimmed, ignoreCase = true) }) return null

        val newId = generatePlaylistId()
        val createdPlaylist = Playlist(
            id = newId,
            name = trimmed,
            songPaths = emptyList()
        )
        playlistsInternal = playlistsInternal + createdPlaylist
        publishPlaylists()
        return createdPlaylist
    }

    fun renamePlaylist(playlistId: Long, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        if (playlistsInternal.any { it.id != playlistId && it.name.equals(trimmed, ignoreCase = true) }) {
            return false
        }

        val updated = playlistsInternal.map {
            if (it.id == playlistId) it.copy(name = trimmed) else it
        }
        if (updated == playlistsInternal) return false

        playlistsInternal = updated
        publishPlaylists()
        return true
    }

    fun deletePlaylist(playlistId: Long): Boolean {
        val beforeSize = playlistsInternal.size
        playlistsInternal = playlistsInternal.filterNot { it.id == playlistId }
        if (playlistsInternal.size == beforeSize) return false
        publishPlaylists()
        return true
    }

    fun addSongToPlaylist(playlistId: Long, song: Song): Boolean {
        return addSongsToPlaylist(playlistId, listOf(song)) > 0
    }

    fun addSongsToPlaylist(playlistId: Long, songs: List<Song>): Int {
        if (songs.isEmpty()) return 0

        val target = playlistsInternal.find { it.id == playlistId } ?: return 0
        val existingPaths = target.songPaths.toMutableSet()
        val pathsToAdd = songs.map { it.path }.filter { existingPaths.add(it) }
        if (pathsToAdd.isEmpty()) return 0

        val updatedPlaylist = target.copy(songPaths = target.songPaths + pathsToAdd)
        playlistsInternal = playlistsInternal.map {
            if (it.id == playlistId) updatedPlaylist else it
        }
        publishPlaylists()
        return pathsToAdd.size
    }

    fun removeSongsFromPlaylist(playlistId: Long, songPaths: Set<String>): Int {
        if (songPaths.isEmpty()) return 0
        val target = playlistsInternal.find { it.id == playlistId } ?: return 0
        val beforeSize = target.songPaths.size
        val updatedPaths = target.songPaths.filterNot { it in songPaths }
        if (beforeSize == updatedPaths.size) return 0

        val updatedPlaylist = target.copy(songPaths = updatedPaths)
        playlistsInternal = playlistsInternal.map {
            if (it.id == playlistId) updatedPlaylist else it
        }
        publishPlaylists()
        return beforeSize - updatedPaths.size
    }

    fun reorderPlaylistSongs(playlistId: Long, reorderedSongPaths: List<String>): Boolean {
        val target = playlistsInternal.find { it.id == playlistId } ?: return false
        if (target.songPaths.size != reorderedSongPaths.size) return false

        val normalized = reorderedSongPaths.distinct()
        if (normalized.size != target.songPaths.size) return false
        if (normalized.toSet() != target.songPaths.toSet()) return false
        if (normalized == target.songPaths) return true

        val updated = target.copy(songPaths = normalized)
        playlistsInternal = playlistsInternal.map {
            if (it.id == playlistId) updated else it
        }
        publishPlaylists()
        return true
    }

    fun getPlaylistSongs(playlistId: Long): List<Song> {
        val playlist = playlistsInternal.find { it.id == playlistId } ?: return emptyList()
        if (playlist.songPaths.isEmpty()) return emptyList()
        val songsByPath = (_songs.value ?: normalQueue).associateBy { it.path }
        return playlist.songPaths.mapNotNull { songsByPath[it] }
    }

    private fun buildShuffledQueue(queue: List<Song>): List<Song> {
        if (queue.isEmpty()) return queue
        return queue.shuffled()
    }

    private fun buildShuffledUpcomingQueue(queue: List<Song>, currentIndex: Int): List<Song> {
        return PlaybackTransitionLogic.buildShuffledUpcomingQueue(queue, currentIndex)
    }

    private fun handleTrackEnded() {
        when (_repeatMode.value ?: 0) {
            2 -> {
                playNextInternal(wrapAround = true)
                _repeatMode.postValue(0)
            }
            1 -> playNextInternal(wrapAround = true)
            else -> playNextInternal(wrapAround = false)
        }
    }

    private fun applyQueueMutation(
        mutableQueue: MutableList<Song>,
        updatedCurrentIndex: Int = currentQueueIndexInternal
    ) {
        activeQueue = mutableQueue.toList()
        _queue.value = activeQueue

        currentQueueIndexInternal = if (updatedCurrentIndex in activeQueue.indices) {
            updatedCurrentIndex
        } else {
            -1
        }
        _currentQueueIndex.value = currentQueueIndexInternal
        persistQueueState()

        if (_isShuffleEnabled.value != true) {
            normalQueue = activeQueue
        } else {
            val queueSongIds = activeQueue.map { it.id }.toSet()
            normalQueue = normalQueue.filter { it.id in queueSongIds }
        }
    }

    private fun persistQueueState(positionOverrideMs: Long? = null) {
        val positionMs = (positionOverrideMs ?: exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
        // Use song path for durable restore across potential MediaStore ID changes.
        prefs.edit()
            .putString(KEY_QUEUE_PATHS, activeQueue.joinToString("\n") { it.path })
            .putString(KEY_QUEUE_IDS, activeQueue.joinToString(",") { it.id.toString() }) // legacy fallback
            .putInt(KEY_CURRENT_QUEUE_INDEX, currentQueueIndexInternal)
            .putLong(KEY_CURRENT_POSITION_MS, positionMs)
            .commit()
    }

    private fun readPersistedPositionMs(): Long {
        return prefs.getLong(KEY_CURRENT_POSITION_MS, 0L).coerceAtLeast(0L)
    }

    private fun prepareRestoredSong(song: Song, positionMs: Long) {
        exoPlayer?.let { player ->
            try {
                val mediaItems = activeQueue.map { mediaItemForSong(it) }
                val startIndex = currentQueueIndexInternal.coerceIn(0, mediaItems.lastIndex)
                player.setMediaItems(mediaItems, startIndex, positionMs.coerceAtLeast(0L))
                player.prepare()
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to prepare restored song ${song.path}", error)
            }
        }
    }

    fun persistPlaybackPosition() {
        persistQueueState()
        refreshPlaybackNotification()
    }

    fun handleExternalPlaybackAction(action: String) {
        handleNotificationAction(action)
    }

    private fun handleNotificationAction(action: String) {
        when (action) {
            PlaybackNotificationManager.ACTION_PREVIOUS -> playPrevious()
            PlaybackNotificationManager.ACTION_PLAY_PAUSE -> {
                if (_shouldRestartQueue.value == true) restartQueueFromBeginning() else playPause()
            }
            PlaybackNotificationManager.ACTION_NEXT -> playNext()
            PlaybackNotificationManager.ACTION_SEEK_BACK -> seekBy(-10_000L)
            PlaybackNotificationManager.ACTION_SEEK_FORWARD -> seekBy(10_000L)
        }
    }

    private fun refreshPlaybackNotification() {
        // Media notification is provided by MusicService + MediaSession.
    }

    private fun mediaItemForSong(song: Song): MediaItem {
        return MediaItem.Builder()
            .setUri(song.path)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()
            )
            .build()
    }

    private fun publishPlaylists() {
        _playlists.value = playlistsInternal
        persistPlaylists()
    }

    private fun generatePlaylistId(): Long {
        val existingIds = playlistsInternal.map { it.id }.toSet()
        var candidate = System.currentTimeMillis()
        while (candidate in existingIds) {
            candidate += 1
        }
        return candidate
    }

    private fun loadPersistedPlaylists() {
        val raw = prefs.getString(KEY_PLAYLISTS_JSON, null) ?: run {
            playlistsInternal = emptyList()
            _playlists.value = playlistsInternal
            return
        }

        playlistsInternal = try {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val json = jsonArray.optJSONObject(index) ?: continue
                    val id = json.optLong("id", -1L)
                    val name = json.optString("name", "").trim()
                    if (id <= 0L || name.isEmpty()) continue

                    val songsArray = json.optJSONArray("song_paths") ?: JSONArray()
                    val paths = buildList {
                        for (songIndex in 0 until songsArray.length()) {
                            val path = songsArray.optString(songIndex, "").trim()
                            if (path.isNotEmpty()) add(path)
                        }
                    }.distinct()

                    add(
                        Playlist(
                            id = id,
                            name = name,
                            songPaths = paths
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        _playlists.value = playlistsInternal
    }

    private fun persistPlaylists() {
        val jsonArray = JSONArray()
        playlistsInternal.forEach { playlist ->
            val playlistJson = JSONObject()
                .put("id", playlist.id)
                .put("name", playlist.name)
                .put("song_paths", JSONArray(playlist.songPaths))
            jsonArray.put(playlistJson)
        }

        prefs.edit()
            .putString(KEY_PLAYLISTS_JSON, jsonArray.toString())
            .commit()
    }

    private fun restorePersistedQueue(allSongs: List<Song>): Pair<List<Song>?, Int> {
        val savedIndex = prefs.getInt(KEY_CURRENT_QUEUE_INDEX, -1)

        val savedPaths = prefs.getString(KEY_QUEUE_PATHS, null)
        if (!savedPaths.isNullOrBlank()) {
            val songByPath = allSongs.associateBy { it.path }
            val restoredByPath = savedPaths
                .split("\n")
                .filter { it.isNotBlank() }
                .mapNotNull { songByPath[it] }

            if (restoredByPath.isEmpty() && allSongs.isNotEmpty()) {
                return Pair(null, -1)
            }
            return Pair(restoredByPath, savedIndex)
        }

        // Backward compatibility with previously stored IDs.
        val savedIdsCsv = prefs.getString(KEY_QUEUE_IDS, null) ?: return Pair(null, -1)
        if (savedIdsCsv.isBlank()) return Pair(emptyList(), -1)

        val songById = allSongs.associateBy { it.id }
        val restoredById = savedIdsCsv
            .split(",")
            .mapNotNull { it.toLongOrNull() }
            .mapNotNull { songById[it] }

        if (restoredById.isEmpty() && allSongs.isNotEmpty()) {
            return Pair(null, -1)
        }
        return Pair(restoredById, savedIndex)
    }

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0L
    }

    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0L
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer = null
    }
}

/** UI-facing state for library loading and permission/error empty states. */
sealed class LibraryLoadState {
    data object Loading : LibraryLoadState()
    data object Content : LibraryLoadState()
    data object Empty : LibraryLoadState()
    data class Error(val message: String) : LibraryLoadState()
}
