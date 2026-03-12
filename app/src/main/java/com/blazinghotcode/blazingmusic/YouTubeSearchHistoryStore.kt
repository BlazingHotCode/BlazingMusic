package com.blazinghotcode.blazingmusic

import android.content.Context
import org.json.JSONArray

object YouTubeSearchHistoryStore {
    private const val PREFS_NAME = "blazing_music_youtube_search"
    private const val KEY_RECENT_QUERIES = "recent_queries"
    private const val MAX_ENTRIES = 8

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_QUERIES, null)
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val query = json.optString(index, "").trim()
                    if (query.isNotEmpty()) add(query)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val updated = buildList {
            add(trimmed)
            load(context)
                .filterNot { it.equals(trimmed, ignoreCase = true) }
                .take(MAX_ENTRIES - 1)
                .forEach(::add)
        }
        val json = JSONArray()
        updated.forEach { json.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_QUERIES, json.toString())
            .apply()
    }
}
