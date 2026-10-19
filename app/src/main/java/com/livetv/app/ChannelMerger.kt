package com.livetv.app

/**
 * Collects several links per channel so playback can fall back when one link is down:
 *
 * - iptv-org lists some channels once per quality feed ("Foo.us@SD", "Foo.us@HD"). Those become one
 *   entry whose links are tried in playlist order. Other feeds (regions such as "@East" or "@Sydney")
 *   are different broadcasts and stay separate.
 * - Backup playlists (e.g. Free-TV) add their links to channels with the same channel id. They don't
 *   add new channels.
 */
object ChannelMerger {

    private val QUALITY_FEEDS = setOf("sd", "hd", "fhd", "uhd", "4k")

    /** "BBCNews.uk@SD" -> "bbcnews.uk". */
    fun baseId(tvgId: String?): String? =
        tvgId?.substringBefore('@')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun merge(primary: List<Channel>, backups: List<List<Channel>> = emptyList()): List<Channel> {
        val out = ArrayList<Channel>(primary.size)
        val qualityEntry = HashMap<String, Int>()
        for (ch in primary) {
            val base = baseId(ch.tvgId)
            val feed = ch.tvgId?.substringAfter('@', "")?.lowercase()
            if (base != null && feed in QUALITY_FEEDS) {
                val idx = qualityEntry[base]
                if (idx != null) {
                    out[idx] = out[idx].withBackups(ch.sources)
                    continue
                }
                qualityEntry[base] = out.size
            }
            out += ch
        }

        if (backups.any { it.isNotEmpty() }) {
            val byBase = HashMap<String, MutableList<Int>>()
            out.forEachIndexed { i, ch -> baseId(ch.tvgId)?.let { byBase.getOrPut(it) { ArrayList(1) } += i } }
            for (list in backups) {
                for (ch in list) {
                    val targets = byBase[baseId(ch.tvgId) ?: continue] ?: continue
                    for (i in targets) out[i] = out[i].withBackups(ch.sources)
                }
            }
        }
        return out
    }

    // Some playlists link to YouTube pages, which aren't streams the player can open.
    private val WEB_PAGE_URL = Regex("""^https?://([^/]+\.)?(youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE)

    private fun isStreamUrl(url: String) = !WEB_PAGE_URL.containsMatchIn(url)

    private fun Channel.withBackups(extra: List<Stream>): Channel {
        val known = sources.mapTo(HashSet()) { it.url }
        val added = extra.filter { isStreamUrl(it.url) && known.add(it.url) }
        return if (added.isEmpty()) this else copy(backups = backups + added)
    }
}
