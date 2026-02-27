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
3. [Add Shuffle & Repeat Modes](#3-add-shuffle--repeat-matives)
4. [Background Playback](#4-background-playback)
5. [Media Notification](#5-media-notification)
6. [Search Functionality](#6-search-functionality)
7. [Better UI Styling](#7-better-ui-styling)

---

## 1. Add Previous/Next Buttons

### Update `activity_main.xml`

**Where to add:** Inside the player `ConstraintLayout`, before the `btnPlayPause` button.

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

**Where to add:** With the other button declarations (near `btnPlayPause`).

Add the new button declarations:

```kotlin
private lateinit var btnPrevious: ImageButton
private lateinit var btnNext: ImageButton
```

**Where to add:** In the `initViews()` function.

Initialize the buttons:

```kotlin
btnPrevious = findViewById(R.id.btnPrevious)
btnNext = findViewById(R.id.btnNext)
```

**Where to add:** In the `setupPlayerControls()` function.

Add click listeners:

```kotlin
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

**Where to add:** Inside the player `ConstraintLayout`, before the album art `ImageView`.

Add a SeekBar and time TextViews:

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

**Where to add:** With the other LiveData declarations (near `currentPosition`).

Add duration LiveData:

```kotlin
private val _duration = MutableLiveData<Long>()
val duration: LiveData<Long> = _duration
```

**Where to add:** In the `initializePlayer()` function, inside the `Player.Listener`.

Update the `onPlaybackStateChanged` method:

```kotlin
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

**Where to add:** With the other view declarations (near `btnPlayPause`).

Add declarations:

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
```

Add these imports at the top:

```kotlin
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
```

**Where to add:** In the `initViews()` function.

Initialize the views:

```kotlin
seekBar = findViewById(R.id.seekBar)
tvCurrentTime = findViewById(R.id.tvCurrentTime)
tvTotalTime = findViewById(R.id.tvTotalTime)
```

**Where to add:** In the `setupPlayerControls()` function.

Add seekbar listener:

```kotlin
seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            viewModel.seekTo(progress.toLong())
        }
    }
    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
})
```

**Where to add:** In the `observeViewModel()` function, with the other observers.

Add duration observer:

```kotlin
viewModel.duration.observe(this) { duration ->
    seekBar.max = duration.toInt()
    tvTotalTime.text = formatDuration(duration)
}
```

**Where to add:** As a new function in MainActivity.

Add helper function:

```kotlin
private fun formatDuration(durationMs: Long): String {
    val minutes = (durationMs / 1000) / 60
    val seconds = (durationMs / 1000) % 60
    return String.format("%d:%02d", minutes, seconds)
}
```

**Where to add:** Add new lifecycle methods to MainActivity.

```kotlin
override fun onResume() {
    super.onResume()
    handler.post(updateSeekbarRunnable)
}

override fun onPause() {
    super.onPause()
    handler.removeCallbacks(updateSeekbarRunnable)
}
```

---

## 3. Add Shuffle & Repeat Modes

### Update `MusicViewModel.kt` (Shuffle/Repeat)

**Where to add:** With the other LiveData declarations (near `currentPosition`).

Add shuffle and repeat state:

```kotlin
private val _isShuffleEnabled = MutableLiveData<Boolean>()
val isShuffleEnabled: LiveData<Boolean> = _isShuffleEnabled

private val _repeatMode = MutableLiveData<Int>() // 0 = off, 1 = repeat all, 2 = repeat one
val repeatMode: LiveData<Int> = _repeatMode
```

**Where to add:** After the `playPrevious()` function.

Add toggle functions:

```kotlin
fun toggleShuffle() {
    _isShuffleEnabled.value = !(_isShuffleEnabled.value ?: false)
}

fun toggleRepeat() {
    val current = _repeatMode.value ?: 0
    _repeatMode.value = (current + 1) % 3
}
```

**Where to add:** In the `playNext()` function.

Update to support shuffle:

```kotlin
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

Add import at the top:

```kotlin
import kotlin.random.Random
```

### Update `activity_main.xml` (Shuffle/Repeat)

**Where to add:** At the top of the player `ConstraintLayout`, as siblings to `ivAlbumArt`.

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

**Where to add:** With the other button declarations.

Add button declarations:

```kotlin
private lateinit var btnShuffle: ImageButton
private lateinit var btnRepeat: ImageButton
```

**Where to add:** In the `initViews()` function.

Initialize buttons:

```kotlin
btnShuffle = findViewById(R.id.btnShuffle)
btnRepeat = findViewById(R.id.btnRepeat)
```

**Where to add:** In the `setupPlayerControls()` function.

Add click listeners:

```kotlin
btnShuffle.setOnClickListener {
    viewModel.toggleShuffle()
}

btnRepeat.setOnClickListener {
    viewModel.toggleRepeat()
}
```

**Where to add:** In the `observeViewModel()` function.

Add observers:

```kotlin
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

The MusicService is already set up. Update `MainActivity.kt` to connect to it.

**Where to add:** At the top of the file with other imports.

Add imports:

```kotlin
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
```

**Where to add:** With the other class-level declarations (near `songAdapter`).

Add controller future:

```kotlin
private var controllerFuture: ListenableFuture<MediaController>? = null
```

**Where to add:** Add new lifecycle methods to MainActivity (after `onCreate` or `onPause`).

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

**Where to add:** Replace the entire class content.

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

**Where to add:** After the `RecyclerView` (`rvSongs`), before the player layout (`playerLayout`).

Add a search EditText:

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

### Update `MainActivity.kt` (Search)

**Where to add:** At the top with other imports.

Add imports:

```kotlin
import android.text.Editable
import android.text.TextWatcher
```

**Where to add:** With other view declarations.

Add search-related declarations:

```kotlin
private lateinit var etSearch: EditText
private var allSongs: List<Song> = emptyList()
```

**Where to add:** In the `initViews()` function.

Initialize search:

```kotlin
etSearch = findViewById(R.id.etSearch)
```

**Where to add:** In the `observeViewModel()` function, modify the songs observer.

Update songs observer:

```kotlin
viewModel.songs.observe(this) { songs ->
    allSongs = songs
    songAdapter.submitList(songs)
}
```

**Where to add:** Add as a new function in MainActivity.

Add search setup function:

```kotlin
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
```

**Where to add:** In `onCreate()`, after `setupRecyclerView()`.

Call the setup function:

```kotlin
setupSearch()
```

---

## 7. Better UI Styling

### Update `themes.xml`

**File:** `app/src/main/res/values/themes.xml`

Replace the entire file content:

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

### Update `SongAdapter.kt` (Rounded Album Art)

**Where to add:** At the top with other imports.

Add imports:

```kotlin
import coil.load
import coil.transform.RoundedCornersTransformation
```

**Where to add:** In the `bind()` function, replace the image loading code.

Replace:

```kotlin
song.albumArtUri?.let { uri ->
    binding.ivAlbumArt.load(uri) {
        crossfade(true)
    }
}
```

With:

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
