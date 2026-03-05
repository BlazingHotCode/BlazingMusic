package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation

class YouTubeBrowseAdapter(
    private val onItemClick: (YouTubeVideo) -> Unit,
    private val onPlayAllClick: (Boolean) -> Unit,
    private val onArtistOptionsClick: () -> Unit,
    private val onSectionSeeAllClick: (sectionTitle: String, browseId: String, browseParams: String?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class HeaderModel(
        val browseType: YouTubeItemType,
        val title: String,
        val subtitle: String,
        val artworkUrl: String?,
        val stateMessage: String,
        val artistInfo: YouTubeArtistInfo?,
        val showArtistDescription: Boolean,
        val showArtistSubscribers: Boolean,
        val showArtistMonthlyListeners: Boolean,
        val canPlay: Boolean,
        val canShuffle: Boolean
    )

    private val rows = mutableListOf<Row>()
    private var hideItemThumbnails = false
    private var headerModel: HeaderModel? = null

    fun setHeader(model: HeaderModel) {
        headerModel = model
        notifyDataSetChanged()
    }

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

    override fun getItemCount(): Int = rows.size + if (headerModel != null) 1 else 0

    override fun getItemViewType(position: Int): Int {
        if (headerModel != null && position == 0) return VIEW_TYPE_HEADER
        val row = rows[rowIndex(position)]
        return when (row) {
            is Row.Section -> VIEW_TYPE_SECTION
            is Row.Item -> VIEW_TYPE_ITEM
            is Row.HorizontalItems -> VIEW_TYPE_HORIZONTAL
        }
    }

    private fun rowIndex(adapterPosition: Int): Int {
        return if (headerModel != null) adapterPosition - 1 else adapterPosition
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_youtube_browse_page_header, parent, false),
                onPlayAllClick,
                onArtistOptionsClick
            )
            VIEW_TYPE_SECTION -> SectionViewHolder(
                inflater.inflate(R.layout.item_youtube_section_header, parent, false),
                onSectionSeeAllClick
            )
            VIEW_TYPE_HORIZONTAL -> HorizontalSectionViewHolder(
                inflater.inflate(R.layout.item_youtube_horizontal_section, parent, false),
                onItemClick
            )
            else -> ItemViewHolder(inflater.inflate(R.layout.item_youtube_video, parent, false), onItemClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (headerModel != null && position == 0) {
            (holder as HeaderViewHolder).bind(headerModel!!)
            return
        }

        when (val row = rows[rowIndex(position)]) {
            is Row.Section -> (holder as SectionViewHolder).bind(row)
            is Row.Item -> (holder as ItemViewHolder).bind(row.item, hideItemThumbnails)
            is Row.HorizontalItems -> (holder as HorizontalSectionViewHolder).bind(row.items)
        }
    }

    private fun buildRows(items: List<YouTubeVideo>): List<Row> {
        if (items.isEmpty()) return emptyList()
        val sections = LinkedHashMap<String, SectionBucket>()
        items.forEach { item ->
            val key = item.sectionTitle?.takeIf { it.isNotBlank() } ?: defaultSectionForType(item.type)
            val bucket = sections.getOrPut(key) { SectionBucket() }
            bucket.items.add(item)
            if (bucket.browseId.isNullOrBlank() && !item.sectionBrowseId.isNullOrBlank()) {
                bucket.browseId = item.sectionBrowseId
                bucket.browseParams = item.sectionBrowseParams
            }
        }

        val sortedSections = sections.entries.sortedBy { (title, sectionBucket) ->
            sectionRank(title, sectionBucket.items)
        }

        val out = mutableListOf<Row>()
        sortedSections.forEach { (title, sectionBucket) ->
            val sectionItems = sectionBucket.items
            out += Row.Section(
                title = title,
                browseId = sectionBucket.browseId,
                browseParams = sectionBucket.browseParams,
                showSeeAll = shouldShowSeeAllButton(title, sectionBucket.browseId)
            )
            if (shouldRenderHorizontal(title, sectionItems)) {
                out += Row.HorizontalItems(sectionItems)
            } else {
                sectionItems.forEach { out += Row.Item(it) }
            }
        }
        return out
    }

    private fun shouldShowSeeAllButton(title: String, browseId: String?): Boolean {
        if (browseId.isNullOrBlank()) return false
        val normalized = title.lowercase()
        return normalized.contains("album") || normalized.contains("single") || normalized.contains("ep")
    }

    private fun sectionRank(title: String, items: List<YouTubeVideo>): Int {
        val normalized = title.lowercase()
        return when {
            normalized.contains("top songs") -> 0
            normalized == "songs" || normalized.contains("popular") -> 1
            normalized.contains("album") -> 2
            normalized.contains("single") || normalized.contains("ep") -> 3
            normalized.contains("video") -> 4
            normalized.contains("playlist") -> 5
            normalized.contains("fans") || normalized.contains("similar") || normalized.contains("related") -> 6
            items.firstOrNull()?.type == YouTubeItemType.SONG -> 7
            items.firstOrNull()?.type == YouTubeItemType.ALBUM -> 8
            items.firstOrNull()?.type == YouTubeItemType.PLAYLIST -> 9
            else -> 10
        }
    }

    private fun shouldRenderHorizontal(title: String, items: List<YouTubeVideo>): Boolean {
        if (items.isEmpty()) return false
        val normalizedTitle = title.lowercase()
        if (
            normalizedTitle.contains("album") ||
            normalizedTitle.contains("single") ||
            normalizedTitle.contains("ep") ||
            normalizedTitle.contains("playlist")
        ) return true
        val firstType = items.first().type
        return firstType == YouTubeItemType.ALBUM || firstType == YouTubeItemType.PLAYLIST || firstType == YouTubeItemType.ARTIST
    }

    private fun defaultSectionForType(type: YouTubeItemType): String = when (type) {
        YouTubeItemType.SONG -> "Songs"
        YouTubeItemType.VIDEO -> "Videos"
        YouTubeItemType.ARTIST -> "Artists"
        YouTubeItemType.ALBUM -> "Albums"
        YouTubeItemType.PLAYLIST -> "Playlists"
        YouTubeItemType.UNKNOWN -> "Results"
    }

    private class HeaderViewHolder(
        itemView: View,
        private val onPlayAllClick: (Boolean) -> Unit,
        private val onArtistOptionsClick: () -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val browseHeaderContainer: ConstraintLayout = itemView.findViewById(R.id.browseHeaderContainer)
        private val ivArtistHeroArt: ImageView = itemView.findViewById(R.id.ivArtistHeroArt)
        private val artistHeroScrim: View = itemView.findViewById(R.id.artistHeroScrim)
        private val tvArtistHeroTitle: TextView = itemView.findViewById(R.id.tvArtistHeroTitle)
        private val tvArtistHeroSubtitle: TextView = itemView.findViewById(R.id.tvArtistHeroSubtitle)
        private val artistActionRow: LinearLayout = itemView.findViewById(R.id.artistActionRow)
        private val btnArtistRadio: Button = itemView.findViewById(R.id.btnArtistRadio)
        private val btnArtistShuffle: Button = itemView.findViewById(R.id.btnArtistShuffle)
        private val btnArtistPageOptions: ImageButton = itemView.findViewById(R.id.btnArtistPageOptions)

        private val ivBrowseHeaderArt: ImageView = itemView.findViewById(R.id.ivBrowseHeaderArt)
        private val tvBrowseHeaderTitle: TextView = itemView.findViewById(R.id.tvBrowseHeaderTitle)
        private val tvBrowseHeaderSubtitle: TextView = itemView.findViewById(R.id.tvBrowseHeaderSubtitle)
        private val browseActionRow: LinearLayout = itemView.findViewById(R.id.browseActionRow)
        private val btnPlayAll: Button = itemView.findViewById(R.id.btnPlayAll)
        private val btnShuffleAll: Button = itemView.findViewById(R.id.btnShuffleAll)

        private val aboutContainer: View = itemView.findViewById(R.id.artistAboutContainer)
        private val tvArtistSubscriberCount: TextView = itemView.findViewById(R.id.tvArtistSubscriberCount)
        private val tvArtistMonthlyListeners: TextView = itemView.findViewById(R.id.tvArtistMonthlyListeners)
        private val tvArtistDescription: TextView = itemView.findViewById(R.id.tvArtistDescription)
        private val tvState: TextView = itemView.findViewById(R.id.tvState)

        fun bind(model: HeaderModel) {
            val isArtist = model.browseType == YouTubeItemType.ARTIST
            val context = itemView.context
            val params = browseHeaderContainer.layoutParams as ViewGroup.MarginLayoutParams
            if (isArtist) {
                params.marginStart = 0
                params.marginEnd = 0
                params.topMargin = 0
                browseHeaderContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                browseHeaderContainer.setPadding(0, 0, 0, 0)
            } else {
                val horizontal = context.resources.displayMetrics.density.times(14).toInt()
                val top = context.resources.displayMetrics.density.times(12).toInt()
                val inner = context.resources.displayMetrics.density.times(12).toInt()
                params.marginStart = horizontal
                params.marginEnd = horizontal
                params.topMargin = top
                browseHeaderContainer.background = ContextCompat.getDrawable(context, R.drawable.bg_song_item)
                browseHeaderContainer.setPadding(inner, inner, inner, inner)
            }
            browseHeaderContainer.layoutParams = params

            ivArtistHeroArt.visibility = if (isArtist) View.VISIBLE else View.GONE
            artistHeroScrim.visibility = if (isArtist) View.VISIBLE else View.GONE
            tvArtistHeroTitle.visibility = if (isArtist) View.VISIBLE else View.GONE
            tvArtistHeroSubtitle.visibility = if (isArtist) View.VISIBLE else View.GONE
            artistActionRow.visibility = if (isArtist) View.VISIBLE else View.GONE

            ivBrowseHeaderArt.visibility = if (isArtist) View.GONE else View.VISIBLE
            tvBrowseHeaderTitle.visibility = if (isArtist) View.GONE else View.VISIBLE
            tvBrowseHeaderSubtitle.visibility = if (isArtist) View.GONE else View.VISIBLE
            browseActionRow.visibility = if (isArtist) View.GONE else View.VISIBLE

            tvBrowseHeaderTitle.text = model.title
            tvBrowseHeaderSubtitle.text = model.subtitle
            tvArtistHeroTitle.text = model.title
            tvArtistHeroSubtitle.text = model.subtitle
            tvState.text = model.stateMessage

            model.artworkUrl?.let { url ->
                ivBrowseHeaderArt.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.ml_library_music)
                    error(R.drawable.ml_library_music)
                    transformations(RoundedCornersTransformation(16f))
                }
                ivArtistHeroArt.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.ml_library_music)
                    error(R.drawable.ml_library_music)
                    transformations(RoundedCornersTransformation(20f))
                }
            } ?: run {
                ivBrowseHeaderArt.setImageResource(R.drawable.ml_library_music)
                ivArtistHeroArt.setImageResource(R.drawable.ml_library_music)
            }

            btnPlayAll.isEnabled = model.canPlay
            btnShuffleAll.isEnabled = model.canShuffle
            btnArtistRadio.isEnabled = model.canPlay
            btnArtistShuffle.isEnabled = model.canShuffle

            btnPlayAll.setOnClickListener { onPlayAllClick(false) }
            btnShuffleAll.setOnClickListener { onPlayAllClick(true) }
            btnArtistRadio.setOnClickListener { onPlayAllClick(false) }
            btnArtistShuffle.setOnClickListener { onPlayAllClick(true) }
            btnArtistPageOptions.setOnClickListener { onArtistOptionsClick() }

            val description = model.artistInfo?.description?.takeIf { it.isNotBlank() }
            val subscribers = model.artistInfo?.subscriberCount?.takeIf { it.isNotBlank() }
            val monthly = model.artistInfo?.monthlyListeners?.takeIf { it.isNotBlank() }

            tvArtistDescription.visibility = if (isArtist && model.showArtistDescription && !description.isNullOrBlank()) View.VISIBLE else View.GONE
            tvArtistDescription.text = description.orEmpty()
            tvArtistSubscriberCount.visibility = if (isArtist && model.showArtistSubscribers && !subscribers.isNullOrBlank()) View.VISIBLE else View.GONE
            tvArtistSubscriberCount.text = subscribers.orEmpty()
            tvArtistMonthlyListeners.visibility = if (isArtist && model.showArtistMonthlyListeners && !monthly.isNullOrBlank()) View.VISIBLE else View.GONE
            tvArtistMonthlyListeners.text = monthly.orEmpty()

            val hasAbout = isArtist && (
                tvArtistDescription.visibility == View.VISIBLE ||
                    tvArtistSubscriberCount.visibility == View.VISIBLE ||
                    tvArtistMonthlyListeners.visibility == View.VISIBLE
                )
            aboutContainer.visibility = if (hasAbout) View.VISIBLE else View.GONE
        }
    }

    private class SectionViewHolder(
        itemView: View,
        private val onSectionSeeAllClick: (sectionTitle: String, browseId: String, browseParams: String?) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvSectionHeader)
        private val btnSeeAll: TextView = itemView.findViewById(R.id.tvSectionSeeAll)

        fun bind(section: Row.Section) {
            title.text = section.title
            val show = section.showSeeAll && !section.browseId.isNullOrBlank()
            btnSeeAll.visibility = if (show) View.VISIBLE else View.GONE
            btnSeeAll.setOnClickListener(null)
            if (show) {
                btnSeeAll.setOnClickListener {
                    onSectionSeeAllClick(section.title, section.browseId!!, section.browseParams)
                }
            }
        }
    }

    private class HorizontalSectionViewHolder(
        itemView: View,
        private val onItemClick: (YouTubeVideo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val rv: RecyclerView = itemView.findViewById(R.id.rvHorizontalItems)
        private val horizontalAdapter = HorizontalItemsAdapter(onItemClick)

        init {
            rv.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = horizontalAdapter
        }

        fun bind(items: List<YouTubeVideo>) {
            horizontalAdapter.submit(items)
        }
    }

    private class HorizontalItemsAdapter(
        private val onItemClick: (YouTubeVideo) -> Unit
    ) : RecyclerView.Adapter<HorizontalItemsAdapter.HorizontalItemViewHolder>() {
        private val items = mutableListOf<YouTubeVideo>()

        fun submit(newItems: List<YouTubeVideo>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HorizontalItemViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_youtube_video_horizontal, parent, false)
            return HorizontalItemViewHolder(view, onItemClick)
        }

        override fun onBindViewHolder(holder: HorizontalItemViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class HorizontalItemViewHolder(
            itemView: View,
            private val onItemClick: (YouTubeVideo) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val thumb: ImageView = itemView.findViewById(R.id.ivVideoThumb)
            private val title: TextView = itemView.findViewById(R.id.tvVideoTitle)
            private val subtitle: TextView = itemView.findViewById(R.id.tvChannelTitle)

            fun bind(video: YouTubeVideo) {
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
                video.thumbnailUrl?.let { url ->
                    thumb.load(url) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(12f))
                    }
                } ?: thumb.setImageResource(R.drawable.ml_library_music)
                itemView.setOnClickListener { onItemClick(video) }
            }
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

    private data class SectionBucket(
        val items: MutableList<YouTubeVideo> = mutableListOf(),
        var browseId: String? = null,
        var browseParams: String? = null
    )

    private sealed class Row {
        data class Section(
            val title: String,
            val browseId: String?,
            val browseParams: String?,
            val showSeeAll: Boolean
        ) : Row()
        data class Item(val item: YouTubeVideo) : Row()
        data class HorizontalItems(val items: List<YouTubeVideo>) : Row()
    }

    private companion object {
        private const val VIEW_TYPE_HEADER = -1
        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_HORIZONTAL = 2
    }
}
