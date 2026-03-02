package com.blazinghotcode.blazingmusic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private var exoPlayer: ExoPlayer? = null

    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> = _songs

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> = _currentSong

    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying

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

    private var normalQueue: List<Song> = emptyList()
    private var activeQueue: List<Song> = emptyList()
    private var currentQueueIndexInternal = -1

    init {
        _isShuffleEnabled.value = false
        _repeatMode.value = 0
        _currentQueueIndex.value = -1
        _queue.value = emptyList()
        initializePlayer()
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(getApplication()).build().apply {
            val player = this
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.postValue(playing)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _duration.postValue(player.duration)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        playNext()
                    }
                }
            })
        }
    }

    fun loadSongs() {
        viewModelScope.launch {
            normalQueue = repository.getAllSongs()
            _songs.value = normalQueue
            val current = _currentSong.value
            activeQueue = if (_isShuffleEnabled.value == true) {
                buildShuffledQueue(normalQueue)
            } else {
                normalQueue
            }
            _queue.value = activeQueue
            currentQueueIndexInternal = current?.let { activeQueue.indexOf(it) } ?: -1
            _currentQueueIndex.value = currentQueueIndexInternal
        }
    }

    fun playSong(song: Song) {
        var index = activeQueue.indexOf(song)
        if (index == -1) {
            // If user selects a song from the full library that is no longer in queue,
            // rebuild queue from full songs and keep shuffle mode behavior.
            val fullLibrary = _songs.value ?: normalQueue
            normalQueue = fullLibrary
            activeQueue = if (_isShuffleEnabled.value == true) {
                buildShuffledQueue(normalQueue)
            } else {
                normalQueue
            }
            _queue.value = activeQueue
            index = activeQueue.indexOf(song)
        }
        if (index != -1) {
            currentQueueIndexInternal = index
            _currentQueueIndex.value = index
        } else {
            currentQueueIndexInternal = -1
            _currentQueueIndex.value = -1
        }
        _currentSong.value = song

        exoPlayer?.let { player ->
            val mediaItem = MediaItem.fromUri(song.path)
            player.setMediaItem(mediaItem)
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
        }
    }

    fun playNext() {
        if (activeQueue.isEmpty()) return
        val nextIndex = if (currentQueueIndexInternal == -1) {
            0
        } else {
            (currentQueueIndexInternal + 1) % activeQueue.size
        }
        playSongAt(nextIndex)
    }

    fun playPrevious() {
        if (activeQueue.isEmpty()) return
        val previousIndex = if (currentQueueIndexInternal == -1) {
            activeQueue.lastIndex
        } else if (currentQueueIndexInternal > 0) {
            currentQueueIndexInternal - 1
        } else {
            activeQueue.lastIndex
        }
        playSongAt(previousIndex)
    }

    fun toggleShuffle() {
        val isEnabled = !(_isShuffleEnabled.value ?: false)
        _isShuffleEnabled.value = isEnabled

        val current = _currentSong.value
        activeQueue = if (isEnabled) {
            buildShuffledQueue(normalQueue)
        } else {
            normalQueue
        }
        _queue.value = activeQueue
        currentQueueIndexInternal = current?.let { activeQueue.indexOf(it) } ?: -1
        _currentQueueIndex.value = currentQueueIndexInternal
    }

    fun toggleRepeat() {
        val current = _repeatMode.value ?: 0
        val nextMode = (current + 1) % 3
        _repeatMode.value = nextMode
        exoPlayer?.repeatMode = when (nextMode) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    fun playSongAt(index: Int) {
        if (index !in activeQueue.indices) return
        currentQueueIndexInternal = index
        _currentQueueIndex.value = index
        playSong(activeQueue[index])
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in activeQueue.indices || toIndex !in activeQueue.indices || fromIndex == toIndex) {
            return
        }

        val mutableQueue = activeQueue.toMutableList()
        val movedSong = mutableQueue.removeAt(fromIndex)
        mutableQueue.add(toIndex, movedSong)
        applyQueueMutation(mutableQueue)
    }

    fun removeQueueItem(index: Int) {
        if (index !in activeQueue.indices) return

        val mutableQueue = activeQueue.toMutableList()
        val removedSong = mutableQueue.removeAt(index)
        applyQueueMutation(mutableQueue)

        val current = _currentSong.value
        if (current?.id == removedSong.id) {
            if (activeQueue.isEmpty()) {
                exoPlayer?.stop()
                _currentSong.value = null
                _isPlaying.value = false
                currentQueueIndexInternal = -1
                _currentQueueIndex.value = -1
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
        applyQueueMutation(mutableQueue)
    }

    fun setQueueOrder(newQueue: List<Song>) {
        if (newQueue.isEmpty() || newQueue.size != activeQueue.size) return
        val currentIds = activeQueue.map { it.id }.toSet()
        val newIds = newQueue.map { it.id }.toSet()
        if (currentIds != newIds) return
        applyQueueMutation(newQueue.toMutableList())
    }

    private fun buildShuffledQueue(queue: List<Song>): List<Song> {
        if (queue.isEmpty()) return queue
        return queue.shuffled()
    }

    private fun applyQueueMutation(mutableQueue: MutableList<Song>) {
        activeQueue = mutableQueue.toList()
        _queue.value = activeQueue

        val current = _currentSong.value
        currentQueueIndexInternal = current?.let { nowPlaying ->
            activeQueue.indexOfFirst { it.id == nowPlaying.id }
        } ?: -1
        _currentQueueIndex.value = currentQueueIndexInternal

        if (_isShuffleEnabled.value != true) {
            normalQueue = activeQueue
        } else {
            val queueSongIds = activeQueue.map { it.id }.toSet()
            normalQueue = normalQueue.filter { it.id in queueSongIds }
        }
    }

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0L
    }

    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0L
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer?.release()
        exoPlayer = null
    }
}
