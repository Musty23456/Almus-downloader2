package com.almus.ytdownloader.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParserTest {
    private val source = "https://www.youtube.com/playlist?list=PL1"

    @Test
    fun readsEntriesAndPlaylistTitle() {
        val out = """
            a1|||First video|||185.0|||My list|||https://www.youtube.com/watch?v=a1
            b2|||Second|||NA|||My list|||https://www.youtube.com/watch?v=b2
        """.trimIndent()
        val p = PlaylistParser.parse(source, out)!!
        assertEquals("My list", p.title)
        assertEquals(2, p.entries.size)
        assertEquals(185L, p.entries[0].durationSeconds)
        assertNull(p.entries[1].durationSeconds)
        assertEquals("https://www.youtube.com/watch?v=b2", p.entries[1].url)
    }

    @Test
    fun skipsBrokenLinesAndEntriesWithoutAddress() {
        val out = "garbage\nx|||T|||1|||L|||NA\ny|||Ok|||60|||L|||https://example.com/y\n"
        val p = PlaylistParser.parse(source, out)!!
        assertEquals(1, p.entries.size)
        assertEquals("Ok", p.entries[0].title)
    }

    @Test
    fun missingTitleFallsBackToAddressAndNoEntriesGivesNull() {
        val p = PlaylistParser.parse(source, "z|||NA|||NA|||NA|||https://example.com/z")!!
        assertEquals("https://example.com/z", p.entries[0].title)
        assertEquals(source, p.title)
        assertNull(PlaylistParser.parse(source, ""))
    }

    @Test
    fun detectsPlaylistPagesOnly() {
        assertTrue(UrlInput.isPlaylist("https://www.youtube.com/playlist?list=PL1"))
        assertTrue(UrlInput.isPlaylist("https://youtube.com/playlist/?list=PL1"))
        assertFalse(UrlInput.isPlaylist("https://www.youtube.com/watch?v=abc&list=PL1"))
        assertFalse(UrlInput.isPlaylist("https://youtu.be/abc?list=PL1"))
    }
}
