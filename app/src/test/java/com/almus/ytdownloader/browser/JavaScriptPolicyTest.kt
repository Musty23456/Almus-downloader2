package com.almus.ytdownloader.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSiteJsStore(var stored: Set<String> = emptySet()) : SiteJsStore {
    var saves = 0

    override fun load(): Set<String> = stored

    override fun save(hosts: Set<String>) {
        stored = hosts
        saves++
    }
}

class JavaScriptPolicyTest {
    @Test fun javaScriptIsOffByDefault() {
        val policy = JavaScriptPolicy(FakeSiteJsStore())
        assertFalse(policy.isAllowed("example.com"))
        assertFalse(policy.isAllowed(null))
        assertFalse(policy.isAllowed(""))
        assertTrue(policy.allowedHosts().isEmpty())
    }

    @Test fun allowingOneSiteDoesNotAllowOthers() {
        val policy = JavaScriptPolicy(FakeSiteJsStore())
        assertTrue(policy.setAllowed("Example.com", true))
        assertTrue(policy.isAllowed("example.com"))
        assertTrue(policy.isAllowed("EXAMPLE.COM."))
        assertFalse(policy.isAllowed("ads.example.com"))
        assertFalse(policy.isAllowed("example.com.evil.org"))
        assertFalse(policy.isAllowed("other.com"))
    }

    @Test fun choiceIsSavedAndRestored() {
        val store = FakeSiteJsStore()
        JavaScriptPolicy(store).setAllowed("example.com", true)
        assertEquals(setOf("example.com"), store.stored)
        assertTrue(JavaScriptPolicy(store).isAllowed("example.com"))
    }

    @Test fun turningOffRemovesTheSite() {
        val store = FakeSiteJsStore()
        val policy = JavaScriptPolicy(store)
        policy.setAllowed("example.com", true)
        assertTrue(policy.setAllowed("example.com", false))
        assertFalse(policy.isAllowed("example.com"))
        assertTrue(store.stored.isEmpty())
    }

    @Test fun unusableHostsAreRefusedAndChangeNothing() {
        val store = FakeSiteJsStore()
        val policy = JavaScriptPolicy(store)
        for (bad in listOf(null, "", "   ", "a b.com", "evil.com/path", "user@evil.com", "x".repeat(300))) {
            assertFalse("<$bad>", policy.setAllowed(bad, true))
        }
        assertEquals(0, store.saves)
        assertTrue(policy.allowedHosts().isEmpty())
    }

    @Test fun junkInTheStoreIsIgnoredOnLoad() {
        val policy = JavaScriptPolicy(FakeSiteJsStore(setOf("  ", "OK.com", "bad host")))
        assertEquals(setOf("ok.com"), policy.allowedHosts())
    }

    @Test fun listIsCapped() {
        val policy = JavaScriptPolicy(FakeSiteJsStore())
        repeat(JavaScriptPolicy.MAX_HOSTS) { assertTrue(policy.setAllowed("site$it.com", true)) }
        assertFalse(policy.setAllowed("one-too-many.com", true))
        assertFalse(policy.isAllowed("one-too-many.com"))
        // An existing site can still be re-saved, and any site can still be turned off.
        assertTrue(policy.setAllowed("site0.com", true))
        assertTrue(policy.setAllowed("site1.com", false))
        assertTrue(policy.setAllowed("one-too-many.com", true))
    }

    @Test fun clearAllRemovesEverything() {
        val store = FakeSiteJsStore()
        val policy = JavaScriptPolicy(store)
        policy.setAllowed("a.com", true)
        policy.setAllowed("b.com", true)
        policy.clearAll()
        assertTrue(policy.allowedHosts().isEmpty())
        assertTrue(store.stored.isEmpty())
    }

    @Test fun hostKeyExtraction() {
        assertEquals("example.com", HostKey.of("https://Example.com:8443/a?b#c"))
        assertEquals("host.org", HostKey.of("http://user:pw@host.org/x"))
        assertEquals("[::1]", HostKey.of("https://[::1]:80/"))
        assertEquals("example.com", HostKey.of("https://example.com./"))
        assertEquals("example.com", HostKey.of("https://example.com"))
        assertNull(HostKey.of("ftp://example.com"))
        assertNull(HostKey.of("about:blank"))
        assertNull(HostKey.of("https://"))
        assertNull(HostKey.of(null))
    }

    @Test fun blankPageDetection() {
        assertTrue(HostKey.isBlank(null))
        assertTrue(HostKey.isBlank(""))
        assertTrue(HostKey.isBlank("about:blank"))
        assertFalse(HostKey.isBlank("https://example.com"))
    }
}
