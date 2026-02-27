# BlazingMusic - Improvement Guide

This guide covers improvements to add to your BlazingMusic app.

---

## Current Features

- ✅ Display list of songs from device
- ✅ Play/pause music
- ✅ Show now playing info
- ✅ Filter out WhatsApp voice messages

---

## Table of Contents

1. [Add Previous/Next Buttons](#1-add-previousnext-buttons)
2. [Add Seekbar & Time Display](#2-add-seekbar--time-display)
3. [Add Shuffle & Repeat Modes](#3-add-shuffle--repeat-modes)
4. [Background Playback](#4-background-playback)
5. [Media Notification](#5-media-notification)
6. [Search Functionality](#6-search-functionality)
7. [Better UI Styling](#7-better-ui-styling)

---

## 1. Add Previous/Next Buttons

### Update `activity_main.xml`

**Position:** Add before the closing `</androidx.constraintlayout.widget.ConstraintLayout>` tag (around line 100), before the `btnPlayPause` button.

Add two new buttons next to the play/pause button:

```xml
<ImageButton
    android:id="@+id/btnPrevious"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="Previous"
    android:src="@android:drawable/ic_media_previous"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintEnd_toStartOf="@id/btnPlayPause"
    app:layout_constraintTop_toTopOf="parent"
    app:tint="#FFFFFF"/>

<ImageButton
    android:id="@+id/btnNext"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="Next"
    android:src="@android:drawable/ic_media_next"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintEnd_toStartOf="@id/btnPrevious"
    app:layout_constraintTop_toTopOf="parent"
    app:tint="#FFFFFF"/>
```

### Update `MainActivity.kt`

**Position:** Add after line 29 (after `btnPlayPause` declaration).

Add the new buttons and their click listeners:

```kotlin
private lateinit var btnPrevious: ImageButton
private lateinit var btnNext: ImageButton

// In initViews() - add after line 56:
btnPrevious = findViewById(R.id.btnPrevious)
btnNext = findViewById(R.id.btnNext)

// In setupPlayerControls() - add after line 72:
btnPrevious.setOnClickListener {
    viewModel.playPrevious()
}

btnNext.setOnClickListener {
    viewModel.playNext()
}
```

---

## 2. Add Seekbar & Time Display

### Update `activity_main.xml` (Seekbar)

**Position:** Add inside the player layout, before the album art ImageView (around line 80).

Add a SeekBar and time TextViews to the player layout:

```xml
<SeekBar
    android:id="@+id/seekBar"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:layout_marginStart="16dp"
    android:layout_marginEnd="16dp"
    android:progressTint="#FFFFFF"
    android:thumbTint="#FFFFFF"
    app:layout_constraintBottom_toTopOf="@id/tvCurrentTime"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintStart_toStartOf="parent"/>

<TextView
    android:id="@+id/tvCurrentTime"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginStart="16dp"
    android:textColor="#AAAAAA"
    android:textSize="12sp"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintStart_toStartOf="parent"/>

<TextView
    android:id="@+id/tvTotalTime"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginEnd="16dp"
    android:textColor="#AAAAAA"
    android:textSize="12sp"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintEnd_toEndOf="parent"/>
```

### Update `MusicViewModel.kt`

**Position:** Add after line 28 (after `currentPosition` declaration).

Add position updates:

```kotlin
private val _duration = MutableLiveData<Long>()
val duration: LiveData<Long> = _duration

// In initializePlayer listener (around line 45), update onPlaybackStateChanged:
override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_READY) {
        _duration.postValue(player.duration)
    }
    if (playbackState == Player.STATE_ENDED) {
        playNext()
    }
}
```

### Update `MainActivity.kt` (Seekbar)

**Position:** Add after line 29 (after btnPlayPause declaration).

```kotlin
private lateinit var seekBar: SeekBar
private lateinit var tvCurrentTime: TextView
private lateinit var tvTotalTime: TextView
private val handler = Handler(Looper.getMainLooper())
private val updateSeekbarRunnable = object : Runnable {
    override fun run() {
        val position = viewModel.getCurrentPosition()
        seekBar.progress = position.toInt()
        tvCurrentTime.text = formatDuration(position)
        handler.postDelayed(this, 1000)
    }
}

// In initViews() - add after line 56:
seekBar = findViewById(R.id.seekBar)
tvCurrentTime = findViewById(R.id.tvCurrentTime)
tvTotalTime = findViewById(R.id.tvTotalTime)

// In setupPlayerControls() - add after line 74:
seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            viewModel.seekTo(progress.toLong())
        }
    }
    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
})

// In observeViewModel() - add after line 104:
viewModel.duration.observe(this) { duration ->
    seekBar.max = duration.toInt()
    tvTotalTime.text = formatDuration(duration)
}

// Add helper function at the end of the class:
private fun formatDuration(durationMs: Long): String {
    val minutes = (durationMs / 1000) / 60
    val seconds = (durationMs / 1000) % 60
    return String.format("%d:%02d", minutes, seconds)
}

// Add lifecycle methods after onCreate:
override fun onResume() {
    super.onResume()
    handler.post(updateSeekbarRunnable)
}

override fun onPause() {
    super.onPause()
    handler.removeCallbacks(updateSeekbarRunnable)
}
```

Add these imports:

```kotlin
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
```

---

## 3. Add Shuffle & Repeat Modes

### Update `MusicViewModel.kt` (Shuffle/Repeat)

**Position:** Add after line 28 (after `currentPosition` declaration).

```kotlin
private val _isShuffleEnabled = MutableLiveData<Boolean>()
val isShuffleEnabled: LiveData<Boolean> = _isShuffleEnabled

private val _repeatMode = MutableLiveData<Int>() // 0 = off, 1 = repeat all, 2 = repeat one
val repeatMode: LiveData<Int> = _repeatMode

// Add these methods after playPrevious():
fun toggleShuffle() {
    _isShuffleEnabled.value = !(_isShuffleEnabled.value ?: false)
}

fun toggleRepeat() {
    val current = _repeatMode.value ?: 0
    _repeatMode.value = (current + 1) % 3
}

// Update playNext() - replace existing method:
fun playNext() {
    if (songList.isEmpty()) return
    
    currentSongIndex = if (_isShuffleEnabled.value == true) {
        Random.nextInt(songList.size)
    } else {
        (currentSongIndex + 1) % songList.size
    }
    playSong(songList[currentSongIndex])
}
```

Add import:

```kotlin
import kotlin.random.Random
```

### Update `activity_main.xml` (Shuffle/Repeat)

**Position:** Add at the top of the player layout, inside the ConstraintLayout (around line 80).

Add shuffle and repeat buttons:

```xml
<ImageButton
    android:id="@+id/btnShuffle"
    android:layout_width="40dp"
    android:layout_height="40dp"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="Shuffle"
    android:src="@android:drawable/ic_menu_directions"
    app:layout_constraintEnd_toStartOf="@id/btnNext"
    app:layout_constraintTop_toTopOf="parent"
    app:tint="#AAAAAA"/>

<ImageButton
    android:id="@+id/btnRepeat"
    android:layout_width="40dp"
    android:layout_height="40dp"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="Repeat"
    android:src="@android:drawable/ic_menu_rotate"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintTop_toTopOf="parent"
    app:tint="#AAAAAA"/>
```

### Update `MainActivity.kt` (Shuffle/Repeat)

**Position:** Add after btnNext declaration.

```kotlin
private lateinit var btnShuffle: ImageButton
private lateinit var btnRepeat: ImageButton

// In initViews() - add after btnNext initialization:
btnShuffle = findViewById(R.id.btnShuffle)
btnRepeat = findViewById(R.id.btnRepeat)

// In setupPlayerControls() - add after btnNext listeners:
btnShuffle.setOnClickListener {
    viewModel.toggleShuffle()
}

btnRepeat.setOnClickListener {
    viewModel.toggleRepeat()
}

// In observeViewModel() - add after isPlaying observer:
viewModel.isShuffleEnabled.observe(this) { isShuffleEnabled ->
    btnShuffle.alpha = if (isShuffleEnabled) 1.0f else 0.5f
}

viewModel.repeatMode.observe(this) { repeatMode ->
    when (repeatMode) {
        0 -> btnRepeat.setImageResource(android.R.drawable.ic_menu_rotate)
        1 -> btnRepeat.setImageResource(android.R.drawable.ic_menu_rotate)
        2 -> btnRepeat.setImageResource(android.R.drawable.ic_menu_revert)
    }
}
```

---

## 4. Background Playback

The MusicService is already set up. Update `MainActivity.kt` to connect to it:

**Position:** Add new imports and methods.

Add imports:

```kotlin
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
```

Add after class declaration:

```kotlin
private var controllerFuture: ListenableFuture<MediaController>? = null
```

Add lifecycle methods after onPause:

```kotlin
override fun onStart() {
    super.onStart()
    val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
    controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
    controllerFuture?.addListener({
        // Controller ready
    }, MoreExecutors.directExecutor())
}

override fun onStop() {
    MediaController.releaseFuture(controllerFuture)
    controllerFuture = null
    super.onStop()
}
```

Add to dependencies in `app/build.gradle.kts`:

```kotlin
implementation("com.google.common.guava:guava:32.1.3-android")
```

---

## 5. Media Notification

The MediaSessionService already handles notifications. Make sure it's properly connected.

For custom notification, update `MusicService.kt`:

**Position:** Replace existing class.

```kotlin
class MusicService : MediaSessionService() {
    
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaSessionCallback())
            .build()
    }
    
    private inner class MediaSessionCallback : MediaSession.Callback {
        // Customize notification actions here
    }
}
```

---

## 6. Search Functionality

### Update `activity_main.xml` (Search)

**Position:** Add after the RecyclerView (around line 35), before the player layout.

Add a search EditText at the top:

```xml
<EditText
    android:id="@+id/etSearch"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:layout_marginStart="16dp"
    android:layout_marginEnd="16dp"
    android:backgroundTint="#FFFFFF"
    android:hint="Search songs..."
    android:inputType="text"
    android:textColor="#FFFFFF"
    android:textColorHint="#AAAAAA"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintTop_toBottomOf="@id/tvTitle"/>
```

Add import:

```kotlin
import android.text.Editable
import android.text.TextWatcher
```

### Update `MainActivity.kt` (Search)

**Position:** Add after btnNext declaration.

```kotlin
private lateinit var etSearch: EditText
private var allSongs: List<Song> = emptyList()

// In initViews() - add after other view initializations:
etSearch = findViewById(R.id.etSearch)

// In observeViewModel() - modify the songs observer:
viewModel.songs.observe(this) { songs ->
    allSongs = songs
    songAdapter.submitList(songs)
}

// Add after setupPlayerControls():
private fun setupSearch() {
    etSearch.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val query = s?.toString()?.lowercase() ?: ""
            val filtered = if (query.isEmpty()) {
                allSongs
            } else {
                allSongs.filter {
                    it.title.lowercase().contains(query) || 
                    it.artist.lowercase().contains(query)
                }
            }
            songAdapter.submitList(filtered)
        }
    })
}

// Call setupSearch() in onCreate after setupRecyclerView()
```

Call `setupSearch()` in `onCreate()`:

```kotlin
// In onCreate, add after setupRecyclerView():
setupSearch()
```

---

## 7. Better UI Styling

### Update `themes.xml`

**Location:** `app/src/main/res/values/themes.xml`

Replace the content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.BlazingMusic" parent="Theme.Material3.Dark.NoActionBar">
        <item name="colorPrimary">#FF6200EE</item>
        <item name="colorPrimaryVariant">#FF3700B3</item>
        <item name="colorOnPrimary">#FFFFFFFF</item>
        <item name="colorSecondary">#FF03DAC5</item>
        <item name="colorSecondaryVariant">#FF018786</item>
        <item name="colorOnSecondary">#FF000000</item>
        <item name="android:statusBarColor">#FF121212</item>
        <item name="android:navigationBarColor">#FF121212</item>
    </style>
</resources>
```

### Add rounded album art

### Update `SongAdapter.kt`

**Position:** In the bind() function, update the image loading.

Add import:

```kotlin
import coil.load
import coil.transform.RoundedCornersTransformation
```

Replace the image loading code:

```kotlin
song.albumArtUri?.let { uri ->
    binding.ivAlbumArt.load(uri) {
        crossfade(true)
        transformations(RoundedCornersTransformation(16f))
    }
} ?: run {
    binding.ivAlbumArt.setImageResource(android.R.drawable.ic_menu_gallery)
}
```

Add dependency in `app/build.gradle.kts`:

```kotlin
implementation("io.coil-kt:coil-transformations:2.5.0")
```

---

## Next Improvements

- Add playlists functionality
- Add equalizer
- Add sleep timer
- Add lyrics support
- Add folder browsing
- Add dark/light theme toggle

---

*Keep Building!* 🔥
