package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.blazinghotcode.blazingmusic.databinding.ItemSongBinding

class SongAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onSongMenuClick: (Song, View) -> Unit
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSongClick(getItem(position))
                }
            }

            binding.btnSongMore.setOnClickListener { anchor ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSongMenuClick(getItem(position), anchor)
                }
            }
        }

        fun bind(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.tvDuration.text = formatDuration(song.duration)

            song.albumArtUri?.let { uri ->
                binding.ivAlbumArt.load(uri) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(16f))
                }
            } ?: run {
                binding.ivAlbumArt.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            return String.format("%d:%02d", minutes, seconds)
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}
