package com.blazinghotcode.blazingmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeThumbnailUtilsTest {

    @Test
    fun toPlaybackArtworkUrl_resizesGoogleThumbnail() {
        val input = "https://lh3.googleusercontent.com/abc=w544-h544-p-l90-rj"

        val actual = YouTubeThumbnailUtils.toPlaybackArtworkUrl(input, "videoId")

        assertEquals("https://lh3.googleusercontent.com/abc=w1200-h1200-p-l90-rj", actual)
    }

    @Test
    fun toPlaybackArtworkUrl_trimsAndFallsBackToVideoThumbnail() {
        val actual = YouTubeThumbnailUtils.toPlaybackArtworkUrl("   ", "abc123")

        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", actual)
    }

    @Test
    fun toPlaybackArtworkUrl_keepsUnknownUrlAsIs() {
        val input = "https://example.com/image.jpg"

        val actual = YouTubeThumbnailUtils.toPlaybackArtworkUrl(input, "abc")

        assertEquals(input, actual)
    }

    @Test
    fun toPlaybackArtworkUrl_withoutAnyData_returnsNull() {
        val actual = YouTubeThumbnailUtils.toPlaybackArtworkUrl(null, null)
        assertNull(actual)
    }
}

