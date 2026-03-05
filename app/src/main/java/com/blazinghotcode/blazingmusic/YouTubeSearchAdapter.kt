package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.blazinghotcode.blazingmusic.databinding.ItemYoutubeVideoBinding

class YouTubeSearchAdapter(
    private val onVideoClick: (YouTubeVideo) -> Unit,
    private val onItemMenuClick: (video: YouTubeVideo, anchor: android.view.View) -> Unit
) : ListAdapter<YouTubeVideo, YouTubeSearchAdapter.YouTubeVideoViewHolder>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): YouTubeVideoViewHolder {
        val binding = ItemYoutubeVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return YouTubeVideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: YouTubeVideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class YouTubeVideoViewHolder(
        private val binding: ItemYoutubeVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: YouTubeVideo) {
            binding.tvVideoTitle.text = video.title
            val typeLabel = when (video.type) {
                YouTubeItemType.SONG -> "Song"
                YouTubeItemType.VIDEO -> "Video"
                YouTubeItemType.ARTIST -> "Artist"
                YouTubeItemType.ALBUM -> "Album"
                YouTubeItemType.PLAYLIST -> "Playlist"
                YouTubeItemType.UNKNOWN -> "Item"
            }
            val subtitle = video.channelTitle.ifBlank { "YouTube Music" }
            binding.tvChannelTitle.text = "$typeLabel • $subtitle"
            video.thumbnailUrl?.let { url ->
                binding.ivVideoThumb.load(url) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(14f))
                }
            } ?: binding.ivVideoThumb.setImageResource(R.drawable.ml_library_music)
            val showMenu = video.type == YouTubeItemType.SONG || video.type == YouTubeItemType.ALBUM
            binding.btnItemMore.visibility = if (showMenu) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnItemMore.setOnClickListener(null)
            if (showMenu) {
                binding.btnItemMore.setOnClickListener { onItemMenuClick(video, binding.btnItemMore) }
            }
            binding.root.setOnClickListener { onVideoClick(video) }
        }
    }

    private class Diff : DiffUtil.ItemCallback<YouTubeVideo>() {
        override fun areItemsTheSame(oldItem: YouTubeVideo, newItem: YouTubeVideo): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: YouTubeVideo, newItem: YouTubeVideo): Boolean {
            return oldItem == newItem
        }
    }
}
