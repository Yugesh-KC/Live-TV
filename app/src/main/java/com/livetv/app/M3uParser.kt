package com.livetv.app

import java.io.BufferedReader
import java.io.StringReader

/**
 * Parses extended M3U playlists such as the ones published by iptv-org:
 *
 *   #EXTINF:-1 tvg-id="Foo.us@SD" tvg-logo="https://..." group-title="News;General",Foo News (720p)
 *   #EXTVLCOPT:http-user-agent=Mozilla/5.0
 *   https://example.com/stream.m3u8
 */
object M3uParser {

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    // iptv-org encodes the country in the tvg-id, e.g. "BBCNews.uk@SD" -> "uk".
    private val tvgIdCountryRegex = Regex("""\.([a-zA-Z]{2})(?:@|$)""")

    fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>(4096)
        val seenUrls = HashSet<String>()

        var info: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var groupOverride: String? = null

        BufferedReader(StringReader(text)).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        info = line
                        userAgent = null
                        referrer = null
                        groupOverride = null
                    }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val opt = line.substringAfter(':')
                        when {
                            opt.startsWith("http-user-agent=", true) -> userAgent = opt.substringAfter('=')
                            opt.startsWith("http-referrer=", true) -> referrer = opt.substringAfter('=')
                        }
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> groupOverride = line.substringAfter(':').trim()
                    line.startsWith("#") -> Unit
                    else -> {
                        val extinf = info
                        if (extinf != null && seenUrls.add(line)) {
                            result += buildChannel(extinf, line, userAgent, referrer, groupOverride)
                        }
                        info = null
                    }
                }
            }
        }
        return result
    }

    private fun buildChannel(
        extinf: String,
        url: String,
        userAgent: String?,
        referrer: String?,
        groupOverride: String?,
    ): Channel {
        val commaIndex = firstCommaOutsideQuotes(extinf)
        val header = if (commaIndex >= 0) extinf.substring(0, commaIndex) else extinf
        val title = if (commaIndex >= 0) extinf.substring(commaIndex + 1).trim() else ""

        val attrs = HashMap<String, String>()
        attrRegex.findAll(header).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2].trim() }

        val name = title.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { url }
        val groups = (attrs["group-title"] ?: groupOverride).orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return Channel(
            name = name,
            url = url,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            groups = groups,
            country = parseCountry(attrs["tvg-country"], attrs["tvg-id"]),
            userAgent = userAgent ?: attrs["http-user-agent"],
            referrer = referrer ?: attrs["http-referrer"],
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseCountry(tvgCountry: String?, tvgId: String?): String? {
        val explicit = tvgCountry?.split(';', ',')?.firstOrNull()?.trim()
        val code = when {
            !explicit.isNullOrEmpty() && explicit.length == 2 -> explicit
            !tvgId.isNullOrEmpty() -> tvgIdCountryRegex.find(tvgId)?.groupValues?.get(1)
            else -> null
        } ?: return null
        val upper = code.uppercase()
        return if (upper == "UK") "GB" else upper
    }

    private fun firstCommaOutsideQuotes(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
