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
import kotlin.random.Random

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

    private var currentSongIndex = 0
    private var songList: List<Song> = emptyList()

    init {
        _isShuffleEnabled.value = false
        _repeatMode.value = 0
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
            songList = repository.getAllSongs()
            _songs.value = songList
        }
    }

    fun playSong(song: Song) {
        val index = songList.indexOf(song)
        if (index != -1) {
            currentSongIndex = index
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
        if (songList.isEmpty()) return
        
        currentSongIndex = if (_isShuffleEnabled.value == true) {
            Random.nextInt(songList.size)
        } else {
            (currentSongIndex + 1) % songList.size
        }
        playSong(songList[currentSongIndex])
    }

    fun playPrevious() {
        if (songList.isEmpty()) return
        currentSongIndex = if (currentSongIndex > 0) {
            currentSongIndex - 1
        } else {
            songList.size - 1
        }
        playSong(songList[currentSongIndex])
    }

    fun toggleShuffle() {
        _isShuffleEnabled.value = !(_isShuffleEnabled.value ?: false)
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
