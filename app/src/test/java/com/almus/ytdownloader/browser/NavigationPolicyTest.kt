package com.almus.ytdownloader.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    private fun block(url: String?, gesture: Boolean = true, main: Boolean = true): NavDecision.Block {
        val d = NavigationPolicy.decide(url, gesture, main)
        assertTrue("expected Block for <$url> but was $d", d is NavDecision.Block)
        return d as NavDecision.Block
    }

    @Test fun webPagesLoadInTheApp() {
        for (url in listOf(
            "https://example.com/a",
            "http://example.com",
            "HTTPS://EXAMPLE.COM",
            "https://example.com/path@x",
            "https://example.com?email=a@b.com",
            "https://example.com:8443/",
            "about:blank",
        )) {
            assertEquals(url, NavDecision.LoadInApp, NavigationPolicy.decide(url, hasGesture = false))
        }
    }

    @Test fun addressesWithCredentialsAreBlocked() {
        assertTrue(block("https://user:pw@example.com/").message.contains("username"))
        block("https://paypal.com@evil.example/")
    }

    @Test fun dangerousAndCustomSchemesAreAlwaysBlocked() {
        for (url in listOf(
            "javascript:alert(1)",
            "intent://x#Intent;scheme=http;end",
            "file:///sdcard/Download/a.html",
            "content://com.android.providers/x",
            "data:text/html;base64,PGgxPg==",
            "blob:https://example.com/uuid",
            "market://details?id=com.x",
            "ftp://example.com/file",
            "myapp://open",
        )) {
            // Even a real user tap never loads these.
            block(url, gesture = true)
            block(url, gesture = false)
        }
    }

    @Test fun blockMessageNamesTheScheme() {
        assertTrue(block("javascript:alert(1)").message.contains("javascript"))
    }

    @Test fun mailAndPhoneLinksNeedARealTapOnTheMainPage() {
        assertEquals(
            NavDecision.OpenExternally("mailto:a@b.com"),
            NavigationPolicy.decide("mailto:a@b.com", hasGesture = true, isMainFrame = true),
        )
        assertEquals(
            NavDecision.OpenExternally("tel:123"),
            NavigationPolicy.decide("tel:123", hasGesture = true, isMainFrame = true),
        )
        block("mailto:a@b.com", gesture = false)
        block("tel:123", gesture = true, main = false)
        block("sms:123", gesture = false)
    }

    @Test fun emptyAndSchemelessAreBlocked() {
        block(null)
        block("")
        block("   ")
        block("no scheme here")
        block("https://")
    }
}
