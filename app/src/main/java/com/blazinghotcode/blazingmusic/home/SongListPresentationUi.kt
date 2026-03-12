package com.blazinghotcode.blazingmusic

object SongListPresentationUi {
    enum class SortMode {
        CUSTOM,
        TITLE,
        ARTIST,
        ALBUM,
        DURATION,
        RECENTLY_ADDED
    }

    fun applySearchAndSort(
        songs: List<Song>,
        query: String,
        sortMode: SortMode
    ): List<Song> {
        val normalized = query.trim().lowercase()
        val filtered = if (normalized.isEmpty()) {
            songs
        } else {
            songs.filter {
                it.title.lowercase().contains(normalized) ||
                    it.artist.lowercase().contains(normalized) ||
                    it.album.lowercase().contains(normalized)
            }
        }
        return when (sortMode) {
            SortMode.CUSTOM -> filtered
            SortMode.TITLE -> filtered.sortedWith(
                compareBy<Song> { it.title.lowercase() }.thenBy { it.artist.lowercase() }
            )
            SortMode.ARTIST -> filtered.sortedWith(
                compareBy<Song> { it.artist.lowercase() }.thenBy { it.title.lowercase() }
            )
            SortMode.ALBUM -> filtered.sortedWith(
                compareBy<Song> { it.album.lowercase() }.thenBy { it.title.lowercase() }
            )
            SortMode.DURATION -> filtered.sortedWith(
                compareByDescending<Song> { it.duration }.thenBy { it.title.lowercase() }
            )
            SortMode.RECENTLY_ADDED -> filtered.sortedWith(
                compareByDescending<Song> { it.dateAddedSeconds }.thenBy { it.title.lowercase() }
            )
        }
    }

    fun sectionLabel(song: Song, sortMode: SortMode): String {
        return when (sortMode) {
            SortMode.TITLE -> song.title
            SortMode.ARTIST -> song.artist
            SortMode.ALBUM -> song.album
            SortMode.CUSTOM,
            SortMode.DURATION,
            SortMode.RECENTLY_ADDED -> song.title
        }
    }

    fun supportsAlphabetIndex(sortMode: SortMode): Boolean {
        return when (sortMode) {
            SortMode.TITLE,
            SortMode.ARTIST,
            SortMode.ALBUM -> true
            SortMode.CUSTOM,
            SortMode.DURATION,
            SortMode.RECENTLY_ADDED -> false
        }
    }
}
