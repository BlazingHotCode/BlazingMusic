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
    private val onVideoClick: (YouTubeVideo) -> Unit
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
            binding.tvChannelTitle.text = video.channelTitle
            video.thumbnailUrl?.let { url ->
                binding.ivVideoThumb.load(url) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(14f))
                }
            } ?: binding.ivVideoThumb.setImageResource(R.drawable.ml_library_music)
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

