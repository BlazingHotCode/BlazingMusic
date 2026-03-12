package com.blazinghotcode.blazingmusic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.appcompat.widget.PopupMenu
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import android.os.Handler
import android.os.Looper
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Main app shell that hosts Home + Playlists sections, mini-player controls,
 * queue bottom sheet, permission flow, and service/controller wiring.
 */
class MainActivity : AppCompatActivity() {
    private enum class BottomTab { HOME, SEARCH, PLAYLISTS }

    companion object {
        const val ACTION_PLAY_PENDING_QUEUE = "com.blazinghotcode.blazingmusic.action.PLAY_PENDING_QUEUE"
        private const val SORT_PREFS_NAME = "blazing_music_sort_prefs"
        private const val KEY_HOME_SORT = "home_sort"
        private const val SEARCH_DEBOUNCE_MS = 220L
        private const val APP_PREFS_NAME = "blazing_music_app_prefs"
        private const val KEY_AUDIO_PERMISSION_REQUESTED = "audio_permission_requested"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val HOME_BROWSE_ID = "FEmusic_home"
        private const val HOME_MAX_RESULTS = 160
        private const val MENU_PLAY_NOW = 2001
        private const val MENU_PLAY_NEXT = 2002
        private const val MENU_ADD_QUEUE = 2003
        private const val MENU_OPEN_PAGE = 2004
    }

    private val viewModel: MusicViewModel by viewModels()
    private val youTubeApiClient by lazy { YouTubeApiClient(applicationContext) }
    private lateinit var songAdapter: SongAdapter
    private lateinit var homeFeedAdapter: YouTubeBrowseAdapter

    private lateinit var rvSongs: RecyclerView
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
    private lateinit var bottomNav: View
    private lateinit var navHome: View
    private lateinit var navSearch: View
    private lateinit var navPlaylists: View
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSortSongs: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var homeStateContainer: View
    private lateinit var tvHomeStateTitle: TextView
    private lateinit var tvHomeStateMessage: TextView
    private lateinit var btnHomeStateAction: Button
    private lateinit var tvHomeTitle: TextView
    private lateinit var homeDiscoveryCard: View
    private lateinit var chipGroupHomeRecentSearches: ChipGroup
    private lateinit var btnHomeExploreOnline: Button
    private lateinit var songAlphabetIndex: AlphabetIndexView
    private lateinit var songScrollTrack: View
    private lateinit var songScrollThumb: View
    private var allSongs: List<Song> = emptyList()
    private var playlists: List<Playlist> = emptyList()
    private var queueSongs: List<Song> = emptyList()
    private var queueCurrentIndex: Int = -1
    private var isCurrentlyPlaying = false
    private var shouldRestartQueue = false
    private lateinit var playlistContainer: View
    private lateinit var youtubeContainer: View
    private var queueDialog: BottomSheetDialog? = null
    private var queueSheetBehavior: BottomSheetBehavior<FrameLayout>? = null
    private var queueEditorAdapter: QueueEditorAdapter? = null
    private var isQueueDragInProgress = false
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var currentSongSort = SongSortOption.TITLE
    private var currentBottomTab: BottomTab = BottomTab.HOME
    private var hasAudioPermission = false
    private var hasNotificationPermission = true
    private var homeFeedJob: Job? = null
    private var homeFeedItems: List<YouTubeVideo> = emptyList()
    private var homeFeedErrorMessage: String? = null
    private var isHomeFeedLoading = false
    private var homeFeedLastUpdatedLabel: String? = null
    private val sortPrefs by lazy {
        getSharedPreferences(SORT_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val appPrefs by lazy {
        getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null
    private var isSongScrollDragging = false
    private val updateSeekbarRunnable = object : Runnable {
        override fun run() {
            val position = viewModel.getCurrentPosition()
            seekBar.progress = position.toInt()
            tvCurrentTime.text = PlaybackTimeFormatter.formatDuration(position)
            handler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            hasAudioPermission = true
            hasNotificationPermission = isNotificationPermissionGranted()
            if (hasNotificationPermission) {
                viewModel.loadSongs()
            } else {
                requestNotificationPermissionIfNeeded()
            }
        } else {
            hasAudioPermission = false
            appPrefs.edit().putBoolean(KEY_AUDIO_PERMISSION_REQUESTED, true).apply()
        }
        updateHomeLibraryStateUi()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appPrefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        }
        if (hasAudioPermission && hasNotificationPermission) {
            viewModel.loadSongs()
        } else if (!isGranted) {
            showToast("Notification permission is required")
        }
        updateHomeLibraryStateUi()
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
        setupBackNavigation()
        checkPermissions()
        handlePlaybackActionIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            // Controller ready
        }, MoreExecutors.directExecutor())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlaybackActionIntent(intent)
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
        btnQueue = findViewById(R.id.btnQueue)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        bottomNav = findViewById(R.id.bottomNav)
        navHome = findViewById(R.id.navHome)
        navSearch = findViewById(R.id.navSearch)
        navPlaylists = findViewById(R.id.navPlaylists)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        etSearch = findViewById(R.id.etSearch)
        btnSortSongs = findViewById(R.id.btnSortSongs)
        btnSettings = findViewById(R.id.btnSettings)
        homeStateContainer = findViewById(R.id.homeStateContainer)
        tvHomeStateTitle = findViewById(R.id.tvHomeStateTitle)
        tvHomeStateMessage = findViewById(R.id.tvHomeStateMessage)
        btnHomeStateAction = findViewById(R.id.btnHomeStateAction)
        tvHomeTitle = findViewById(R.id.tvTitle)
        homeDiscoveryCard = findViewById(R.id.homeDiscoveryCard)
        chipGroupHomeRecentSearches = findViewById(R.id.chipGroupHomeRecentSearches)
        btnHomeExploreOnline = findViewById(R.id.btnHomeExploreOnline)
        songAlphabetIndex = findViewById(R.id.songAlphabetIndex)
        songScrollTrack = findViewById(R.id.songScrollTrack)
        songScrollThumb = findViewById(R.id.songScrollThumb)
        playlistContainer = findViewById(R.id.playlistContainer)
        youtubeContainer = findViewById(R.id.youtubeContainer)
        currentSongSort = loadHomeSort()
        btnHomeStateAction.setOnClickListener { onHomeStateActionClicked() }
        btnHomeExploreOnline.setOnClickListener { openSearchTab() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        setupDebugAnalyticsViewer()
        refreshHomeDiscovery()
        tintSearchStartIcon()
        applySystemInsets()
    }

    private fun setupDebugAnalyticsViewer() {
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return
        tvHomeTitle.setOnLongClickListener {
            showPlaybackAnalyticsDebugDialog()
            true
        }
    }

    private fun showPlaybackAnalyticsDebugDialog() {
        val events = PlaybackAnalyticsLogger.recentEventsSnapshot()
        val message = if (events.isEmpty()) {
            "No playback analytics events yet.\n\n" +
                "Trigger play/pause/next/previous or wait for a track transition."
        } else {
            events.joinToString(separator = "\n\n")
        }
        dialogBuilder()
            .setTitle("Playback analytics (last 20)")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .showStyledDialog()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song ->
                if (viewModel.isShuffleEnabled.value == true) {
                    viewModel.toggleShuffle()
                }
                val currentOrderedSongs = songAdapter.currentList.toList()
                val queueSource = if (currentOrderedSongs.isNotEmpty()) currentOrderedSongs else allSongs
                viewModel.playSongFromQueue(song, queueSource)
            },
            onSongMenuClick = { song, anchor ->
                showSongOptionsMenu(song, anchor)
            },
            onSelectionStateChanged = { isSelectionMode, selectedCount ->
                updateHomeSelectionUi(isSelectionMode, selectedCount)
            }
        )
        homeFeedAdapter = YouTubeBrowseAdapter(
            onItemClick = { item -> onHomeFeedItemClicked(item) },
            onItemMenuClick = { item, anchor -> showHomeFeedItemMenu(item, anchor) },
            onPlayAllClick = { _ -> },
            onStartRadioClick = { },
            onArtistOptionsClick = { },
            onSectionSeeAllClick = { sectionTitle, browseId, browseParams ->
                openYouTubeBrowse(
                    YouTubeVideo(
                        id = "home_section_$browseId",
                        title = sectionTitle,
                        channelTitle = "",
                        thumbnailUrl = null,
                        browseId = browseId,
                        browseParams = browseParams,
                        type = when {
                            sectionTitle.contains("album", ignoreCase = true) -> YouTubeItemType.ALBUM
                            sectionTitle.contains("artist", ignoreCase = true) -> YouTubeItemType.ARTIST
                            sectionTitle.contains("playlist", ignoreCase = true) -> YouTubeItemType.PLAYLIST
                            else -> YouTubeItemType.SONG
                        }
                    )
                )
            }
        )
        rvSongs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = homeFeedAdapter
        }
        setupSongScrollThumb()
        songAlphabetIndex.setOnSectionSelectedListener { section ->
            scrollSongsToSection(section)
        }
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

        MiniPlayerExpandGestureController(
            playerLayout = playerLayout,
            toPx = { value -> value * resources.displayMetrics.density },
            onOpen = { showFullScreenPlayer() }
        ).attach()
    }

    private fun handlePlaybackActionIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_PLAY_PENDING_QUEUE -> {
                val pending = PendingPlaybackStore.consume()
                if (pending != null) {
                    val queue = pending.first
                    val startIndex = pending.second.coerceIn(0, queue.lastIndex)
                    val song = queue[startIndex]
                    viewModel.playSongFromQueue(song, queue)
                } else {
                    showToast("No pending playback item")
                }
                intent.action = null
            }
            PlaybackNotificationManager.ACTION_PREVIOUS,
            PlaybackNotificationManager.ACTION_PLAY_PAUSE,
            PlaybackNotificationManager.ACTION_NEXT,
            PlaybackNotificationManager.ACTION_SEEK_BACK,
            PlaybackNotificationManager.ACTION_SEEK_FORWARD -> {
                viewModel.handleExternalPlaybackAction(action)
                intent.action = null
            }
        }
    }

    fun playTemporaryQueue(queue: List<Song>, startIndex: Int) {
        if (queue.isEmpty()) return
        val resolvedIndex = startIndex.coerceIn(0, queue.lastIndex)
        viewModel.playSongFromQueue(queue[resolvedIndex], queue)
    }

    fun appendSongsToCurrentQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        songs.forEach { viewModel.addSongToQueue(it) }
    }

    fun addSongToCurrentQueue(song: Song) {
        viewModel.addSongToQueue(song)
    }

    fun addSongToPlayNext(song: Song) {
        viewModel.addSongToPlayNext(song)
    }

    fun replaceUpcomingQueue(songs: List<Song>) {
        viewModel.replaceUpcomingQueue(songs)
    }

    private fun setupPlaylistControls() {
        navHome.setOnClickListener { openHomeTab() }
        navSearch.setOnClickListener { openSearchTab() }
        navPlaylists.setOnClickListener { openPlaylistsTab() }
        btnSortSongs.setOnClickListener {
            if (songAdapter.isSelectionMode()) {
                showHomeSelectionActions()
            } else {
                showSongSortOptions()
            }
        }
        updateSortButtonLabel()
    }

    private fun observeViewModel() {
        viewModel.songs.observe(this) { songs ->
            allSongs = songs
            applySongListPresentation()
        }

        viewModel.libraryLoadState.observe(this) {
            updateHomeLibraryStateUi()
        }

        viewModel.currentSong.observe(this) { song ->
            songAdapter.setCurrentSong(song)
            song?.let {
                playerLayout.visibility = View.VISIBLE
                MiniPlayerSongUi.bindSong(tvSongTitle, tvArtist, ivAlbumArt, it)
            }
        }

        viewModel.isPlaying.observe(this) { isPlaying ->
            isCurrentlyPlaying = isPlaying
            updatePrimaryControlButton()
        }

        viewModel.duration.observe(this) { duration ->
            seekBar.max = duration.toInt()
            tvTotalTime.text = PlaybackTimeFormatter.formatDuration(duration)
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

    fun showQueueSheet() {
        showQueueDialog()
    }

    fun beginQueueSheetDrag() {
        if (queueSongs.isEmpty()) return
        showQueueDialog()
        val behavior = queueSheetBehavior ?: return
        behavior.isFitToContents = false
        behavior.skipCollapsed = false
        behavior.isHideable = true
        behavior.peekHeight = dp(120)
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    fun updateQueueSheetDrag(dragDistancePx: Float) {
        val behavior = queueSheetBehavior ?: return
        val minPeek = dp(120)
        val maxPeek = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        val targetPeek = (minPeek + dragDistancePx.toInt()).coerceIn(minPeek, maxPeek)
        behavior.peekHeight = targetPeek
        if (behavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    fun endQueueSheetDrag(shouldOpen: Boolean) {
        val behavior = queueSheetBehavior ?: return
        if (shouldOpen) {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        } else {
            queueDialog?.dismiss()
        }
    }

    fun showFullScreenPlayer() {
        if (viewModel.currentSong.value == null) return
        if (supportFragmentManager.findFragmentByTag(FullPlayerDialogFragment.TAG) != null) return
        FullPlayerDialogFragment().show(supportFragmentManager, FullPlayerDialogFragment.TAG)
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
        if (queueDialog?.isShowing == true) {
            queueEditorAdapter?.submitQueue(queueSongs, queueCurrentIndex)
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
            setOnShowListener { dialog ->
                val sheet = (dialog as BottomSheetDialog)
                    .findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                queueSheetBehavior = sheet?.let { BottomSheetBehavior.from(it) }
                sheet?.let { bottomSheet ->
                    attachQueueFastCloseGesture(bottomSheet, root, title)
                }
            }
            show()
        }
        queueDialog?.setOnDismissListener {
            queueEditorAdapter = null
            queueDialog = null
            queueSheetBehavior = null
        }
    }

    private fun attachQueueFastCloseGesture(
        bottomSheet: FrameLayout,
        root: View,
        title: TextView
    ) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val deltaY = e2.rawY - start.rawY
                val verticalDominant = kotlin.math.abs(deltaY) > kotlin.math.abs(e2.rawX - start.rawX)
                val fastCloseDistance = dp(12)
                val fastCloseVelocity = dp(320).toFloat()
                val shouldFastClose = verticalDominant &&
                    deltaY > fastCloseDistance &&
                    velocityY > fastCloseVelocity
                if (shouldFastClose) {
                    queueDialog?.dismiss()
                    return true
                }
                return false
            }
        })

        val listener = View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }

        bottomSheet.setOnTouchListener(listener)
        root.setOnTouchListener(listener)
        title.setOnTouchListener(listener)
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
            menu.add(Menu.NONE, 4, Menu.NONE, "Select multiple")
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

    private fun showHomeSelectionActions() {
        val selectedSongs = songAdapter.getSelectedSongs()
        if (selectedSongs.isEmpty()) {
            showToast("No songs selected")
            songAdapter.exitSelectionMode()
            return
        }
        PopupMenu(ContextThemeWrapper(this, R.style.ThemeOverlay_BlazingMusic_PopupMenu), btnSortSongs).apply {
            menu.add(Menu.NONE, 1, Menu.NONE, "Add to queue")
            menu.add(Menu.NONE, 2, Menu.NONE, "Play next")
            menu.add(Menu.NONE, 3, Menu.NONE, "Add to playlist")
            menu.add(Menu.NONE, 4, Menu.NONE, "Cancel selection")
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
                        songAdapter.exitSelectionMode()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showAddMultipleSongsToPlaylistDialog(selectedSongs: List<Song>) {
        if (playlists.isEmpty()) {
            showToast("No playlists yet")
            return
        }
        showSimpleBottomSheet(
            title = "Add ${selectedSongs.size} songs to playlist",
            items = playlists.map { it.name }
        ) { index ->
            val playlist = playlists.getOrNull(index) ?: return@showSimpleBottomSheet
            val addedCount = viewModel.addSongsToPlaylist(playlist.id, selectedSongs)
            if (addedCount > 0) {
                showToast("Added $addedCount songs to ${playlist.name}")
            } else {
                showToast("No new songs added")
            }
        }
    }

    private fun updateHomeSelectionUi(isSelectionMode: Boolean, selectedCount: Int) {
        if (!isSelectionMode) {
            updateSortButtonLabel()
            return
        }
        btnSortSongs.text = if (selectedCount > 0) {
            "Actions ($selectedCount)"
        } else {
            "Actions"
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
        val actions = if (playlist.isLocalMusicSystemPlaylist()) {
            arrayOf("View songs")
        } else {
            arrayOf("View songs", "Add songs", "Rename", "Delete")
        }
        showSimpleBottomSheet(
            title = playlist.name,
            items = actions.toList(),
            secondaryLabel = "Back",
            onSecondaryClick = { showPlaylistBrowserDialog() }
        ) { index ->
            when (index) {
                0 -> showPlaylistSongsDialog(playlist.id)
                1 -> if (!playlist.isLocalMusicSystemPlaylist()) showAddSongsToPlaylistDialog(playlist.id)
                2 -> if (!playlist.isLocalMusicSystemPlaylist()) showRenamePlaylistDialog(playlist)
                3 -> if (!playlist.isLocalMusicSystemPlaylist()) showDeletePlaylistDialog(playlist)
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
        if (playlist.isLocalMusicSystemPlaylist()) {
            showToast("Local music playlist cannot be renamed")
            return
        }
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
        if (playlist.isLocalMusicSystemPlaylist()) {
            showToast("Local music playlist cannot be deleted")
            return
        }
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
        PlaybackControlUi.bindShuffleControl(
            context = this,
            button = btnShuffle,
            isEnabled = isEnabled,
            labels = PlaybackControlUi.ToggleLabels(
                off = "Shuffle off",
                on = "Shuffle on"
            )
        )
    }

    private fun updateRepeatUi(mode: Int) {
        PlaybackControlUi.bindRepeatControl(
            context = this,
            button = btnRepeat,
            mode = mode,
            labels = PlaybackControlUi.RepeatLabels(
                off = "Repeat off",
                repeatAll = "Repeat all",
                repeatOne = "Repeat one"
            )
        )
    }

    private fun updatePrimaryControlButton() {
        PlaybackControlUi.bindPrimaryControl(
            button = btnPlayPause,
            isPlaying = isCurrentlyPlaying,
            shouldRestartQueue = shouldRestartQueue,
            labels = PlaybackControlUi.PrimaryControlLabels(
                play = "Play",
                pause = "Pause",
                restart = "Restart queue"
            )
        )
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDebounceRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable { applySongListPresentation() }
                searchDebounceRunnable = runnable
                handler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun showSongSortOptions() {
        val options = SongSortOption.entries
        showSimpleBottomSheet(
            title = "Sort songs",
            items = options.map { it.label }
        ) { index ->
            val selected = options.getOrNull(index) ?: return@showSimpleBottomSheet
            currentSongSort = selected
            persistHomeSort(selected)
            updateSortButtonLabel()
            applySongListPresentation(preserveScrollOffset = true)
        }
    }

    private fun persistHomeSort(option: SongSortOption) {
        sortPrefs.edit().putString(KEY_HOME_SORT, option.name).apply()
    }

    private fun loadHomeSort(): SongSortOption {
        val raw = sortPrefs.getString(KEY_HOME_SORT, SongSortOption.TITLE.name)
        return SongSortOption.entries.firstOrNull { it.name == raw } ?: SongSortOption.TITLE
    }

    private fun updateSortButtonLabel() {
        btnSortSongs.text = currentSongSort.label
    }

    private fun applySongListPresentation(preserveScrollOffset: Boolean = false) {
        val previousOffset = if (preserveScrollOffset) rvSongs.computeVerticalScrollOffset() else 0
        val query = etSearch.text?.toString().orEmpty()
        val sorted = SongListPresentationUi.applySearchAndSort(
            songs = allSongs,
            query = query,
            sortMode = currentSortMode()
        )
        SongListIndexUi.updateAvailableSections(
            alphabetIndex = songAlphabetIndex,
            songs = sorted,
            isEnabled = supportsAlphabetIndexForCurrentSort(),
            sectionForSong = { sectionFromCurrentSort(it) }
        )
        if (preserveScrollOffset) {
            songAdapter.submitList(sorted) {
                rvSongs.post {
                    val currentOffset = rvSongs.computeVerticalScrollOffset()
                    rvSongs.scrollBy(0, previousOffset - currentOffset)
                }
                refreshSongAlphabetIndexVisibility()
            }
        } else {
            songAdapter.submitList(sorted) {
                refreshSongAlphabetIndexVisibility()
            }
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
            SongSortOption.TITLE -> SongListPresentationUi.SortMode.TITLE
            SongSortOption.ARTIST -> SongListPresentationUi.SortMode.ARTIST
            SongSortOption.ALBUM -> SongListPresentationUi.SortMode.ALBUM
            SongSortOption.DURATION -> SongListPresentationUi.SortMode.DURATION
            SongSortOption.RECENTLY_ADDED -> SongListPresentationUi.SortMode.RECENTLY_ADDED
        }
    }

    private fun refreshSongAlphabetIndexVisibility() {
        songAlphabetIndex.visibility = View.GONE
        songScrollTrack.visibility = View.GONE
        songScrollThumb.visibility = View.GONE
    }

    private fun updateHomeLibraryStateUi() {
        val isOnHomeTab = currentBottomTab == BottomTab.HOME
        if (!isOnHomeTab) {
            homeStateContainer.visibility = View.GONE
            rvSongs.visibility = View.GONE
            etSearch.visibility = View.GONE
            btnSortSongs.visibility = View.GONE
            songAlphabetIndex.visibility = View.GONE
            songScrollTrack.visibility = View.GONE
            songScrollThumb.visibility = View.GONE
            return
        }

        etSearch.visibility = View.GONE
        btnSortSongs.visibility = View.GONE
        songAlphabetIndex.visibility = View.GONE
        songScrollTrack.visibility = View.GONE
        songScrollThumb.visibility = View.GONE

        if (homeFeedItems.isNotEmpty()) {
            homeStateContainer.visibility = View.GONE
            rvSongs.visibility = View.VISIBLE
            bindHomeFeedHeader()
            homeFeedAdapter.submit(homeFeedItems)
            refreshHomeDiscovery()
            return
        }

        if (isHomeFeedLoading) {
            showHomeState(
                title = "Loading home",
                message = "Fetching your YouTube Music shelves...",
                actionLabel = "Refresh"
            )
            return
        }

        if (!homeFeedErrorMessage.isNullOrBlank()) {
            showHomeState(
                title = "Could not load home",
                message = homeFeedErrorMessage!!,
                actionLabel = "Retry"
            )
            return
        }

        showHomeState(
            title = "Welcome back",
            message = "Tap refresh to load a Metrolist-style online home feed.",
            actionLabel = "Refresh"
        )
        if (!isHomeFeedLoading) {
            refreshHomeFeed(forceRefresh = false)
        }
    }

    private fun showHomeState(title: String, message: String, actionLabel: String) {
        homeStateContainer.visibility = View.VISIBLE
        rvSongs.visibility = View.GONE
        etSearch.visibility = View.GONE
        btnSortSongs.visibility = View.GONE
        songAlphabetIndex.visibility = View.GONE
        songScrollTrack.visibility = View.GONE
        songScrollThumb.visibility = View.GONE
        tvHomeStateTitle.text = title
        tvHomeStateMessage.text = message
        btnHomeStateAction.text = actionLabel
    }

    private fun onHomeStateActionClicked() {
        refreshHomeFeed(forceRefresh = true)
    }

    private fun requestAudioPermissionFromState() {
        val permission = audioReadPermission()
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            hasAudioPermission = true
            viewModel.loadSongs()
            updateHomeLibraryStateUi()
            return
        }

        val requestedBefore = appPrefs.getBoolean(KEY_AUDIO_PERMISSION_REQUESTED, false)
        val shouldShowRationale = shouldShowRequestPermissionRationale(permission)
        if (!requestedBefore || shouldShowRationale) {
            appPrefs.edit().putBoolean(KEY_AUDIO_PERMISSION_REQUESTED, true).apply()
            permissionLauncher.launch(permission)
        } else {
            showToast("Enable audio permission in App settings")
            openAppPermissionSettings()
        }
    }

    private fun openAppPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun requestNotificationPermissionFromState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = true
            viewModel.loadSongs()
            updateHomeLibraryStateUi()
            return
        }
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            hasNotificationPermission = true
            viewModel.loadSongs()
            updateHomeLibraryStateUi()
            return
        }

        val requestedBefore = appPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        val shouldShowRationale = shouldShowRequestPermissionRationale(permission)
        if (!requestedBefore || shouldShowRationale) {
            appPrefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
            notificationPermissionLauncher.launch(permission)
        } else {
            showToast("Enable notification permission in App settings")
            openAppPermissionSettings()
        }
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

    private fun audioReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun isNotificationPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissions() {
        val permission = audioReadPermission()

        when {
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED -> {
                hasAudioPermission = true
                hasNotificationPermission = isNotificationPermissionGranted()
                if (hasNotificationPermission) {
                    viewModel.loadSongs()
                } else {
                    requestNotificationPermissionIfNeeded()
                }
                updateHomeLibraryStateUi()
            }
            else -> {
                hasAudioPermission = false
                hasNotificationPermission = isNotificationPermissionGranted()
                updateHomeLibraryStateUi()
                appPrefs.edit().putBoolean(KEY_AUDIO_PERMISSION_REQUESTED, true).apply()
                permissionLauncher.launch(permission)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            hasNotificationPermission = true
            return
        }
        hasNotificationPermission = false
        appPrefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPlaybackSettingsFromStorage()
        hasAudioPermission = ContextCompat.checkSelfPermission(this, audioReadPermission()) ==
            PackageManager.PERMISSION_GRANTED
        hasNotificationPermission = isNotificationPermissionGranted()
        if (hasAudioPermission && hasNotificationPermission && allSongs.isEmpty()) {
            viewModel.loadSongs()
        }
        updateHomeLibraryStateUi()
        refreshHomeDiscovery()
        handler.post(updateSeekbarRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateSeekbarRunnable)
        viewModel.persistPlaybackPosition()
    }

    override fun onDestroy() {
        searchDebounceRunnable?.let { handler.removeCallbacks(it) }
        searchDebounceRunnable = null
        super.onDestroy()
    }

    private fun dialogBuilder(): AlertDialog.Builder {
        return AlertDialog.Builder(this)
    }

    private fun openPlaylists() {
        openPlaylistsTab()
    }

    fun openPlaylistsTab() {
        val previousTab = currentBottomTab
        val current = supportFragmentManager.findFragmentById(R.id.playlistContainer)
        if (previousTab == BottomTab.PLAYLISTS && playlistContainer.visibility == View.VISIBLE && current is PlaylistsFragment) return

        if (playlistContainer.visibility != View.VISIBLE) {
            playlistContainer.visibility = View.VISIBLE
            supportFragmentManager.beginTransaction()
                .replace(R.id.playlistContainer, PlaylistsFragment())
                .commit()
            animateContainerIn(playlistContainer, fromRight = isForwardTabMove(previousTab, BottomTab.PLAYLISTS))
        } else if (supportFragmentManager.backStackEntryCount > 0) {
            // If user is on playlist detail, go back to playlist root with back stack animation.
            supportFragmentManager.popBackStack()
        } else {
            supportFragmentManager.beginTransaction()
                .replace(R.id.playlistContainer, PlaylistsFragment())
                .commit()
        }
        if (youtubeContainer.visibility == View.VISIBLE) {
            animateContainerOut(youtubeContainer, toRight = !isForwardTabMove(previousTab, BottomTab.PLAYLISTS))
        }
        currentBottomTab = BottomTab.PLAYLISTS
        updateBottomNavSelection(BottomTab.PLAYLISTS)
        refreshSongAlphabetIndexVisibility()
        updateHomeLibraryStateUi()
    }

    fun openPlaylistSongs(playlistId: Long, playlistName: String) {
        if (youtubeContainer.visibility == View.VISIBLE) {
            youtubeContainer.visibility = View.GONE
        }
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
        currentBottomTab = BottomTab.PLAYLISTS
        updateBottomNavSelection(BottomTab.PLAYLISTS)
    }

    fun openHomeTab() {
        val previousTab = currentBottomTab
        if (playlistContainer.visibility != View.VISIBLE && youtubeContainer.visibility != View.VISIBLE) {
            currentBottomTab = BottomTab.HOME
            updateBottomNavSelection(BottomTab.HOME)
            refreshSongAlphabetIndexVisibility()
            updateHomeLibraryStateUi()
            refreshHomeDiscovery()
            return
        }
        if (youtubeContainer.visibility == View.VISIBLE) {
            animateContainerOut(youtubeContainer, toRight = !isForwardTabMove(previousTab, BottomTab.HOME))
        }
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        if (playlistContainer.visibility == View.VISIBLE) {
            animateContainerOut(playlistContainer, toRight = !isForwardTabMove(previousTab, BottomTab.HOME)) {
                refreshSongAlphabetIndexVisibility()
                updateHomeLibraryStateUi()
            }
        } else {
            refreshSongAlphabetIndexVisibility()
            updateHomeLibraryStateUi()
        }
        currentBottomTab = BottomTab.HOME
        updateBottomNavSelection(BottomTab.HOME)
        refreshHomeDiscovery()
    }

    fun refreshHomeDiscovery() {
        val recentQueries = YouTubeSearchHistoryStore.load(this)
        chipGroupHomeRecentSearches.removeAllViews()
        recentQueries.forEach { query ->
            val chip = Chip(this).apply {
                text = query
                isCheckable = false
                isClickable = true
                setCheckedIconVisible(false)
                setEnsureMinTouchTargetSize(false)
                setOnClickListener { openSearchTab(query) }
            }
            chipGroupHomeRecentSearches.addView(chip)
        }
        chipGroupHomeRecentSearches.visibility = if (recentQueries.isEmpty()) View.GONE else View.VISIBLE
        homeDiscoveryCard.visibility = if (currentBottomTab == BottomTab.HOME) View.VISIBLE else View.GONE
    }

    private fun refreshHomeFeed(forceRefresh: Boolean) {
        if (!forceRefresh && (isHomeFeedLoading || homeFeedItems.isNotEmpty())) {
            updateHomeLibraryStateUi()
            return
        }
        homeFeedJob?.cancel()
        isHomeFeedLoading = true
        homeFeedErrorMessage = null
        if (forceRefresh) {
            homeFeedItems = emptyList()
        }
        updateHomeLibraryStateUi()
        homeFeedJob = lifecycleScope.launch {
            val result = runCatching {
                youTubeApiClient.browseCollection(
                    browseId = HOME_BROWSE_ID,
                    maxResults = HOME_MAX_RESULTS
                )
            }
            isHomeFeedLoading = false
            homeFeedItems = result.getOrDefault(emptyList())
                .filterNot { it.title.isBlank() }
                .filterNot { item ->
                    val section = item.sectionTitle.orEmpty().lowercase()
                    section.contains("community") || section.contains("podcast")
                }
            homeFeedLastUpdatedLabel = if (homeFeedItems.isNotEmpty()) {
                "Updated ${currentHomeRefreshLabel()}"
            } else {
                null
            }
            homeFeedErrorMessage = if (homeFeedItems.isEmpty()) {
                result.exceptionOrNull()?.message ?: "No home sections are available right now."
            } else {
                null
            }
            updateHomeLibraryStateUi()
        }
    }

    private fun bindHomeFeedHeader() {
        val subtitle = buildString {
            append("Online shelves")
            homeFeedLastUpdatedLabel?.let {
                append(" • ")
                append(it)
            }
        }
        homeFeedAdapter.setHeader(
            YouTubeBrowseAdapter.HeaderModel(
                browseType = YouTubeItemType.UNKNOWN,
                title = greetingTitle(),
                subtitle = subtitle,
                artworkUrl = homeFeedItems.firstNotNullOfOrNull { it.thumbnailUrl },
                stateMessage = "Quick picks, albums, playlists, and mixes from your online home.",
                artistInfo = null,
                showArtistDescription = false,
                showArtistSubscribers = false,
                showArtistMonthlyListeners = false,
                canPlay = false,
                canShuffle = false
            )
        )
    }

    private fun greetingTitle(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private fun currentHomeRefreshLabel(): String {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR)
            .let { if (it == 0) 12 else it }
        val minute = now.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        val suffix = if (now.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        return "$hour:$minute $suffix"
    }

    private fun onHomeFeedItemClicked(item: YouTubeVideo) {
        when {
            !item.videoId.isNullOrBlank() -> playHomeFeedItem(item)
            !item.browseId.isNullOrBlank() -> openYouTubeBrowse(item)
            else -> showToast("This item cannot be opened yet")
        }
    }

    private fun showHomeFeedItemMenu(item: YouTubeVideo, anchor: View) {
        val popup = PopupMenu(ContextThemeWrapper(this, R.style.ThemeOverlay_BlazingMusic_PopupMenu), anchor)
        when (item.type) {
            YouTubeItemType.SONG -> {
                popup.menu.add(0, MENU_PLAY_NOW, 0, "Play now")
                popup.menu.add(0, MENU_PLAY_NEXT, 1, "Play next")
                popup.menu.add(0, MENU_ADD_QUEUE, 2, "Add to queue")
            }
            YouTubeItemType.ALBUM, YouTubeItemType.ARTIST, YouTubeItemType.PLAYLIST -> {
                popup.menu.add(0, MENU_OPEN_PAGE, 0, "Open")
            }
            else -> return
        }
        popup.setOnMenuItemClickListener { menu ->
            when (menu.itemId) {
                MENU_PLAY_NOW -> {
                    playHomeFeedItem(item)
                    true
                }
                MENU_PLAY_NEXT -> {
                    queueHomeFeedItem(item, playNext = true)
                    true
                }
                MENU_ADD_QUEUE -> {
                    queueHomeFeedItem(item, playNext = false)
                    true
                }
                MENU_OPEN_PAGE -> {
                    if (!item.browseId.isNullOrBlank()) openYouTubeBrowse(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun playHomeFeedItem(item: YouTubeVideo) {
        val videoId = item.videoId ?: return
        lifecycleScope.launch {
            showToast("Resolving ${item.title}")
            val streamUrl = runCatching { youTubeApiClient.resolveAudioStreamUrl(videoId) }.getOrNull()
            if (streamUrl.isNullOrBlank()) {
                showToast("Could not start playback")
                return@launch
            }
            val playableSong = item.toSong(streamUrl)
            playTemporaryQueue(listOf(playableSong), 0)
        }
    }

    private fun queueHomeFeedItem(item: YouTubeVideo, playNext: Boolean) {
        val videoId = item.videoId ?: return
        lifecycleScope.launch {
            val streamUrl = runCatching { youTubeApiClient.resolveAudioStreamUrl(videoId) }.getOrNull()
            if (streamUrl.isNullOrBlank()) {
                showToast("Could not resolve this song")
                return@launch
            }
            val song = item.toSong(streamUrl)
            if (playNext) {
                addSongToPlayNext(song)
                showToast("Added to play next")
            } else {
                addSongToCurrentQueue(song)
                showToast("Added to queue")
            }
        }
    }

    private fun YouTubeVideo.toSong(streamUrl: String): Song {
        val stableId = (id.hashCode().toLong() and 0x7fffffffL) + 10_000_000_000L
        return Song(
            id = stableId,
            title = title,
            artist = channelTitle.ifBlank { "YouTube Music" },
            album = sectionTitle ?: "YouTube",
            duration = 0L,
            dateAddedSeconds = System.currentTimeMillis() / 1000,
            path = streamUrl,
            albumArtUri = YouTubeThumbnailUtils.toPlaybackArtworkUrl(thumbnailUrl, videoId),
            sourceVideoId = videoId,
            sourcePlaylistId = sourcePlaylistId,
            sourcePlaylistSetVideoId = sourcePlaylistSetVideoId,
            sourceParams = sourceParams,
            sourceIndex = sourceIndex
        )
    }

    fun openSearchTab(initialQuery: String? = null) {
        val previousTab = currentBottomTab
        if (previousTab == BottomTab.SEARCH && youtubeContainer.visibility == View.VISIBLE && initialQuery.isNullOrBlank()) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate(
                    null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
                )
                supportFragmentManager.beginTransaction()
                    .replace(R.id.youtubeContainer, YouTubeSearchFragment.newInstance())
                    .commit()
            }
            return
        }
        if (playlistContainer.visibility == View.VISIBLE) {
            supportFragmentManager.popBackStackImmediate(
                null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
            animateContainerOut(playlistContainer, toRight = !isForwardTabMove(previousTab, BottomTab.SEARCH))
        }
        youtubeContainer.visibility = View.VISIBLE
        val current = supportFragmentManager.findFragmentById(R.id.youtubeContainer)
        if (current !is YouTubeSearchFragment || !initialQuery.isNullOrBlank()) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.youtubeContainer, YouTubeSearchFragment.newInstance(initialQuery))
                .commit()
        }
        animateContainerIn(youtubeContainer, fromRight = isForwardTabMove(previousTab, BottomTab.SEARCH))
        currentBottomTab = BottomTab.SEARCH
        updateBottomNavSelection(BottomTab.SEARCH)
        refreshSongAlphabetIndexVisibility()
        updateHomeLibraryStateUi()
        refreshHomeDiscovery()
    }

    fun openYouTubeBrowse(item: YouTubeVideo) {
        val browseId = item.browseId ?: return
        youtubeContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            .replace(
                R.id.youtubeContainer,
                YouTubeBrowseFragment.newInstance(
                    browseId = browseId,
                    browseParams = item.browseParams,
                    title = item.title,
                    subtitle = item.channelTitle,
                    thumbUrl = item.thumbnailUrl,
                    type = item.type
                )
            )
            .addToBackStack("youtube_browse")
            .commit()
        currentBottomTab = BottomTab.SEARCH
        updateBottomNavSelection(BottomTab.SEARCH)
    }

    private fun isForwardTabMove(from: BottomTab, to: BottomTab): Boolean {
        return tabIndex(to) > tabIndex(from)
    }

    private fun tabIndex(tab: BottomTab): Int {
        return when (tab) {
            BottomTab.HOME -> 0
            BottomTab.SEARCH -> 1
            BottomTab.PLAYLISTS -> 2
        }
    }

    private fun animateContainerIn(container: View, fromRight: Boolean) {
        container.animate().cancel()
        container.visibility = View.VISIBLE
        container.post {
            val distance = container.width.toFloat().takeIf { it > 0f } ?: 1200f
            container.translationX = if (fromRight) distance else -distance
            container.alpha = 1f
            container.animate()
                .translationX(0f)
                .setDuration(220L)
                .start()
        }
    }

    private fun animateContainerOut(container: View, toRight: Boolean, onEnd: (() -> Unit)? = null) {
        container.animate().cancel()
        if (container.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        val distance = container.width.toFloat().takeIf { it > 0f } ?: 1200f
        container.animate()
            .translationX(if (toRight) distance else -distance)
            .setDuration(220L)
            .withEndAction {
                container.visibility = View.GONE
                container.translationX = 0f
                onEnd?.invoke()
            }
            .start()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleAppBackPress()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private fun handleAppBackPress(): Boolean {
        if (songAdapter.isSelectionMode()) {
            songAdapter.exitSelectionMode()
            return true
        }
        if (youtubeContainer.visibility == View.VISIBLE) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
                currentBottomTab = BottomTab.SEARCH
                updateBottomNavSelection(BottomTab.SEARCH)
                return true
            }
            val fragment = supportFragmentManager.findFragmentById(R.id.youtubeContainer) as? YouTubeSearchFragment
            if (fragment?.handleBackNavigation() == true) {
                return true
            }
            openHomeTab()
            return true
        }
        if (playlistContainer.visibility == View.VISIBLE) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
                currentBottomTab = BottomTab.PLAYLISTS
                updateBottomNavSelection(BottomTab.PLAYLISTS)
            } else {
                openHomeTab()
            }
            return true
        }
        return false
    }

    private fun updateBottomNavSelection(selectedTab: BottomTab) {
        val homeIcon = findViewById<ImageView>(R.id.ivNavHome)
        val homeText = findViewById<TextView>(R.id.tvNavHome)
        val searchIcon = findViewById<ImageView>(R.id.ivNavSearch)
        val searchText = findViewById<TextView>(R.id.tvNavSearch)
        val playlistIcon = findViewById<ImageView>(R.id.ivNavPlaylists)
        val playlistText = findViewById<TextView>(R.id.tvNavPlaylists)

        val isHome = selectedTab == BottomTab.HOME
        val isSearch = selectedTab == BottomTab.SEARCH
        val isPlaylists = selectedTab == BottomTab.PLAYLISTS

        homeIcon.setImageResource(if (isHome) R.drawable.ml_home_filled else R.drawable.ml_home_outlined)
        homeIcon.setColorFilter(ContextCompat.getColor(this, if (isHome) R.color.accent_lavender else R.color.text_secondary))
        homeText.setTextColor(ContextCompat.getColor(this, if (isHome) R.color.accent_lavender else R.color.text_secondary))

        searchIcon.setColorFilter(ContextCompat.getColor(this, if (isSearch) R.color.accent_lavender else R.color.text_secondary))
        searchText.setTextColor(ContextCompat.getColor(this, if (isSearch) R.color.accent_lavender else R.color.text_secondary))

        playlistIcon.setImageResource(if (isPlaylists) R.drawable.ml_library_music_filled else R.drawable.ml_library_music_outlined)
        playlistIcon.setColorFilter(ContextCompat.getColor(this, if (isPlaylists) R.color.accent_lavender else R.color.text_secondary))
        playlistText.setTextColor(ContextCompat.getColor(this, if (isPlaylists) R.color.accent_lavender else R.color.text_secondary))
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

    private enum class SongSortOption(val label: String) {
        TITLE("Title"),
        ARTIST("Artist"),
        ALBUM("Album"),
        DURATION("Duration"),
        RECENTLY_ADDED("Recent")
    }
}
