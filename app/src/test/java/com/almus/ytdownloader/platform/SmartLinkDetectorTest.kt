package com.almus.ytdownloader.platform

import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartLinkDetectorTest {
    private val detector = SmartLinkDetector()
    private val yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    // ------------------------------------------------------------------ validation and blocking

    @Test fun codeRunningSchemesAreBlocked() {
        listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", "  javascript:alert(1)  ", "data:text/html,<b>x</b>",
            "file:///sdcard/a.txt", "intent://x#Intent;scheme=http;end", "vbscript:msgbox(1)", "content://media/1", "blob:https://a.com/1",
        ).forEach {
            val d = detector.detect(it)
            assertEquals("$it should be blocked", LinkVerdict.Blocked, d.verdict)
            assertNull(d.url)
            assertNull(d.platform)
            assertTrue(d.message.contains("blocked"))
            assertFalse(d.canCheckAsFile)
        }
    }

    @Test fun passwordInsideTheAddressIsBlocked() {
        val d = detector.detect("https://www.youtube.com@evil.com/watch?v=x")
        assertEquals(LinkVerdict.Blocked, d.verdict)
        assertTrue(d.message.contains("username or password"))
        assertEquals(LinkVerdict.Blocked, detector.detect("https://user:pw@example.com/a.zip").verdict)
    }

    @Test fun otherBadInputIsInvalidNotBlocked() {
        listOf("", "   ", "ftp://example.com/a", "mailto:a@b.com", "youtube.com/watch?v=dQw4w9WgXcQ", "https://", "https://exa mple.com/a", "https:///a")
            .forEach { assertEquals("$it", LinkVerdict.Invalid, detector.detect(it).verdict) }
        assertEquals(LinkVerdict.Invalid, detector.detect(null).verdict)
        assertEquals(LinkVerdict.Invalid, detector.detect("https://example.com/" + "a".repeat(2100)).verdict)
    }

    @Test fun invalidResultsKeepTheValidatorMessage() {
        val expected = (UrlValidator.validate("ftp://x.com/a") as UrlValidation.Invalid).error.message
        assertEquals(expected, detector.detect("ftp://x.com/a").message)
    }

    @Test fun whitespaceAroundAValidLinkIsTrimmed() {
        val d = detector.detect("  $yt \n")
        assertEquals(MediaPlatform.YouTube, d.platform)
        assertEquals(yt, d.url)
    }

    // ------------------------------------------------------------------ verdicts and messages

    @Test fun platformPageIsExplainedAndNotOfferedAsAFile() {
        val d = detector.detect(yt)
        assertEquals(LinkVerdict.PageNotDownloadable, d.verdict)
        assertEquals("YouTube video", d.title)
        assertTrue(d.message.contains("not to a file"))
        assertTrue(d.message.contains("YouTube"))
        assertTrue(d.details.any { it.contains("dQw4w9WgXcQ") })
        assertFalse(d.canCheckAsFile)
    }

    @Test fun restrictedLinksExplainWhy() {
        val d = detector.detect("https://www.instagram.com/stories/someone/123/")
        assertEquals(LinkVerdict.Restricted, d.verdict)
        assertEquals(RestrictionReason.LoginRequired, d.restriction)
        assertTrue(d.message.contains("never signs in"))
        assertTrue(d.message.contains("Instagram"))
    }

    @Test fun everyRestrictionReasonHasAMessage() {
        RestrictionReason.entries.forEach { assertTrue(it.message("Example").isNotBlank()) }
    }

    @Test fun directFileAndServerCheckGoOnToTheFileCheck() {
        val file = detector.detect("https://example.com/video.mp4")
        assertEquals(LinkVerdict.DirectFile, file.verdict)
        assertTrue(file.canCheckAsFile)
        assertEquals("Direct link · MP4 video file", file.hint)
        assertNotNull(file.media)

        val web = detector.detect("https://example.com/download?id=3")
        assertEquals(LinkVerdict.ServerCheck, web.verdict)
        assertTrue(web.canCheckAsFile)
    }

    @Test fun shortLinkAsksForASafeRedirectCheck() {
        val d = detector.detect("https://t.co/AbC123")
        assertEquals(LinkVerdict.ShortLink, d.verdict)
        assertFalse(d.canCheckAsFile)
        assertTrue(d.message.contains("${SmartLinkDetector.MAX_HOPS}"))
    }

    @Test fun sourceLineTellsWhereAFileComesFrom() {
        assertEquals("Source: direct link", detector.detect("https://example.com/a.zip").sourceLine)
        assertEquals("Source: file hosted by Reddit", detector.detect("https://i.redd.it/abcdef123.jpg").sourceLine)
        assertNull(detector.detect("javascript:alert(1)").sourceLine)
    }

    // ------------------------------------------------------------------ what a redirect check saw

    @Test fun refusedPlatformPageBecomesRestricted() {
        val d = detector.detect(yt, ObservedResponse(listOf("https://youtu.be/x", yt), 403))
        assertEquals(LinkVerdict.Restricted, d.verdict)
        assertEquals(RestrictionReason.PrivateOrProtected, d.restriction)
        assertEquals(2, d.redirectHops.size)
    }

    @Test fun missingPlatformPageBecomesUnavailable() {
        listOf(404, 410).forEach {
            val d = detector.detect(yt, ObservedResponse(status = it))
            assertEquals(LinkVerdict.Restricted, d.verdict)
            assertEquals(RestrictionReason.Unavailable, d.restriction)
        }
    }

    @Test fun anOkAnswerChangesNothingAndFileLinksIgnoreStatus() {
        assertEquals(LinkVerdict.PageNotDownloadable, detector.detect(yt, ObservedResponse(status = 200)).verdict)
        assertEquals(LinkVerdict.DirectFile, detector.detect("https://example.com/a.zip", ObservedResponse(status = 404)).verdict)
    }

    // ------------------------------------------------------------------ safety notes

    private fun kinds(url: String) = detector.detect(url).notes.map { it.kind }

    @Test fun plainHttpIsNoted() {
        assertTrue(SafetyNoteKind.InsecureHttp in kinds("http://example.com/a.zip"))
        assertFalse(SafetyNoteKind.InsecureHttp in kinds("https://example.com/a.zip"))
    }

    @Test fun localNetworkIsNotedButNotBlocked() {
        val d = detector.detect("http://192.168.1.5/a.zip")
        assertTrue(SafetyNoteKind.LocalNetwork in d.notes.map { it.kind })
        assertEquals(LinkVerdict.DirectFile, d.verdict)
    }

    @Test fun punycodeIsNoted() {
        assertTrue(SafetyNoteKind.InternationalizedName in kinds("https://xn--e1afmkfd.com/a.zip"))
    }

    @Test fun lookalikeAddressesAreWarnedAbout() {
        listOf("https://youtube-login.com/a.zip", "https://faceb00k.com/a.zip", "https://notyoutube.com/x", "https://tiktok-free-coins.net/a.zip")
            .forEach { assertTrue("$it", SafetyNoteKind.LookalikeAddress in kinds(it)) }
        assertTrue(detector.detect("https://faceb00k.com/a.zip").notes.first { it.kind == SafetyNoteKind.LookalikeAddress }.message.contains("Facebook"))
    }

    @Test fun realPlatformAddressesAreNotCalledLookalikes() {
        listOf(yt, "https://youtu.be/dQw4w9WgXcQ", "https://www.facebook.com/somepage", "https://i.redd.it/a.jpg", "https://www.pinterest.co.uk/pin/123456/")
            .forEach { assertFalse("$it", SafetyNoteKind.LookalikeAddress in kinds(it)) }
    }

    @Test fun ordinaryAddressesHaveNoNotes() {
        assertEquals(emptyList<SafetyNote>(), detector.detect("https://example.com/a.zip").notes)
    }

    // ------------------------------------------------------------------ adapter architecture

    @Test fun eachPlatformHasExactlyOneAdapter() {
        val platforms = SmartLinkDetector.defaultAdapters.map { it.platform }
        assertEquals(platforms.size, platforms.toSet().size)
        assertEquals(
            setOf(
                MediaPlatform.YouTube, MediaPlatform.Facebook, MediaPlatform.TikTok, MediaPlatform.Instagram, MediaPlatform.X,
                MediaPlatform.Vimeo, MediaPlatform.Dailymotion, MediaPlatform.Reddit, MediaPlatform.Pinterest,
            ),
            platforms.toSet(),
        )
        assertFalse(MediaPlatform.DirectLink in platforms)
    }

    @Test fun noHostIsOwnedByTwoAdapters() {
        val hosts = listOf(
            "www.youtube.com", "youtu.be", "i.ytimg.com", "rr1.googlevideo.com", "www.facebook.com", "fb.watch", "video.fbcdn.net",
            "www.tiktok.com", "vm.tiktok.com", "www.instagram.com", "scontent.cdninstagram.com", "x.com", "twitter.com", "t.co", "pbs.twimg.com",
            "vimeo.com", "player.vimeo.com", "www.dailymotion.com", "dai.ly", "www.reddit.com", "redd.it", "i.redd.it", "v.redd.it",
            "www.pinterest.com", "pin.it", "i.pinimg.com",
        )
        hosts.forEach { h ->
            val owners = SmartLinkDetector.defaultAdapters.filter { it.ownsHost(h) }
            assertEquals("$h owners: ${owners.map { it.platform }}", 1, owners.size)
        }
    }

    @Test fun lookalikeHostsAreOwnedByNoPlatformAdapter() {
        listOf(
            "youtube.com.evil.com", "notyoutube.com", "facebook.com.evil.org", "x.com.evil.com", "myx.com", "t.co.evil.com",
            "pinterest.com.evil.com", "pinterest.evil.com", "evilpinterest.com", "redd.it.evil.com", "vimeo.com.evil.org", "evil.com",
        ).forEach { h ->
            assertTrue("$h", SmartLinkDetector.defaultAdapters.none { it.ownsHost(h) })
        }
    }

    @Test fun aCustomAdapterCanBePlugged() {
        val fake = object : MediaPlatformAdapter {
            override val platform = MediaPlatform.Vimeo
            override val brandTokens = emptyList<String>()
            override fun ownsHost(host: String) = host == "only.example"
            override fun classify(url: ParsedUrl) = LinkClassification(LinkKind.Video, "42")
        }
        val d = SmartLinkDetector(adapters = listOf(fake)).detect("https://only.example/x")
        assertEquals(MediaPlatform.Vimeo, d.platform)
        assertEquals("42", d.contentId)
        assertEquals(MediaPlatform.DirectLink, SmartLinkDetector(adapters = listOf(fake)).detect("https://other.example/x").platform)
    }

    @Test fun detectionNeverTouchesTheNetworkOrThrows() {
        listOf("https://example.com/%zz", "https://a.b/" + "x/".repeat(500), "https://[::1]/", "http://0.0.0.0/", "https://xn--/", "https://a--b.com/?=&&=")
            .forEach { detector.detect(it) }
    }
}
