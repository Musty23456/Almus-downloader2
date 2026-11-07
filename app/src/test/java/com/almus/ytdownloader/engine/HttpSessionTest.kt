package com.almus.ytdownloader.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpSessionTest {
    private fun open(transport: FakeTransport, url: String, max: Int = HttpSession.MAX_REDIRECTS): HttpSession.Opened =
        runBlocking { HttpSession(transport, max).open(url) { TransportRequest.Builder(it).addRequestHeader("User-Agent", "test").build() } }

    private fun refused(transport: FakeTransport, url: String): String {
        try {
            open(transport, url)
        } catch (e: DownloadRefused) {
            return e.message ?: ""
        }
        fail("expected the link to be refused")
        return ""
    }

    @Test fun returnsTheFirstAnswerWhenThereIsNoRedirect() {
        val t = FakeTransport { FakeResponse(200) }
        val opened = open(t, "https://example.com/a.bin")
        assertEquals(200, opened.response.code)
        assertEquals("https://example.com/a.bin", opened.finalUrl)
    }

    @Test fun followsRedirectsAndClosesTheRedirectAnswers() {
        val t = FakeTransport { url ->
            when (url) {
                "https://a.example/1" -> FakeResponse(302, mapOf("Location" to "https://b.example/2"))
                "https://b.example/2" -> FakeResponse(301, mapOf("Location" to "/3"))
                else -> FakeResponse(200)
            }
        }
        val opened = open(t, "https://a.example/1")
        assertEquals("https://b.example/3", opened.finalUrl)
        assertEquals(listOf("https://a.example/1", "https://b.example/2", "https://b.example/3"), t.requested)
        assertTrue(t.responses[0].closed && t.responses[1].closed)
    }

    @Test fun theSameHeadersAreSentOnEveryHop() {
        val t = FakeTransport { url -> if (url.endsWith("/1")) FakeResponse(302, mapOf("Location" to "https://other.example/2")) else FakeResponse(200) }
        open(t, "https://a.example/1")
        assertTrue(t.requestHeaders.all { it == mapOf("User-Agent" to "test") })
    }

    @Test fun stopsAfterTheMaximumNumberOfRedirects() {
        var n = 0
        val t = FakeTransport { FakeResponse(302, mapOf("Location" to "https://x.example/${++n}")) }
        assertTrue(refused(t, "https://x.example/0").contains("too many"))
    }

    @Test fun detectsACircle() {
        val t = FakeTransport { url -> FakeResponse(302, mapOf("Location" to if (url.endsWith("/a")) "/b" else "/a")) }
        assertTrue(refused(t, "https://c.example/a").contains("circle"))
    }

    @Test fun neverGoesFromHttpsToHttp() {
        val t = FakeTransport { FakeResponse(302, mapOf("Location" to "http://s.example/b")) }
        assertTrue(refused(t, "https://s.example/a").contains("insecure"))
    }

    @Test fun neverGoesFromAPublicAddressToTheLocalNetwork() {
        listOf("https://192.168.0.1/x", "https://localhost/x", "https://10.1.2.3/", "https://[::1]/").forEach { target ->
            val t = FakeTransport { FakeResponse(302, mapOf("Location" to target)) }
            assertTrue(target, refused(t, "https://public.example/a").contains("local network"))
        }
    }

    @Test fun refusesTargetsThatAreNotWebAddresses() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "ftp://example.com/a", "https://user:pw@evil.example/").forEach { target ->
            val t = FakeTransport { FakeResponse(302, mapOf("Location" to target)) }
            refused(t, "https://public.example/a")
        }
    }

    @Test fun aRedirectWithoutALocationIsRefused() {
        assertTrue(refused(FakeTransport { FakeResponse(302) }, "https://x.example/a").contains("without saying"))
    }

    @Test fun anInvalidStartIsRefused() {
        assertTrue(refused(FakeTransport { FakeResponse(200) }, "javascript:alert(1)").isNotBlank())
        assertEquals(emptyList<String>(), FakeTransport { FakeResponse(200) }.requested)
    }
}
