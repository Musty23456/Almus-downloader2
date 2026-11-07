package com.almus.ytdownloader.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data-driven: every line of src/test/resources/link_vectors.tsv is
 * url, platform, kind, verdict, content id ("-" for none).
 * One list covers all nine platforms, direct links and look-alike hosts.
 */
class LinkVectorsTest {
    private val detector = SmartLinkDetector()

    private data class Vector(val url: String, val platform: String, val kind: String, val verdict: String, val id: String)

    private fun vectors(): List<Vector> {
        val stream = javaClass.classLoader!!.getResourceAsStream("link_vectors.tsv")
        assertTrue("link_vectors.tsv is missing from the test resources", stream != null)
        return stream!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map {
                val p = it.split('\t')
                assertEquals("bad vector line: $it", 5, p.size)
                Vector(p[0], p[1], p[2], p[3], p[4])
            }
    }

    @Test fun everyVectorIsDetectedAsExpected() {
        val all = vectors()
        assertTrue("expected a large vector list", all.size > 100)
        val failures = all.mapNotNull { v ->
            val d = detector.detect(v.url)
            val got = listOf(d.platform?.name, d.kind.name, d.verdict.name, d.contentId ?: "-")
            val want = listOf(v.platform, v.kind, v.verdict, v.id)
            if (got == want) null else "${v.url}\n    expected $want\n    got      $got"
        }
        assertTrue("mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun everyPlatformHasAtLeastOnePageAndOneRestrictionOrFileVector() {
        val all = vectors()
        val platforms = SmartLinkDetector.defaultAdapters.map { it.platform.name }
        assertEquals(9, platforms.size)
        platforms.forEach { name ->
            assertTrue("no page vector for $name", all.any { it.platform == name && it.verdict == "PageNotDownloadable" })
            assertTrue("no file/restricted vector for $name", all.any { it.platform == name && (it.verdict == "DirectFile" || it.verdict == "Restricted") })
        }
    }

    @Test fun aPlatformPageIsNeverOfferedAsAFileDownload() {
        vectors().forEach { v ->
            val d = detector.detect(v.url)
            if (d.verdict == LinkVerdict.PageNotDownloadable || d.verdict == LinkVerdict.Restricted) {
                assertFalse("${v.url} must not go to the file check", d.canCheckAsFile)
            }
        }
    }

    @Test fun everyResultHasATitleAndAMessage() {
        vectors().forEach { v ->
            val d = detector.detect(v.url)
            assertTrue("no title for ${v.url}", d.title.isNotBlank())
            assertTrue("no message for ${v.url}", d.message.isNotBlank())
            assertTrue("no hint for ${v.url}", d.hint.isNotBlank())
        }
    }
}
