package com.blazinghotcode.blazingmusic

import android.content.res.Resources

/**
 * Search filters modeled after Metrolist/InnerTube search params.
 */
enum class YouTubeSearchFilter(
    val displayName: String,
    val params: String?
) {
    ALL("All", null),
    SONGS("Songs", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    VIDEOS("Videos", "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"),
    ALBUMS("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
    ARTISTS("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
    PLAYLISTS("Playlists", "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D");

    companion object {
        fun fromDisplayName(displayName: String?): YouTubeSearchFilter {
            val normalized = displayName?.trim().orEmpty()
            return entries.firstOrNull { it.displayName.equals(normalized, ignoreCase = true) } ?: ALL
        }

        fun defaultFromResources(resources: Resources): YouTubeSearchFilter {
            return fromDisplayName(resources.getString(R.string.youtube_search_default_filter_display_name))
        }
    }
}
