package com.livetv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    @Test
    fun parsesAttributesNameAndOptions() {
        val text = """
            #EXTM3U x-tvg-url="https://example.com/epg.xml"
            #EXTINF:-1 tvg-id="BBCNews.uk@SD" tvg-logo="https://x/logo.png" group-title="News;General",BBC News, London (720p)
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            #EXTVLCOPT:http-referrer=https://ref.example/
            https://example.com/bbc.m3u8
            #EXTINF:-1 tvg-country="IN" group-title="Movies",Some Movies
            http://example.com/movies
            #EXTINF:-1,No Attrs
            https://example.com/plain.m3u8
            https://example.com/orphan.m3u8
        """.trimIndent()

        val list = M3uParser.parse(text)
        assertEquals(3, list.size)

        val bbc = list[0]
        assertEquals("BBC News, London (720p)", bbc.name)
        assertEquals("https://x/logo.png", bbc.logo)
        assertEquals(listOf("News", "General"), bbc.groups)
        assertEquals("GB", bbc.country)
        assertEquals("Mozilla/5.0", bbc.userAgent)
        assertEquals("https://ref.example/", bbc.referrer)
        assertEquals("https://example.com/bbc.m3u8", bbc.url)

        assertEquals("IN", list[1].country)
        assertEquals(listOf("Movies"), list[1].groups)

        assertEquals("No Attrs", list[2].name)
        assertNull(list[2].logo)
        assertNull(list[2].country)
    }

    /** Run with -DliveM3u=path/to/index.m3u to sanity-check a real playlist. */
    @Test
    fun parsesRealPlaylistIfProvided() {
        val path = System.getProperty("liveM3u") ?: return
        val text = java.io.File(path).readText()
        val list = M3uParser.parse(text)
        val entries = Regex("^#EXTINF", RegexOption.MULTILINE).findAll(text).count()
        println("EXTINF entries: $entries, parsed channels: ${list.size}")
        println("with logo: ${list.count { it.logo != null }}, with country: ${list.count { it.country != null }}")
        println("categories: ${list.flatMap { it.groups }.distinct().size}, countries: ${list.mapNotNull { it.country }.distinct().size}")
        list.take(5).forEach { println("  ${it.name} | ${it.groups} | ${it.country} | ${it.url}") }
        assert(list.size > entries * 0.9)
    }
}
