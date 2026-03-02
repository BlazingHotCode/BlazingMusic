# BlazingMusic

A simple Android music player app built with Kotlin and Media3 (ExoPlayer).

## Features

- Display songs from device storage
- Play/pause music
- Show now playing info (title, artist, album art)
- Filter out WhatsApp voice messages
- Dark theme UI

## Screenshots

The app features a dark-themed UI with:

- Song list with album art
- Mini player at the bottom
- Material Design 3 styling

## Building the Project

### Prerequisites

- Android Studio (Koala or newer)
- Android SDK (API 24+)
- Java 17

### Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean and rebuild
./gradlew clean assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`

## Permissions Required

- `READ_MEDIA_AUDIO` - Access music files (Android 13+)
- `READ_EXTERNAL_STORAGE` - Access music files (Android 12 and below)
- `FOREGROUND_SERVICE` - Background playback
- `POST_NOTIFICATIONS` - Media notifications (Android 13+)

## Project Structure

```text
BlazingMusic/
├── app/
│   ├── src/main/
│   │   ├── java/com/blazinghotcode/blazingmusic/
│   │   │   ├── MainActivity.kt
│   │   │   ├── Song.kt
│   │   │   ├── MusicRepository.kt
│   │   │   ├── MusicViewModel.kt
│   │   │   ├── MusicService.kt
│   │   │   └── SongAdapter.kt
│   │   └── res/
│   │       ├── layout/
│   │       └── values/
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

---

## License

MIT License - Built with Kotlin & Jetpack
