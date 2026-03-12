package com.blazinghotcode.blazingmusic

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
/** Playlist detail screen for song browsing, sorting, bulk actions, and playback start. */
class PlaylistSongsFragment : Fragment(R.layout.fragment_playlist_songs) {

    companion object {
        private const val ARG_PLAYLIST_ID = "arg_playlist_id"
        private const val ARG_PLAYLIST_NAME = "arg_playlist_name"
        private const val SORT_PREFS_NAME = "blazing_music_sort_prefs"
        private const val PLAYLIST_SORT_KEY_PREFIX = "playlist_sort_"
        private const val SEARCH_DEBOUNCE_MS = 220L

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
    private lateinit var songAlphabetIndex: AlphabetIndexView
    private lateinit var songScrollTrack: View
    private lateinit var songScrollThumb: View
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
    private lateinit var btnLike: ImageButton
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
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null
    private var isSongScrollDragging = false

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
        songAlphabetIndex = root.findViewById(R.id.songAlphabetIndex)
        songScrollTrack = root.findViewById(R.id.songScrollTrack)
        songScrollThumb = root.findViewById(R.id.songScrollThumb)
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
        btnLike = root.findViewById(R.id.btnLike)
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
            },
            onSelectionStateChanged = { isSelectionMode, selectedCount ->
                updatePlaylistSelectionUi(isSelectionMode, selectedCount)
            }
        )

        rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
        }
        setupSongScrollThumb()
        songAlphabetIndex.setOnSectionSelectedListener { section ->
            scrollSongsToSection(section)
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

    private fun setupSongScrollThumb() {
        rvSongs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isSongScrollDragging) {
                    updateSongScrollThumbPosition()
                }
            }
        })
        songScrollThumb.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isSongScrollDragging = true
                    scrollSongsByThumbTouch(event.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSongScrollDragging = false
                    updateSongScrollThumbPosition()
                    true
                }
                else -> false
            }
        }
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
        btnLike.setOnClickListener {
            val song = viewModel.currentSong.value
            if (!viewModel.canToggleSongLike(song)) {
                showToast("Sign in to like YouTube songs")
                return@setOnClickListener
            }
            val targetSong = song ?: return@setOnClickListener
            val willLike = !viewModel.isSongLiked(targetSong)
            if (viewModel.setSongLiked(targetSong, willLike)) {
                updateLikeUi(targetSong)
                showToast(if (willLike) "Added to Liked Music" else "Removed from Liked Music")
            }
        }
        MiniPlayerExpandGestureController(
            playerLayout = playerLayout,
            toPx = { value -> value * resources.displayMetrics.density },
            onOpen = { (activity as? MainActivity)?.showFullScreenPlayer() }
        ).attach()
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
            songAdapter.setCurrentSong(song)
            song?.let {
                playerLayout.visibility = View.VISIBLE
                MiniPlayerSongUi.bindSong(tvSongTitle, tvArtist, ivAlbumArt, it)
            }
            updateLikeUi(song)
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
            tvTotalTime.text = PlaybackTimeFormatter.formatDuration(duration)
        }

        viewModel.currentPosition.observe(viewLifecycleOwner) { position ->
            seekBar.progress = position.toInt()
            tvCurrentTime.text = PlaybackTimeFormatter.formatDuration(position)
        }

        viewModel.isShuffleEnabled.observe(viewLifecycleOwner) { enabled -> updateShuffleUi(enabled) }
        viewModel.repeatMode.observe(viewLifecycleOwner) { mode -> updateRepeatUi(mode) }
        viewModel.likedSongsRevision.observe(viewLifecycleOwner) { updateLikeUi(viewModel.currentSong.value) }
    }

    private fun showSongOptionsMenu(song: Song, anchor: View) {
        val canEditCurrentPlaylist = viewModel.playlists.value.orEmpty()
            .firstOrNull { it.id == playlistId }
            ?.isEditablePlaylist() == true
        androidx.appcompat.widget.PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_BlazingMusic_PopupMenu),
            anchor
        ).apply {
            menu.add(Menu.NONE, 1, Menu.NONE, "Play next")
            menu.add(Menu.NONE, 2, Menu.NONE, "Add to queue")
            menu.add(Menu.NONE, 6, Menu.NONE, "Add to playlist")
            if (viewModel.canToggleSongLike(song)) {
                menu.add(Menu.NONE, 5, Menu.NONE, if (viewModel.isSongLiked(song)) "Unlike" else "Like")
            }
            if (canEditCurrentPlaylist) {
                menu.add(Menu.NONE, 3, Menu.NONE, "Remove from playlist")
            }
            menu.add(Menu.NONE, 4, Menu.NONE, "Select multiple")
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
                    6 -> {
                        (activity as? MainActivity)?.openAddToPlaylistDialog(song)
                        true
                    }
                    5 -> {
                        val willLike = !viewModel.isSongLiked(song)
                        if (viewModel.setSongLiked(song, willLike)) {
                            updateLikeUi(viewModel.currentSong.value)
                            showToast(if (willLike) "Added to Liked Music" else "Removed from Liked Music")
                        } else {
                            showToast("Sign in to like YouTube songs")
                        }
                        true
                    }
                    3 -> {
                        val removed = viewModel.removeSongsFromPlaylist(playlistId, setOf(song.path))
                        if (removed > 0) showToast("Removed from playlist") else showToast("Unable to remove")
                        true
                    }
                    4 -> {
                        songAdapter.enterSelectionMode(song)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showAddMultipleSongsToPlaylistDialog(selectedSongs: List<Song>) {
        val playlists = viewModel.playlists.value.orEmpty().filter { it.isEditablePlaylist() }
        if (playlists.isEmpty()) {
            showToast("No playlists yet")
            return
        }
        androidx.appcompat.widget.PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_BlazingMusic_PopupMenu),
            btnSortList
        ).apply {
            playlists.forEachIndexed { index, playlist ->
                menu.add(Menu.NONE, index + 1, Menu.NONE, playlist.name)
            }
            setOnMenuItemClickListener { item ->
                val playlist = playlists.getOrNull(item.itemId - 1) ?: return@setOnMenuItemClickListener false
                val added = viewModel.addSongsToPlaylist(playlist.id, selectedSongs)
                if (added > 0) showToast("Added $added songs to ${playlist.name}")
                else showToast("No new songs added")
                true
            }
            show()
        }
    }

    private fun setupSearch() {
        etSearchSongs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                searchDebounceRunnable?.let { searchDebounceHandler.removeCallbacks(it) }
                val runnable = Runnable { applySongFilter(query) }
                searchDebounceRunnable = runnable
                searchDebounceHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    override fun onDestroyView() {
        searchDebounceRunnable?.let { searchDebounceHandler.removeCallbacks(it) }
        searchDebounceRunnable = null
        super.onDestroyView()
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
        if (songAdapter.isSelectionMode()) {
            showPlaylistSelectionActions()
            return
        }
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
                applySongFilter(
                    query = etSearchSongs.text?.toString().orEmpty(),
                    preserveScrollOffset = true
                )
                true
            }
            show()
        }
    }

    private fun updateSortButtonLabel() {
        btnSortList.text = currentSongSort.label
    }

    private fun showPlaylistSelectionActions() {
        val selectedSongs = songAdapter.getSelectedSongs()
        val canEditCurrentPlaylist = viewModel.playlists.value.orEmpty()
            .firstOrNull { it.id == playlistId }
            ?.isEditablePlaylist() == true
        if (selectedSongs.isEmpty()) {
            showToast("No songs selected")
            songAdapter.exitSelectionMode()
            return
        }
        androidx.appcompat.widget.PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_BlazingMusic_PopupMenu),
            btnSortList
        ).apply {
            menu.add(Menu.NONE, 1, Menu.NONE, "Add to queue")
            menu.add(Menu.NONE, 2, Menu.NONE, "Play next")
            menu.add(Menu.NONE, 3, Menu.NONE, "Add to playlist")
            if (canEditCurrentPlaylist) {
                menu.add(Menu.NONE, 4, Menu.NONE, "Remove")
            }
            menu.add(Menu.NONE, 5, Menu.NONE, "Cancel selection")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        selectedSongs.forEach { viewModel.addSongToQueue(it) }
                        showToast("Added ${selectedSongs.size} songs to queue")
                        songAdapter.exitSelectionMode()
                        true
                    }
                    2 -> {
                        selectedSongs.asReversed().forEach { viewModel.addSongToPlayNext(it) }
                        showToast("Queued ${selectedSongs.size} songs to play next")
                        songAdapter.exitSelectionMode()
                        true
                    }
                    3 -> {
                        showAddMultipleSongsToPlaylistDialog(selectedSongs)
                        songAdapter.exitSelectionMode()
                        true
                    }
                    4 -> {
                        val removed = viewModel.removeSongsFromPlaylist(
                            playlistId,
                            selectedSongs.map { it.path }.toSet()
                        )
                        if (removed > 0) showToast("Removed $removed songs")
                        else showToast("No songs removed")
                        songAdapter.exitSelectionMode()
                        true
                    }
                    5 -> {
                        songAdapter.exitSelectionMode()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun updatePlaylistSelectionUi(isSelectionMode: Boolean, selectedCount: Int) {
        if (!isSelectionMode) {
            updateSortButtonLabel()
            return
        }
        btnSortList.text = if (selectedCount > 0) {
            "Actions ($selectedCount)"
        } else {
            "Actions"
        }
    }

    private fun applySongFilter(query: String, preserveScrollOffset: Boolean = false) {
        val previousOffset = if (preserveScrollOffset) rvSongs.computeVerticalScrollOffset() else 0
        filteredSongs = SongListPresentationUi.applySearchAndSort(
            songs = playlistSongs,
            query = query,
            sortMode = currentSortMode()
        )
        SongListIndexUi.updateAvailableSections(
            alphabetIndex = songAlphabetIndex,
            songs = filteredSongs,
            isEnabled = supportsAlphabetIndexForCurrentSort(),
            sectionForSong = { sectionFromCurrentSort(it) }
        )
        if (preserveScrollOffset) {
            songAdapter.submitList(filteredSongs) {
                rvSongs.post {
                    val currentOffset = rvSongs.computeVerticalScrollOffset()
                    rvSongs.scrollBy(0, previousOffset - currentOffset)
                }
                refreshSongAlphabetIndexVisibility()
            }
        } else {
            songAdapter.submitList(filteredSongs) {
                refreshSongAlphabetIndexVisibility()
            }
        }
        tvEmpty.visibility = if (filteredSongs.isEmpty()) View.VISIBLE else View.GONE
        updatePlaylistSubtitle(query)
    }

    private fun updatePlaylistSubtitle(query: String) {
        val totalSongs = playlistSongs.size
        val totalDurationMs = playlistSongs.sumOf { it.duration.coerceAtLeast(0L) }
        val totalCountLabel = "$totalSongs ${if (totalSongs == 1) "song" else "songs"}"
        val durationLabel = totalDurationMs.takeIf { it > 0L }?.let(::formatPlaylistDuration)

        tvSubtitle.text = when {
            query.isNotBlank() && filteredSongs.size != totalSongs -> {
                val filteredLabel = "${filteredSongs.size} shown"
                listOf(filteredLabel, totalCountLabel, durationLabel).filterNotNull().joinToString(" • ")
            }
            else -> listOf(totalCountLabel, durationLabel).filterNotNull().joinToString(" • ")
        }
    }

    private fun formatPlaylistDuration(durationMs: Long): String {
        val totalMinutes = durationMs / 1000L / 60L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
            hours > 0L -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    private fun scrollSongsToSection(section: Char) {
        SongListIndexUi.scrollToSection(
            recyclerView = rvSongs,
            songs = songAdapter.currentList,
            section = section,
            sectionForSong = { sectionFromCurrentSort(it) }
        )
    }

    private fun scrollSongsByThumbTouch(rawY: Float) {
        SongListIndexUi.scrollByThumbTouch(
            recyclerView = rvSongs,
            scrollTrack = songScrollTrack,
            rawY = rawY,
            onThumbMoved = { updateSongScrollThumbPosition() }
        )
    }

    private fun updateSongScrollThumbPosition() {
        SongListIndexUi.updateThumbPosition(
            recyclerView = rvSongs,
            scrollTrack = songScrollTrack,
            scrollThumb = songScrollThumb
        )
    }

    private fun sectionFromCurrentSort(song: Song): Char {
        val key = SongListPresentationUi.sectionLabel(song, currentSortMode())
        return SongListIndexUi.sectionFromLabel(key)
    }

    private fun supportsAlphabetIndexForCurrentSort(): Boolean {
        return SongListPresentationUi.supportsAlphabetIndex(currentSortMode())
    }

    private fun currentSortMode(): SongListPresentationUi.SortMode {
        return when (currentSongSort) {
            PlaylistSongSortOption.CUSTOM -> SongListPresentationUi.SortMode.CUSTOM
            PlaylistSongSortOption.TITLE -> SongListPresentationUi.SortMode.TITLE
            PlaylistSongSortOption.ARTIST -> SongListPresentationUi.SortMode.ARTIST
            PlaylistSongSortOption.ALBUM -> SongListPresentationUi.SortMode.ALBUM
            PlaylistSongSortOption.DURATION -> SongListPresentationUi.SortMode.DURATION
            PlaylistSongSortOption.RECENTLY_ADDED -> SongListPresentationUi.SortMode.RECENTLY_ADDED
        }
    }

    private fun refreshSongAlphabetIndexVisibility() {
        val hasSongs = songAdapter.currentList.isNotEmpty()
        val shouldShow = hasSongs && supportsAlphabetIndexForCurrentSort()
        songAlphabetIndex.visibility = if (shouldShow) View.VISIBLE else View.GONE
        songScrollTrack.visibility = if (hasSongs) View.VISIBLE else View.GONE
        songScrollThumb.visibility = if (hasSongs) View.VISIBLE else View.GONE
        if (hasSongs) {
            rvSongs.post { updateSongScrollThumbPosition() }
        }
    }

    private fun canDragCustomOrder(): Boolean {
        if (currentSongSort != PlaylistSongSortOption.CUSTOM) return false
        val canEditCurrentPlaylist = viewModel.playlists.value.orEmpty()
            .firstOrNull { it.id == playlistId }
            ?.isEditablePlaylist() == true
        if (!canEditCurrentPlaylist) return false
        if (songAdapter.isSelectionMode()) return false
        if (etSearchSongs.text?.isNotBlank() == true) return false
        return filteredSongs.size > 1
    }

    private fun updateShuffleUi(isEnabled: Boolean) {
        PlaybackControlUi.bindShuffleControl(
            context = requireContext(),
            button = btnShuffle,
            isEnabled = isEnabled
        )
    }

    private fun updateRepeatUi(mode: Int) {
        PlaybackControlUi.bindRepeatControl(
            context = requireContext(),
            button = btnRepeat,
            mode = mode
        )
    }

    private fun updateLikeUi(song: Song?) {
        PlaybackControlUi.bindLikeControl(
            context = requireContext(),
            button = btnLike,
            isLiked = viewModel.isSongLiked(song),
            isVisible = viewModel.canToggleSongLike(song)
        )
    }

    private fun updatePrimaryControlButton() {
        PlaybackControlUi.bindPrimaryControl(
            button = btnPlayPause,
            isPlaying = isCurrentlyPlaying,
            shouldRestartQueue = shouldRestartQueue
        )
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
