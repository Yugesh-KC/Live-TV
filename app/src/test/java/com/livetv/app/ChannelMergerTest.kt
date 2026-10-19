package com.livetv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMergerTest {

    private fun ch(id: String?, url: String, name: String = url) =
        Channel(name = name, url = url, logo = null, groups = emptyList(), country = null, tvgId = id)

    @Test
    fun mergesQualityFeedsButKeepsRegionalFeeds() {
        val merged = ChannelMerger.merge(
            listOf(
                ch("Foo.us@SD", "a", "Foo"),
                ch("Bar.us@East", "b"),
                ch("Foo.us@HD", "c", "Foo HD"),
                ch("Bar.us@West", "d"),
                ch(null, "e"),
            ),
        )
        assertEquals(listOf("a", "b", "d", "e"), merged.map { it.url })
        assertEquals("Foo", merged[0].name)
        assertEquals(listOf("a", "c"), merged[0].sources.map { it.url })
        assertEquals(1, merged[1].sources.size)
    }

    @Test
    fun backupPlaylistsAddLinksByIdWithoutAddingChannels() {
        val main = listOf(ch("Foo.us@SD", "a"), ch("Bar.uk@SD", "b"))
        val backup = listOf(
            ch("Foo.us", "x"),
            ch("foo.us", "a"), // same link as the main one: ignored
            ch("Other.fr", "y"), // not in the main playlist: ignored
            ch(null, "z"),
            ch("Foo.us", "https://www.youtube.com/watch?v=abc"), // web page, not a stream: ignored
        )
        val merged = ChannelMerger.merge(main, listOf(backup))
        assertEquals(2, merged.size)
        assertEquals(listOf("a", "x"), merged[0].sources.map { it.url })
        assertEquals(listOf("b"), merged[1].sources.map { it.url })
    }

    @Test
    fun keepsHeadersPerLink() {
        val withUa = Channel("Foo", "x", null, emptyList(), null, userAgent = "UA", referrer = "R", tvgId = "Foo.us")
        val merged = ChannelMerger.merge(listOf(ch("Foo.us@SD", "a")), listOf(listOf(withUa)))
        assertEquals(Stream("x", "UA", "R"), merged[0].sources[1])
    }

    /** Run with -DliveM3u=main.m3u -DliveBackupM3u=backup.m3u to measure a real merge. */
    @Test
    fun mergesRealPlaylistsIfProvided() {
        val main = System.getProperty("liveM3u") ?: return
        val backup = System.getProperty("liveBackupM3u")
        val primary = M3uParser.parse(java.io.File(main).readText())
        val backups = listOfNotNull(backup?.let { M3uParser.parse(java.io.File(it).readText()) })
        val merged = ChannelMerger.merge(primary, backups)
        println("main entries: ${primary.size}, channels after merge: ${merged.size}")
        println("channels with >1 link: ${merged.count { it.sources.size > 1 }}, total links: ${merged.sumOf { it.sources.size }}")
        merged.filter { it.sources.size > 2 }.take(3).forEach { c -> println("  ${c.name}: ${c.sources.map { it.url }}") }
    }
}
