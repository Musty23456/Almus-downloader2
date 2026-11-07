package com.almus.ytdownloader.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieFileTest {
    private fun line(domain: String, name: String = "SID") = "$domain\tTRUE\t/\tTRUE\t1893456000\t$name\tvalue"

    @Test
    fun keepsYouTubeAndGoogleAndDropsOtherSites() {
        val raw = listOf(
            "# Netscape HTTP Cookie File",
            line(".youtube.com"),
            line(".google.com", "HSID"),
            line("accounts.google.com", "LSID"),
            line(".facebook.com", "c_user"),
            line(".example.org", "token"),
        ).joinToString("\n")
        val f = CookieFile.filterForYouTube(raw)!!
        assertEquals(3, f.kept)
        assertEquals(2, f.dropped)
        assertFalse(f.text.contains("facebook"))
        assertFalse(f.text.contains("example.org"))
        assertTrue(f.text.startsWith("# Netscape HTTP Cookie File"))
    }

    @Test
    fun lookAlikeDomainsAreNotKept() {
        val raw = listOf(line(".evilgoogle.com"), line("google.com.evil.com"), line(".notyoutube.com")).joinToString("\n")
        assertNull(CookieFile.filterForYouTube(raw))
    }

    @Test
    fun httpOnlyLinesAreKeptAndPlainCommentsIgnored() {
        val raw = "# a comment\n#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tPSID\tabc\n"
        val f = CookieFile.filterForYouTube(raw)
        assertNotNull(f)
        assertEquals(1, f!!.kept)
    }

    @Test
    fun emptyValueKeepsItsTrailingTab() {
        val raw = ".youtube.com\tTRUE\t/\tTRUE\t0\tX\t\n"
        assertEquals(1, CookieFile.filterForYouTube(raw)!!.kept)
    }

    @Test
    fun garbageGivesNull() {
        assertNull(CookieFile.filterForYouTube("not a cookie file\nat all"))
        assertNull(CookieFile.filterForYouTube(""))
    }
}
