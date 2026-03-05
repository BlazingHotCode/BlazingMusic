package com.blazinghotcode.blazingmusic

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
}
