package com.livetv.app

import java.util.Locale

/** One link to a channel's stream, with the HTTP headers it needs. */
data class Stream(
    val url: String,
    val userAgent: String? = null,
    val referrer: String? = null,
)

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val groups: List<String>,
    /** ISO 3166 alpha-2 country code in upper case, or null if unknown. */
    val country: String?,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** iptv-org style id such as "BBCNews.uk@SD" (channel id, then the feed after '@'). */
    val tvgId: String? = null,
    /** Other links for the same channel, tried in order when [url] doesn't play. */
    val backups: List<Stream> = emptyList(),
) {
    /** All links, main one first. [url] stays the channel's identity for favorites and the list. */
    val sources: List<Stream>
        get() = listOf(Stream(url, userAgent, referrer)) + backups

    val subtitle: String
        get() = listOfNotNull(
            groups.joinToString(", ").ifBlank { null },
            country?.let { countryName(it) },
            if (backups.isNotEmpty()) "${backups.size + 1} links" else null,
        ).joinToString(" · ")

    companion object {
        fun countryName(code: String): String {
            val name = Locale("", code).getDisplayCountry(Locale.getDefault())
            return if (name.isBlank() || name.equals(code, ignoreCase = true)) code else name
        }
    }
}
