# Key Components

This index maps the main Kotlin files to their responsibilities.

## App shell and screens

- `MainActivity.kt`
  - Activity shell, Home/Playlists navigation, mini-player, queue sheet, permission gating, and view binding.
- `PlaylistsFragment.kt`
  - Playlist list/search management and playlist CRUD dialogs.
- `PlaylistSongsFragment.kt`
  - Playlist detail list, per-playlist sort, multi-select actions, and playlist-scoped playback starts.
- `FullPlayerDialogFragment.kt`
  - Full-screen player, drag-to-dismiss behavior, and queue-sheet gesture entry points.

## Playback core

- `MusicViewModel.kt`
  - Central playback state machine (songs, queue, current track, repeat/shuffle, persistence, playlists).
- `SharedPlayer.kt`
  - Singleton `ExoPlayer` holder shared by UI + service.
- `MusicService.kt`
  - `MediaSessionService` for background playback and system integration.
- `BlazingMediaNotificationProvider.kt`
  - Media3 notification provider customizations (title/ticker).
- `PlaybackNotificationReceiver.kt`
  - Broadcast handler for notification transport/seek actions.
- `PlaybackNotificationManager.kt`
  - Legacy/custom notification builder with media-style actions and metadata updates.
- `PlaybackCommandBus.kt` / `PlaybackActionStore.kt`
  - In-process + persisted bridge for deferred playback action dispatch.

## Library and models

- `MusicRepository.kt`
  - `MediaStore` query and song mapping.
- `Song.kt`
  - Song model used in UI, queue, and persistence.
- `Playlist.kt`
  - Playlist model (id, name, ordered song paths).

## Lists and UI helpers

- `SongAdapter.kt`
  - Song row binding, now-playing highlight, row menus, and multi-select mode.
- `PlaylistAdapter.kt`
  - Playlist row binding and row menu handling.
- `QueueEditorAdapter.kt`
  - Queue editing list with reorder/remove/current-item visuals.
- `AlphabetIndexView.kt`
  - Vertical A-Z jump index for fast section navigation.

## Test coverage entry point

- `app/src/androidTest/java/com/blazinghotcode/blazingmusic/MainActivityEspressoTest.kt`
  - UI smoke/regression coverage for startup, tab switches, dialogs, and key interaction safety.
