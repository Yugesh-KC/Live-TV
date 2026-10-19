package com.livetv.app

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("livetv", Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = sp.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAYLIST_URL
        set(value) = sp.edit { putString(KEY_URL, value.trim()) }

    /** Extra playlists whose links are used as backups for channels in the main playlist. */
    var backupPlaylists: List<String>
        get() = (sp.getString(KEY_BACKUPS, null) ?: DEFAULT_BACKUP_PLAYLISTS.joinToString("\n"))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = sp.edit { putString(KEY_BACKUPS, value.joinToString("\n")) }

    /** Changes whenever the main or backup playlist URLs change. */
    val playlistKey: String
        get() = (listOf(playlistUrl) + backupPlaylists).joinToString("\n")

    /** Favorites are keyed by stream URL, which is unique within a playlist. */
    var favorites: Set<String>
        get() = sp.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit { putStringSet(KEY_FAVORITES, value) }

    var dimPhoneInTvMode: Boolean
        get() = sp.getBoolean(KEY_DIM, true)
        set(value) = sp.edit { putBoolean(KEY_DIM, value) }

    /** Maximum video height in pixels, [QUALITY_AUTO] or [QUALITY_DATA_SAVER]. */
    var quality: Int
        get() = sp.getInt(KEY_QUALITY, QUALITY_AUTO)
        set(value) = sp.edit { putInt(KEY_QUALITY, value) }

    var lastChannelUrl: String?
        get() = sp.getString(KEY_LAST, null)
        set(value) = sp.edit { putString(KEY_LAST, value) }

    companion object {
        const val QUALITY_AUTO = 0
        const val QUALITY_DATA_SAVER = -1
        const val DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.m3u"
        val DEFAULT_BACKUP_PLAYLISTS = listOf("https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8")
        private const val KEY_URL = "playlist_url"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_DIM = "dim_phone_tv_mode"
        private const val KEY_LAST = "last_channel"
        private const val KEY_QUALITY = "quality"
        private const val KEY_BACKUPS = "backup_playlists"
    }
}
