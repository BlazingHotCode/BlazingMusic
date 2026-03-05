package com.blazinghotcode.blazingmusic

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

object SongListIndexUi {
    fun updateAvailableSections(
        alphabetIndex: AlphabetIndexView,
        songs: List<Song>,
        isEnabled: Boolean,
        sectionForSong: (Song) -> Char
    ) {
        if (!isEnabled) {
            alphabetIndex.setAvailableSections(emptySet())
            return
        }
        alphabetIndex.setAvailableSections(
            songs.asSequence()
                .map(sectionForSong)
                .toSet()
        )
    }

    fun scrollToSection(
        recyclerView: RecyclerView,
        songs: List<Song>,
        section: Char,
        sectionForSong: (Song) -> Char
    ) {
        if (songs.isEmpty()) return
        val targetIndex = findSectionTargetIndex(songs, section, sectionForSong)
        if (targetIndex == -1) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        layoutManager.scrollToPositionWithOffset(targetIndex, 0)
    }

    fun scrollByThumbTouch(
        recyclerView: RecyclerView,
        scrollTrack: View,
        rawY: Float,
        onThumbMoved: () -> Unit
    ) {
        if (scrollTrack.height <= 0) return
        val location = IntArray(2)
        scrollTrack.getLocationOnScreen(location)
        val trackTop = location[1].toFloat()
        val fraction = ((rawY - trackTop) / scrollTrack.height.toFloat()).coerceIn(0f, 1f)
        val scrollRange = (recyclerView.computeVerticalScrollRange() - recyclerView.computeVerticalScrollExtent())
            .coerceAtLeast(0)
        val targetOffset = (scrollRange * fraction).toInt()
        val currentOffset = recyclerView.computeVerticalScrollOffset()
        recyclerView.scrollBy(0, targetOffset - currentOffset)
        onThumbMoved()
    }

    fun updateThumbPosition(
        recyclerView: RecyclerView,
        scrollTrack: View,
        scrollThumb: View
    ) {
        if (scrollThumb.visibility != View.VISIBLE || scrollTrack.height <= 0) return
        val scrollRange = (recyclerView.computeVerticalScrollRange() - recyclerView.computeVerticalScrollExtent())
            .coerceAtLeast(0)
        val fraction = if (scrollRange == 0) 0f else recyclerView.computeVerticalScrollOffset() / scrollRange.toFloat()
        val travel = (scrollTrack.height - scrollThumb.height).coerceAtLeast(0)
        scrollThumb.translationY = travel * fraction
    }

    fun sectionFromLabel(label: String): Char {
        val first = label.trim().firstOrNull()?.uppercaseChar() ?: return '#'
        return if (first in 'A'..'Z') first else '#'
    }

    private fun findSectionTargetIndex(
        songs: List<Song>,
        section: Char,
        sectionForSong: (Song) -> Char
    ): Int {
        val exactMatch = songs.indexOfFirst { sectionForSong(it) == section }
        if (exactMatch >= 0) return exactMatch
        if (section == '#') return 0
        val fallback = songs.indexOfFirst {
            val key = sectionForSong(it)
            key in 'A'..'Z' && key > section
        }
        return if (fallback >= 0) fallback else songs.lastIndex
    }
}
