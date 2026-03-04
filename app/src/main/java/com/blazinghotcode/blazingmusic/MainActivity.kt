package com.blazinghotcode.blazingmusic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import android.os.Handler
import android.os.Looper
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : AppCompatActivity() {

    private val viewModel: MusicViewModel by viewModels()
    private lateinit var songAdapter: SongAdapter

    private lateinit var rvSongs: RecyclerView
    private lateinit var playerLayout: View
    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var ivAlbumArt: ImageView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPlaylists: ImageButton
    private lateinit var btnQueue: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var bottomNav: View
    private lateinit var navHome: View
    private lateinit var navPlaylists: View
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var etSearch: EditText
    private var allSongs: List<Song> = emptyList()
    private var playlists: List<Playlist> = emptyList()
    private var queueSongs: List<Song> = emptyList()
    private var queueCurrentIndex: Int = -1
    private var isCurrentlyPlaying = false
    private var shouldRestartQueue = false
    private lateinit var playlistContainer: View
    private var queueDialog: BottomSheetDialog? = null
    private var queueEditorAdapter: QueueEditorAdapter? = null
    private var isQueueDragInProgress = false
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekbarRunnable = object : Runnable {
        override fun run() {
            val position = viewModel.getCurrentPosition()
            seekBar.progress = position.toInt()
            tvCurrentTime.text = formatDuration(position)
            handler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.loadSongs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerView()
        setupSearch()
        setupPlayerControls()
        setupPlaylistControls()
        observeViewModel()
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            // Controller ready
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun initViews() {
        rvSongs = findViewById(R.id.rvSongs)
        playerLayout = findViewById(R.id.playerLayout)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvArtist = findViewById(R.id.tvArtist)
        ivAlbumArt = findViewById(R.id.ivAlbumArt)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnNext = findViewById(R.id.btnNext)
        btnPlaylists = findViewById(R.id.btnPlaylists)
        btnQueue = findViewById(R.id.btnQueue)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        bottomNav = findViewById(R.id.bottomNav)
        navHome = findViewById(R.id.navHome)
        navPlaylists = findViewById(R.id.navPlaylists)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        etSearch = findViewById(R.id.etSearch)
        playlistContainer = findViewById(R.id.playlistContainer)
        tintSearchStartIcon()
        applySystemInsets()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                viewModel.playSong(song)
            },
            onSongMenuClick = { song, anchor ->
                showSongOptionsMenu(song, anchor)
            }
        )
        rvSongs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = songAdapter
        }
    }

    private fun setupPlayerControls() {
        seekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekbar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPlayPause.setOnClickListener {
            if (shouldRestartQueue) {
                viewModel.restartQueueFromBeginning()
            } else {
                viewModel.playPause()
            }
        }

        btnPrevious.setOnClickListener {
            viewModel.playPrevious()
        }

        btnNext.setOnClickListener {
            viewModel.playNext()
        }

        btnQueue.setOnClickListener {
            showQueueDialog()
        }

        btnShuffle.setOnClickListener {
            viewModel.toggleShuffle()
        }

        btnRepeat.setOnClickListener {
            viewModel.toggleRepeat()
        }

        playerLayout.setOnClickListener {
            showQueueDialog()
        }
    }

    private fun setupPlaylistControls() {
        btnPlaylists.setOnClickListener {
            openPlaylistsTab()
        }
        navHome.setOnClickListener { openHomeTab() }
        navPlaylists.setOnClickListener { openPlaylistsTab() }
    }

    private fun observeViewModel() {
        viewModel.songs.observe(this) { songs ->
            allSongs = songs
            songAdapter.submitList(songs)
        }

        viewModel.currentSong.observe(this) { song ->
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
                } ?: run {
                    ivAlbumArt.setImageResource(R.drawable.ml_library_music)
                }
            }
        }

        viewModel.isPlaying.observe(this) { isPlaying ->
            isCurrentlyPlaying = isPlaying
            updatePrimaryControlButton()
        }

        viewModel.duration.observe(this) { duration ->
            seekBar.max = duration.toInt()
            tvTotalTime.text = formatDuration(duration)
        }

        viewModel.isShuffleEnabled.observe(this) { isShuffleEnabled ->
            updateShuffleUi(isShuffleEnabled)
        }

        viewModel.repeatMode.observe(this) { repeatMode ->
            updateRepeatUi(repeatMode)
        }

        viewModel.queue.observe(this) { queue ->
            queueSongs = queue
            if (!isQueueDragInProgress) {
                queueEditorAdapter?.submitQueue(queueSongs, queueCurrentIndex)
            }
        }

        viewModel.playlists.observe(this) { updated ->
            playlists = updated
        }

        viewModel.currentQueueIndex.observe(this) { index ->
            queueCurrentIndex = index
            if (!isQueueDragInProgress) {
                queueEditorAdapter?.submitQueue(queueSongs, queueCurrentIndex)
            }
        }

        viewModel.shouldRestartQueue.observe(this) { shouldRestart ->
            shouldRestartQueue = shouldRestart
            updatePrimaryControlButton()
        }
    }

    private fun showQueueDialog() {
        if (queueSongs.isEmpty()) {
            dialogBuilder()
                .setTitle("Playback Queue")
                .setMessage("Queue is empty.")
                .setPositiveButton("OK", null)
                .showStyledDialog()
            return
        }

        val queueRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(12))
        }
        val recyclerContainer = FrameLayout(this).apply {
            val horizontalPadding = dp(16)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                queueRecycler,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        val adapter = QueueEditorAdapter(
            onSongClick = { index -> viewModel.playSongAt(index) },
            onItemMenuClick = { index, anchor -> showQueueItemMenu(index, anchor) }
        )
        adapter.submitQueue(queueSongs, queueCurrentIndex)
        queueEditorAdapter = adapter
        queueRecycler.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START or ItemTouchHelper.END
        ) {
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isQueueDragInProgress = true
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromIndex = viewHolder.bindingAdapterPosition
                val toIndex = target.bindingAdapterPosition
                if (fromIndex == RecyclerView.NO_POSITION || toIndex == RecyclerView.NO_POSITION) {
                    return false
                }
                adapter.moveItem(fromIndex, toIndex)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return
                adapter.removeItem(index)
                viewModel.removeQueueItem(index)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (isQueueDragInProgress) {
                    isQueueDragInProgress = false
                    viewModel.setQueueOrder(
                        adapter.getQueueSnapshot(),
                        adapter.getCurrentIndexSnapshot()
                    )
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(queueRecycler)

        queueDialog?.dismiss()
        val title = TextView(this).apply {
            text = "Playback Queue"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(20), dp(4))
        }
        val close = Button(this).apply {
            text = "Close"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_lavender))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { queueDialog?.dismiss() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(recyclerContainer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(close, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                marginEnd = dp(12)
                bottomMargin = dp(8)
            })
        }
        queueDialog = BottomSheetDialog(this, R.style.ThemeOverlay_BlazingMusic_BottomSheet).apply {
            setContentView(root)
            show()
        }
        queueDialog?.setOnDismissListener {
            queueEditorAdapter = null
            queueDialog = null
        }
    }

    private fun showQueueItemMenu(index: Int, anchor: View) {
        PopupMenu(ContextThemeWrapper(this, R.style.ThemeOverlay_BlazingMusic_PopupMenu), anchor).apply {
            menu.add(Menu.NONE, 1, Menu.NONE, "Play next")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        viewModel.moveSongToPlayNext(index)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showSongOptionsMenu(song: Song, anchor: View) {
        PopupMenu(ContextThemeWrapper(this, R.style.ThemeOverlay_BlazingMusic_PopupMenu), anchor).apply {
            menu.add(Menu.NONE, 2, Menu.NONE, "Play next")
            menu.add(Menu.NONE, 1, Menu.NONE, "Add to queue")
            menu.add(Menu.NONE, 3, Menu.NONE, "Add to playlist")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        viewModel.addSongToQueue(song)
                        true
                    }
                    2 -> {
                        viewModel.addSongToPlayNext(song)
                        true
                    }
                    3 -> {
                        showAddSongToPlaylistDialog(song)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showPlaylistBrowserDialog() {
        if (playlists.isEmpty()) {
            dialogBuilder()
                .setTitle("Playlists")
                .setMessage("No playlists yet.")
                .setPositiveButton("Create") { _, _ ->
                    showCreatePlaylistDialog()
                }
                .setNegativeButton("Close", null)
                .showStyledDialog()
            return
        }
        showSimpleBottomSheet(
            title = "Playlists",
            items = playlists.map { "${it.name} (${it.songPaths.size})" },
            primaryLabel = "Create",
            onPrimaryClick = { showCreatePlaylistDialog() }
        ) { index ->
            val selected = playlists.getOrNull(index) ?: return@showSimpleBottomSheet
            showPlaylistActionsDialog(selected)
        }
    }

    private fun showPlaylistActionsDialog(playlist: Playlist) {
        val actions = arrayOf("View songs", "Add songs", "Rename", "Delete")
        showSimpleBottomSheet(
            title = playlist.name,
            items = actions.toList(),
            secondaryLabel = "Back",
            onSecondaryClick = { showPlaylistBrowserDialog() }
        ) { index ->
            when (index) {
                0 -> showPlaylistSongsDialog(playlist.id)
                1 -> showAddSongsToPlaylistDialog(playlist.id)
                2 -> showRenamePlaylistDialog(playlist)
                3 -> showDeletePlaylistDialog(playlist)
            }
        }
    }

    private fun showPlaylistSongsDialog(playlistId: Long) {
        val playlist = playlists.find { it.id == playlistId } ?: return
        val playlistSongs = viewModel.getPlaylistSongs(playlistId)
        if (playlistSongs.isEmpty()) {
            dialogBuilder()
                .setTitle(playlist.name)
                .setMessage("This playlist has no songs.")
                .setPositiveButton("Add songs") { _, _ ->
                    showAddSongsToPlaylistDialog(playlistId)
                }
                .setNegativeButton("Back") { _, _ ->
                    showPlaylistActionsDialog(playlist)
                }
                .showStyledDialog()
            return
        }

        showSimpleBottomSheet(
            title = playlist.name,
            items = playlistSongs.map { "${it.title} - ${it.artist}" },
            primaryLabel = "Add songs",
            secondaryLabel = "Remove songs",
            onPrimaryClick = { showAddSongsToPlaylistDialog(playlistId) },
            onSecondaryClick = { showRemoveSongsFromPlaylistDialog(playlistId) }
        ) { index ->
            val selectedSong = playlistSongs.getOrNull(index) ?: return@showSimpleBottomSheet
            viewModel.playSongFromQueue(selectedSong, playlistSongs)
        }
    }

    private fun showAddSongToPlaylistDialog(song: Song) {
        if (playlists.isEmpty()) {
            dialogBuilder()
                .setTitle("Add to playlist")
                .setMessage("No playlists yet.")
                .setPositiveButton("Create") { _, _ ->
                    showCreatePlaylistDialog(song)
                }
                .setNegativeButton("Cancel", null)
                .showStyledDialog()
            return
        }

        showSimpleBottomSheet(
            title = "Add to playlist",
            items = playlists.map { it.name },
            primaryLabel = "Create new",
            onPrimaryClick = { showCreatePlaylistDialog(song) }
        ) { index ->
            val playlist = playlists.getOrNull(index) ?: return@showSimpleBottomSheet
            val added = viewModel.addSongToPlaylist(playlist.id, song)
            if (added) {
                showToast("Added to ${playlist.name}")
            } else {
                showToast("Song is already in ${playlist.name}")
            }
        }
    }

    private fun showCreatePlaylistDialog(songToAdd: Song? = null) {
        showTextInputBottomSheet(
            title = "Create playlist",
            hint = "Playlist name",
            confirmLabel = "Create"
        ) { name ->
            val createdPlaylist = viewModel.createPlaylist(name)
            if (createdPlaylist == null) {
                showToast("Unable to create playlist")
                return@showTextInputBottomSheet
            }
            if (songToAdd != null) {
                viewModel.addSongToPlaylist(createdPlaylist.id, songToAdd)
                showToast("Playlist created and song added")
            } else {
                showToast("Playlist created")
            }
        }
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        showTextInputBottomSheet(
            title = "Rename playlist",
            hint = "Playlist name",
            initialText = playlist.name,
            confirmLabel = "Save"
        ) { updatedName ->
            val renamed = viewModel.renamePlaylist(playlist.id, updatedName)
            if (renamed) {
                showToast("Playlist renamed")
            } else {
                showToast("Unable to rename playlist")
            }
        }
    }

    private fun showDeletePlaylistDialog(playlist: Playlist) {
        showConfirmBottomSheet(
            title = "Delete playlist",
            message = "Delete \"${playlist.name}\"?",
            confirmLabel = "Delete"
        ) {
            val deleted = viewModel.deletePlaylist(playlist.id)
            if (deleted) {
                showToast("Playlist deleted")
            } else {
                showToast("Unable to delete playlist")
            }
        }
    }

    private fun showAddSongsToPlaylistDialog(playlistId: Long) {
        val playlist = playlists.find { it.id == playlistId } ?: return
        if (allSongs.isEmpty()) {
            showToast("No songs available")
            return
        }

        showMultiSelectBottomSheet(
            title = "Add songs to ${playlist.name}",
            items = allSongs.map { "${it.title} - ${it.artist}" },
            confirmLabel = "Add"
        ) { selectedIndices ->
            val selectedSongs = allSongs.filterIndexed { index, _ -> selectedIndices.contains(index) }
            val addedCount = viewModel.addSongsToPlaylist(playlistId, selectedSongs)
            if (addedCount > 0) {
                showToast("Added $addedCount songs")
            } else {
                showToast("No new songs added")
            }
        }
    }

    private fun showRemoveSongsFromPlaylistDialog(playlistId: Long) {
        val playlist = playlists.find { it.id == playlistId } ?: return
        val playlistSongs = viewModel.getPlaylistSongs(playlistId)
        if (playlistSongs.isEmpty()) {
            showToast("Playlist is already empty")
            return
        }

        showMultiSelectBottomSheet(
            title = "Remove songs from ${playlist.name}",
            items = playlistSongs.map { "${it.title} - ${it.artist}" },
            confirmLabel = "Remove"
        ) { selectedIndices ->
            val selectedPaths = playlistSongs
                .filterIndexed { index, _ -> selectedIndices.contains(index) }
                .map { it.path }
                .toSet()
            val removedCount = viewModel.removeSongsFromPlaylist(playlistId, selectedPaths)
            if (removedCount > 0) {
                showToast("Removed $removedCount songs")
            } else {
                showToast("No songs removed")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateShuffleUi(isEnabled: Boolean) {
        val icon = if (isEnabled) {
            R.drawable.ml_shuffle_on
        } else {
            R.drawable.ml_shuffle
        }
        val tint = if (isEnabled) {
            ContextCompat.getColor(this, R.color.accent_lavender)
        } else {
            ContextCompat.getColor(this, R.color.text_secondary)
        }
        btnShuffle.setImageResource(icon)
        ImageViewCompat.setImageTintList(btnShuffle, android.content.res.ColorStateList.valueOf(tint))
        btnShuffle.contentDescription = if (isEnabled) "Shuffle on" else "Shuffle off"
    }

    private fun updateRepeatUi(mode: Int) {
        val icon = when (mode) {
            1 -> R.drawable.ml_repeat_on
            2 -> R.drawable.ml_repeat_one_on
            else -> R.drawable.ml_repeat
        }
        val tint = if (mode == 0) {
            ContextCompat.getColor(this, R.color.text_secondary)
        } else {
            ContextCompat.getColor(this, R.color.accent_lavender)
        }
        val description = when (mode) {
            1 -> "Repeat all"
            2 -> "Repeat one"
            else -> "Repeat off"
        }
        btnRepeat.setImageResource(icon)
        ImageViewCompat.setImageTintList(btnRepeat, android.content.res.ColorStateList.valueOf(tint))
        btnRepeat.contentDescription = description
    }

    private fun updatePrimaryControlButton() {
        if (shouldRestartQueue) {
            btnPlayPause.setImageResource(R.drawable.ml_replay)
            btnPlayPause.contentDescription = "Restart queue"
            return
        }
        val icon = if (isCurrentlyPlaying) {
            R.drawable.ml_pause
        } else {
            R.drawable.ml_play
        }
        btnPlayPause.setImageResource(icon)
        btnPlayPause.contentDescription = if (isCurrentlyPlaying) "Pause" else "Play"
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) {
                    allSongs
                } else {
                    allSongs.filter {
                        it.title.lowercase().contains(query) ||
                            it.artist.lowercase().contains(query)
                    }
                }
                songAdapter.submitList(filtered)
            }
        })
    }

    private fun tintSearchStartIcon() {
        val drawables = etSearch.compoundDrawablesRelative
        val start = drawables[0]?.mutate()
        start?.setTint(ContextCompat.getColor(this, R.color.text_muted))
        etSearch.setCompoundDrawablesRelativeWithIntrinsicBounds(
            start,
            drawables[1],
            drawables[2],
            drawables[3]
        )
    }

    private fun EditText.applyDialogInputStyle() {
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
        setBackgroundResource(R.drawable.bg_search_field)
        val horizontal = (12 * resources.displayMetrics.density).toInt()
        val vertical = (10 * resources.displayMetrics.density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
    }

    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED -> {
                viewModel.loadSongs()
            }
            else -> {
                permissionLauncher.launch(permission)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateSeekbarRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateSeekbarRunnable)
    }

    private fun formatDuration(durationMs: Long): String {
        val minutes = (durationMs / 1000) / 60
        val seconds = (durationMs / 1000) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun dialogBuilder(): AlertDialog.Builder {
        return AlertDialog.Builder(this)
    }

    private fun openPlaylists() {
        openPlaylistsTab()
    }

    fun openPlaylistsTab() {
        if (playlistContainer.visibility == View.VISIBLE &&
            supportFragmentManager.findFragmentById(R.id.playlistContainer) is PlaylistsFragment
        ) {
            return
        }
        playlistContainer.visibility = View.VISIBLE
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            .replace(R.id.playlistContainer, PlaylistsFragment())
            .commit()
        updateBottomNavSelection(homeSelected = false)
    }

    fun openPlaylistSongs(playlistId: Long, playlistName: String) {
        playlistContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            .replace(R.id.playlistContainer, PlaylistSongsFragment.newInstance(playlistId, playlistName))
            .addToBackStack("playlist_songs")
            .commit()
        updateBottomNavSelection(homeSelected = false)
    }

    fun openHomeTab() {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        playlistContainer.visibility = View.GONE
        updateBottomNavSelection(homeSelected = true)
    }

    override fun onBackPressed() {
        if (playlistContainer.visibility == View.VISIBLE) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else {
                openHomeTab()
            }
            return
        }
        super.onBackPressed()
    }

    private fun updateBottomNavSelection(homeSelected: Boolean) {
        val homeIcon = findViewById<ImageView>(R.id.ivNavHome)
        val homeText = findViewById<TextView>(R.id.tvNavHome)
        val playlistIcon = findViewById<ImageView>(R.id.ivNavPlaylists)
        val playlistText = findViewById<TextView>(R.id.tvNavPlaylists)

        if (homeSelected) {
            homeIcon.setImageResource(R.drawable.ml_home_filled)
            homeIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_lavender))
            homeText.setTextColor(ContextCompat.getColor(this, R.color.accent_lavender))
            playlistIcon.setImageResource(R.drawable.ml_library_music_outlined)
            playlistIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            playlistText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            homeIcon.setImageResource(R.drawable.ml_home_outlined)
            homeIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            homeText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            playlistIcon.setImageResource(R.drawable.ml_library_music_filled)
            playlistIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_lavender))
            playlistText.setTextColor(ContextCompat.getColor(this, R.color.accent_lavender))
        }
    }

    private fun applySystemInsets() {
        val originalBottomPadding = bottomNav.paddingBottom
        val layoutParams = bottomNav.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val originalBottomMargin = layoutParams.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val tappableBottom = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
            val gestureBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            var bottomInset = maxOf(navBottom, tappableBottom, gestureBottom)
            if (bottomInset == 0) {
                bottomInset = getNavigationBarHeightFallback()
            }
            view.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = originalBottomMargin + bottomInset
            }
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                originalBottomPadding
            )
            insets
        }
        ViewCompat.requestApplyInsets(bottomNav)
    }

    private fun getNavigationBarHeightFallback(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId <= 0) return 0
        return resources.getDimensionPixelSize(resourceId)
    }

    private fun showSimpleBottomSheet(
        title: String,
        items: List<String>,
        primaryLabel: String? = null,
        secondaryLabel: String? = null,
        onPrimaryClick: (() -> Unit)? = null,
        onSecondaryClick: (() -> Unit)? = null,
        onItemClick: (Int) -> Unit
    ): BottomSheetDialog {
        val sheet = BottomSheetDialog(this, R.style.ThemeOverlay_BlazingMusic_BottomSheet)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = SimpleTextAdapter(items) { index ->
                sheet.dismiss()
                onItemClick(index)
            }
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(20), dp(4))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val close = Button(this@MainActivity).apply {
                text = "Close"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { sheet.dismiss() }
            }
            addView(close)
            if (!primaryLabel.isNullOrBlank()) {
                val create = Button(this@MainActivity).apply {
                    id = android.R.id.button1
                    text = primaryLabel
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_lavender))
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setOnClickListener {
                        sheet.dismiss()
                        onPrimaryClick?.invoke()
                    }
                }
                addView(create)
            }
            if (!secondaryLabel.isNullOrBlank()) {
                val secondary = Button(this@MainActivity).apply {
                    text = secondaryLabel
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setOnClickListener {
                        sheet.dismiss()
                        onSecondaryClick?.invoke()
                    }
                }
                addView(secondary)
            }
            setPadding(0, 0, dp(12), dp(10))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(controls)
        }
        sheet.setContentView(root)
        sheet.show()
        return sheet
    }

    private fun showMultiSelectBottomSheet(
        title: String,
        items: List<String>,
        confirmLabel: String,
        onConfirm: (Set<Int>) -> Unit
    ) {
        val sheet = BottomSheetDialog(this, R.style.ThemeOverlay_BlazingMusic_BottomSheet)
        val adapter = MultiSelectAdapter(items)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = adapter
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(20), dp(4))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val cancel = Button(this@MainActivity).apply {
                text = "Cancel"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { sheet.dismiss() }
            }
            val confirm = Button(this@MainActivity).apply {
                text = confirmLabel
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_lavender))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    sheet.dismiss()
                    onConfirm(adapter.selectedPositions())
                }
            }
            addView(cancel)
            addView(confirm)
            setPadding(0, 0, dp(12), dp(10))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(controls)
        }
        sheet.setContentView(root)
        sheet.show()
    }

    private fun showTextInputBottomSheet(
        title: String,
        hint: String,
        initialText: String = "",
        confirmLabel: String,
        onConfirm: (String) -> Unit
    ) {
        val sheet = BottomSheetDialog(this, R.style.ThemeOverlay_BlazingMusic_BottomSheet)
        val titleView = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(20), dp(6))
        }
        val input = EditText(this).apply {
            this.hint = hint
            setText(initialText)
            if (initialText.isNotEmpty()) {
                setSelection(initialText.length)
            }
            applyDialogInputStyle()
        }
        val inputContainer = FrameLayout(this).apply {
            setPadding(dp(16), dp(0), dp(16), dp(10))
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val cancel = Button(this@MainActivity).apply {
                text = "Cancel"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { sheet.dismiss() }
            }
            val confirm = Button(this@MainActivity).apply {
                text = confirmLabel
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_lavender))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    val value = input.text?.toString().orEmpty()
                    sheet.dismiss()
                    onConfirm(value)
                }
            }
            addView(cancel)
            addView(confirm)
            setPadding(0, 0, dp(12), dp(10))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(inputContainer)
            addView(controls)
        }
        sheet.setContentView(root)
        sheet.show()
    }

    private fun showConfirmBottomSheet(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit
    ) {
        val sheet = BottomSheetDialog(this, R.style.ThemeOverlay_BlazingMusic_BottomSheet)
        val titleView = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(20), dp(6))
        }
        val messageView = TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(20), dp(0), dp(20), dp(10))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val cancel = Button(this@MainActivity).apply {
                text = "Cancel"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { sheet.dismiss() }
            }
            val confirm = Button(this@MainActivity).apply {
                text = confirmLabel
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_lavender))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    sheet.dismiss()
                    onConfirm()
                }
            }
            addView(cancel)
            addView(confirm)
            setPadding(0, 0, dp(12), dp(10))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(messageView)
            addView(controls)
        }
        sheet.setContentView(root)
        sheet.show()
    }

    private fun AlertDialog.Builder.showStyledDialog(): AlertDialog {
        val dialog = show()
        runCatching {
            val buttons = listOf(
                dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            )
            buttons.forEach { button ->
                button?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
        }
        return dialog
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class SimpleTextAdapter(
        private val items: List<String>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<SimpleTextAdapter.SimpleTextViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimpleTextViewHolder {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            val horizontal = (14 * density).toInt()
            val vertical = (12 * density).toInt()
            val text = TextView(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(horizontal, vertical, horizontal, vertical)
                background = ContextCompat.getDrawable(context, R.drawable.bg_queue_item)
            }
            return SimpleTextViewHolder(text)
        }

        override fun onBindViewHolder(holder: SimpleTextViewHolder, position: Int) {
            val textView = holder.itemView as TextView
            textView.text = items[position]
            val params = textView.layoutParams as RecyclerView.LayoutParams
            params.bottomMargin = (6 * textView.resources.displayMetrics.density).toInt()
            textView.layoutParams = params
            textView.setOnClickListener { onClick(position) }
        }

        override fun getItemCount(): Int = items.size

        class SimpleTextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    private class MultiSelectAdapter(
        private val items: List<String>
    ) : RecyclerView.Adapter<MultiSelectAdapter.MultiSelectViewHolder>() {

        private val selected = mutableSetOf<Int>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MultiSelectViewHolder {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            val horizontal = (14 * density).toInt()
            val vertical = (10 * density).toInt()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(horizontal, vertical, horizontal, vertical)
                background = ContextCompat.getDrawable(context, R.drawable.bg_queue_item)
                gravity = Gravity.CENTER_VERTICAL
            }
            val checkBox = CheckBox(context)
            val label = TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding((6 * density).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            row.addView(checkBox)
            row.addView(label)
            return MultiSelectViewHolder(row, checkBox, label)
        }

        override fun onBindViewHolder(holder: MultiSelectViewHolder, position: Int) {
            holder.label.text = items[position]
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = selected.contains(position)
            holder.itemView.setOnClickListener {
                val newChecked = !holder.checkBox.isChecked
                holder.checkBox.isChecked = newChecked
                if (newChecked) selected.add(position) else selected.remove(position)
            }
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(position) else selected.remove(position)
            }
            val params = holder.itemView.layoutParams as RecyclerView.LayoutParams
            params.bottomMargin = (6 * holder.itemView.resources.displayMetrics.density).toInt()
            holder.itemView.layoutParams = params
        }

        override fun getItemCount(): Int = items.size

        fun selectedPositions(): Set<Int> = selected.toSet()

        class MultiSelectViewHolder(
            itemView: View,
            val checkBox: CheckBox,
            val label: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }
}
