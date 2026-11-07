package com.almus.ytdownloader.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {
    @Test fun localAndPrivateAddressesAreRecognised() {
        listOf(
            "localhost", "app.localhost", "printer.local", "nas", "intranet", "router.lan", "x.internal",
            "127.0.0.1", "10.0.0.5", "172.16.5.4", "172.31.255.255", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "0.0.0.0", "[::1]", "[fe80::1]", "[fd12:3456::1]", "[::ffff:192.168.0.1]",
            "2130706433", "127.1", "0x7f000001",
        ).forEach { assertTrue("$it should count as local", UrlSafety.isLocalOrPrivate(it)) }
    }

    @Test fun publicAddressesAreNotLocal() {
        listOf("example.com", "www.youtube.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "[2001:db8::1]", "[::ffff:8.8.8.8]")
            .forEach { assertFalse("$it should be public", UrlSafety.isLocalOrPrivate(it)) }
    }

    @Test fun punycodeIsFlagged() {
        assertTrue(UrlSafety.hasPunycode("xn--e1afmkfd.com"))
        assertTrue(UrlSafety.hasPunycode("www.xn--80ak6aa92e.com"))
        assertFalse(UrlSafety.hasPunycode("example.com"))
    }

    @Test fun hostIsMeansSameOrSubdomain() {
        assertTrue(UrlSafety.hostIs("youtube.com", "youtube.com"))
        assertTrue(UrlSafety.hostIs("www.youtube.com", "youtube.com"))
        assertTrue(UrlSafety.hostIs("WWW.YouTube.com.", "youtube.com"))
        assertFalse(UrlSafety.hostIs("youtube.com.evil.com", "youtube.com"))
        assertFalse(UrlSafety.hostIs("notyoutube.com", "youtube.com"))
        assertFalse(UrlSafety.hostIs("youtube.co", "youtube.com"))
    }

    @Test fun squashUndoesCommonSwaps() {
        assertEquals("facebookcom", UrlSafety.squash("faceb00k.com"))
        assertEquals("youtubelogincom", UrlSafety.squash("YouTube-Login.com"))
        assertEquals("reddlt", UrlSafety.squash("redd1t"))
    }
}
