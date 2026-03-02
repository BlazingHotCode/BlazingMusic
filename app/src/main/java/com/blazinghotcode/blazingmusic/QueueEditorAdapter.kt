package com.blazinghotcode.blazingmusic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QueueEditorAdapter(
    private val onSongClick: (Int) -> Unit,
    private val onItemMenuClick: (Int, View) -> Unit
) : RecyclerView.Adapter<QueueEditorAdapter.QueueViewHolder>() {

    private val songs = mutableListOf<Song>()
    private var currentIndex: Int = -1

    fun submitQueue(queue: List<Song>, currentQueueIndex: Int) {
        songs.clear()
        songs.addAll(queue)
        currentIndex = currentQueueIndex
        notifyDataSetChanged()
    }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in songs.indices || toIndex !in songs.indices) return
        val song = songs.removeAt(fromIndex)
        songs.add(toIndex, song)

        if (currentIndex == fromIndex) {
            currentIndex = toIndex
        } else if (fromIndex < currentIndex && toIndex >= currentIndex) {
            currentIndex -= 1
        } else if (fromIndex > currentIndex && toIndex <= currentIndex) {
            currentIndex += 1
        }

        notifyItemMoved(fromIndex, toIndex)
    }

    fun removeItem(index: Int) {
        if (index !in songs.indices) return
        songs.removeAt(index)

        if (index < currentIndex) {
            currentIndex -= 1
        } else if (index == currentIndex) {
            currentIndex = -1
        }

        notifyItemRemoved(index)
    }

    fun getQueueSnapshot(): List<Song> = songs.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(songs[position], position, currentIndex == position)
    }

    override fun getItemCount(): Int = songs.size

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvQueueIndex: TextView = itemView.findViewById(R.id.tvQueueIndex)
        private val tvQueueTitle: TextView = itemView.findViewById(R.id.tvQueueTitle)
        private val tvQueueArtist: TextView = itemView.findViewById(R.id.tvQueueArtist)
        private val btnQueueItemMore: ImageButton = itemView.findViewById(R.id.btnQueueItemMore)

        fun bind(song: Song, position: Int, isCurrent: Boolean) {
            val prefix = if (isCurrent) "\u25B6 " else ""
            tvQueueIndex.text = "$prefix${position + 1}."
            tvQueueTitle.text = song.title
            tvQueueArtist.text = song.artist
            tvQueueTitle.alpha = if (isCurrent) 1.0f else 0.9f
            tvQueueArtist.alpha = if (isCurrent) 1.0f else 0.85f

            itemView.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) {
                    onSongClick(index)
                }
            }

            btnQueueItemMore.setOnClickListener { anchor ->
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) {
                    onItemMenuClick(index, anchor)
                }
            }
        }
    }
}
