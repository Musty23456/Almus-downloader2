package com.almus.ytdownloader.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine core must stay free of Android, must only reach the network through [HttpTransport]
 * (OkHttp lives in one file), and must never handle cookies or login data.
 */
class EngineSourceGuardTest {
    private val root: File = listOf("src/main/java/com/almus/ytdownloader/engine", "app/src/main/java/com/almus/ytdownloader/engine")
        .map { File(it) }.firstOrNull { it.exists() } ?: error("engine sources not found from ${File(".").absolutePath}")

    private val sources: List<Pair<String, String>> = root.listFiles { f -> f.extension == "kt" }!!
        .map { it.name to it.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("(?m)(^|\\s)//[^\\n]*"), " ") }

    private fun hits(pattern: Regex): List<String> = sources.filter { pattern.containsMatchIn(it.second) }.map { it.first }.sorted()

    @Test fun sourcesWereFound() {
        assertTrue(sources.size >= 8)
        assertTrue(sources.any { it.first == "DownloadEngine.kt" })
    }

    @Test fun noAndroidApiInTheEngineCore() {
        assertEquals(emptyList<String>(), hits(Regex("import\\s+android\\.")))
    }

    @Test fun okHttpIsUsedInOneFileOnly() {
        assertEquals(listOf("OkHttpTransport.kt"), hits(Regex("import\\s+okhttp3\\.")))
    }

    @Test fun noPlatformUrlConnectionsAreUsedDirectly() {
        assertEquals(emptyList<String>(), hits(Regex("HttpURLConnection|openConnection")))
    }

    @Test fun noCookiesOrLoginData() {
        assertEquals(emptyList<String>(), hits(Regex("(?i)cookie|authorization|bearer|setAuthenticator|CookieJar")))
    }

    @Test fun onlyReviewedRequestHeadersAreSet() {
        val allowed = setOf("User-Agent", "Accept-Encoding", "Accept", "Range", "If-Range")
        val used = sources.flatMap { (_, text) -> Regex("addRequestHeader\\s*\\(\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals(allowed, used)
    }
}
