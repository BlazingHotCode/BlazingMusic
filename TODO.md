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
13. [ ] Build queue controls: drag to reorder, swipe to remove, and "play next."
14. [ ] Persist queue + current index across app restarts.
15. [ ] Add playlist creation and management (create, rename, delete playlists; add/remove songs).
16. [ ] Add sort options: title, artist, album, duration, recently added.
17. [ ] Add "Up Next" section in mini-player (next 3 songs).
18. [ ] Add media notification actions: previous, play/pause, next, seek.
19. [ ] Add lock-screen metadata/controls (MediaSession metadata updates).
20. [ ] Request and handle POST_NOTIFICATIONS permission on Android 13+.
21. [ ] Debounce search input for smoother filtering on large libraries.
22. [ ] Add fast-scrolling and alphabetical indexing in song list.
23. [ ] Show empty/error states (no songs, permission denied, load failure).
24. [ ] Replace deprecated adapterPosition usage in SongAdapter.
25. [ ] Add tests for MusicViewModel playback transitions (next/prev/repeat/shuffle).
26. [ ] Add analytics/logging hooks for playback errors and skips.
27. [ ] Add settings screen for default repeat/shuffle behavior and audio focus options.
28. [ ] Add YouTube API integration to fetch/play songs from YouTube sources.
29. [ ] Add Google account sign-in and sync for playlists, saved songs, and related library data.
