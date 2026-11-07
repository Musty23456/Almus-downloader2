package com.almus.ytdownloader.platform

import com.almus.ytdownloader.platform.adapters.TikTokAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL detection for TikTok: videos in every address shape, short links, other pages, file hosts and look-alikes. */
class TikTokAdapterTest {
    private val detector = SmartLinkDetector()
    private val id = "7234567890123456789"

    private fun detect(url: String?, observed: ObservedResponse? = null) = detector.detect(url, observed)

    private fun assertKind(url: String, kind: LinkKind, verdict: LinkVerdict, contentId: String? = null) {
        val d = detect(url)
        assertEquals("platform of $url", MediaPlatform.TikTok, d.platform)
        assertEquals("kind of $url", kind, d.kind)
        assertEquals("verdict of $url", verdict, d.verdict)
        assertEquals("id of $url", contentId, d.contentId)
    }

    @Test fun recognisesEveryVideoShape() {
        val page = LinkVerdict.PageNotDownloadable
        listOf(
            "https://www.tiktok.com/@user/video/$id",
            "https://www.tiktok.com/@user/video/$id/",
            "https://www.tiktok.com/@user/video/$id?is_from_webapp=1&sender_device=pc",
            "https://www.tiktok.com/@user/video/$id?_r=1&_t=ZT-abc&u_code=xyz&share_app_id=1233#fragment",
            "https://tiktok.com/@user/video/$id",
            "https://m.tiktok.com/@user/video/$id",
            "https://us.tiktok.com/@user/video/$id",
            "https://www.tiktok.com/@some.user_01/video/$id",
            "https://www.tiktok.com/@/video/$id",
            "https://WWW.TIKTOK.COM/@User/video/$id",
            "http://www.tiktok.com/@user/video/$id",
            "https://www.tiktok.com/embed/$id",
            "https://www.tiktok.com/embed/v2/$id",
            "https://www.tiktok.com/player/v1/$id?music_info=1&description=1",
            "https://m.tiktok.com/v/$id.html",
            "https://www.tiktok.com/v/$id",
        ).forEach { assertKind(it, LinkKind.Video, page, id) }
    }

    @Test fun recognisesPhotoLiveAndProfilePages() {
        val page = LinkVerdict.PageNotDownloadable
        assertKind("https://www.tiktok.com/@user/photo/$id", LinkKind.Photo, page, id)
        assertKind("https://www.tiktok.com/@user/live", LinkKind.Live, page)
        assertKind("https://www.tiktok.com/live", LinkKind.Live, page)
        assertKind("https://www.tiktok.com/@user", LinkKind.Channel, page, "@user")
        assertKind("https://www.tiktok.com/", LinkKind.Home, page)
    }

    @Test fun shortLinksNeedARedirectCheckAndNothingElse() {
        listOf(
            "https://vm.tiktok.com/ZMabc123/",
            "https://vt.tiktok.com/ZSabc123/",
            "https://vm.tiktok.com/ZMabc123",
            "https://www.tiktok.com/t/ZTabc123/",
            "https://www.tiktok.com/t/ZTabc123",
            "https://m.tiktok.com/t/ZTabc123",
        ).forEach { assertKind(it, LinkKind.ShortLink, LinkVerdict.ShortLink) }
        assertKind("https://vm.tiktok.com/", LinkKind.Home, LinkVerdict.PageNotDownloadable)
        assertKind("https://www.tiktok.com/t", LinkKind.Other, LinkVerdict.PageNotDownloadable)
    }

    @Test fun signInPagesAreRestricted() {
        assertKind("https://www.tiktok.com/login", LinkKind.Login, LinkVerdict.Restricted)
        assertKind("https://www.tiktok.com/login/phone-or-email/email", LinkKind.Login, LinkVerdict.Restricted)
        assertKind("https://www.tiktok.com/signup", LinkKind.Login, LinkVerdict.Restricted)
        assertEquals(RestrictionReason.LoginRequired, detect("https://www.tiktok.com/login").restriction)
    }

    @Test fun addressesThatLookLikeVideosButAreNotAreNotVideos() {
        val page = LinkVerdict.PageNotDownloadable
        listOf(
            "https://www.tiktok.com/@user/video",
            "https://www.tiktok.com/@user/video/",
            "https://www.tiktok.com/@user/video/abc",
            "https://www.tiktok.com/@user/video/123abc",
            "https://www.tiktok.com/@user/video/-1234",
            "https://www.tiktok.com/@user/collection/my-list-7123",
            "https://www.tiktok.com/@user/playlist/x-$id",
            "https://www.tiktok.com/embed",
            "https://www.tiktok.com/embed/@user",
            "https://www.tiktok.com/player/v1",
            "https://www.tiktok.com/player/v2/$id",
            "https://www.tiktok.com/v/abc.html",
            "https://www.tiktok.com/v/$id/extra",
            "https://www.tiktok.com/video/$id",
            "https://www.tiktok.com/tag/cats",
            "https://www.tiktok.com/music/original-sound-6689804660171082501",
            "https://www.tiktok.com/discover",
            "https://www.tiktok.com/search?q=cats",
            "https://www.tiktok.com/foryou",
            "https://www.tiktok.com/explore",
        ).forEach { assertKind(it, LinkKind.Other, page) }
    }

    @Test fun fileAndStreamHostsAreNeverVideoPages() {
        assertKind("https://v16-webapp.tiktokcdn.com/abc/def/video.mp4", LinkKind.DirectFile, LinkVerdict.DirectFile)
        assertKind("https://v16.tiktokcdn-us.com/video/tos/x/master.m3u8", LinkKind.StreamEndpoint, LinkVerdict.Restricted)
        assertKind("https://v16.tiktokcdn.com/video/tos/x", LinkKind.Other, LinkVerdict.PageNotDownloadable)
        assertEquals(RestrictionReason.AdaptiveStream, detect("https://v16.tiktokcdn-us.com/video/tos/x/master.m3u8").restriction)
    }

    @Test fun lookAlikeHostsAreNotTikTok() {
        listOf(
            "https://tiktok.com.evil.com/@user/video/$id",
            "https://nottiktok.com/@user/video/$id",
            "https://evil.com/tiktok.com/@user/video/$id",
            "https://vm.tiktok.com.evil.org/ZMabc123/",
            "https://tiktok-video.net/@user/video/$id",
            "https://tikt0k.com/@user/video/$id",
        ).forEach {
            val d = detect(it)
            assertEquals("platform of $it", MediaPlatform.DirectLink, d.platform)
            assertNull("no id for $it", d.contentId)
            assertFalse("$it must not be a platform page", d.verdict == LinkVerdict.PageNotDownloadable)
        }
    }

    @Test fun unsafeOrInvalidLinksNeverReachTheAdapter() {
        assertEquals(LinkVerdict.Blocked, detect("https://user:pass@www.tiktok.com/@user/video/$id").verdict)
        assertEquals(LinkVerdict.Blocked, detect("javascript:alert(1)//tiktok.com/@user/video/$id").verdict)
        assertEquals(LinkVerdict.Invalid, detect("www.tiktok.com/@user/video/$id").verdict)
        assertEquals(LinkVerdict.Invalid, detect("https://www.tiktok.com/@user/video/$id and more").verdict)
        assertEquals(LinkVerdict.Invalid, detect("").verdict)
        assertEquals(LinkVerdict.Invalid, detect(null).verdict)
        assertEquals(LinkVerdict.Invalid, detect("ftp://www.tiktok.com/@user/video/$id").verdict)
    }

    @Test fun plainHttpGetsASafetyNoteButIsStillRecognised() {
        val d = detect("http://www.tiktok.com/@user/video/$id")
        assertEquals(MediaPlatform.TikTok, d.platform)
        assertTrue(d.notes.any { it.kind == SafetyNoteKind.InsecureHttp })
    }

    @Test fun aVideoPageIsNeverOfferedAsAFile() {
        val d = detect("https://www.tiktok.com/@user/video/$id")
        assertFalse(d.canCheckAsFile)
        assertEquals("TikTok video", d.title)
        assertTrue(d.details.contains("ID: $id"))
    }

    @Test fun aPageStatusIsNotReadAsAnAccessVerdictForTikTok() {
        val url = "https://www.tiktok.com/@user/video/$id"
        listOf(200, 401, 403, 404, 410, 429, 500).forEach { status ->
            val d = detect(url, ObservedResponse(status = status))
            assertEquals("verdict for status $status", LinkVerdict.PageNotDownloadable, d.verdict)
            assertNull(d.restriction)
        }
    }

    @Test fun otherPlatformsStillReadAPageStatus() {
        val yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals(LinkVerdict.Restricted, detect(yt, ObservedResponse(status = 403)).verdict)
        assertEquals(LinkVerdict.Restricted, detect("https://www.facebook.com/reel/123456789012345", ObservedResponse(status = 404)).verdict)
        val unreliable = SmartLinkDetector.defaultAdapters.filter { !it.pageStatusIsReliable }.map { it.platform }
        assertEquals(listOf(MediaPlatform.TikTok), unreliable)
    }

    @Test fun theAdapterOwnsOnlyTikTokHosts() {
        listOf("tiktok.com", "www.tiktok.com", "vm.tiktok.com", "m.tiktok.com", "v16.tiktokcdn.com", "x.tiktokcdn-us.com", "api.tiktokv.com")
            .forEach { assertTrue("owns $it", TikTokAdapter.ownsHost(it)) }
        listOf("nottiktok.com", "tiktok.com.evil.com", "tiktok.org", "example.com", "evil-tiktokcdn.com")
            .forEach { assertFalse("does not own $it", TikTokAdapter.ownsHost(it)) }
    }
}
