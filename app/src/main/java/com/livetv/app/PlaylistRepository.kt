package com.livetv.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and parses M3U playlists, keeping the last successful download of each on disk
 * so the channel list can be shown instantly on the next launch.
 *
 * The main playlist decides which channels exist. Backup playlists only add extra links to
 * those channels (see [ChannelMerger]), and a backup that fails to download is skipped.
 */
class PlaylistRepository(context: Context) {

    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }

    init {
        // Cache files from before backup playlists existed.
        File(context.filesDir, "playlist.m3u").delete()
        File(context.filesDir, "playlist.url").delete()
    }

    suspend fun loadCached(url: String, backupUrls: List<String>): List<Channel>? = withContext(Dispatchers.IO) {
        try {
            val main = readCache(url)?.let(M3uParser::parse)?.takeIf { it.isNotEmpty() }
                ?: return@withContext null
            ChannelMerger.merge(main, backupUrls.mapNotNull { u -> readCache(u)?.let(M3uParser::parse) })
        } catch (e: Exception) {
            null
        }
    }

    suspend fun download(url: String, backupUrls: List<String>): List<Channel> = withContext(Dispatchers.IO) {
        coroutineScope {
            val backups = backupUrls.map { u -> async { downloadBackup(u) } }
            val text = fetch(url)
            val main = M3uParser.parse(text)
            if (main.isEmpty()) throw IOException("No channels found in playlist")
            writeCache(url, text)
            ChannelMerger.merge(main, backups.awaitAll())
        }
    }

    private fun downloadBackup(url: String): List<Channel> = try {
        val text = fetch(url)
        M3uParser.parse(text).also { if (it.isNotEmpty()) writeCache(url, text) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        readCache(url)?.let(M3uParser::parse).orEmpty()
    }

    private fun cacheFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.m3u")
    }

    private fun readCache(url: String): String? = cacheFile(url).takeIf { it.exists() }?.readText()

    private fun writeCache(url: String, text: String) {
        val file = cacheFile(url)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    private fun fetch(url: String, redirects: Int = 0): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiveTV-Android/1.0")
        }
        try {
            val code = conn.responseCode
            // HttpURLConnection does not follow http <-> https redirects by itself.
            if (code in 300..399 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                return fetch(URL(URL(url), location).toString(), redirects + 1)
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
