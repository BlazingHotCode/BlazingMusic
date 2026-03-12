# Changelog

All notable changes to this project will be documented in this file.

## [1.3.1] - 2026-03-13

### Changed

- Reworked YouTube playlist handling, including dedicated
  playlist completion for signed-in playlist surfaces and internal liked-music sync.
- Updated song overflow actions across player, playlist, browse, and search surfaces so `Add to
  playlist` appears more consistently.
- Changed playlist shuffle to use a dedicated one-shot shuffled queue instead of toggling the
  global shuffle state.

### Fixed

- Fixed YouTube playlist navigation so opening a playlist from the Playlists tab no longer routes
  back through the wrong tab stack.
- Improved internal `Liked Music` sync performance by avoiding eager stream resolution for every
  liked track during sync.
- Improved YouTube playlist metadata, row indicators, and playlist screen behavior.

## [1.3.0] - 2026-03-12

### Added

- Internal `Liked Music` playlist synced from YouTube likes, with Metrolist-style heart actions in
  player controls and song overflow menus.
- Signed-in YouTube playlist management improvements, including synced remove/reorder actions,
  playlist artwork loading, manual liked-music sync, and playlist metadata display.

### Changed

- Replaced the direct YouTube liked playlist surface with an internal app playlist that keeps
  YouTube order by default, supports local-only alternate sorting, and hides duplicate liked
  playlist entries from account browsing.
- Adjusted browse and player controls, including
  progressive queue building and more compact action buttons.

### Fixed

- Reduced queue stutter by switching to append-only queue updates with heavier startup buffering.
- Fixed YouTube playlist song menus, synced playlist editing behavior, liked-songs visibility,
  now-playing indicators for YouTube tracks, and playlist subtitle details for song count and total
  duration.

## [1.2.0] - 2026-03-05

### Added

- YouTube Music in-app browsing and playback flow, including dedicated browse pages, search filters,
  and songs-only ranking/toggle improvements.
- Endpoint-aware Mix/Radio generation with continuation paging, plus radio/up-next actions
  integrated across YouTube flows.
- New Search tab between Home and Library, playback defaults settings screen, and a protected local
  system playlist for device music.
- Full-screen player entry from mini-player, custom media notification controls/branding, and
  Android 13+ notification permission handling.
- UI and test infrastructure upgrades, including a dedicated `uitest` build type and expanded
  unit/UI test coverage.

### Changed

- Refined app styling and song list UX with fast-scroll thumb + alphabet index, marquee/full-title
  handling, and empty/error states.
- Improved queue behavior with persistent sort/order preferences, playlist song ordering,
  multi-select actions, and stronger queue/session restoration.
- Enhanced YouTube performance by parallelizing Mix/Radio resolution, caching streams, and
  progressively stitching radio queues.
- Upgraded Android/Gradle toolchain and cleaned AGP warning flags.

### Fixed

- Improved playback reliability by refreshing stale streams, recovering queue/player sync after
  idle, and stabilizing transition handling.
- Fixed UI polish issues such as title/duration overlap, list scroll resets on sort changes, and
  song artwork normalization.
- Fixed Android/Media3 compatibility warnings by aligning annotation usage and opting `MusicService`
  into unstable APIs where required.

## [1.1.0] - 2026-03-05

### Added in 1.1.0

- Playlist tab and playlist-song screens integrated into main app navigation flow.
- Playlist queue/session persistence improvements, including restored playback position on app
  relaunch.
- Styled secondary action button drawable for playlist actions (`Play All` / `Shuffle`) consistency.

### Changed

- Updated app layout toward a Spotify/Metrolist-style structure with dedicated Home and Playlists
  tabs.
- Reworked playlist management prompts (create/rename/delete) to use bottom-sheet style UI matching
  the app.
- Unified 3-dot overflow menu behavior/styling across songs and playlists.
- Improved header/title behavior and player control spacing for better small-screen rendering.
- Shuffle behavior now only randomizes upcoming songs (tracks after the current queue index).
- `Play All` and Home song tap now disable shuffle before starting playback.

### Fixed

- Fixed crashes when opening playlist and queue views.
- Fixed relaunch playback issue where pressing Play could no-op until Next/Previous was pressed.
- Fixed bottom-nav/tab indicator state sync when switching between Home and Playlists.
- Fixed tab/back navigation animation inconsistencies.
- Fixed Playlists header overlap where `Create` could render behind search.
- Fixed deprecated back-press handling by migrating to `OnBackPressedDispatcher`.
- Fixed crash when pressing `Create playlist` caused by dialog inflation/theme mismatch.

## [1.0.0] - 2026-03-02

### Added

- Display songs from device storage
- Play/pause music
- Previous/Next track controls
- Seekbar with time display
- Show now playing info (title, artist, album art)
- Filter out WhatsApp voice messages
- Dark theme UI
- Permission handling for Android 13+

---

*Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)*
