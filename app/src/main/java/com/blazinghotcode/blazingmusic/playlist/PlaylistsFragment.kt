package com.blazinghotcode.blazingmusic

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Playlist list screen for create/search/rename/delete/open actions. */
class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {
    companion object {
        private const val SEARCH_DEBOUNCE_MS = 220L
    }

    private val viewModel: MusicViewModel by activityViewModels()
    private lateinit var rvPlaylists: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvPlaylistCount: TextView
    private lateinit var tvTitle: TextView
    private lateinit var etSearchPlaylists: EditText
    private lateinit var btnCreatePlaylist: View
    private lateinit var btnSyncLikedMusic: Button
    private lateinit var btnBack: ImageButton
    private lateinit var playlistAdapter: PlaylistAdapter
    private var allPlaylists: List<Playlist> = emptyList()
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        applyHeaderInsets(view)
        setupRecycler()
        setupActions()
        observeData()
        updateBottomClearance()
    }

    private fun initViews(root: View) {
        rvPlaylists = root.findViewById(R.id.rvPlaylists)
        tvEmpty = root.findViewById(R.id.tvEmpty)
        tvPlaylistCount = root.findViewById(R.id.tvPlaylistCount)
        tvTitle = root.findViewById(R.id.tvTitle)
        etSearchPlaylists = root.findViewById(R.id.etSearchPlaylists)
        btnCreatePlaylist = root.findViewById(R.id.btnCreatePlaylist)
        btnSyncLikedMusic = root.findViewById(R.id.btnSyncLikedMusic)
        btnBack = root.findViewById(R.id.btnBack)

        applyHeaderTitleSizing(tvTitle)
        tintSearchStartIcon()
    }

    private fun applyHeaderTitleSizing(titleView: TextView) {
        val text = titleView.text?.toString().orEmpty().trim()
        val isSingleWord = text.isNotEmpty() && text.none { it.isWhitespace() }
        titleView.maxLines = if (isSingleWord) 1 else 2
        titleView.ellipsize = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            titleView.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            titleView.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
    }

    private fun applyHeaderInsets(root: View) {
        val topGap = dp(6)
        val back = btnBack
        val title = root.findViewById<TextView>(R.id.tvTitle)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (back.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = topInset + topGap
                back.layoutParams = lp
            }
            title?.let { header ->
                (header.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.marginEnd = maxOf(lp.marginEnd, dp(16))
                    header.layoutParams = lp
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupRecycler() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist ->
                val main = activity as? MainActivity ?: return@PlaylistAdapter
                if (playlist.isRemoteSystemPlaylist()) {
                    main.openYouTubeBrowseFromPlaylists(
                        YouTubeVideo(
                            id = "playlist:${playlist.id}",
                            title = playlist.name,
                            channelTitle = "",
                            thumbnailUrl = null,
                            browseId = playlist.remoteBrowseId,
                            browseParams = null,
                            type = playlist.remoteBrowseType
                        )
                    )
                } else {
                    main.openPlaylistSongs(playlist.id, playlist.name)
                }
            },
            onPlaylistMenuClick = { playlist, anchor ->
                showPlaylistMenu(playlist, anchor)
            }
        )

        rvPlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
    }

    private fun updateBottomClearance() {
        val host = activity ?: return
        val player = host.findViewById<View>(R.id.playerLayout) ?: return
        val bottomNav = host.findViewById<View>(R.id.bottomNav) ?: return

        val apply = {
            val playerHeight = if (player.visibility == View.VISIBLE) player.height else 0
            val bottomPadding = bottomNav.height + playerHeight + dp(20)
            rvPlaylists.setPadding(
                rvPlaylists.paddingLeft,
                rvPlaylists.paddingTop,
                rvPlaylists.paddingRight,
                bottomPadding
            )
            tvEmpty.setPadding(
                tvEmpty.paddingLeft,
                tvEmpty.paddingTop,
                tvEmpty.paddingRight,
                bottomPadding
            )
        }

        player.doOnLayout { apply() }
        bottomNav.doOnLayout { apply() }
        rvPlaylists.post { apply() }
    }

    private fun setupActions() {
        btnBack.setOnClickListener { (activity as? MainActivity)?.openHomeTab() }
        btnCreatePlaylist.setOnClickListener { showCreatePlaylistDialog() }
        btnSyncLikedMusic.setOnClickListener {
            viewModel.refreshSpecialPlaylists()
            showToast("Syncing liked music from YouTube...")
        }
        etSearchPlaylists.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                searchDebounceRunnable?.let { searchDebounceHandler.removeCallbacks(it) }
                val runnable = Runnable { applyFilter(query) }
                searchDebounceRunnable = runnable
                searchDebounceHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun observeData() {
        viewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            allPlaylists = playlists
            applyFilter(etSearchPlaylists.text?.toString().orEmpty())
        }
    }

    override fun onDestroyView() {
        searchDebounceRunnable?.let { searchDebounceHandler.removeCallbacks(it) }
        searchDebounceRunnable = null
        super.onDestroyView()
    }

    private fun applyFilter(query: String) {
        val normalized = query.trim().lowercase()
        val filtered = if (normalized.isEmpty()) {
            allPlaylists
        } else {
            allPlaylists.filter { it.name.lowercase().contains(normalized) }
        }
        playlistAdapter.submitList(filtered)
        val countWord = if (filtered.size == 1) "playlist" else "playlists"
        tvPlaylistCount.text = "${filtered.size} $countWord"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showPlaylistMenu(playlist: Playlist, anchor: View) {
        androidx.appcompat.widget.PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_BlazingMusic_PopupMenu),
            anchor
        ).apply {
            menu.add(Menu.NONE, 1, Menu.NONE, "Open")
            if (playlist.isEditablePlaylist()) {
                menu.add(Menu.NONE, 2, Menu.NONE, "Rename")
                menu.add(Menu.NONE, 3, Menu.NONE, "Delete")
                menu.add(Menu.NONE, 4, Menu.NONE, "Select multiple")
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val main = activity as? MainActivity ?: return@setOnMenuItemClickListener false
                        if (playlist.isRemoteSystemPlaylist()) {
                            main.openYouTubeBrowseFromPlaylists(
                                YouTubeVideo(
                                    id = "playlist:${playlist.id}",
                                    title = playlist.name,
                                    channelTitle = "",
                                    thumbnailUrl = null,
                                    browseId = playlist.remoteBrowseId,
                                    browseParams = null,
                                    type = playlist.remoteBrowseType
                                )
                            )
                        } else {
                            main.openPlaylistSongs(playlist.id, playlist.name)
                        }
                        true
                    }
                    2 -> {
                        showRenamePlaylistDialog(playlist)
                        true
                    }
                    3 -> {
                        showDeletePlaylistDialog(playlist)
                        true
                    }
                    4 -> {
                        showMultiSelectPlaylistsDialog()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showMultiSelectPlaylistsDialog() {
        val editablePlaylists = allPlaylists.filter { it.isEditablePlaylist() }
        if (editablePlaylists.isEmpty()) {
            showToast("No playlists yet")
            return
        }
        showMultiSelectBottomSheet(
            title = "Select playlists",
            items = editablePlaylists.map {
                val songsWord = if (it.songPaths.size == 1) "song" else "songs"
                "${it.name} (${it.songPaths.size} $songsWord)"
            },
            confirmLabel = "Delete selected"
        ) { selectedIndices ->
            val selected = editablePlaylists.filterIndexed { index, _ -> selectedIndices.contains(index) }
            if (selected.isEmpty()) {
                showToast("No playlists selected")
                return@showMultiSelectBottomSheet
            }
            var deletedCount = 0
            selected.forEach { playlist ->
                if (viewModel.deletePlaylist(playlist.id)) {
                    deletedCount += 1
                }
            }
            if (deletedCount > 0) {
                showToast("Deleted $deletedCount playlists")
            } else {
                showToast("No playlists deleted")
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        showTextInputBottomSheet(
            title = "Create playlist",
            hint = "Playlist name",
            confirmLabel = "Create"
        ) { value ->
            val created = viewModel.createPlaylist(value)
            if (created == null) showToast("Unable to create playlist") else showToast("Playlist created")
        }
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        if (playlist.isLocalMusicSystemPlaylist()) {
            showToast("Local music playlist cannot be renamed")
            return
        }
        if (!playlist.isEditablePlaylist()) {
            showToast("This playlist cannot be renamed")
            return
        }
        showTextInputBottomSheet(
            title = "Rename playlist",
            hint = "Playlist name",
            initialText = playlist.name,
            confirmLabel = "Save"
        ) { value ->
            val renamed = viewModel.renamePlaylist(playlist.id, value)
            if (renamed) showToast("Playlist renamed") else showToast("Unable to rename playlist")
        }
    }

    private fun showDeletePlaylistDialog(playlist: Playlist) {
        if (playlist.isLocalMusicSystemPlaylist()) {
            showToast("Local music playlist cannot be deleted")
            return
        }
        if (!playlist.isEditablePlaylist()) {
            showToast("This playlist cannot be deleted")
            return
        }
        showConfirmBottomSheet(
            title = "Delete playlist",
            message = "Delete \"${playlist.name}\"?",
            confirmLabel = "Delete"
        ) {
            val deleted = viewModel.deletePlaylist(playlist.id)
            if (deleted) showToast("Playlist deleted") else showToast("Unable to delete playlist")
        }
    }

    private fun EditText.applyDialogInputStyle() {
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        setHintTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        setBackgroundResource(R.drawable.bg_search_field)
        val horizontal = (12 * resources.displayMetrics.density).toInt()
        val vertical = (10 * resources.displayMetrics.density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
    }

    private fun showTextInputBottomSheet(
        title: String,
        hint: String,
        initialText: String = "",
        confirmLabel: String,
        onConfirm: (String) -> Unit
    ) {
        val context = requireContext()
        val sheet = BottomSheetDialog(context, R.style.ThemeOverlay_BlazingMusic_BottomSheet)
        val titleView = TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(20), dp(6))
        }
        val input = EditText(context).apply {
            this.hint = hint
            setText(initialText)
            if (initialText.isNotEmpty()) {
                setSelection(initialText.length)
            }
            applyDialogInputStyle()
        }
        val inputContainer = FrameLayout(context).apply {
            setPadding(dp(16), 0, dp(16), dp(10))
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val cancel = Button(context).apply {
                text = "Cancel"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { sheet.dismiss() }
            }
            val confirm = Button(context).apply {
                text = confirmLabel
                setTextColor(ContextCompat.getColor(context, R.color.accent_lavender))
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
        val root = LinearLayout(context).apply {
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
        val context = requireContext()
        val sheet = BottomSheetDialog(context, R.style.ThemeOverlay_BlazingMusic_BottomSheet)
        val titleView = TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(20), dp(6))
        }
        val messageView = TextView(context).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(20), 0, dp(20), dp(10))
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val cancel = Button(context).apply {
                text = "Cancel"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { sheet.dismiss() }
            }
            val confirm = Button(context).apply {
                text = confirmLabel
                setTextColor(ContextCompat.getColor(context, R.color.accent_lavender))
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
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(messageView)
            addView(controls)
        }
        sheet.setContentView(root)
        sheet.show()
    }

    private fun showMultiSelectBottomSheet(
        title: String,
        items: List<String>,
        confirmLabel: String,
        onConfirm: (Set<Int>) -> Unit
    ) {
        val context = requireContext()
        val sheet = BottomSheetDialog(context, R.style.ThemeOverlay_BlazingMusic_BottomSheet)
        val adapter = MultiSelectAdapter(items)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }
        val titleView = TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(20), dp(4))
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val cancel = Button(context).apply {
                text = "Cancel"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { sheet.dismiss() }
            }
            val confirm = Button(context).apply {
                text = confirmLabel
                setTextColor(ContextCompat.getColor(context, R.color.accent_lavender))
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
        val root = LinearLayout(context).apply {
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    private fun tintSearchStartIcon() {
        val drawables = etSearchPlaylists.compoundDrawablesRelative
        val start = drawables[0]?.mutate()
        start?.setTint(ContextCompat.getColor(requireContext(), R.color.text_muted))
        etSearchPlaylists.setCompoundDrawablesRelativeWithIntrinsicBounds(
            start,
            drawables[1],
            drawables[2],
            drawables[3]
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
