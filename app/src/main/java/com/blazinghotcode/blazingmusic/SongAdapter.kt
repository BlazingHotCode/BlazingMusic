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

/** RecyclerView adapter for songs with now-playing and multi-select states. */
class SongAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onSongMenuClick: (Song, View) -> Unit,
    private val onSelectionStateChanged: (isSelectionMode: Boolean, selectedCount: Int) -> Unit = { _, _ -> }
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {
    private var isSelectionModeEnabled = false
    private val selectedSongPaths = linkedSetOf<String>()
    private var currentSongPath: String? = null

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

    override fun onCurrentListChanged(previousList: MutableList<Song>, currentList: MutableList<Song>) {
        super.onCurrentListChanged(previousList, currentList)
        val validPaths = currentList.map { it.path }.toSet()
        selectedSongPaths.retainAll(validPaths)
        publishSelectionState()
    }

    fun isSelectionMode(): Boolean = isSelectionModeEnabled

    fun getSelectedSongs(): List<Song> = currentList.filter { it.path in selectedSongPaths }

    fun setCurrentSong(song: Song?) {
        val newPath = song?.path
        if (currentSongPath == newPath) return
        currentSongPath = newPath
        notifyDataSetChanged()
    }

    fun enterSelectionMode(initialSong: Song? = null) {
        if (!isSelectionModeEnabled) {
            isSelectionModeEnabled = true
        }
        initialSong?.let { selectedSongPaths.add(it.path) }
        notifyDataSetChanged()
        publishSelectionState()
    }

    fun exitSelectionMode() {
        if (!isSelectionModeEnabled && selectedSongPaths.isEmpty()) return
        isSelectionModeEnabled = false
        selectedSongPaths.clear()
        notifyDataSetChanged()
        publishSelectionState()
    }

    private fun toggleSelection(song: Song) {
        if (song.path in selectedSongPaths) {
            selectedSongPaths.remove(song.path)
        } else {
            selectedSongPaths.add(song.path)
        }
        notifyDataSetChanged()
        publishSelectionState()
    }

    private fun publishSelectionState() {
        onSelectionStateChanged(isSelectionModeEnabled, selectedSongPaths.size)
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private var boundSong: Song? = null

        init {
            binding.root.setOnClickListener {
                val song = boundSong ?: return@setOnClickListener
                if (isSelectionModeEnabled) {
                    toggleSelection(song)
                } else {
                    onSongClick(song)
                }
            }

            binding.btnSongMore.setOnClickListener { anchor ->
                val song = boundSong ?: return@setOnClickListener
                onSongMenuClick(song, anchor)
            }
        }

        fun bind(song: Song) {
            boundSong = song
            val isNowPlaying = song.path == currentSongPath
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.tvDuration.text = PlaybackTimeFormatter.formatDuration(song.duration)
            binding.tvNowPlaying.visibility = if (isNowPlaying) View.VISIBLE else View.GONE
            binding.root.setBackgroundResource(
                if (isNowPlaying) R.drawable.bg_song_item_now_playing else R.drawable.bg_song_item
            )
            val inSelectionMode = isSelectionModeEnabled
            animateSelectionModeViews(inSelectionMode)

            if (inSelectionMode) {
                val shouldBeChecked = song.path in selectedSongPaths
                val wasChecked = binding.cbSelectSong.isSelected
                binding.cbSelectSong.isSelected = shouldBeChecked
                if (shouldBeChecked && !wasChecked) {
                    binding.cbSelectSong.scaleX = 0.86f
                    binding.cbSelectSong.scaleY = 0.86f
                    binding.cbSelectSong.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140L)
                        .start()
                }
            } else {
                song.albumArtUri?.let { uri ->
                    binding.ivAlbumArt.load(uri) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(16f))
                    }
                } ?: run {
                    binding.ivAlbumArt.setImageResource(R.drawable.ml_library_music)
                }
            }
        }

        private fun animateSelectionModeViews(inSelectionMode: Boolean) {
            if (inSelectionMode) {
                if (binding.ivAlbumArt.visibility == View.VISIBLE) {
                    binding.ivAlbumArt.animate()
                        .alpha(0f)
                        .setDuration(120L)
                        .withEndAction {
                            binding.ivAlbumArt.visibility = View.INVISIBLE
                            binding.ivAlbumArt.alpha = 1f
                        }
                        .start()
                }
                if (binding.cbSelectSong.visibility != View.VISIBLE) {
                    binding.cbSelectSong.alpha = 0f
                    binding.cbSelectSong.scaleX = 0.86f
                    binding.cbSelectSong.scaleY = 0.86f
                    binding.cbSelectSong.visibility = View.VISIBLE
                    binding.cbSelectSong.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140L)
                        .start()
                }
            } else {
                if (binding.cbSelectSong.visibility == View.VISIBLE) {
                    binding.cbSelectSong.animate()
                        .alpha(0f)
                        .scaleX(0.86f)
                        .scaleY(0.86f)
                        .setDuration(100L)
                        .withEndAction {
                            binding.cbSelectSong.visibility = View.GONE
                            binding.cbSelectSong.alpha = 1f
                            binding.cbSelectSong.scaleX = 1f
                            binding.cbSelectSong.scaleY = 1f
                        }
                        .start()
                }
                if (binding.ivAlbumArt.visibility != View.VISIBLE) {
                    binding.ivAlbumArt.alpha = 0f
                    binding.ivAlbumArt.visibility = View.VISIBLE
                    binding.ivAlbumArt.animate()
                        .alpha(1f)
                        .setDuration(120L)
                        .start()
                }
            }
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
