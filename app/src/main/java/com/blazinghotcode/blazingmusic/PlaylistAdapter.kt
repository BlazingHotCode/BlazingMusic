package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
            val songsWord = if (playlist.songPaths.size == 1) "song" else "songs"
            binding.tvPlaylistCount.text = "${playlist.songPaths.size} $songsWord"
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
