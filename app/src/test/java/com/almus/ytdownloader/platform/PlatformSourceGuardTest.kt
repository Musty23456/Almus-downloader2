package com.almus.ytdownloader.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the detection package's own source and fails if it ever stops being "detection only":
 * it must not use Android APIs, open links in a WebView or another app, start programs, read response bodies,
 * or touch the network anywhere except the one reviewed redirect resolver. Adapters must stay independent.
 * Comments are ignored. Gradle runs unit tests with the module folder (app/) as the working directory.
 */
class PlatformSourceGuardTest {
    private val root: File = listOf("src/main/java/com/almus/ytdownloader/platform", "app/src/main/java/com/almus/ytdownloader/platform")
        .map { File(it) }.firstOrNull { it.exists() } ?: error("platform sources not found from ${File(".").absolutePath}")

    private val sources: List<Pair<String, String>> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path to strip(it.readText()) }.toList()
    }

    private fun strip(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("(?m)(^|\\s)//[^\\n]*"), " ")

    private fun hits(pattern: Regex, include: (String) -> Boolean = { true }): List<String> =
        sources.filter { include(it.first) && pattern.containsMatchIn(it.second) }.map { File(it.first).name }

    @Test fun sourcesWereFound() {
        assertTrue(sources.size >= 18)
        assertTrue(sources.any { it.first.endsWith("SmartLinkDetector.kt") })
    }

    @Test fun noAndroidApiIsUsed() {
        assertEquals(emptyList<String>(), hits(Regex("import\\s+android\\.")))
    }

    @Test fun nothingIsOpenedLaunchedOrRun() {
        val pattern = Regex("WebView|\\bIntent\\b|startActivity|loadUrl|evaluateJavascript|ProcessBuilder|Runtime\\.getRuntime|Class\\.forName|ClassLoader|System\\.load")
        assertEquals(emptyList<String>(), hits(pattern))
    }

    @Test fun responseBodiesAreNeverRead() {
        assertEquals(emptyList<String>(), hits(Regex("inputStream|getInputStream|readBytes|readText|bufferedReader|\\.content\\b")))
    }

    @Test fun onlyTheRedirectResolverTouchesTheNetwork() {
        val net = Regex("HttpURLConnection|openConnection|java\\.net\\.URL\\b|java\\.net\\.Socket|kotlinx\\.coroutines")
        assertEquals(listOf("SafeRedirectResolver.kt"), hits(net).filter { it != "LinkIntake.kt" }.distinct())
    }

    @Test fun adaptersAreIndependentOfEachOther() {
        val adapterFiles = sources.filter { it.first.contains("${File.separator}adapters${File.separator}") }
        val names = adapterFiles.map { File(it.first).nameWithoutExtension }
        assertEquals(10, names.size)
        adapterFiles.forEach { (path, text) ->
            val self = File(path).nameWithoutExtension
            val others = names.filter { it != self }
            others.forEach { other ->
                assertTrue("$self refers to $other", !Regex("\\b$other\\b").containsMatchIn(text))
            }
            assertTrue("$self must implement MediaPlatformAdapter", Regex("object\\s+$self\\s*:\\s*MediaPlatformAdapter").containsMatchIn(text))
        }
    }

    @Test fun adaptersDoNotUseTheNetworkOrCoroutines() {
        val bad = hits(Regex("java\\.net|kotlinx|suspend|withContext|Dispatchers")) { it.contains("${File.separator}adapters${File.separator}") }
        assertEquals(emptyList<String>(), bad)
    }
}
