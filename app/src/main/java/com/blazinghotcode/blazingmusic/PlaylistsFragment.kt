package com.blazinghotcode.blazingmusic

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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
        val input = EditText(requireContext()).apply {
            hint = "Playlist name"
            applyDialogInputStyle()
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Create playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val created = viewModel.createPlaylist(input.text?.toString().orEmpty())
                if (created == null) showToast("Unable to create playlist") else showToast("Playlist created")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        val input = EditText(requireContext()).apply {
            setText(playlist.name)
            setSelection(playlist.name.length)
            applyDialogInputStyle()
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Rename playlist")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val renamed = viewModel.renamePlaylist(playlist.id, input.text?.toString().orEmpty())
                if (renamed) showToast("Playlist renamed") else showToast("Unable to rename playlist")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePlaylistDialog(playlist: Playlist) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete playlist")
            .setMessage("Delete \"${playlist.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                val deleted = viewModel.deletePlaylist(playlist.id)
                if (deleted) showToast("Playlist deleted") else showToast("Unable to delete playlist")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun EditText.applyDialogInputStyle() {
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        setHintTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        setBackgroundResource(R.drawable.bg_search_field)
        val horizontal = (12 * resources.displayMetrics.density).toInt()
        val vertical = (10 * resources.displayMetrics.density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
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
