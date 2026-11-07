package com.almus.ytdownloader.platform

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the resolver against scripted fake connections: no network, and the response body can never be read. */
class SafeRedirectResolverTest {
    private class Reply(val code: Int, val location: String? = null, val error: IOException? = null)

    private class Recorder {
        val methods = mutableListOf<String>()
        val headerNames = mutableSetOf<String>()
        val rangeSentOn = mutableListOf<String>()
    }

    private class FakeConnection(
        url: URL,
        private val script: (String, String) -> Reply,
        private val recorder: Recorder,
    ) : HttpURLConnection(url) {
        // Evaluated once, on the first answer, after the request method was set.
        private val reply: Reply by lazy { script(getURL().toString(), requestMethod) }

        override fun disconnect() {}
        override fun usingProxy() = false
        override fun connect() {}

        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null) {
                recorder.headerNames += key
                if (key == "Range") recorder.rangeSentOn += requestMethod
            }
            super.setRequestProperty(key, value)
        }

        override fun getResponseCode(): Int {
            recorder.methods += requestMethod
            reply.error?.let { throw it }
            return reply.code
        }

        override fun getHeaderField(name: String?): String? = if (name.equals("Location", ignoreCase = true)) reply.location else null

        override fun getInputStream(): InputStream = throw AssertionError("the response body must never be read")
    }

    private fun resolve(
        start: String,
        recorder: Recorder = Recorder(),
        max: Int = SafeRedirectResolver.MAX_REDIRECTS,
        script: (String, String) -> Reply,
    ): RedirectTrace {
        val resolver = SafeRedirectResolver(maxRedirects = max, openConnection = { FakeConnection(it, script, recorder) })
        return runBlocking { resolver.resolve(start) }
    }

    private fun failed(trace: RedirectTrace): String = (trace as RedirectTrace.Failed).message

    @Test fun followsAbsoluteAndRelativeRedirectsAndListsTheHops() {
        val trace = resolve("https://short.example/a") { url, _ ->
            when (url) {
                "https://short.example/a" -> Reply(302, "https://short.example/b")
                "https://short.example/b" -> Reply(301, "/c")
                else -> Reply(200)
            }
        } as RedirectTrace.Resolved
        assertEquals("https://short.example/c", trace.finalUrl)
        assertEquals(listOf("https://short.example/a", "https://short.example/b", "https://short.example/c"), trace.hops)
        assertEquals(200, trace.status)
    }

    @Test fun noRedirectMeansTheSameAddress() {
        val trace = resolve("https://example.com/x") { _, _ -> Reply(200) } as RedirectTrace.Resolved
        assertEquals("https://example.com/x", trace.finalUrl)
        assertEquals(1, trace.hops.size)
    }

    @Test fun stopsAfterTheMaximumNumberOfRedirects() {
        var n = 0
        val trace = resolve("https://loop.example/0") { _, _ -> Reply(302, "https://loop.example/${++n}") }
        assertTrue(failed(trace).contains("too many"))
    }

    @Test fun exactlyTheMaximumIsStillAllowed() {
        val trace = resolve("https://x.example/0", max = 2) { url, _ ->
            when (url) {
                "https://x.example/0" -> Reply(302, "/1")
                "https://x.example/1" -> Reply(302, "/2")
                else -> Reply(200)
            }
        }
        assertTrue(trace is RedirectTrace.Resolved)
    }

    @Test fun detectsACircle() {
        val trace = resolve("https://c.example/a") { url, _ ->
            if (url.endsWith("/a")) Reply(302, "/b") else Reply(302, "/a")
        }
        assertTrue(failed(trace).contains("circle"))
    }

    @Test fun neverGoesFromHttpsToHttp() {
        val trace = resolve("https://secure.example/a") { _, _ -> Reply(302, "http://secure.example/b") }
        assertTrue(failed(trace).contains("insecure"))
    }

    @Test fun httpToHttpsIsFine() {
        val trace = resolve("http://plain.example/a") { url, _ ->
            if (url.startsWith("http://")) Reply(301, "https://plain.example/a") else Reply(200)
        }
        assertTrue(trace is RedirectTrace.Resolved)
    }

    @Test fun neverGoesFromAPublicAddressToTheLocalNetwork() {
        listOf("https://192.168.0.1/admin", "https://10.0.0.2/", "https://localhost/x", "https://169.254.169.254/latest", "https://[::1]/")
            .forEach { target ->
                val trace = resolve("https://public.example/a") { _, _ -> Reply(302, target) }
                assertTrue("$target: ${(trace as? RedirectTrace.Failed)?.message}", failed(trace).contains("local network"))
            }
    }

    @Test fun aLocalStartMayStayLocal() {
        val trace = resolve("http://localhost:8080/a") { url, _ ->
            if (url.endsWith("/a")) Reply(302, "http://localhost:8080/b") else Reply(200)
        }
        assertTrue(trace is RedirectTrace.Resolved)
    }

    @Test fun refusesTargetsThatAreNotWebAddresses() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "data:text/html,x", "ftp://example.com/a").forEach { target ->
            val trace = resolve("https://public.example/a") { _, _ -> Reply(302, target) }
            assertTrue("$target should fail", trace is RedirectTrace.Failed)
        }
    }

    @Test fun refusesATargetWithCredentials() {
        val trace = resolve("https://public.example/a") { _, _ -> Reply(302, "https://user:pw@evil.example/") }
        assertTrue(trace is RedirectTrace.Failed)
    }

    @Test fun aRedirectWithoutALocationFails() {
        assertTrue(failed(resolve("https://x.example/a") { _, _ -> Reply(302) }).contains("without saying"))
        assertTrue(failed(resolve("https://x.example/a") { _, _ -> Reply(302, "  ") }).contains("without saying"))
    }

    @Test fun anInvalidStartFails() {
        assertTrue(resolve("javascript:alert(1)") { _, _ -> Reply(200) } is RedirectTrace.Failed)
        assertTrue(resolve("") { _, _ -> Reply(200) } is RedirectTrace.Failed)
    }

    @Test fun retriesOnceWithAOneByteGetWhenHeadIsRefused() {
        val recorder = Recorder()
        val trace = resolve("https://x.example/a", recorder) { _, method -> if (method == "HEAD") Reply(405) else Reply(200) }
        assertTrue(trace is RedirectTrace.Resolved)
        assertEquals(listOf("HEAD", "GET"), recorder.methods)
        assertEquals(listOf("GET"), recorder.rangeSentOn)
    }

    @Test fun aFinalErrorStatusIsReportedNotThrown() {
        val trace = resolve("https://x.example/a") { _, _ -> Reply(404) } as RedirectTrace.Resolved
        assertEquals(404, trace.status)
        val denied = resolve("https://x.example/a") { _, _ -> Reply(403) } as RedirectTrace.Resolved
        assertEquals(403, denied.status)
    }

    @Test fun sendsOnlyPlainHeadersAndNeverReadsTheBody() {
        val recorder = Recorder()
        resolve("https://x.example/a", recorder) { _, method -> if (method == "HEAD") Reply(405) else Reply(200) }
        assertEquals(setOf("User-Agent", "Accept-Encoding", "Accept", "Range"), recorder.headerNames)
        // getInputStream() throws AssertionError in the fake: reaching this line proves it was never called.
    }

    @Test fun networkProblemsBecomeFriendlyMessages() {
        assertTrue(failed(resolve("https://x.example/a") { _, _ -> Reply(0, error = UnknownHostException("x")) }).contains("find"))
        assertTrue(failed(resolve("https://x.example/a") { _, _ -> Reply(0, error = SocketTimeoutException("x")) }).contains("too long"))
        assertTrue(failed(resolve("https://x.example/a") { _, _ -> Reply(0, error = SSLHandshakeException("x")) }).contains("secure connection"))
        assertTrue(failed(resolve("https://x.example/a") { _, _ -> Reply(0, error = IOException("x")) }).contains("connect"))
    }
}
