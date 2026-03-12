package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation

class YouTubeSearchAdapter(
    private val onVideoClick: (YouTubeVideo) -> Unit,
    private val onItemMenuClick: (video: YouTubeVideo, anchor: View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<Row>()

    fun submitResults(items: List<YouTubeVideo>, grouped: Boolean) {
        rows.clear()
        rows += if (grouped) buildGroupedRows(items) else items.map { Row.Item(it) }
        notifyDataSetChanged()
    }

    fun clear() {
        rows.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_youtube_section_header, parent, false)
            )

            else -> ItemViewHolder(
                inflater.inflate(R.layout.item_youtube_video, parent, false),
                onVideoClick,
                onItemMenuClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row.title)
            is Row.Item -> (holder as ItemViewHolder).bind(row.video)
        }
    }

    private fun buildGroupedRows(items: List<YouTubeVideo>): List<Row> {
        if (items.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String, MutableList<YouTubeVideo>>()
        items.forEach { item ->
            val key = item.sectionTitle?.takeIf { it.isNotBlank() } ?: defaultSectionForType(item.type)
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }
        return buckets.entries
            .sortedBy { (title, groupItems) -> sectionRank(title, groupItems.firstOrNull()?.type) }
            .flatMap { (title, groupItems) ->
                buildList {
                    add(Row.Header(title))
                    groupItems.forEach { add(Row.Item(it)) }
                }
            }
    }

    private fun defaultSectionForType(type: YouTubeItemType): String = when (type) {
        YouTubeItemType.SONG -> "Songs"
        YouTubeItemType.VIDEO -> "Videos"
        YouTubeItemType.ARTIST -> "Artists"
        YouTubeItemType.ALBUM -> "Albums"
        YouTubeItemType.PLAYLIST -> "Playlists"
        YouTubeItemType.UNKNOWN -> "Results"
    }

    private fun sectionRank(title: String, type: YouTubeItemType?): Int {
        val normalized = title.lowercase()
        return when {
            normalized.contains("top result") -> 0
            normalized.contains("song") -> 1
            normalized.contains("video") -> 2
            normalized.contains("album") -> 3
            normalized.contains("artist") -> 4
            normalized.contains("playlist") -> 5
            type == YouTubeItemType.SONG -> 6
            type == YouTubeItemType.VIDEO -> 7
            type == YouTubeItemType.ALBUM -> 8
            type == YouTubeItemType.ARTIST -> 9
            type == YouTubeItemType.PLAYLIST -> 10
            else -> 11
        }
    }

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Item(val video: YouTubeVideo) : Row
    }

    private class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.tvSectionHeader)
        private val seeAll = itemView.findViewById<TextView>(R.id.tvSectionSeeAll)

        fun bind(value: String) {
            title.text = value
            seeAll.visibility = View.GONE
            seeAll.setOnClickListener(null)
        }
    }

    private class ItemViewHolder(
        itemView: View,
        private val onVideoClick: (YouTubeVideo) -> Unit,
        private val onItemMenuClick: (video: YouTubeVideo, anchor: View) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.tvVideoTitle)
        private val subtitle = itemView.findViewById<TextView>(R.id.tvChannelTitle)
        private val thumb = itemView.findViewById<ImageView>(R.id.ivVideoThumb)
        private val menu = itemView.findViewById<ImageButton>(R.id.btnItemMore)

        fun bind(video: YouTubeVideo) {
            title.text = video.title
            subtitle.text = "${typeLabel(video.type)} - ${video.channelTitle.ifBlank { "YouTube Music" }}"
            video.thumbnailUrl?.let { url ->
                thumb.load(url) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(14f))
                }
            } ?: thumb.setImageResource(R.drawable.ml_library_music)

            val showMenu = video.type == YouTubeItemType.SONG || video.type == YouTubeItemType.ALBUM
            menu.visibility = if (showMenu) View.VISIBLE else View.GONE
            menu.setOnClickListener(null)
            if (showMenu) {
                menu.setOnClickListener { onItemMenuClick(video, menu) }
            }
            itemView.setOnClickListener { onVideoClick(video) }
        }

        private fun typeLabel(type: YouTubeItemType): String = when (type) {
            YouTubeItemType.SONG -> "Song"
            YouTubeItemType.VIDEO -> "Video"
            YouTubeItemType.ARTIST -> "Artist"
            YouTubeItemType.ALBUM -> "Album"
            YouTubeItemType.PLAYLIST -> "Playlist"
            YouTubeItemType.UNKNOWN -> "Item"
        }
    }

    private companion object {
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_ITEM = 2
    }
}
