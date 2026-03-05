# BlazingMusic

BlazingMusic is a local Android music player built with Kotlin and Media3 (ExoPlayer), with playlist management, queue editing, full-screen player UI, and lock-screen/notification controls.

## Features

- Local library scan from `MediaStore` (WhatsApp voice/audio filtered out)
- Playback controls: play/pause, next/previous, seek, shuffle, repeat (off/all/one)
- Queue management: reorder, remove, play next, and persisted queue state
- Full-screen player opened from mini-player (with gesture interactions)
- Playlist support: create, rename, delete, per-playlist song view and sorting
- Multi-select actions for song/playlist bulk operations
- Search with debounce for smoother filtering
- Fast-scrolling + alphabetical index in song lists
- Now-playing row indicator in library and playlist views
- Media notification + lock-screen controls via `MediaSession`
- Playback restore on relaunch (queue/index/position)
- Empty/loading/error permission states for library UI
- Playback analytics/logging hooks for errors, skips, and transitions
- YouTube source integration (search via YouTube Data API + YouTube Music handoff, no video playback)

## Developer Documentation

- Architecture: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Playback flow: [`docs/PLAYBACK_FLOW.md`](docs/PLAYBACK_FLOW.md)
- Key components index: [`docs/KEY_COMPONENTS.md`](docs/KEY_COMPONENTS.md)

## Requirements

- Android Studio (Koala or newer recommended)
- Java 17
- Android SDK with build tools/platform for this project
- Android device/emulator (for instrumentation tests)

## Build

```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease
```

Debug APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

YouTube API key setup (for YouTube source search):

- Add to your Gradle properties (`~/.gradle/gradle.properties` or project `gradle.properties`):
  - `YOUTUBE_API_KEY=your_api_key_here`

## Test

```bash
# Unit tests (if present)
./gradlew testDebugUnitTest

# Espresso/instrumentation tests on connected device/emulator
./gradlew connectedDebugAndroidTest
```

## Debug Tools

- Playback analytics viewer (debug builds):
  - Long-press the Home title (`BLAZING MUSIC`) to open "Playback analytics (last 20)".
  - Shows recent skip/transition/error events without needing `adb logcat`.

## YouTube Sources

- Tap the YouTube button on Home header to open YouTube source search.
- Search uses YouTube Data API v3 (music category results).
- Tapping a result opens YouTube Music app.
- This integration intentionally does not support in-app or browser video playback.

## Permissions

- `READ_MEDIA_AUDIO` (Android 13+): read local audio files
- `READ_EXTERNAL_STORAGE` (Android 12 and below): read local audio files
- `POST_NOTIFICATIONS` (Android 13+): playback notification controls
- `FOREGROUND_SERVICE` / media playback service permissions: background playback

## CI

GitHub Actions workflow:

- `.github/workflows/build-debug.yml`
  - Builds debug APK on push to `main`
  - Runs Espresso tests on an Android emulator
  - Uploads APK and test report artifacts

## Project Layout

```text
app/src/main/java/com/blazinghotcode/blazingmusic/
  MainActivity.kt
  MusicViewModel.kt
  MusicService.kt
  MusicRepository.kt
  FullPlayerDialogFragment.kt
  PlaylistsFragment.kt
  PlaylistSongsFragment.kt
  SongAdapter.kt
  PlaylistAdapter.kt
  QueueEditorAdapter.kt
  ...
```

## License

MIT
