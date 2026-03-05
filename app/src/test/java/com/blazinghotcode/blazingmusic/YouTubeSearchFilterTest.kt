package com.blazinghotcode.blazingmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchFilterTest {

    @Test
    fun allFilter_hasNoParams() {
        assertNull(YouTubeSearchFilter.ALL.params)
    }

    @Test
    fun nonAllFilters_haveParams() {
        YouTubeSearchFilter.entries
            .filter { it != YouTubeSearchFilter.ALL }
            .forEach { filter ->
                assertNotNull("Missing params for ${filter.name}", filter.params)
                assertTrue("Blank params for ${filter.name}", !filter.params.isNullOrBlank())
            }
    }

    @Test
    fun displayNames_matchExpectedUiLabels() {
        val names = YouTubeSearchFilter.entries.associate { it to it.displayName }
        assertEquals("All", names[YouTubeSearchFilter.ALL])
        assertEquals("Songs", names[YouTubeSearchFilter.SONGS])
        assertEquals("Videos", names[YouTubeSearchFilter.VIDEOS])
        assertEquals("Albums", names[YouTubeSearchFilter.ALBUMS])
        assertEquals("Artists", names[YouTubeSearchFilter.ARTISTS])
        assertEquals("Playlists", names[YouTubeSearchFilter.PLAYLISTS])
    }
}

