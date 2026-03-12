package com.blazinghotcode.blazingmusic

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.absoluteValue

/**
 * Central playback/state coordinator for songs, queue, player controls, and playlists.
 * Owns persistence for queue/index/position and exposes UI-observable state via LiveData.
 */
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "blazing_music_prefs"
        private const val KEY_QUEUE_IDS = "queue_ids"
        private const val KEY_QUEUE_PATHS = "queue_paths"
        private const val KEY_QUEUE_SONGS_JSON = "queue_songs_json"
        private const val KEY_CURRENT_QUEUE_INDEX = "current_queue_index"
        private const val KEY_CURRENT_POSITION_MS = "current_position_ms"
        private const val KEY_PLAYLISTS_JSON = "playlists_json"
        private const val KEY_YOUTUBE_LIKED_SONGS_JSON = "youtube_liked_songs_json"
        private const val LOCAL_MUSIC_PLAYLIST_ID = PlaylistSystem.LOCAL_MUSIC_ID
        private const val YOUTUBE_LIKED_MUSIC_PLAYLIST_ID = PlaylistSystem.YOUTUBE_LIKED_MUSIC_ID
        private const val YOUTUBE_LIKED_MAX_RESULTS = 500
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 4_000L
        private const val TAG = "MusicViewModel"
    }

    private val repository = MusicRepository(application)
    private val youTubeApiClient = YouTubeApiClient(getApplication<Application>().applicationContext)
    private var exoPlayer: ExoPlayer? = null
    private var listenerAttachedPlayer: ExoPlayer? = null
    private var playbackSettings: PlaybackSettings = PlaybackSettings(
        defaultShuffleEnabled = false,
        defaultRepeatMode = 0,
        handleAudioFocus = true,
        pauseOnNoisyOutput = true
    )
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
    private var accountRemotePlaylists: List<Playlist> = emptyList()
    private var accountLikedSongs: List<Song> = emptyList()
    private var accountRemotePlaylistsFingerprint: String? = null
    private var isRefreshingAccountRemotePlaylists = false
    private var isRefreshingAccountLikedSongs = false
    private val retryingYouTubeVideoIds = mutableSetOf<String>()
    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.postValue(playing)
            refreshPlaybackNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = exoPlayer ?: return
            if (playbackState == Player.STATE_READY) {
                _duration.postValue(player.duration)
            }
            if (playbackState == Player.STATE_ENDED) {
                handleTrackEnded()
            }
            refreshPlaybackNotification()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previousSong = _currentSong.value
            val path = mediaItem?.localConfiguration?.uri?.toString() ?: return
            val index = activeQueue.indexOfFirst { it.path == path }
            if (index !in activeQueue.indices) return
            val transitionedSong = activeQueue[index]
            PlaybackAnalyticsLogger.logTransition(
                reason = reason,
                fromSong = previousSong,
                toSong = transitionedSong,
                toIndex = index
            )
            if (index != currentQueueIndexInternal) {
                currentQueueIndexInternal = index
                _currentQueueIndex.postValue(index)
                _currentSong.postValue(transitionedSong)
                persistQueueState()
            }
            refreshPlaybackNotification()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            PlaybackAnalyticsLogger.logPlaybackError(
                error = error,
                song = _currentSong.value,
                queueIndex = currentQueueIndexInternal,
                queueSize = activeQueue.size
            )
            maybeRecoverYouTubeSourceError(error)
        }
    }

    init {
        playbackSettings = PlaybackSettingsStore.read(application.applicationContext)
        _isShuffleEnabled.value = playbackSettings.defaultShuffleEnabled
        _repeatMode.value = playbackSettings.defaultRepeatMode
        _shouldRestartQueue.value = false
        _currentQueueIndex.value = -1
        _queue.value = emptyList()
        loadPersistedPlaylists()
        initializePlayer()
    }

    private fun initializePlayer() {
        val app = getApplication<Application>()
        app.startService(Intent(app, MusicService::class.java))
        bindToSharedPlayer()
    }

    private fun bindToSharedPlayer(): ExoPlayer {
        val app = getApplication<Application>()
        val shared = SharedPlayer.getOrCreate(app)
        if (exoPlayer !== shared) {
            exoPlayer = shared
            applyPlaybackAudioOptions(shared, playbackSettings)
        }
        if (listenerAttachedPlayer !== shared) {
            listenerAttachedPlayer?.removeListener(playbackListener)
            shared.addListener(playbackListener)
            listenerAttachedPlayer = shared
        }
        return shared
    }

    private fun maybeRecoverYouTubeSourceError(error: androidx.media3.common.PlaybackException) {
        if (error.errorCode != androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            return
        }
        val current = _currentSong.value ?: return
        val videoId = current.sourceVideoId ?: return
        if (!retryingYouTubeVideoIds.add(videoId)) return

        val currentIndex = currentQueueIndexInternal
        val resumePositionMs = getCurrentPosition().coerceAtLeast(0L)
        viewModelScope.launch {
            try {
                youTubeApiClient.invalidateCachedStreamUrl(videoId)
                val refreshedUrl = runCatching { youTubeApiClient.resolveAudioStreamUrlFresh(videoId) }.getOrNull()
                if (refreshedUrl.isNullOrBlank()) return@launch
                val playable = runCatching { youTubeApiClient.isStreamPlayable(refreshedUrl) }.getOrDefault(false)
                if (!playable) return@launch
                if (currentIndex !in activeQueue.indices) return@launch

                val updatedQueue = activeQueue.toMutableList()
                val updatedSong = updatedQueue[currentIndex].copy(path = refreshedUrl)
                updatedQueue[currentIndex] = updatedSong
                activeQueue = updatedQueue
                _queue.postValue(activeQueue)
                _currentSong.postValue(updatedSong)
                persistQueueState(positionOverrideMs = resumePositionMs)

                exoPlayer?.let { player ->
                    val mediaItems = activeQueue.map { mediaItemForSong(it) }
                    val startIndex = currentIndex.coerceIn(0, mediaItems.lastIndex)
                    player.setMediaItems(mediaItems, startIndex, resumePositionMs)
                    player.prepare()
                    player.play()
                }
            } finally {
                retryingYouTubeVideoIds.remove(videoId)
            }
        }
    }

    fun loadSongs() {
        viewModelScope.launch {
            _libraryLoadState.value = LibraryLoadState.Loading
            try {
                normalQueue = repository.getAllSongs()
                _songs.value = normalQueue
                ensureLocalMusicPlaylist()
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
                ensureLocalMusicPlaylist()
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
        bindToSharedPlayer().let { player ->
            val mediaItems = activeQueue.map { mediaItemForSong(it) }
            val startIndex = currentQueueIndexInternal.coerceIn(0, mediaItems.lastIndex)
            player.setMediaItems(mediaItems, startIndex, 0L)
            player.prepare()
            player.play()
        }
    }

    fun playPause() {
        bindToSharedPlayer().let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                when (player.playbackState) {
                    Player.STATE_IDLE -> {
                        if (player.mediaItemCount == 0 && activeQueue.isNotEmpty()) {
                            val mediaItems = activeQueue.map { mediaItemForSong(it) }
                            val startIndex = currentQueueIndexInternal
                                .coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0))
                            player.setMediaItems(mediaItems, startIndex, getCurrentPosition())
                        }
                        player.prepare()
                        player.play()
                    }
                    Player.STATE_ENDED -> {
                        val index = currentQueueIndexInternal.takeIf { it in activeQueue.indices } ?: 0
                        if (activeQueue.isNotEmpty()) {
                            player.seekToDefaultPosition(index)
                        }
                        player.play()
                    }
                    else -> player.play()
                }
            }
            persistPlaybackPosition()
            refreshPlaybackNotification()
        }
    }

    fun playNext(source: String = "ui") {
        val fromSong = _currentSong.value
        val fromIndex = currentQueueIndexInternal
        val repeatMode = _repeatMode.value ?: 0
        val plan = PlaybackTransitionLogic.resolveManualNextRepeatPlan(repeatMode)
        playNextInternal(wrapAround = plan.wrapAround)
        _repeatMode.value = plan.nextRepeatMode
        PlaybackAnalyticsLogger.logSkip(
            action = "next",
            source = source,
            fromSong = fromSong,
            toSong = _currentSong.value,
            fromIndex = fromIndex,
            toIndex = currentQueueIndexInternal
        )
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

    fun playPrevious(source: String = "ui") {
        if (activeQueue.isEmpty()) return
        val fromSong = _currentSong.value
        val fromIndex = currentQueueIndexInternal
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
        PlaybackAnalyticsLogger.logSkip(
            action = "previous",
            source = source,
            fromSong = fromSong,
            toSong = _currentSong.value,
            fromIndex = fromIndex,
            toIndex = currentQueueIndexInternal
        )
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
        addSongsToQueue(listOf(song))
    }

    fun addSongsToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val mutableQueue = activeQueue.toMutableList()
        mutableQueue.addAll(songs)
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

    fun replaceUpcomingQueue(newUpcoming: List<Song>) {
        if (activeQueue.isEmpty()) return
        val currentIndex = currentQueueIndexInternal
        if (currentIndex !in activeQueue.indices) return
        val prefix = activeQueue.take(currentIndex + 1)
        val updatedQueue = (prefix + newUpcoming).distinctBy { song ->
            song.sourceVideoId?.takeIf { it.isNotBlank() } ?: song.path
        }
        applyQueueMutation(updatedQueue.toMutableList(), currentIndex)
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
        val target = playlistsInternal.find { it.id == playlistId } ?: return false
        if (!target.isEditablePlaylist()) return false
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
        val target = playlistsInternal.find { it.id == playlistId } ?: return false
        if (!target.isEditablePlaylist()) return false
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
        if (playlistId == LOCAL_MUSIC_PLAYLIST_ID) return 0
        if (songs.isEmpty()) return 0

        val target = playlistsInternal.find { it.id == playlistId } ?: return 0
        if (!target.isEditablePlaylist()) return 0
        val existingKeys = target.songs.map(::playlistSongKey).toMutableSet()
        val songsToAdd = songs.filter { existingKeys.add(playlistSongKey(it)) }
        if (songsToAdd.isEmpty()) return 0

        val updatedSongs = target.songs + songsToAdd
        val updatedPlaylist = target.copy(
            songPaths = updatedSongs.map { it.path },
            songs = updatedSongs,
            coverArtUri = updatedSongs.firstOrNull()?.albumArtUri ?: target.coverArtUri
        )
        playlistsInternal = playlistsInternal.map {
            if (it.id == playlistId) updatedPlaylist else it
        }
        publishPlaylists()
        return songsToAdd.size
    }

    fun removeSongsFromPlaylist(playlistId: Long, songPaths: Set<String>): Int {
        if (playlistId == LOCAL_MUSIC_PLAYLIST_ID) return 0
        if (songPaths.isEmpty()) return 0
        val target = playlistsInternal.find { it.id == playlistId } ?: return 0
        if (!target.isEditablePlaylist()) return 0
        val beforeSize = target.songs.size
        val updatedSongs = target.songs.filterNot { it.path in songPaths }
        if (beforeSize == updatedSongs.size) return 0

        val updatedPlaylist = target.copy(
            songPaths = updatedSongs.map { it.path },
            songs = updatedSongs,
            coverArtUri = updatedSongs.firstOrNull()?.albumArtUri
        )
        playlistsInternal = playlistsInternal.map {
            if (it.id == playlistId) updatedPlaylist else it
        }
        publishPlaylists()
        return beforeSize - updatedSongs.size
    }

    fun reorderPlaylistSongs(playlistId: Long, reorderedSongPaths: List<String>): Boolean {
        if (playlistId == LOCAL_MUSIC_PLAYLIST_ID) return false
        val target = playlistsInternal.find { it.id == playlistId } ?: return false
        if (!target.isEditablePlaylist()) return false
        if (target.songPaths.size != reorderedSongPaths.size) return false

        val normalized = reorderedSongPaths.distinct()
        if (normalized.size != target.songPaths.size) return false
        if (normalized.toSet() != target.songPaths.toSet()) return false
        if (normalized == target.songPaths) return true

        val songsByPath = target.songs.associateBy { it.path }
        val updated = target.copy(
            songPaths = normalized,
            songs = normalized.mapNotNull { songsByPath[it] },
            coverArtUri = normalized.firstOrNull()?.let { songsByPath[it]?.albumArtUri } ?: target.coverArtUri
        )
        playlistsInternal = playlistsInternal.map {
            if (it.id == playlistId) updated else it
        }
        publishPlaylists()
        return true
    }

    fun getPlaylistSongs(playlistId: Long): List<Song> {
        if (playlistId == LOCAL_MUSIC_PLAYLIST_ID) {
            return (_songs.value ?: normalQueue).distinctBy { it.path }
        }
        val playlist = playlistsInternal.find { it.id == playlistId } ?: return emptyList()
        if (playlist.songs.isNotEmpty()) {
            val localSongsByPath = (_songs.value ?: normalQueue).associateBy { it.path }
            return playlist.songs.map { stored -> localSongsByPath[stored.path] ?: stored }
        }
        if (playlist.songPaths.isEmpty()) return emptyList()
        val songsByPath = (_songs.value ?: normalQueue).associateBy { it.path }
        return playlist.songPaths.mapNotNull { songsByPath[it] }
    }

    fun refreshSpecialPlaylists() {
        val account = YouTubeAccountStore.read(getApplication<Application>().applicationContext)
        val fingerprint = if (account.isLoggedIn) {
            listOf(account.cookie.take(48), account.visitorData, account.dataSyncId).joinToString("|")
        } else {
            null
        }

        if (!account.isLoggedIn) {
            accountRemotePlaylists = emptyList()
            accountLikedSongs = emptyList()
            accountRemotePlaylistsFingerprint = null
            persistYouTubeLikedSongs(emptyList())
        } else if (fingerprint != accountRemotePlaylistsFingerprint && !isRefreshingAccountRemotePlaylists) {
            isRefreshingAccountRemotePlaylists = true
            viewModelScope.launch {
                try {
                    val fetched = runCatching {
                        youTubeApiClient.browseCollection(
                            browseId = YouTubeAccountSurfaces.PLAYLISTS_BROWSE_ID,
                            maxResults = 240
                        )
                    }.getOrDefault(emptyList())

                    accountRemotePlaylists = fetched
                        .filter {
                            it.type == YouTubeItemType.PLAYLIST &&
                                !it.browseId.isNullOrBlank() &&
                                it.title.isNotBlank() &&
                                !it.browseId.equals(PlaylistSystem.YOUTUBE_LIKED_MUSIC_BROWSE_ID, ignoreCase = true)
                        }
                        .distinctBy { it.browseId }
                        .mapIndexed { index, item ->
                            Playlist(
                                id = -10_000_000L - index - item.browseId.orEmpty().hashCode().toLong().absoluteValue,
                                name = item.title,
                                songPaths = emptyList(),
                                coverArtUri = item.thumbnailUrl,
                                remoteBrowseId = item.browseId,
                                remoteBrowseType = YouTubeItemType.PLAYLIST
                            )
                        }
                    accountRemotePlaylistsFingerprint = fingerprint
                    rebuildSystemPlaylists()
                    _playlists.postValue(playlistsInternal)
                } finally {
                    isRefreshingAccountRemotePlaylists = false
                }
            }
        }

        if (account.isLoggedIn && !isRefreshingAccountLikedSongs) {
            isRefreshingAccountLikedSongs = true
            viewModelScope.launch {
                try {
                    syncYouTubeLikedSongsPlaylist()
                    rebuildSystemPlaylists()
                    _playlists.postValue(playlistsInternal)
                } finally {
                    isRefreshingAccountLikedSongs = false
                }
            }
        }

        rebuildSystemPlaylists()
        _playlists.value = playlistsInternal
    }

    private suspend fun syncYouTubeLikedSongsPlaylist() {
        val fetched = runCatching {
            youTubeApiClient.browseCollection(
                browseId = PlaylistSystem.YOUTUBE_LIKED_MUSIC_BROWSE_ID,
                maxResults = YOUTUBE_LIKED_MAX_RESULTS
            )
        }.getOrNull() ?: return

        if (fetched.isEmpty()) {
            accountLikedSongs = emptyList()
            persistYouTubeLikedSongs(emptyList())
            return
        }

        val existingByVideoId = accountLikedSongs
            .mapNotNull { song -> song.sourceVideoId?.takeIf { it.isNotBlank() }?.let { it to song } }
            .toMap()
            .toMutableMap()

        val syncedSongs = buildList {
            fetched
                .asSequence()
                .mapNotNull { item -> item.videoId?.trim()?.takeIf { it.isNotEmpty() }?.let { it to item } }
                .distinctBy { it.first }
                .forEach { (videoId, item) ->
                    val existing = existingByVideoId[videoId]
                    val streamUrl = existing?.path?.takeIf { it.isNotBlank() }
                        ?: runCatching { youTubeApiClient.resolveAudioStreamUrlFast(videoId) }.getOrNull()
                    if (streamUrl.isNullOrBlank()) return@forEach
                    add(
                        Song(
                            id = existing?.id ?: (-20_000_000L - videoId.hashCode().toLong().absoluteValue),
                            title = item.title.ifBlank { existing?.title ?: "Unknown title" },
                            artist = item.channelTitle.ifBlank { existing?.artist ?: "YouTube Music" },
                            album = existing?.album ?: "YouTube Music",
                            duration = existing?.duration ?: 0L,
                            dateAddedSeconds = existing?.dateAddedSeconds ?: 0L,
                            path = streamUrl,
                            albumArtUri = item.thumbnailUrl ?: existing?.albumArtUri,
                            sourceVideoId = videoId,
                            sourcePlaylistId = item.sourcePlaylistId ?: PlaylistSystem.YOUTUBE_LIKED_MUSIC_BROWSE_ID,
                            sourcePlaylistSetVideoId = item.sourcePlaylistSetVideoId,
                            sourceParams = item.sourceParams,
                            sourceIndex = item.sourceIndex
                        )
                    )
                }
        }

        accountLikedSongs = syncedSongs
        persistYouTubeLikedSongs(syncedSongs)
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
        val currentPosition = getCurrentPosition().coerceAtLeast(0L)
        val wasPlaying = exoPlayer?.isPlaying == true
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

        syncPlayerQueueWithActiveQueue(
            positionMs = currentPosition,
            keepPlaying = wasPlaying
        )
    }

    private fun syncPlayerQueueWithActiveQueue(positionMs: Long, keepPlaying: Boolean) {
        if (activeQueue.isEmpty()) return
        val player = bindToSharedPlayer()
        val mediaItems = activeQueue.map { mediaItemForSong(it) }
        val startIndex = currentQueueIndexInternal.coerceIn(0, mediaItems.lastIndex)
        val wasPlayWhenReady = player.playWhenReady || keepPlaying
        player.setMediaItems(mediaItems, startIndex, positionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = wasPlayWhenReady
    }

    private fun persistQueueState(positionOverrideMs: Long? = null) {
        val positionMs = (positionOverrideMs ?: exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
        val queueSongsJson = JSONArray().apply {
            activeQueue.forEach { song ->
                put(
                    JSONObject()
                        .put("id", song.id)
                        .put("title", song.title)
                        .put("artist", song.artist)
                        .put("album", song.album)
                        .put("duration", song.duration)
                        .put("date_added_seconds", song.dateAddedSeconds)
                        .put("path", song.path)
                        .put("album_art_uri", song.albumArtUri)
                        .put("source_video_id", song.sourceVideoId)
                        .put("source_playlist_id", song.sourcePlaylistId)
                        .put("source_playlist_set_video_id", song.sourcePlaylistSetVideoId)
                        .put("source_params", song.sourceParams)
                        .put("source_index", song.sourceIndex)
                )
            }
        }
        // Use song path for durable restore across potential MediaStore ID changes.
        prefs.edit()
            .putString(KEY_QUEUE_SONGS_JSON, queueSongsJson.toString())
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

    fun refreshPlaybackSettingsFromStorage() {
        val updated = PlaybackSettingsStore.read(getApplication<Application>().applicationContext)
        playbackSettings = updated
        val isIdleQueue = activeQueue.isEmpty() || _currentSong.value == null
        if (isIdleQueue) {
            _isShuffleEnabled.value = updated.defaultShuffleEnabled
            _repeatMode.value = updated.defaultRepeatMode
        }
        exoPlayer?.let { applyPlaybackAudioOptions(it, updated) }
    }

    fun handleExternalPlaybackAction(action: String) {
        handleNotificationAction(action)
    }

    private fun handleNotificationAction(action: String) {
        when (action) {
            PlaybackNotificationManager.ACTION_PREVIOUS -> playPrevious(source = "notification")
            PlaybackNotificationManager.ACTION_PLAY_PAUSE -> {
                if (_shouldRestartQueue.value == true) restartQueueFromBeginning() else playPause()
            }
            PlaybackNotificationManager.ACTION_NEXT -> playNext(source = "notification")
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
                    .setArtworkUri(song.albumArtUri?.let { Uri.parse(it) })
                    .build()
            )
            .build()
    }

    private fun applyPlaybackAudioOptions(player: ExoPlayer, settings: PlaybackSettings) {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(attributes, settings.handleAudioFocus)
        player.setHandleAudioBecomingNoisy(settings.pauseOnNoisyOutput)
    }

    private fun publishPlaylists() {
        rebuildSystemPlaylists()
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
        accountLikedSongs = loadPersistedYouTubeLikedSongs()
        val raw = prefs.getString(KEY_PLAYLISTS_JSON, null) ?: run {
            playlistsInternal = emptyList()
            rebuildSystemPlaylists()
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
                    if (id <= 0L || name.isEmpty() || id == LOCAL_MUSIC_PLAYLIST_ID) continue

                    val songPathsArray = json.optJSONArray("song_paths") ?: JSONArray()
                    val paths = buildList {
                        for (songIndex in 0 until songPathsArray.length()) {
                            val path = songPathsArray.optString(songIndex, "").trim()
                            if (path.isNotEmpty()) add(path)
                        }
                    }.distinct()

                    val songsArray = json.optJSONArray("songs") ?: JSONArray()
                    val songs = buildList {
                        for (songIndex in 0 until songsArray.length()) {
                            val songJson = songsArray.optJSONObject(songIndex) ?: continue
                            songFromJson(songJson)?.let(::add)
                        }
                    }

                    add(
                        Playlist(
                            id = id,
                            name = name,
                            songPaths = if (songs.isNotEmpty()) songs.map { it.path } else paths,
                            songs = songs,
                            coverArtUri = json.optString("cover_art_uri", "").ifBlank { songs.firstOrNull()?.albumArtUri },
                            remoteBrowseId = json.optString("remote_browse_id", "").ifBlank { null },
                            remoteBrowseType = YouTubeItemType.entries.getOrNull(
                                json.optInt("remote_browse_type", YouTubeItemType.UNKNOWN.ordinal)
                            ) ?: YouTubeItemType.UNKNOWN
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        rebuildSystemPlaylists()
        _playlists.value = playlistsInternal
    }

    private fun persistPlaylists() {
        val jsonArray = JSONArray()
        playlistsInternal
            .filter { it.id > 0L }
            .forEach { playlist ->
            val playlistJson = JSONObject()
                .put("id", playlist.id)
                .put("name", playlist.name)
                .put("song_paths", JSONArray(playlist.songPaths))
                .put("songs", JSONArray().apply { playlist.songs.forEach { put(songToJson(it)) } })
                .put("cover_art_uri", playlist.coverArtUri)
                .put("remote_browse_id", playlist.remoteBrowseId)
                .put("remote_browse_type", playlist.remoteBrowseType.ordinal)
            jsonArray.put(playlistJson)
        }

        prefs.edit()
            .putString(KEY_PLAYLISTS_JSON, jsonArray.toString())
            .commit()
    }

    private fun loadPersistedYouTubeLikedSongs(): List<Song> {
        val raw = prefs.getString(KEY_YOUTUBE_LIKED_SONGS_JSON, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val songJson = jsonArray.optJSONObject(index) ?: continue
                    songFromJson(songJson)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistYouTubeLikedSongs(songs: List<Song>) {
        val jsonArray = JSONArray().apply {
            songs.forEach { song ->
                put(songToJson(song))
            }
        }
        prefs.edit()
            .putString(KEY_YOUTUBE_LIKED_SONGS_JSON, jsonArray.toString())
            .commit()
    }

    private fun youtubeLikedMusicPlaylist(): Playlist {
        val songs = accountLikedSongs
        return Playlist(
            id = YOUTUBE_LIKED_MUSIC_PLAYLIST_ID,
            name = PlaylistSystem.YOUTUBE_LIKED_MUSIC_NAME,
            songPaths = songs.map { it.path },
            songs = songs,
            coverArtUri = songs.firstOrNull()?.albumArtUri
        )
    }

    private fun ensureLocalMusicPlaylist() {
        val localPaths = (_songs.value ?: normalQueue).map { it.path }.distinct()
        val localSongs = (_songs.value ?: normalQueue).distinctBy { it.path }
        val localPlaylist = Playlist(
            id = LOCAL_MUSIC_PLAYLIST_ID,
            name = PlaylistSystem.LOCAL_MUSIC_NAME,
            songPaths = localPaths,
            coverArtUri = localSongs.firstOrNull()?.albumArtUri
        )
        val others = playlistsInternal.filterNot { it.id == LOCAL_MUSIC_PLAYLIST_ID }
        playlistsInternal = listOf(localPlaylist) + others
    }

    private fun rebuildSystemPlaylists() {
        ensureLocalMusicPlaylist()
        val account = YouTubeAccountStore.read(getApplication<Application>().applicationContext)
        val custom = playlistsInternal.filter { it.id > 0L }.sortedByDescending { it.id }
        val system = mutableListOf(playlistsInternal.first { it.id == LOCAL_MUSIC_PLAYLIST_ID })
        if (account.isLoggedIn) {
            system += youtubeLikedMusicPlaylist()
            system += accountRemotePlaylists
        }
        playlistsInternal = system + custom
    }

    private fun playlistSongKey(song: Song): String {
        return song.sourceVideoId?.takeIf { it.isNotBlank() } ?: song.path
    }

    private fun songToJson(song: Song): JSONObject {
        return JSONObject()
            .put("id", song.id)
            .put("title", song.title)
            .put("artist", song.artist)
            .put("album", song.album)
            .put("duration", song.duration)
            .put("date_added_seconds", song.dateAddedSeconds)
            .put("path", song.path)
            .put("album_art_uri", song.albumArtUri)
            .put("source_video_id", song.sourceVideoId)
            .put("source_playlist_id", song.sourcePlaylistId)
            .put("source_playlist_set_video_id", song.sourcePlaylistSetVideoId)
            .put("source_params", song.sourceParams)
            .put("source_index", song.sourceIndex)
    }

    private fun songFromJson(json: JSONObject): Song? {
        val path = json.optString("path", "").trim()
        if (path.isBlank()) return null
        return Song(
            id = json.optLong("id", 0L),
            title = json.optString("title", ""),
            artist = json.optString("artist", ""),
            album = json.optString("album", ""),
            duration = json.optLong("duration", 0L),
            dateAddedSeconds = json.optLong("date_added_seconds", 0L),
            path = path,
            albumArtUri = json.optString("album_art_uri", "").ifBlank { null },
            sourceVideoId = json.optString("source_video_id", "").ifBlank { null },
            sourcePlaylistId = json.optString("source_playlist_id", "").ifBlank { null },
            sourcePlaylistSetVideoId = json.optString("source_playlist_set_video_id", "").ifBlank { null },
            sourceParams = json.optString("source_params", "").ifBlank { null },
            sourceIndex = json.optInt("source_index").takeIf { !json.isNull("source_index") }
        )
    }

    private fun restorePersistedQueue(allSongs: List<Song>): Pair<List<Song>?, Int> {
        val savedIndex = prefs.getInt(KEY_CURRENT_QUEUE_INDEX, -1)

        val savedQueueSongsJson = prefs.getString(KEY_QUEUE_SONGS_JSON, null)
        if (!savedQueueSongsJson.isNullOrBlank()) {
            val restoredSongs = runCatching {
                val jsonArray = JSONArray(savedQueueSongsJson)
                buildList {
                    for (i in 0 until jsonArray.length()) {
                        val json = jsonArray.optJSONObject(i) ?: continue
                        val path = json.optString("path", "").trim()
                        if (path.isEmpty()) continue
                        add(
                            Song(
                                id = json.optLong("id", 0L),
                                title = json.optString("title", ""),
                                artist = json.optString("artist", ""),
                                album = json.optString("album", ""),
                                duration = json.optLong("duration", 0L),
                                dateAddedSeconds = json.optLong("date_added_seconds", 0L),
                                path = path,
                                albumArtUri = json.optString("album_art_uri", "").ifBlank { null },
                                sourceVideoId = json.optString("source_video_id", "").ifBlank { null },
                                sourcePlaylistId = json.optString("source_playlist_id", "").ifBlank { null },
                                sourcePlaylistSetVideoId = json.optString("source_playlist_set_video_id", "").ifBlank { null },
                                sourceParams = json.optString("source_params", "").ifBlank { null },
                                sourceIndex = if (json.has("source_index") && !json.isNull("source_index")) {
                                    json.optInt("source_index")
                                } else {
                                    null
                                }
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())

            if (restoredSongs.isNotEmpty()) {
                return Pair(restoredSongs.distinctBy { it.path }, savedIndex)
            }
        }

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
        listenerAttachedPlayer?.removeListener(playbackListener)
        listenerAttachedPlayer = null
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
