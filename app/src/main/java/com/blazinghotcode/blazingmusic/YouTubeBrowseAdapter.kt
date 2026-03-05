package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation

class YouTubeBrowseAdapter(
    private val onItemClick: (YouTubeVideo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<Row>()
    private var hideItemThumbnails = false

    fun submit(items: List<YouTubeVideo>) {
        rows.clear()
        rows.addAll(buildRows(items))
        notifyDataSetChanged()
    }

    fun setHideItemThumbnails(hide: Boolean) {
        if (hideItemThumbnails == hide) return
        hideItemThumbnails = hide
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Section -> VIEW_TYPE_SECTION
            is Row.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_SECTION) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_youtube_section_header, parent, false)
            SectionViewHolder(view)
        } else {
            val itemView = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_youtube_video, parent, false)
            ItemViewHolder(itemView, onItemClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionViewHolder).bind(row.title)
            is Row.Item -> (holder as ItemViewHolder).bind(row.item, hideItemThumbnails)
        }
    }

    private fun buildRows(items: List<YouTubeVideo>): List<Row> {
        if (items.isEmpty()) return emptyList()
        val sections = LinkedHashMap<String, MutableList<YouTubeVideo>>()
        items.forEach { item ->
            val key = item.sectionTitle?.takeIf { it.isNotBlank() } ?: defaultSectionForType(item.type)
            val bucket = sections.getOrPut(key) { mutableListOf() }
            bucket += item
        }

        val out = mutableListOf<Row>()
        sections.forEach { (title, sectionItems) ->
            out += Row.Section(title)
            sectionItems.forEach { out += Row.Item(it) }
        }
        return out
    }

    private fun defaultSectionForType(type: YouTubeItemType): String {
        return when (type) {
            YouTubeItemType.SONG -> "Songs"
            YouTubeItemType.VIDEO -> "Videos"
            YouTubeItemType.ARTIST -> "Artists"
            YouTubeItemType.ALBUM -> "Albums"
            YouTubeItemType.PLAYLIST -> "Playlists"
            YouTubeItemType.UNKNOWN -> "Results"
        }
    }

    private class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvSectionHeader)
        fun bind(value: String) {
            title.text = value
        }
    }

    private class ItemViewHolder(
        itemView: View,
        private val onItemClick: (YouTubeVideo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val root: ConstraintLayout = itemView as ConstraintLayout
        private val thumb: ImageView = itemView.findViewById(R.id.ivVideoThumb)
        private val title: TextView = itemView.findViewById(R.id.tvVideoTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.tvChannelTitle)

        fun bind(video: YouTubeVideo, hideThumbnail: Boolean) {
            applyThumbnailMode(hideThumbnail)
            title.text = video.title
            val typeLabel = when (video.type) {
                YouTubeItemType.SONG -> "Song"
                YouTubeItemType.VIDEO -> "Video"
                YouTubeItemType.ARTIST -> "Artist"
                YouTubeItemType.ALBUM -> "Album"
                YouTubeItemType.PLAYLIST -> "Playlist"
                YouTubeItemType.UNKNOWN -> "Item"
            }
            val sub = video.channelTitle.ifBlank { "YouTube Music" }
            subtitle.text = "$typeLabel • $sub"
            if (!hideThumbnail) {
                video.thumbnailUrl?.let { url ->
                    thumb.load(url) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(14f))
                    }
                } ?: thumb.setImageResource(R.drawable.ml_library_music)
            } else {
                thumb.setImageDrawable(null)
            }
            itemView.setOnClickListener { onItemClick(video) }
        }

        private fun applyThumbnailMode(hideThumbnail: Boolean) {
            thumb.visibility = if (hideThumbnail) View.GONE else View.VISIBLE

            val titleParams = title.layoutParams as ConstraintLayout.LayoutParams
            val subtitleParams = subtitle.layoutParams as ConstraintLayout.LayoutParams

            if (hideThumbnail) {
                titleParams.startToEnd = ConstraintLayout.LayoutParams.UNSET
                titleParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                titleParams.marginStart = 0

                subtitleParams.startToEnd = ConstraintLayout.LayoutParams.UNSET
                subtitleParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                subtitleParams.marginStart = 0
            } else {
                titleParams.startToStart = ConstraintLayout.LayoutParams.UNSET
                titleParams.startToEnd = R.id.ivVideoThumb
                titleParams.marginStart = 12

                subtitleParams.startToStart = ConstraintLayout.LayoutParams.UNSET
                subtitleParams.startToEnd = R.id.ivVideoThumb
                subtitleParams.marginStart = 12
            }

            title.layoutParams = titleParams
            subtitle.layoutParams = subtitleParams
            root.requestLayout()
        }
    }

    private sealed class Row {
        data class Section(val title: String) : Row()
        data class Item(val item: YouTubeVideo) : Row()
    }

    private companion object {
        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_ITEM = 1
    }
}
