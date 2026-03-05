# Playback Flow

This document describes the runtime playback path from app launch to notification/lock-screen controls.

## 1. Startup and restore

1. `MainActivity` creates `MusicViewModel`.
2. `MusicViewModel.initializePlayer()`:
   - Starts `MusicService`.
   - Gets shared `ExoPlayer` from `SharedPlayer`.
   - Attaches `Player.Listener` callbacks.
3. After permissions, `loadSongs()`:
   - Queries songs through `MusicRepository`.
   - Restores queue/index/position from preferences if valid.
   - Primes player with restored song (`setMediaItem + prepare + seekTo`) without autoplay.

## 2. User starts playback

1. User taps a song (Home or Playlist detail).
2. UI sends song + current visible list ordering to `viewModel.playSongFromQueue(...)`.
3. `MusicViewModel`:
   - Sets `normalQueue`.
   - Builds `activeQueue` (shuffled only when mode is on).
   - Resolves index and persists queue/index/position.
   - Calls `startPlayback()` (`setMediaItems + prepare + play`).

## 3. During playback

- Player callbacks update LiveData:
  - `onIsPlayingChanged` -> `isPlaying`.
  - `onPlaybackStateChanged` -> `duration`, end-of-track handling.
  - `onMediaItemTransition` -> `currentSong`, `currentQueueIndex`, persistence.
- UI observers (activity/fragments/full player) re-render controls and metadata.
- `MainActivity` periodically updates mini-player seek/time UI.

## 4. Queue, shuffle, repeat behavior

- Queue is explicit and persisted.
- Shuffle affects upcoming order in the queue model, not just transient player state.
- Repeat mode is app-managed and synchronized to playback transitions.
- Previous behavior:
  - If current position is above restart threshold, previous seeks to song start.
  - Otherwise it navigates queue based on repeat/shuffle rules.

## 5. Background playback and notifications

1. `MusicService` hosts `MediaSessionService` with the shared player.
2. `BlazingMediaNotificationProvider` provides Media3 notification content.
3. Session activity intent points to `MainActivity`, so tapping notification opens app.
4. Notification actions route through media session/player and keep app/service states aligned.

## 6. Persistence checkpoints

- Queue/index/position are persisted on:
  - Track changes.
  - Play/pause and seek updates.
  - Lifecycle snapshots (`onPause` / service-driven updates).
- Playlist definitions and per-playlist ordering/sort preferences are persisted separately.
