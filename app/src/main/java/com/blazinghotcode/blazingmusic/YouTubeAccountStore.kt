package com.blazinghotcode.blazingmusic

import android.content.Context

object YouTubeAccountStore {
    private const val PREFS_NAME = "blazing_music_youtube_account"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_VISITOR_DATA = "visitor_data"
    private const val KEY_DATA_SYNC_ID = "data_sync_id"
    private const val KEY_ACCOUNT_NAME = "account_name"

    data class AccountAuth(
        val cookie: String,
        val visitorData: String,
        val dataSyncId: String,
        val accountName: String
    ) {
        val isLoggedIn: Boolean
            get() = cookie.isNotBlank()
    }

    fun read(context: Context): AccountAuth {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AccountAuth(
            cookie = prefs.getString(KEY_COOKIE, "").orEmpty(),
            visitorData = prefs.getString(KEY_VISITOR_DATA, "").orEmpty(),
            dataSyncId = prefs.getString(KEY_DATA_SYNC_ID, "").orEmpty(),
            accountName = prefs.getString(KEY_ACCOUNT_NAME, "").orEmpty()
        )
    }

    fun save(
        context: Context,
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        accountName: String
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, cookie.trim())
            .putString(KEY_VISITOR_DATA, visitorData.trim())
            .putString(KEY_DATA_SYNC_ID, dataSyncId.trim())
            .putString(KEY_ACCOUNT_NAME, accountName.trim())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
