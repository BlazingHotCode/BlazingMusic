# TODO

1. [x] Display songs from device storage.
2. [x] Play and pause selected songs.
3. [x] Show current "Now Playing" song info.
4. [x] Filter out WhatsApp audio/voice files from the library query.
5. [x] Add previous/next playback controls and wire them to ViewModel actions.
6. [x] Add seek bar with current time and total duration display.
7. [x] Add shuffle and repeat controls with visible mode state icons.
8. [x] Connect `MainActivity` to `MusicService` via `MediaController` lifecycle setup.
9. [x] Add search field and live filtering by song title/artist.
10. [x] Improve UI theme and album art presentation (rounded corners + fallback art).
11. [x] Add a playback queue screen/state: show the full list of songs queued and the exact order they will play in.
12. [x] Rework shuffle behavior: when enabled, randomize the full queue once and keep that fixed shuffled order while navigating next/previous; when disabled, restore the normal queue order.
13. [x] Build queue controls: drag to reorder, swipe to remove, and "play next."
14. [x] Persist queue + current index across app restarts.
15. [x] Add playlist creation and management (create, rename, delete playlists; add/remove songs).
16. [x] Add multi-select support for songs/playlists bulk actions.
17. [x] Add sort options: title, artist, album, duration, recently added.
18. [x] Add a clear "currently playing" indicator in the main song list.
19. [x] Open a full-screen player when tapping the mini-player.
20. [x] Add media notification player controls via `MediaStyle` notification actions: previous, play/pause, next, seek.
21. [x] Add lock-screen player metadata/controls driven by `MediaSession` metadata and playback state updates.
22. [x] Request and handle POST_NOTIFICATIONS permission on Android 13+.
23. [x] Debounce search input for smoother filtering on large libraries.
24. [x] Add fast-scrolling and alphabetical indexing in song list.
25. [x] Modernize the full UI/UX and visual design so the app looks polished and production-ready.
26. [x] Show empty/error states (no songs, permission denied, load failure).
27. [x] Replace deprecated adapterPosition usage in SongAdapter.
28. [x] Add developer-facing code documentation for architecture, playback flow, and key components.
29. [x] Add tests for MusicViewModel playback transitions (next/prev/repeat/shuffle).
30. [x] Add analytics/logging hooks for playback errors and skips.
31. [x] Add settings screen for default repeat/shuffle behavior and audio focus options.
32. [x] Add YouTube API integration to fetch/play songs from YouTube sources.
33. [x] Replace YouTube Music handoff with Metrolist-style in-app YouTube audio playback (no external app handoff).
34. [ ] Refactor duplicated UI/playback code into modular shared components (reduce near-identical implementations like song entry rows across Home/Playlist and similar feature variants with only minor differences).
35. [ ] Add Google account sign-in and sync for playlists, saved songs, and related library data.
36. [ ] Centralize YouTube search defaults (e.g., `songsOnly`) into one source of truth shared by Fragment, Activity, and XML.
