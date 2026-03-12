package com.blazinghotcode.blazingmusic

import android.content.Context
import android.content.Intent

object YouTubeAccountSurfaces {
    const val HISTORY_BROWSE_ID = "FEmusic_history"
    const val PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
    const val ALBUMS_BROWSE_ID = "FEmusic_liked_albums"
    const val ARTISTS_BROWSE_ID = "FEmusic_library_corpus_artists"

    fun accountHubIntent(context: Context): Intent {
        return Intent(context, YouTubeAccountHubActivity::class.java)
    }
}
