package com.blazinghotcode.blazingmusic

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.RoundedCornersTransformation

class YouTubeAccountHubActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_account_hub)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val ivAccountAvatar = findViewById<ImageView>(R.id.ivAccountAvatar)
        val tvAccountName = findViewById<TextView>(R.id.tvAccountName)
        val tvAccountSummary = findViewById<TextView>(R.id.tvAccountSummary)
        val btnHistory = findViewById<Button>(R.id.btnAccountHubHistory)
        val btnPlaylists = findViewById<Button>(R.id.btnAccountHubPlaylists)
        val btnAlbums = findViewById<Button>(R.id.btnAccountHubAlbums)
        val btnArtists = findViewById<Button>(R.id.btnAccountHubArtists)

        val account = YouTubeAccountStore.read(this)
        account.avatarUrl.takeIf { it.isNotBlank() }?.let { avatarUrl ->
            ivAccountAvatar.load(avatarUrl) {
                crossfade(true)
                transformations(RoundedCornersTransformation(999f))
            }
        } ?: ivAccountAvatar.setImageResource(R.drawable.ic_account)
        tvAccountName.text = account.accountName.ifBlank { "Your YouTube account" }
        tvAccountSummary.text = "Open native signed-in sections inspired by Metrolist."

        btnBack.setOnClickListener { finish() }
        btnHistory.setOnClickListener {
            startActivity(MainActivity.accountBrowseIntent(this, YouTubeAccountSurfaces.HISTORY_BROWSE_ID, "History", YouTubeItemType.SONG))
            finish()
        }
        btnPlaylists.setOnClickListener {
            startActivity(MainActivity.accountBrowseIntent(this, YouTubeAccountSurfaces.PLAYLISTS_BROWSE_ID, "Playlists", YouTubeItemType.PLAYLIST))
            finish()
        }
        btnAlbums.setOnClickListener {
            startActivity(MainActivity.accountBrowseIntent(this, YouTubeAccountSurfaces.ALBUMS_BROWSE_ID, "Albums", YouTubeItemType.ALBUM))
            finish()
        }
        btnArtists.setOnClickListener {
            startActivity(MainActivity.accountBrowseIntent(this, YouTubeAccountSurfaces.ARTISTS_BROWSE_ID, "Artists", YouTubeItemType.ARTIST))
            finish()
        }
    }
}
