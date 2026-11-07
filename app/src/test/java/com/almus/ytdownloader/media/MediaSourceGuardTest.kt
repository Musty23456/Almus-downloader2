package com.almus.ytdownloader.media

import com.almus.ytdownloader.media.android.OkHttpMediaHttp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the media module's own source and fails if it ever stops being "authorized sources only": no page scraping, no
 * stream or manifest handling, no sign-in, cookie or token code, no host outside the reviewed list, only reviewed
 * request headers, no web view and no file writing. Comments are ignored.
 */
class MediaSourceGuardTest {
    private val root: File = listOf("src/main/java/com/almus/ytdownloader/media", "app/src/main/java/com/almus/ytdownloader/media")
        .map { File(it) }.firstOrNull { it.exists() } ?: error("media sources not found from ${File(".").absolutePath}")

    private val sources: List<Pair<String, String>> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path to strip(it.readText()) }.toList()
    }

    private fun strip(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("(?m)(^|\\s)//[^\\n]*"), " ")

    private fun hits(pattern: Regex, include: (String) -> Boolean = { true }): List<String> =
        sources.filter { include(it.first) && pattern.containsMatchIn(it.second) }.map { File(it.first).name }.sorted()

    @Test fun sourcesWereFound() {
        assertTrue(sources.size >= 6)
        assertTrue(sources.any { it.first.endsWith("InternetArchiveSource.kt") })
        assertTrue(sources.any { it.first.endsWith("CommonsSource.kt") })
    }

    @Test fun noScrapingOrStreamHandling() {
        val pattern = Regex("(?i)og:video|jsoup|\\.select\\(|querySelector|\\bscrape|\\bmanifest|\\bm3u8|\\bmpd\\b|videoplayback|player_config|DASHPlaylist|_signature|playAddr|downloadAddr|__PWS_INITIAL|window\\.__|EXT-X-|\\.ts\\b")
        assertEquals(emptyList<String>(), hits(pattern))
    }

    @Test fun noSignInCookiesOrTokens() {
        val pattern = Regex("(?i)cookie|CookieJar|sessionid|csrf|\\bauthorization\\b|\\bbearer\\b|oauth|access_?token|api_?key|client_?secret|/login|WebViewClient|shouldInterceptRequest")
        assertEquals(emptyList<String>(), hits(pattern))
    }

    @Test fun onlyReviewedHostsAreAddressed() {
        val allowed = setOf("archive.org", "commons.wikimedia.org", "upload.wikimedia.org")
        val found = sources.flatMap { (_, text) -> Regex("https?://([A-Za-z0-9.\\-]+)").findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(found.isNotEmpty())
        assertEquals("unexpected hosts: ${found - allowed}", emptySet<String>(), found - allowed)
    }

    @Test fun onlyReviewedRequestHeadersAreSet() {
        val direct = sources.flatMap { (_, text) -> Regex("\\.header\\(\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals(setOf("User-Agent", "Accept"), direct)
    }

    @Test fun networkCodeLivesOnlyInTheAndroidFolder() {
        assertEquals(listOf("OkHttpMediaHttp.kt"), hits(Regex("import\\s+okhttp3\\.")))
        val core = { path: String -> !path.contains("${File.separator}android${File.separator}") }
        assertEquals(emptyList<String>(), hits(Regex("import\\s+(android|androidx|okhttp3|okio)\\."), core))
        assertEquals(emptyList<String>(), hits(Regex("HttpURLConnection|openConnection|java\\.net\\.URL\\b|java\\.net\\.Socket|WebView|ProcessBuilder"), core))
    }

    @Test fun nothingIsWrittenToStorageByThisModule() {
        assertEquals(emptyList<String>(), hits(Regex("FileOutputStream|openFileOutput|MediaStore|DownloadManager|ContentResolver|File\\(|writeBytes|\\.copyTo")))
    }

    @Test fun theTransportRefusesEverythingExceptTheTwoMetadataServices() {
        assertTrue(OkHttpMediaHttp.isOfficialService("archive.org", "/metadata/bigbuck"))
        assertTrue(OkHttpMediaHttp.isOfficialService("commons.wikimedia.org", "/w/api.php"))
        assertFalse(OkHttpMediaHttp.isOfficialService("archive.org", "/download/bigbuck/a.mp4"))
        assertFalse(OkHttpMediaHttp.isOfficialService("archive.org", "/metadata/"))
        assertFalse(OkHttpMediaHttp.isOfficialService("www.archive.org", "/metadata/bigbuck"))
        assertFalse(OkHttpMediaHttp.isOfficialService("upload.wikimedia.org", "/w/api.php"))
        assertFalse(OkHttpMediaHttp.isOfficialService("commons.wikimedia.org", "/wiki/File:X.ogv"))
    }
}
