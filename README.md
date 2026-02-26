# BlazingMusic

An Android music player app built with Kotlin and Media3 (ExoPlayer).

## Table of Contents

- [Prerequisites](#prerequisites)
- [Building the Project](#building-the-project)
  - [Using Android Studio](#using-android-studio)
  - [Using Command Line](#using-command-line)
- [Running on Device/Emulator](#running-on-deviceemulator)
- [Permissions Required](#permissions-required)
- [Troubleshooting](#troubleshooting)
- [Project Structure](#project-structure)
- [Next Steps](#next-steps)

## Prerequisites

- Android Studio (Koala or newer)
- Android SDK (API 34)
- Gradle (included via wrapper)
- Kotlin knowledge basics
- Android device/emulator for testing

## Building the Project

### Using Android Studio

1. Open the project in Android Studio:
   ```
   File → Open → Select BlazingMusic folder
   ```
2. Wait for Gradle sync to complete
3. Build the project:
   - **Debug build**: `Build → Make Project` (Ctrl+F9)
   - Or run directly on a device/emulator with `Run → Run 'app'` (Shift+F10)

### Using Command Line

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean and rebuild
./gradlew clean assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`

## Running on Device/Emulator

1. Connect your Android device or start an emulator
2. In Android Studio, select your device from the run configuration dropdown
3. Press `Shift+F10` or click the Run button

## Permissions Required

The app requests the following permissions:
- `READ_MEDIA_AUDIO` - Access music files (Android 13+)
- `READ_EXTERNAL_STORAGE` - Access music files (Android 12 and below)
- `FOREGROUND_SERVICE` - Background playback
- `POST_NOTIFICATIONS` - Media notifications (Android 13+)

## Troubleshooting

| Issue | Solution |
|-------|----------|
| No songs found | Ensure device has music files; grant permission |
| App crashes | Check Logcat for errors; verify layouts |
| Permission denied | Settings → Apps → BlazingMusic → Permissions |
| Build fails | Check Gradle sync; verify Android Studio version |

## Project Structure

```
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

## Next Steps

See [GUIDE.md](./GUIDE.md) for a complete development guide with detailed implementation steps.
