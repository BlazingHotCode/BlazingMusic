# Changelog

All notable changes to this project will be documented in this file.

## [1.2.0] - 2026-03-05

### Added

- YouTube Music in-app browsing and playback flow, including dedicated browse pages, search filters, and songs-only ranking/toggle improvements.
- Endpoint-aware Mix/Radio generation with continuation paging, plus radio/up-next actions integrated across YouTube flows.
- New Search tab between Home and Library, playback defaults settings screen, and a protected local system playlist for device music.
- Full-screen player entry from mini-player, custom media notification controls/branding, and Android 13+ notification permission handling.
- UI and test infrastructure upgrades, including a dedicated `uitest` build type and expanded unit/UI test coverage.

### Changed

- Refined app styling and song list UX with fast-scroll thumb + alphabet index, marquee/full-title handling, and empty/error states.
- Improved queue behavior with persistent sort/order preferences, playlist song ordering, multi-select actions, and stronger queue/session restoration.
- Enhanced YouTube performance by parallelizing Mix/Radio resolution, caching streams, and progressively stitching radio queues.
- Upgraded Android/Gradle toolchain and cleaned AGP warning flags.

### Fixed

- Improved playback reliability by refreshing stale streams, recovering queue/player sync after idle, and stabilizing transition handling.
- Fixed UI polish issues such as title/duration overlap, list scroll resets on sort changes, and song artwork normalization.
- Fixed Android/Media3 compatibility warnings by aligning annotation usage and opting `MusicService` into unstable APIs where required.

## [1.1.0] - 2026-03-05

### Added in 1.1.0

- Playlist tab and playlist-song screens integrated into main app navigation flow.
- Playlist queue/session persistence improvements, including restored playback position on app relaunch.
- Styled secondary action button drawable for playlist actions (`Play All` / `Shuffle`) consistency.

### Changed

- Updated app layout toward a Spotify/Metrolist-style structure with dedicated Home and Playlists tabs.
- Reworked playlist management prompts (create/rename/delete) to use bottom-sheet style UI matching the app.
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
