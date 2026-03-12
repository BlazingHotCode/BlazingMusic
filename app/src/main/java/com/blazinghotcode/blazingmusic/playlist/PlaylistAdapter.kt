package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.blazinghotcode.blazingmusic.databinding.ItemPlaylistBinding

/** RecyclerView adapter for playlists on the Playlists tab. */
class PlaylistAdapter(
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onPlaylistMenuClick: (Playlist, View) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(
        private val binding: ItemPlaylistBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) {
                    onPlaylistClick(getItem(index))
                }
            }

            binding.btnPlaylistMore.setOnClickListener { anchor ->
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) {
                    onPlaylistMenuClick(getItem(index), anchor)
                }
            }
        }

        fun bind(playlist: Playlist) {
            binding.tvPlaylistName.text = playlist.name
            binding.tvPlaylistCount.text = when {
                playlist.isRemoteSystemPlaylist() -> "Signed-in YouTube"
                playlist.isYouTubeLikedSystemPlaylist() -> "Synced with YouTube likes"
                else -> {
                    val songsWord = if (playlist.songPaths.size == 1) "song" else "songs"
                    "${playlist.songPaths.size} $songsWord"
                }
            }

            val iconPadding = (12 * binding.root.resources.displayMetrics.density).toInt()
            binding.ivPlaylist.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            binding.ivPlaylist.load(null)
            if (playlist.isLocalMusicSystemPlaylist()) {
                binding.ivPlaylist.setImageResource(R.drawable.ml_library_music_filled)
                binding.ivPlaylist.imageTintList =
                    ContextCompat.getColorStateList(binding.root.context, R.color.accent_lavender)
                binding.ivPlaylist.contentDescription = "Local music playlist"
            } else if (playlist.isYouTubeLikedSystemPlaylist()) {
                binding.ivPlaylist.setImageResource(R.drawable.ic_account)
                binding.ivPlaylist.imageTintList =
                    ContextCompat.getColorStateList(binding.root.context, R.color.accent_lavender)
                binding.ivPlaylist.contentDescription = "Liked music playlist"
            } else if (playlist.isRemoteSystemPlaylist()) {
                binding.ivPlaylist.setImageResource(R.drawable.ic_account)
                binding.ivPlaylist.imageTintList =
                    ContextCompat.getColorStateList(binding.root.context, R.color.text_primary)
                binding.ivPlaylist.contentDescription = "YouTube account surface"
            } else {
                binding.ivPlaylist.setImageResource(R.drawable.ml_playlist_play)
                binding.ivPlaylist.imageTintList =
                    ContextCompat.getColorStateList(binding.root.context, R.color.accent_lavender)
                binding.ivPlaylist.contentDescription = "Playlist"
            }

            playlist.coverArtUri
                ?.takeIf { it.isNotBlank() }
                ?.let { coverArtUri ->
                    binding.ivPlaylist.imageTintList = null
                    binding.ivPlaylist.setPadding(0, 0, 0, 0)
                    binding.ivPlaylist.load(coverArtUri) {
                        crossfade(true)
                    }
                    binding.ivPlaylist.contentDescription = "Playlist artwork"
                }
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem == newItem
        }
    }
}
