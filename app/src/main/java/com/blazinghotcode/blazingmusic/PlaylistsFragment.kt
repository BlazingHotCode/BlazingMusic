package com.blazinghotcode.blazingmusic

import android.os.Bundle
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {

    private val viewModel: MusicViewModel by activityViewModels()
    private lateinit var rvPlaylists: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvPlaylistCount: TextView
    private lateinit var etSearchPlaylists: EditText
    private lateinit var btnCreatePlaylist: View
    private lateinit var btnBack: ImageButton
    private lateinit var playlistAdapter: PlaylistAdapter
    private var allPlaylists: List<Playlist> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupRecycler()
        setupActions()
        observeData()
    }

    private fun initViews(root: View) {
        rvPlaylists = root.findViewById(R.id.rvPlaylists)
        tvEmpty = root.findViewById(R.id.tvEmpty)
        tvPlaylistCount = root.findViewById(R.id.tvPlaylistCount)
        etSearchPlaylists = root.findViewById(R.id.etSearchPlaylists)
        btnCreatePlaylist = root.findViewById(R.id.btnCreatePlaylist)
        btnBack = root.findViewById(R.id.btnBack)

        tintSearchStartIcon()
    }

    private fun setupRecycler() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist ->
                (activity as? MainActivity)?.openPlaylistSongs(playlist.id, playlist.name)
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

    private fun setupActions() {
        btnBack.setOnClickListener { (activity as? MainActivity)?.openHomeTab() }
        btnCreatePlaylist.setOnClickListener { showCreatePlaylistDialog() }
        etSearchPlaylists.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun observeData() {
        viewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            allPlaylists = playlists
            applyFilter(etSearchPlaylists.text?.toString().orEmpty())
        }
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
            menu.add(Menu.NONE, 2, Menu.NONE, "Rename")
            menu.add(Menu.NONE, 3, Menu.NONE, "Delete")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        (activity as? MainActivity)?.openPlaylistSongs(playlist.id, playlist.name)
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
                    else -> false
                }
            }
            show()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
