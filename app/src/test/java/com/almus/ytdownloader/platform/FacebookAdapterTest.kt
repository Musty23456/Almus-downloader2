package com.almus.ytdownloader.platform

import com.almus.ytdownloader.platform.adapters.FacebookAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL detection for Facebook: videos, Reels, live videos, short links, restricted content and look-alikes. */
class FacebookAdapterTest {
    private val detector = SmartLinkDetector()
    private val id = "123456789012345"

    private fun detect(url: String) = detector.detect(url)

    private fun assertKind(url: String, kind: LinkKind, verdict: LinkVerdict, contentId: String? = null) {
        val d = detect(url)
        assertEquals("platform of $url", MediaPlatform.Facebook, d.platform)
        assertEquals("kind of $url", kind, d.kind)
        assertEquals("verdict of $url", verdict, d.verdict)
        assertEquals("id of $url", contentId, d.contentId)
    }

    @Test fun recognisesEveryVideoShape() {
        val page = LinkVerdict.PageNotDownloadable
        listOf(
            "https://www.facebook.com/watch/?v=$id",
            "https://www.facebook.com/watch?v=$id",
            "https://facebook.com/watch/?v=$id&ref=search",
            "https://m.facebook.com/watch/?v=$id",
            "https://web.facebook.com/watch/?v=$id",
            "https://mbasic.facebook.com/video.php?v=$id",
            "https://www.facebook.com/video.php?v=$id",
            "https://fb.com/video.php?v=$id",
            "https://www.facebook.com/somepage/videos/$id",
            "https://www.facebook.com/somepage/videos/$id/",
            "https://www.facebook.com/somepage/videos/a-title-in-words/$id/",
            "https://www.facebook.com/somepage/videos/vb.100001/$id/?type=3",
            "https://www.facebook.com/someone.name/videos/$id",
        ).forEach { assertKind(it, LinkKind.Video, page, id) }
    }

    @Test fun recognisesReelsAndLiveVideos() {
        assertKind("https://www.facebook.com/reel/$id", LinkKind.ShortVideo, LinkVerdict.PageNotDownloadable, id)
        assertKind("https://m.facebook.com/reel/$id?s=single_unit", LinkKind.ShortVideo, LinkVerdict.PageNotDownloadable, id)
        assertKind("https://www.facebook.com/reels/$id", LinkKind.ShortVideo, LinkVerdict.PageNotDownloadable, id)
        assertKind("https://www.facebook.com/watch/live/?v=$id", LinkKind.Live, LinkVerdict.PageNotDownloadable, id)
    }

    @Test fun reelsAreDescribedAsReels() {
        assertEquals("Facebook Reel", detect("https://www.facebook.com/reel/$id").title)
    }

    @Test fun shortLinksNeedARedirectCheckAndNothingElse() {
        listOf("https://fb.watch/abc123xyz/", "https://www.facebook.com/share/v/1AbCdEf/", "https://www.facebook.com/share/r/1AbCdEf/", "https://www.facebook.com/share/p/1AbCdEf/")
            .forEach { assertKind(it, LinkKind.ShortLink, LinkVerdict.ShortLink) }
        assertKind("https://fb.watch/", LinkKind.Home, LinkVerdict.PageNotDownloadable)
    }

    @Test fun videoAddressesWithoutAUsableIdAreNotVideos() {
        listOf(
            "https://www.facebook.com/watch/",
            "https://www.facebook.com/watch/?v=abc",
            "https://www.facebook.com/watch/?v=",
            "https://www.facebook.com/reel/",
            "https://www.facebook.com/reel/not-a-number",
            "https://www.facebook.com/reels/",
            "https://www.facebook.com/video.php",
            "https://www.facebook.com/somepage/videos/",
            "https://www.facebook.com/somepage/videos/just-words",
        ).forEach { url ->
            val d = detect(url)
            assertFalse("$url must not be a video", d.kind == LinkKind.Video || d.kind == LinkKind.ShortVideo || d.kind == LinkKind.Live)
            assertNull("$url must carry no id", d.contentId)
        }
    }

    @Test fun restrictedContentIsRecognisedAsRestricted() {
        assertKind("https://www.facebook.com/groups/12345/posts/678", LinkKind.Group, LinkVerdict.Restricted)
        assertKind("https://www.facebook.com/groups/somegroup/", LinkKind.Group, LinkVerdict.Restricted)
        assertKind("https://www.facebook.com/stories/123/456", LinkKind.Story, LinkVerdict.Restricted)
        assertKind("https://www.facebook.com/login/?next=https%3A%2F%2Fwww.facebook.com%2Fwatch%2F%3Fv%3D$id", LinkKind.Login, LinkVerdict.Restricted)
        assertKind("https://www.facebook.com/checkpoint/", LinkKind.Login, LinkVerdict.Restricted)
        assertEquals(RestrictionReason.LoginRequired, detect("https://www.facebook.com/login.php").restriction)
        assertEquals(RestrictionReason.PrivateOrProtected, detect("https://www.facebook.com/groups/1/").restriction)
    }

    @Test fun anAccessDeniedAnswerTurnsAVideoIntoRestrictedContent() {
        val d = detector.detect("https://www.facebook.com/watch/?v=$id", ObservedResponse(status = 403))
        assertEquals(LinkVerdict.Restricted, d.verdict)
        assertEquals(RestrictionReason.PrivateOrProtected, d.restriction)
        val gone = detector.detect("https://www.facebook.com/reel/$id", ObservedResponse(status = 404))
        assertEquals(RestrictionReason.Unavailable, gone.restriction)
    }

    @Test fun postsPhotosAndProfilesAreNotVideos() {
        assertKind("https://www.facebook.com/somepage/posts/pfbid0abc", LinkKind.Post, LinkVerdict.PageNotDownloadable)
        assertKind("https://m.facebook.com/story.php?story_fbid=1&id=2", LinkKind.Post, LinkVerdict.PageNotDownloadable)
        assertKind("https://www.facebook.com/photo.php?fbid=123", LinkKind.Photo, LinkVerdict.PageNotDownloadable, "123")
        assertKind("https://www.facebook.com/somepage", LinkKind.Channel, LinkVerdict.PageNotDownloadable, "somepage")
        assertKind("https://www.facebook.com/profile.php?id=100001", LinkKind.Channel, LinkVerdict.PageNotDownloadable, "100001")
        assertKind("https://www.facebook.com/", LinkKind.Home, LinkVerdict.PageNotDownloadable)
    }

    @Test fun facebookToolPagesAreNotMistakenForProfiles() {
        listOf("reels", "marketplace", "events", "gaming", "friends", "hashtag", "bookmarks", "home.php", "l.php", "sharer.php", "watch", "live")
            .forEach { name -> assertEquals("/$name", LinkKind.Other, detect("https://www.facebook.com/$name").kind) }
        assertEquals(LinkKind.Other, detect("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com").kind)
    }

    @Test fun facebookFileHostsAreFilesOnlyWhenTheyNameAFile() {
        assertKind("https://video.xx.fbcdn.net/v/t42/clip.mp4?x=1", LinkKind.DirectFile, LinkVerdict.DirectFile)
        assertKind("https://scontent.xx.fbcdn.net/v/t39/photo.jpg", LinkKind.DirectFile, LinkVerdict.DirectFile)
        assertEquals(LinkVerdict.PageNotDownloadable, detect("https://video.xx.fbcdn.net/v/t42/noextension?x=1").verdict)
        assertEquals(LinkVerdict.Restricted, detect("https://video.xx.fbcdn.net/v/t42/master.m3u8").verdict)
    }

    @Test fun lookAlikeHostsAreNeverFacebook() {
        listOf(
            "https://facebook.com.evil.example/watch/?v=$id",
            "https://notfacebook.com/watch/?v=$id",
            "https://fb.watch.evil.example/abc",
            "https://facebook.example/reel/$id",
        ).forEach { url ->
            val d = detect(url)
            assertEquals("$url must not be Facebook", MediaPlatform.DirectLink, d.platform)
        }
        assertTrue(detect("https://faceb00k.com/watch/?v=$id").notes.any { it.kind == SafetyNoteKind.LookalikeAddress })
    }

    @Test fun unsafeOrBrokenInputIsRejectedBeforeAnyAdapterSeesIt() {
        assertEquals(LinkVerdict.Blocked, detect("javascript:alert(1)").verdict)
        assertEquals(LinkVerdict.Blocked, detect("https://user:pw@www.facebook.com/watch/?v=$id").verdict)
        assertEquals(LinkVerdict.Invalid, detect("www.facebook.com/watch/?v=$id").verdict)
        assertEquals(LinkVerdict.Invalid, detect("").verdict)
        assertEquals(LinkVerdict.Invalid, detect("not a link").verdict)
    }

    @Test fun theAdapterOwnsExactlyFacebooksHosts() {
        assertTrue(FacebookAdapter.ownsHost("www.facebook.com"))
        assertTrue(FacebookAdapter.ownsHost("fb.watch"))
        assertTrue(FacebookAdapter.ownsHost("video.xx.fbcdn.net"))
        assertFalse(FacebookAdapter.ownsHost("facebook.com.evil.example"))
        assertFalse(FacebookAdapter.ownsHost("example.com"))
    }

    @Test fun aRecognisedVideoIsNeverOfferedAsAFile() {
        listOf("https://www.facebook.com/watch/?v=$id", "https://www.facebook.com/reel/$id", "https://www.facebook.com/groups/1/")
            .forEach { assertFalse("$it must not go to the file check", detect(it).canCheckAsFile) }
    }
}
