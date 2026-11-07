package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.InspectResult
import com.almus.ytdownloader.download.LinkInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkIntakeTest {
    private val yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    private class Calls {
        val resolved = mutableListOf<String>()
        val inspected = mutableListOf<String>()
    }

    private fun info(url: String) = LinkInfo(
        url = url, finalUrl = url, fileName = "a.zip", mimeType = "application/zip", contentLength = 10,
        redirectCount = 0, insecure = false, looksLikeWebPage = false,
    )

    private fun intake(
        calls: Calls,
        duplicate: Boolean = false,
        inspectResult: (String) -> InspectResult = { InspectResult.Success(info(it)) },
        resolve: (String) -> RedirectTrace = { error("no redirect check expected for $it") },
    ) = LinkIntake(
        detector = SmartLinkDetector(),
        resolve = { calls.resolved += it; resolve(it) },
        inspect = { calls.inspected += it; inspectResult(it) },
        isDuplicate = { duplicate },
    )

    private fun check(i: LinkIntake, url: String, skip: Boolean = false) = runBlocking { i.check(url, skip) }

    @Test fun aDirectFileLinkGoesToTheServerCheck() {
        val calls = Calls()
        val result = check(intake(calls), "https://example.com/a.zip") as IntakeResult.Ready
        assertEquals(listOf("https://example.com/a.zip"), calls.inspected)
        assertEquals(MediaPlatform.DirectLink, result.detection.platform)
        assertEquals(emptyList<String>(), calls.resolved)
    }

    @Test fun anOrdinaryWebLinkGoesToTheServerCheck() {
        val calls = Calls()
        assertTrue(check(intake(calls), "https://example.com/download?id=3") is IntakeResult.Ready)
        assertEquals(1, calls.inspected.size)
    }

    @Test fun aPlatformPageIsExplainedAndTheServerIsNotAsked() {
        val calls = Calls()
        val result = check(intake(calls), yt) as IntakeResult.Detected
        assertEquals(MediaPlatform.YouTube, result.detection.platform)
        assertEquals(emptyList<String>(), calls.inspected)
    }

    @Test fun theUserMayCheckAPlatformPageAsAnOrdinaryLink() {
        val calls = Calls()
        assertTrue(check(intake(calls), yt, skip = true) is IntakeResult.Ready)
        assertEquals(listOf(yt), calls.inspected)
    }

    @Test fun aRestrictedLinkCanNeverBeForced() {
        val calls = Calls()
        val url = "https://www.instagram.com/stories/someone/123/"
        assertTrue(check(intake(calls), url) is IntakeResult.Detected)
        assertTrue(check(intake(calls), url, skip = true) is IntakeResult.Detected)
        assertEquals(emptyList<String>(), calls.inspected)
    }

    @Test fun aShortLinkIsResolvedThenCheckedAsAFile() {
        val calls = Calls()
        val i = intake(calls, resolve = { RedirectTrace.Resolved("https://example.com/f.zip", listOf(it, "https://example.com/f.zip"), 200) })
        val result = check(i, "https://t.co/AbC123") as IntakeResult.Ready
        assertEquals(listOf("https://t.co/AbC123"), calls.resolved)
        assertEquals(listOf("https://example.com/f.zip"), calls.inspected)
        assertEquals(MediaPlatform.DirectLink, result.detection.platform)
    }

    @Test fun aShortLinkToAPlatformPageIsExplained() {
        val calls = Calls()
        val i = intake(calls, resolve = { RedirectTrace.Resolved(yt, listOf(it, yt), 200) })
        val result = check(i, "https://t.co/AbC123") as IntakeResult.Detected
        assertEquals(LinkVerdict.PageNotDownloadable, result.detection.verdict)
        assertEquals(2, result.detection.redirectHops.size)
        assertEquals(emptyList<String>(), calls.inspected)
    }

    @Test fun aShortLinkToARefusedPageIsRestricted() {
        val calls = Calls()
        val i = intake(calls, resolve = { RedirectTrace.Resolved(yt, listOf(it, yt), 403) })
        val result = check(i, "https://t.co/AbC123") as IntakeResult.Detected
        assertEquals(LinkVerdict.Restricted, result.detection.verdict)
    }

    @Test fun aFailedRedirectCheckStopsEverything() {
        val calls = Calls()
        val i = intake(calls, resolve = { RedirectTrace.Failed("blocked redirect") })
        val result = check(i, "https://t.co/AbC123") as IntakeResult.Rejected
        assertEquals("blocked redirect", result.message)
        assertEquals(emptyList<String>(), calls.inspected)
    }

    @Test fun aShortLinkThatLeadsToAnotherShortLinkIsRefused() {
        val calls = Calls()
        val i = intake(calls, resolve = { RedirectTrace.Resolved("https://t.co/Other1", listOf(it), 200) })
        assertTrue(check(i, "https://t.co/AbC123") is IntakeResult.Rejected)
        assertEquals(emptyList<String>(), calls.inspected)
    }

    @Test fun dangerousAndInvalidLinksNeverReachTheNetwork() {
        val calls = Calls()
        listOf("javascript:alert(1)", "file:///etc/passwd", "https://u:p@example.com/a", "", "not a link", "ftp://example.com/a").forEach {
            assertTrue("$it", check(intake(calls), it) is IntakeResult.Rejected)
        }
        assertEquals(emptyList<String>(), calls.inspected)
        assertEquals(emptyList<String>(), calls.resolved)
    }

    @Test fun aDuplicateIsRejectedBeforeTheServerIsAsked() {
        val calls = Calls()
        val result = check(intake(calls, duplicate = true), "https://example.com/a.zip") as IntakeResult.Rejected
        assertTrue(result.message.contains("already"))
        assertEquals(emptyList<String>(), calls.inspected)
    }

    @Test fun theServersRefusalIsPassedOn() {
        val calls = Calls()
        val i = intake(calls, inspectResult = { InspectResult.Failure("File not found (404).") })
        assertEquals("File not found (404).", (check(i, "https://example.com/a.zip") as IntakeResult.Rejected).message)
    }
}
