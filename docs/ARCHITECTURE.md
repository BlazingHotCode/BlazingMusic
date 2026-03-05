# Architecture

This project is a single-module Android app (`app`) built around a shared `MusicViewModel` and a process-wide shared `ExoPlayer` instance.

## High-level layout

- `UI layer`
  - `MainActivity` hosts Home + Playlists tabs, mini-player, queue sheet, and navigation.
  - `PlaylistsFragment` manages playlist list/search/create/rename/delete.
  - `PlaylistSongsFragment` manages playlist detail playback and sorting.
  - `FullPlayerDialogFragment` is the full-screen player UI.
  - `SongAdapter`, `PlaylistAdapter`, and `QueueEditorAdapter` render list content.
- `State + orchestration layer`
  - `MusicViewModel` is the central source of truth for songs, queue, playback state, repeat/shuffle, and playlists.
  - `SharedPlayer` owns the singleton `ExoPlayer`.
- `Data layer`
  - `MusicRepository` queries `MediaStore` for device audio.
  - `SharedPreferences` persists queue/index/position, sort preferences, playlist JSON, and permission flags.
- `Playback service + notifications`
  - `MusicService` exposes a `MediaSessionService` for background playback.
  - `BlazingMediaNotificationProvider` configures Media3 notification behavior.
  - `PlaybackNotificationReceiver` + `PlaybackNotificationManager` support custom action handling compatibility.

## State ownership

- `MusicViewModel` owns:
  - Full song library (`songs`).
  - Active queue + current index (`queue`, `currentQueueIndex`).
  - Playback state (`currentSong`, `isPlaying`, `currentPosition`, `duration`).
  - Modes (`isShuffleEnabled`, `repeatMode`).
  - Playlist model (`playlists`).
  - Library load state (`LibraryLoadState`).
- `MainActivity` and fragments only render state and send intent-like actions back to `MusicViewModel`.

## Key design choices

- Single shared player (`SharedPlayer`) avoids diverging playback state between activity and service.
- Queue persistence restores exact user context across relaunch (queue membership, index, and position).
- Playlist state is app-owned JSON (not external DB) for simplicity and easy migration later.
- Notification/media controls are mediated by `MediaSession` to support lock screen and system transport controls.

## Navigation model

- Home and Playlists are activity-level sections (tab-like UI).
- Playlist detail is a fragment pushed into `playlistContainer`.
- Full player is a `DialogFragment` over current screen and can open queue sheet interactions.
