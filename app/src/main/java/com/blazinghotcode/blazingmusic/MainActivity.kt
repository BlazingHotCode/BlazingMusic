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
import android.widget.PopupMenu
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var queueDialog: AlertDialog? = null
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
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        etSearch = findViewById(R.id.etSearch)
        tintSearchStartIcon()
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
            // Could open full player screen
        }
    }

    private fun setupPlaylistControls() {
        btnPlaylists.setOnClickListener {
            showPlaylistBrowserDialog()
        }
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
            setPadding(0, 8, 0, 8)
        }
        val recyclerContainer = FrameLayout(this).apply {
            val horizontalPadding = (12 * resources.displayMetrics.density).toInt()
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
        queueDialog = dialogBuilder()
            .setTitle("Playback Queue")
            .setView(recyclerContainer)
            .setNegativeButton("Close", null)
            .showStyledDialog()
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

        val items = playlists.map { playlist ->
            "${playlist.name} (${playlist.songPaths.size})"
        }.toTypedArray()

        dialogBuilder()
            .setTitle("Playlists")
            .setItems(items) { _, index ->
                val selected = playlists.getOrNull(index) ?: return@setItems
                showPlaylistActionsDialog(selected)
            }
            .setPositiveButton("Create") { _, _ ->
                showCreatePlaylistDialog()
            }
            .setNegativeButton("Close", null)
            .showStyledDialog()
    }

    private fun showPlaylistActionsDialog(playlist: Playlist) {
        val actions = arrayOf("View songs", "Add songs", "Rename", "Delete")
        dialogBuilder()
            .setTitle(playlist.name)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> showPlaylistSongsDialog(playlist.id)
                    1 -> showAddSongsToPlaylistDialog(playlist.id)
                    2 -> showRenamePlaylistDialog(playlist)
                    3 -> showDeletePlaylistDialog(playlist)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showPlaylistBrowserDialog()
            }
            .showStyledDialog()
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

        val items = playlistSongs.map { "${it.title} - ${it.artist}" }.toTypedArray()
        dialogBuilder()
            .setTitle(playlist.name)
            .setItems(items) { _, index ->
                val selectedSong = playlistSongs.getOrNull(index) ?: return@setItems
                viewModel.playSongFromQueue(selectedSong, playlistSongs)
            }
            .setPositiveButton("Add songs") { _, _ ->
                showAddSongsToPlaylistDialog(playlistId)
            }
            .setNeutralButton("Remove songs") { _, _ ->
                showRemoveSongsFromPlaylistDialog(playlistId)
            }
            .setNegativeButton("Back") { _, _ ->
                showPlaylistActionsDialog(playlist)
            }
            .showStyledDialog()
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

        val names = playlists.map { it.name }.toTypedArray()
        dialogBuilder()
            .setTitle("Add to playlist")
            .setItems(names) { _, index ->
                val playlist = playlists.getOrNull(index) ?: return@setItems
                val added = viewModel.addSongToPlaylist(playlist.id, song)
                if (added) {
                    showToast("Added to ${playlist.name}")
                } else {
                    showToast("Song is already in ${playlist.name}")
                }
            }
            .setPositiveButton("Create new") { _, _ ->
                showCreatePlaylistDialog(song)
            }
            .setNegativeButton("Cancel", null)
            .showStyledDialog()
    }

    private fun showCreatePlaylistDialog(songToAdd: Song? = null) {
        val input = EditText(this).apply {
            hint = "Playlist name"
            applyDialogInputStyle()
        }
        dialogBuilder()
            .setTitle("Create playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString().orEmpty()
                val createdPlaylist = viewModel.createPlaylist(name)
                if (createdPlaylist == null) {
                    showToast("Unable to create playlist")
                    return@setPositiveButton
                }
                if (songToAdd != null) {
                    viewModel.addSongToPlaylist(createdPlaylist.id, songToAdd)
                    showToast("Playlist created and song added")
                } else {
                    showToast("Playlist created")
                }
            }
            .setNegativeButton("Cancel", null)
            .showStyledDialog()
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        val input = EditText(this).apply {
            setText(playlist.name)
            setSelection(playlist.name.length)
            applyDialogInputStyle()
        }
        dialogBuilder()
            .setTitle("Rename playlist")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val renamed = viewModel.renamePlaylist(playlist.id, input.text?.toString().orEmpty())
                if (renamed) {
                    showToast("Playlist renamed")
                } else {
                    showToast("Unable to rename playlist")
                }
            }
            .setNegativeButton("Cancel", null)
            .showStyledDialog()
    }

    private fun showDeletePlaylistDialog(playlist: Playlist) {
        dialogBuilder()
            .setTitle("Delete playlist")
            .setMessage("Delete \"${playlist.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = viewModel.deletePlaylist(playlist.id)
                if (deleted) {
                    showToast("Playlist deleted")
                } else {
                    showToast("Unable to delete playlist")
                }
            }
            .setNegativeButton("Cancel", null)
            .showStyledDialog()
    }

    private fun showAddSongsToPlaylistDialog(playlistId: Long) {
        val playlist = playlists.find { it.id == playlistId } ?: return
        if (allSongs.isEmpty()) {
            showToast("No songs available")
            return
        }

        val songItems = allSongs.map { "${it.title} - ${it.artist}" }.toTypedArray()
        val checkedItems = BooleanArray(allSongs.size)

        dialogBuilder()
            .setTitle("Add songs to ${playlist.name}")
            .setMultiChoiceItems(songItems, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Add") { _, _ ->
                val selectedSongs = allSongs.filterIndexed { index, _ -> checkedItems[index] }
                val addedCount = viewModel.addSongsToPlaylist(playlistId, selectedSongs)
                if (addedCount > 0) {
                    showToast("Added $addedCount songs")
                } else {
                    showToast("No new songs added")
                }
            }
            .setNegativeButton("Cancel", null)
            .showStyledDialog()
    }

    private fun showRemoveSongsFromPlaylistDialog(playlistId: Long) {
        val playlist = playlists.find { it.id == playlistId } ?: return
        val playlistSongs = viewModel.getPlaylistSongs(playlistId)
        if (playlistSongs.isEmpty()) {
            showToast("Playlist is already empty")
            return
        }

        val songItems = playlistSongs.map { "${it.title} - ${it.artist}" }.toTypedArray()
        val checkedItems = BooleanArray(playlistSongs.size)

        dialogBuilder()
            .setTitle("Remove songs from ${playlist.name}")
            .setMultiChoiceItems(songItems, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Remove") { _, _ ->
                val selectedPaths = playlistSongs
                    .filterIndexed { index, _ -> checkedItems[index] }
                    .map { it.path }
                    .toSet()
                val removedCount = viewModel.removeSongsFromPlaylist(playlistId, selectedPaths)
                if (removedCount > 0) {
                    showToast("Removed $removedCount songs")
                } else {
                    showToast("No songs removed")
                }
            }
            .setNegativeButton("Cancel", null)
            .showStyledDialog()
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
}
