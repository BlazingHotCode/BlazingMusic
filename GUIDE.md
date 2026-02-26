# BlazingMusic - Complete Development Guide

> **Progress**: ✅ Step 1 Completed (Dependencies & Manifest)

This guide walks you through building a fully functional Android music player app.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Step 1: Project Setup & Dependencies](#step-1-project-setup--dependencies)
  - [1.1 Update app/build.gradle.kts](#11-update-appbuildgradlekts)
  - [1.2 Update AndroidManifest.xml](#12-update-androidmanifestxml)
- [Step 2: Create Data Models](#step-2-create-data-models)
  - [2.1 Create Song.kt](#21-create-songkt)
- [Step 3: Create Repository](#step-3-create-repository-for-media-access)
  - [3.1 Create MusicRepository.kt](#31-create-musicrepositorykt)
- [Step 4: Create ViewModel](#step-4-create-viewmodel)
  - [4.1 Create MusicViewModel.kt](#41-create-musicviewmodelkt)
- [Step 5: Create Music Service](#step-5-create-music-service-background-playback)
  - [5.1 Create MusicService.kt](#51-create-musicservicekt)
- [Step 6: Create Layouts](#step-6-create-layouts)
  - [6.1 Create activity_main.xml](#61-create-activity_mainxml)
  - [6.2 Create item_song.xml](#62-create-item_songxml)
- [Step 7: Create Adapter](#step-7-create-adapter-for-recyclerview)
  - [7.1 Create SongAdapter.kt](#71-create-songadapterkt)
- [Step 8: Update MainActivity](#step-8-update-mainactivity)
  - [8.1 Update MainActivity.kt](#81-update-mainactivitykt)
- [Step 9: Add String Resources](#step-9-add-string-resources)
  - [9.1 Update res/values/strings.xml](#91-update-resvaluesstringsxml)
- [Step 10: Build and Run](#step-10-build-and-run)
- [Next Steps (Enhancements)](#next-steps-enhancements)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

- Android Studio (Koala or newer)
- Kotlin knowledge basics
- Android device/emulator for testing

---

## Step 1: Project Setup & Dependencies

### 1.1 Update `app/build.gradle.kts`

Replace the dependencies section with:

```kotlin
dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Media Playback (Media3 ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Image Loading (for album art)
    implementation("io.coil-kt:coil:2.5.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
```

### 1.2 Update `AndroidManifest.xml`

Add permissions and service declaration:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.BlazingMusic"
        tools:targetApi="34">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.BlazingMusic">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".MusicService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>

    </application>

</manifest>
```

---

## Step 2: Create Data Models

### 2.1 Create `Song.kt`

```kotlin
package com.blazinghotcode.blazingmusic

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val albumArtUri: String?
)
```

---

## Step 3: Create Repository for Media Access

### 3.1 Create `MusicRepository.kt`

```kotlin
package com.blazinghotcode.blazingmusic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                ).toString()

                songs.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleColumn) ?: "Unknown",
                        artist = cursor.getString(artistColumn) ?: "Unknown Artist",
                        album = cursor.getString(albumColumn) ?: "Unknown Album",
                        duration = cursor.getLong(durationColumn),
                        path = cursor.getString(pathColumn) ?: "",
                        albumArtUri = albumArtUri
                    )
                )
            }
        }

        songs
    }
}
```

---

## Step 4: Create ViewModel

### 4.1 Create `MusicViewModel.kt`

```kotlin
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

    private val _currentPosition = MutableLiveData<Long>()
    val currentPosition: LiveData<Long> = _currentPosition

    private var currentSongIndex = 0
    private var songList: List<Song> = emptyList()

    init {
        initializePlayer()
        loadSongs()
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(getApplication()).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.postValue(playing)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
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
        currentSongIndex = (currentSongIndex + 1) % songList.size
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
```

---

## Step 5: Create Music Service (Background Playback)

### 5.1 Create `MusicService.kt`

```kotlin
package com.blazinghotcode.blazingmusic

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
```

---

## Step 6: Create Layouts

### 6.1 Create `activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#121212"
    tools:context=".MainActivity">

    <TextView
        android:id="@+id/tvTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="BlazingMusic"
        android:textColor="#FFFFFF"
        android:textSize="24sp"
        android:textStyle="bold"
        android:layout_marginTop="16dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvSongs"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="16dp"
        android:layout_marginBottom="150dp"
        app:layout_constraintTop_toBottomOf="@id/tvTitle"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/playerLayout"
        android:layout_width="0dp"
        android:layout_height="150dp"
        android:background="#1E1E1E"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        tools:visibility="visible">

        <ImageView
            android:id="@+id/ivAlbumArt"
            android:layout_width="80dp"
            android:layout_height="80dp"
            android:layout_marginStart="16dp"
            android:background="#333333"
            android:contentDescription="Album Art"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent"/>

        <TextView
            android:id="@+id/tvSongTitle"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            app:layout_constraintBottom_toTopOf="@id/tvArtist"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toEndOf="@id/ivAlbumArt"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintVertical_chainStyle="packed"
            tools:text="Song Title"/>

        <TextView
            android:id="@+id/tvArtist"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="#AAAAAA"
            android:textSize="14sp"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toEndOf="@id/ivAlbumArt"
            app:layout_constraintTop_toBottomOf="@id/tvSongTitle"
            tools:text="Artist Name"/>

        <ImageButton
            android:id="@+id/btnPlayPause"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_marginEnd="16dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Play/Pause"
            android:src="@android:drawable/ic_media_play"
            app:layout_constraintBottom_toBottomOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:tint="#FFFFFF"/>

    </androidx.constraintlayout.widget.ConstraintLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

### 6.2 Create `item_song.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="16dp"
    android:background="?attr/selectableItemBackground">

    <ImageView
        android:id="@+id/ivAlbumArt"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:background="#333333"
        android:contentDescription="Album Art"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"/>

    <TextView
        android:id="@+id/tvTitle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:ellipsize="end"
        android:maxLines="1"
        android:textColor="#FFFFFF"
        android:textSize="16sp"
        app:layout_constraintBottom_toTopOf="@id/tvArtist"
        app:layout_constraintEnd_toStartOf="@id/tvDuration"
        app:layout_constraintStart_toEndOf="@id/ivAlbumArt"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintVertical_chainStyle="packed"
        tools:text="Song Title"/>

    <TextView
        android:id="@+id/tvArtist"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:ellipsize="end"
        android:maxLines="1"
        android:textColor="#AAAAAA"
        android:textSize="14sp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toStartOf="@id/tvDuration"
        app:layout_constraintStart_toEndOf="@id/ivAlbumArt"
        app:layout_constraintTop_toBottomOf="@id/tvTitle"
        tools:text="Artist"/>

    <TextView
        android:id="@+id/tvDuration"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textColor="#AAAAAA"
        android:textSize="12sp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        tools:text="3:45"/>

</androidx.constraintlayout.widget.ConstraintLayout>
```

---

## Step 7: Create Adapter for RecyclerView

### 7.1 Create `SongAdapter.kt`

```kotlin
package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.blazinghotcode.blazingmusic.databinding.ItemSongBinding

class SongAdapter(
    private val onSongClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSongClick(getItem(position))
                }
            }
        }

        fun bind(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.tvDuration.text = formatDuration(song.duration)

            song.albumArtUri?.let { uri ->
                binding.ivAlbumArt.load(uri) {
                    crossfade(true)
                }
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            return String.format("%d:%02d", minutes, seconds)
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}
```

---

## Step 8: Update MainActivity

### 8.1 Update `MainActivity.kt`

```kotlin
package com.blazinghotcode.blazingmusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

class MainActivity : AppCompatActivity() {

    private val viewModel: MusicViewModel by viewModels()
    private lateinit var songAdapter: SongAdapter

    private lateinit var rvSongs: RecyclerView
    private lateinit var playerLayout: View
    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var ivAlbumArt: ImageView
    private lateinit var btnPlayPause: ImageButton

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.loadSongs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerView()
        setupPlayerControls()
        observeViewModel()
        checkPermissions()
    }

    private fun initViews() {
        rvSongs = findViewById(R.id.rvSongs)
        playerLayout = findViewById(R.id.playerLayout)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvArtist = findViewById(R.id.tvArtist)
        ivAlbumArt = findViewById(R.id.ivAlbumArt)
        btnPlayPause = findViewById(R.id.btnPlayPause)
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter { song ->
            viewModel.playSong(song)
        }
        rvSongs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = songAdapter
        }
    }

    private fun setupPlayerControls() {
        btnPlayPause.setOnClickListener {
            viewModel.playPause()
        }

        playerLayout.setOnClickListener {
            // Could open full player screen
        }
    }

    private fun observeViewModel() {
        viewModel.songs.observe(this) { songs ->
            songAdapter.submitList(songs)
        }

        viewModel.currentSong.observe(this) { song ->
            song?.let {
                playerLayout.visibility = View.VISIBLE
                tvSongTitle.text = it.title
                tvArtist.text = it.artist
                it.albumArtUri?.let { uri ->
                    ivAlbumArt.load(uri) {
                        crossfade(true)
                    }
                }
            }
        }

        viewModel.isPlaying.observe(this) { isPlaying ->
            val icon = if (isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            btnPlayPause.setImageResource(icon)
        }
    }

    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED -> {
                viewModel.loadSongs()
            }
            else -> {
                permissionLauncher.launch(permission)
            }
        }
    }
}
```

---

## Step 9: Add String Resources

### 9.1 Update `res/values/strings.xml`

```xml
<resources>
    <string name="app_name">BlazingMusic</string>
</resources>
```

---

## Step 10: Build and Run

1. Click **Build** → **Make Project** (or press `Ctrl+F9`)
2. If build succeeds, run on emulator/device

---

## Next Steps (Enhancements)

Once the basic app works, consider adding:

1. **Full Player Screen** - Seekbar, previous/next buttons, shuffle/repeat
2. **Search Functionality** - Filter songs by title/artist
3. **Playlists** - Create and manage playlists
4. **Equalizer** - Audio effects
5. **Notifications** - Media notification with controls
6. **Dark/Light Theme** - Material Design 3 theming

---

## Troubleshooting

| Issue             | Solution                                              |
|-------------------|-------------------------------------------------------|
| No songs found    | Ensure device has music files; grant permission       |
| App crashes       | Check Logcat for errors; verify layouts               |
| Permission denied | Go to Settings → Apps → BlazingMusic → Permissions    |
| Build fails       | Check Gradle sync; verify dependencies version        |

---

*Happy Coding!* 🔥
