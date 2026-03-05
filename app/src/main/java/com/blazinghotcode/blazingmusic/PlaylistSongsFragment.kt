package com.blazinghotcode.blazingmusic

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation

class PlaylistSongsFragment : Fragment(R.layout.fragment_playlist_songs) {

    companion object {
        private const val ARG_PLAYLIST_ID = "arg_playlist_id"
        private const val ARG_PLAYLIST_NAME = "arg_playlist_name"
        private const val SORT_PREFS_NAME = "blazing_music_sort_prefs"
        private const val PLAYLIST_SORT_KEY_PREFIX = "playlist_sort_"

        fun newInstance(playlistId: Long, playlistName: String): PlaylistSongsFragment {
            return PlaylistSongsFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_PLAYLIST_ID, playlistId)
                    putString(ARG_PLAYLIST_NAME, playlistName)
                }
            }
        }
    }

    private val viewModel: MusicViewModel by activityViewModels()
    private lateinit var songAdapter: SongAdapter

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearchSongs: EditText
    private lateinit var btnPlayAll: Button
    private lateinit var btnShuffleList: Button
    private lateinit var btnSortList: Button
    private lateinit var rvSongs: RecyclerView
    private lateinit var btnBack: ImageButton

    private lateinit var playerLayout: View
    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var ivAlbumArt: ImageView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnQueue: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    private var playlistId: Long = -1L
    private var playlistName: String = "Playlist"
    private var playlistSongs: List<Song> = emptyList()
    private var filteredSongs: List<Song> = emptyList()
    private var currentSongSort = PlaylistSongSortOption.TITLE
    private var dragSnapshot: MutableList<Song> = mutableListOf()
    private var isDragInProgress = false
    private val sortPrefs by lazy {
        requireContext().getSharedPreferences(SORT_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var isCurrentlyPlaying = false
    private var shouldRestartQueue = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        playlistId = arguments?.getLong(ARG_PLAYLIST_ID, -1L) ?: -1L
        playlistName = arguments?.getString(ARG_PLAYLIST_NAME).orEmpty().ifBlank { "Playlist" }
        currentSongSort = loadSortForCurrentPlaylist()

        initViews(view)
        setupRecyclerView()
        setupPlayerControls()
        observeViewModel()
    }

    private fun initViews(root: View) {
        tvTitle = root.findViewById(R.id.tvTitle)
        tvSubtitle = root.findViewById(R.id.tvSubtitle)
        tvEmpty = root.findViewById(R.id.tvEmpty)
        etSearchSongs = root.findViewById(R.id.etSearchSongs)
        btnPlayAll = root.findViewById(R.id.btnPlayAll)
        btnShuffleList = root.findViewById(R.id.btnShuffleList)
        btnSortList = root.findViewById(R.id.btnSortList)
        rvSongs = root.findViewById(R.id.rvSongs)
        btnBack = root.findViewById(R.id.btnBack)

        playerLayout = root.findViewById(R.id.playerLayout)
        tvSongTitle = root.findViewById(R.id.tvSongTitle)
        tvArtist = root.findViewById(R.id.tvArtist)
        ivAlbumArt = root.findViewById(R.id.ivAlbumArt)
        btnPlayPause = root.findViewById(R.id.btnPlayPause)
        btnPrevious = root.findViewById(R.id.btnPrevious)
        btnNext = root.findViewById(R.id.btnNext)
        btnQueue = root.findViewById(R.id.btnQueue)
        btnShuffle = root.findViewById(R.id.btnShuffle)
        btnRepeat = root.findViewById(R.id.btnRepeat)
        seekBar = root.findViewById(R.id.seekBar)
        tvCurrentTime = root.findViewById(R.id.tvCurrentTime)
        tvTotalTime = root.findViewById(R.id.tvTotalTime)

        tvTitle.text = playlistName
        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        btnQueue.setOnClickListener { showToast("Queue editing is available on Home") }
        tintSearchStartIcon()
        setupSearch()
        setupListActions()
        updateSortButtonLabel()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                val currentOrderedSongs = songAdapter.currentList.toList()
                val queueSource = when {
                    currentOrderedSongs.isNotEmpty() -> currentOrderedSongs
                    filteredSongs.isNotEmpty() -> filteredSongs
                    else -> playlistSongs
                }
                if (queueSource.isNotEmpty()) {
                    viewModel.playSongFromQueue(song, queueSource)
                }
            },
            onSongMenuClick = { song, anchor ->
                showSongOptionsMenu(song, anchor)
            }
        )

        rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = canDragCustomOrder()

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (!canDragCustomOrder()) return makeMovementFlags(0, 0)
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!canDragCustomOrder()) return false
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                    return false
                }
                if (dragSnapshot.isEmpty()) {
                    dragSnapshot = songAdapter.currentList.toMutableList()
                }
                val moved = dragSnapshot.removeAt(from)
                dragSnapshot.add(to, moved)
                songAdapter.submitList(dragSnapshot.toList())
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragInProgress = true
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (!isDragInProgress) return
                isDragInProgress = false
                if (dragSnapshot.isEmpty()) return

                val reorderedPaths = dragSnapshot.map { it.path }
                val saved = viewModel.reorderPlaylistSongs(playlistId, reorderedPaths)
                if (!saved) {
                    showToast("Unable to save custom order")
                }
                dragSnapshot = mutableListOf()
            }
        })
        itemTouchHelper.attachToRecyclerView(rvSongs)
    }

    private fun setupPlayerControls() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPlayPause.setOnClickListener {
            if (shouldRestartQueue) viewModel.restartQueueFromBeginning() else viewModel.playPause()
        }
        btnPrevious.setOnClickListener { viewModel.playPrevious() }
        btnNext.setOnClickListener { viewModel.playNext() }
        btnShuffle.setOnClickListener { viewModel.toggleShuffle() }
        btnRepeat.setOnClickListener { viewModel.toggleRepeat() }
    }

    private fun observeViewModel() {
        viewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            val playlist = playlists.find { it.id == playlistId }
            if (playlist == null) {
                parentFragmentManager.popBackStack()
                return@observe
            }
            playlistName = playlist.name
            tvTitle.text = playlistName
            playlistSongs = viewModel.getPlaylistSongs(playlistId)
            applySongFilter(etSearchSongs.text?.toString().orEmpty())
        }

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            song?.let {
                playerLayout.visibility = View.VISIBLE
                tvSongTitle.text = it.title
                tvSongTitle.isSelected = true
                tvArtist.text = it.artist
                it.albumArtUri?.let { uri ->
                    ivAlbumArt.load(uri) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(20f))
                    }
                } ?: ivAlbumArt.setImageResource(R.drawable.ml_library_music)
            }
        }

        viewModel.isPlaying.observe(viewLifecycleOwner) {
            isCurrentlyPlaying = it
            updatePrimaryControlButton()
        }

        viewModel.shouldRestartQueue.observe(viewLifecycleOwner) {
            shouldRestartQueue = it
            updatePrimaryControlButton()
        }

        viewModel.duration.observe(viewLifecycleOwner) { duration ->
            seekBar.max = duration.toInt()
            tvTotalTime.text = formatDuration(duration)
        }

        viewModel.currentPosition.observe(viewLifecycleOwner) { position ->
            seekBar.progress = position.toInt()
            tvCurrentTime.text = formatDuration(position)
        }

        viewModel.isShuffleEnabled.observe(viewLifecycleOwner) { enabled -> updateShuffleUi(enabled) }
        viewModel.repeatMode.observe(viewLifecycleOwner) { mode -> updateRepeatUi(mode) }
    }

    private fun showSongOptionsMenu(song: Song, anchor: View) {
        androidx.appcompat.widget.PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_BlazingMusic_PopupMenu),
            anchor
        ).apply {
            menu.add(Menu.NONE, 1, Menu.NONE, "Play next")
            menu.add(Menu.NONE, 2, Menu.NONE, "Add to queue")
            menu.add(Menu.NONE, 3, Menu.NONE, "Remove from playlist")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        viewModel.addSongToPlayNext(song)
                        true
                    }
                    2 -> {
                        viewModel.addSongToQueue(song)
                        true
                    }
                    3 -> {
                        val removed = viewModel.removeSongsFromPlaylist(playlistId, setOf(song.path))
                        if (removed > 0) showToast("Removed from playlist") else showToast("Unable to remove")
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun setupSearch() {
        etSearchSongs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applySongFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun setupListActions() {
        btnPlayAll.setOnClickListener {
            val source = if (filteredSongs.isNotEmpty()) filteredSongs else playlistSongs
            source.firstOrNull()?.let { first ->
                if (viewModel.isShuffleEnabled.value == true) {
                    viewModel.toggleShuffle()
                }
                viewModel.playSongFromQueue(first, source)
            } ?: showToast("No songs in playlist")
        }
        btnShuffleList.setOnClickListener {
            val source = if (filteredSongs.isNotEmpty()) filteredSongs else playlistSongs
            source.shuffled().firstOrNull()?.let { first ->
                viewModel.playSongFromQueue(first, source)
                if (viewModel.isShuffleEnabled.value != true) {
                    viewModel.toggleShuffle()
                }
            } ?: showToast("No songs in playlist")
        }
        btnSortList.setOnClickListener { showSortOptionsMenu() }
    }

    private fun showSortOptionsMenu() {
        androidx.appcompat.widget.PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_BlazingMusic_PopupMenu),
            btnSortList
        ).apply {
            PlaylistSongSortOption.entries.forEachIndexed { index, option ->
                menu.add(Menu.NONE, index + 1, Menu.NONE, option.label)
            }
            setOnMenuItemClickListener { item ->
                val selected = PlaylistSongSortOption.entries.getOrNull(item.itemId - 1)
                    ?: return@setOnMenuItemClickListener false
                currentSongSort = selected
                persistSortForCurrentPlaylist(selected)
                updateSortButtonLabel()
                applySongFilter(etSearchSongs.text?.toString().orEmpty())
                true
            }
            show()
        }
    }

    private fun updateSortButtonLabel() {
        btnSortList.text = currentSongSort.label
    }

    private fun applySongFilter(query: String) {
        val normalized = query.trim().lowercase()
        val base = if (normalized.isEmpty()) {
            playlistSongs
        } else {
            playlistSongs.filter {
                it.title.lowercase().contains(normalized) ||
                    it.artist.lowercase().contains(normalized) ||
                    it.album.lowercase().contains(normalized)
            }
        }
        filteredSongs = when (currentSongSort) {
            PlaylistSongSortOption.CUSTOM -> base
            PlaylistSongSortOption.TITLE -> base.sortedWith(
                compareBy<Song> { it.title.lowercase() }.thenBy { it.artist.lowercase() }
            )
            PlaylistSongSortOption.ARTIST -> base.sortedWith(
                compareBy<Song> { it.artist.lowercase() }.thenBy { it.title.lowercase() }
            )
            PlaylistSongSortOption.ALBUM -> base.sortedWith(
                compareBy<Song> { it.album.lowercase() }.thenBy { it.title.lowercase() }
            )
            PlaylistSongSortOption.DURATION -> base.sortedWith(
                compareByDescending<Song> { it.duration }.thenBy { it.title.lowercase() }
            )
            PlaylistSongSortOption.RECENTLY_ADDED -> base.sortedWith(
                compareByDescending<Song> { it.dateAddedSeconds }.thenBy { it.title.lowercase() }
            )
        }
        songAdapter.submitList(filteredSongs)
        tvEmpty.visibility = if (filteredSongs.isEmpty()) View.VISIBLE else View.GONE
        val countWord = if (filteredSongs.size == 1) "song" else "songs"
        tvSubtitle.text = "${filteredSongs.size} $countWord"
    }

    private fun canDragCustomOrder(): Boolean {
        if (currentSongSort != PlaylistSongSortOption.CUSTOM) return false
        if (etSearchSongs.text?.isNotBlank() == true) return false
        return filteredSongs.size > 1
    }

    private fun updateShuffleUi(isEnabled: Boolean) {
        btnShuffle.setImageResource(if (isEnabled) R.drawable.ml_shuffle_on else R.drawable.ml_shuffle)
        val tint = if (isEnabled) R.color.accent_lavender else R.color.text_secondary
        ImageViewCompat.setImageTintList(
            btnShuffle,
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tint))
        )
    }

    private fun updateRepeatUi(mode: Int) {
        val icon = when (mode) {
            1 -> R.drawable.ml_repeat_on
            2 -> R.drawable.ml_repeat_one_on
            else -> R.drawable.ml_repeat
        }
        btnRepeat.setImageResource(icon)
        val tint = if (mode == 0) R.color.text_secondary else R.color.accent_lavender
        ImageViewCompat.setImageTintList(
            btnRepeat,
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tint))
        )
    }

    private fun updatePrimaryControlButton() {
        if (shouldRestartQueue) {
            btnPlayPause.setImageResource(R.drawable.ml_replay)
            return
        }
        btnPlayPause.setImageResource(if (isCurrentlyPlaying) R.drawable.ml_pause else R.drawable.ml_play)
    }

    private fun formatDuration(durationMs: Long): String {
        val minutes = (durationMs / 1000) / 60
        val seconds = (durationMs / 1000) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun tintSearchStartIcon() {
        val drawables = etSearchSongs.compoundDrawablesRelative
        val start = drawables[0]?.mutate()
        start?.setTint(ContextCompat.getColor(requireContext(), R.color.text_muted))
        etSearchSongs.setCompoundDrawablesRelativeWithIntrinsicBounds(
            start,
            drawables[1],
            drawables[2],
            drawables[3]
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun playlistSortKey(): String = "$PLAYLIST_SORT_KEY_PREFIX$playlistId"

    private fun persistSortForCurrentPlaylist(option: PlaylistSongSortOption) {
        sortPrefs.edit().putString(playlistSortKey(), option.name).apply()
    }

    private fun loadSortForCurrentPlaylist(): PlaylistSongSortOption {
        if (playlistId <= 0L) return PlaylistSongSortOption.TITLE
        val raw = sortPrefs.getString(playlistSortKey(), PlaylistSongSortOption.TITLE.name)
        return PlaylistSongSortOption.entries.firstOrNull { it.name == raw } ?: PlaylistSongSortOption.TITLE
    }

    private enum class PlaylistSongSortOption(val label: String) {
        CUSTOM("Custom"),
        TITLE("Title"),
        ARTIST("Artist"),
        ALBUM("Album"),
        DURATION("Duration"),
        RECENTLY_ADDED("Recent")
    }
}
