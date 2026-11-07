package com.almus.ytdownloader.media

import com.almus.ytdownloader.download.LinkInfo
import com.almus.ytdownloader.download.MediaDetails
import com.almus.ytdownloader.download.MediaKind
import com.almus.ytdownloader.media.sources.CommonsSource
import com.almus.ytdownloader.media.sources.InternetArchiveSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMediaHttp(private val script: (String) -> MediaReply) : MediaHttp {
    val urls = mutableListOf<String>()
    override suspend fun get(url: String): MediaReply {
        urls += url
        return script(url)
    }
}

class MediaSourcesTest {
    private val ia = InternetArchiveSource(FakeMediaHttp { MediaReply(200, "{}") })
    private val commons = CommonsSource(FakeMediaHttp { MediaReply(200, "{}") })

    // ---------------------------------------------------------------- matching (text only)

    @Test fun archiveItemPagesAreRecognised() {
        assertEquals("bigbuck", ia.match("https://archive.org/details/bigbuck"))
        assertEquals("bigbuck", ia.match("https://archive.org/details/bigbuck?query=x#top"))
        assertEquals("big_buck-1.0", ia.match("https://www.archive.org/details/big_buck-1.0/"))
        assertEquals("bigbuck", ia.match("http://archive.org/details/bigbuck"))
    }

    @Test fun otherArchiveAddressesAndLookAlikesAreNot() {
        listOf(
            "https://archive.org/download/bigbuck/a.mp4",
            "https://archive.org/details/",
            "https://archive.org/details/../x",
            "https://archive.org/search?query=x",
            "https://archive.org.evil.example/details/bigbuck",
            "https://evil.example/details/bigbuck",
        ).forEach { assertNull("matched $it", ia.match(it)) }
    }

    @Test fun commonsFilePagesAreRecognised() {
        assertEquals("File:Big Buck Bunny.ogv", commons.match("https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny.ogv"))
        assertEquals("File:Café.webm", commons.match("https://commons.wikimedia.org/wiki/File:Caf%C3%A9.webm"))
        listOf(
            "https://commons.wikimedia.org/wiki/Category:Videos",
            "https://commons.wikimedia.org/wiki/File:",
            "https://en.wikipedia.org/wiki/File:X.webm",
            "https://commons.wikimedia.org.evil.example/wiki/File:X.webm",
        ).forEach { assertNull("matched $it", commons.match(it)) }
    }

    @Test fun theRegistryFindsTheRightSourceAndIgnoresEverythingElse() {
        val sources = MediaSources(listOf(ia, commons))
        assertEquals("internet-archive", sources.match("https://archive.org/details/bigbuck")!!.source.id)
        assertEquals("wikimedia-commons", sources.match("https://commons.wikimedia.org/wiki/File:X.webm")!!.source.id)
        assertNull(sources.match("https://example.com/video.mp4"))
        assertNull(sources.match("not a link"))
        assertNull(sources.match(null))
    }

    // ---------------------------------------------------------------- Internet Archive answers

    private val archiveItem = """{"server":"ia800","is_dark":false,
        "metadata":{"identifier":"bigbuck","mediatype":"movies","title":"Big Buck Bunny","licenseurl":"https://creativecommons.org/licenses/by/3.0/"},
        "files":[
          {"name":"bigbuck.mp4","source":"original","format":"MPEG4","size":"276134947","length":"596.45","height":"1080","width":"1920"},
          {"name":"my file_512kb.mp4","source":"derivative","format":"512Kb MPEG4","size":"60243812","length":"596.45","height":"288","width":"512"},
          {"name":"bigbuck.ogv","source":"derivative","format":"Ogg Video","size":"85000000","length":"596.45","height":"360","width":"640"},
          {"name":"bigbuck.mp3","source":"derivative","format":"VBR MP3","size":"9500000","length":"9:56","bitrate":"128"},
          {"name":"bigbuck_meta.xml","source":"original","format":"Metadata","size":"1200"},
          {"name":"hidden.mp4","source":"derivative","format":"MPEG4","private":"true","size":"1"}
        ]}"""

    private fun archive(body: String, status: Int = 200): CatalogResult =
        runBlocking { InternetArchiveSource(FakeMediaHttp { MediaReply(status, body) }).load("bigbuck") }

    @Test fun anArchiveItemListsOnlyItsRealFilesWithTheirOwnDetails() {
        val catalog = (archive(archiveItem) as CatalogResult.Found).catalog
        assertEquals("Big Buck Bunny", catalog.title)
        assertEquals(596, catalog.durationSeconds)
        assertTrue(catalog.conversionAllowed)
        assertTrue(catalog.notices.any { it.contains("creativecommons.org") })
        assertEquals(4, catalog.variants.size)
        val options = MediaOptionsBuilder.build(catalog)
        assertEquals(listOf("1080p", "360p", "288p"), options.video.map { it.qualityLabel })
        assertEquals(setOf(VideoRung.P1080, VideoRung.P360), options.video.mapNotNull { it.rung }.toSet())
        assertEquals(EstimatedSize(276_134_947L, true), options.video[0].size)
        assertEquals(listOf("MP3 audio"), options.audio.map { it.title })
        assertEquals(128, options.audio[0].variant.bitrateKbps)
    }

    @Test fun archiveDownloadAddressesAreBuiltOnArchiveOrgAndEncoded() {
        val catalog = (archive(archiveItem) as CatalogResult.Found).catalog
        val urls = catalog.variants.map { it.url }
        assertTrue(urls.contains("https://archive.org/download/bigbuck/bigbuck.mp4"))
        assertTrue(urls.contains("https://archive.org/download/bigbuck/my%20file_512kb.mp4"))
        assertTrue(urls.all { it.startsWith("https://archive.org/download/bigbuck/") })
    }

    @Test fun archiveFilesThatAreNotMediaOrArePrivateAreLeftOut() {
        val catalog = (archive(archiveItem) as CatalogResult.Found).catalog
        assertTrue(catalog.variants.none { it.url.endsWith(".xml") || it.url.contains("hidden") })
    }

    @Test fun archiveLengthsCanBeSecondsOrClockText() {
        val body = """{"metadata":{"mediatype":"audio","title":"T"},"files":[
            {"name":"a.mp3","source":"original","format":"VBR MP3","length":"1:02:03","size":"100"}]}"""
        assertEquals(3723, ((archive(body) as CatalogResult.Found).catalog).durationSeconds)
    }

    @Test fun anItemOfSeveralOriginalsIsExplainedInsteadOfListingEveryFile() {
        val body = """{"metadata":{"mediatype":"audio","title":"Album"},"files":[
            {"name":"1.mp3","source":"original","format":"VBR MP3","size":"1"},
            {"name":"2.mp3","source":"original","format":"VBR MP3","size":"1"}]}"""
        val result = archive(body) as CatalogResult.Unavailable
        assertTrue(result.message.contains("2 separate media files"))
        assertTrue(result.message.contains("archive.org/download/bigbuck/FILE"))
    }

    @Test fun restrictedDarkAndNonMediaItemsAreRefused() {
        assertTrue(archive("""{"metadata":{"mediatype":"movies","access-restricted-item":"true"},"files":[]}""") is CatalogResult.Unavailable)
        assertTrue(archive("""{"is_dark":true,"metadata":{"mediatype":"movies"},"files":[]}""") is CatalogResult.Unavailable)
        assertTrue(archive("""{"metadata":{"mediatype":"texts"},"files":[]}""") is CatalogResult.Unavailable)
        assertTrue(archive("{}") is CatalogResult.Unavailable)
        assertTrue(archive("""{"metadata":{"mediatype":"movies"},"files":[{"name":"a.xml","format":"Metadata"}]}""") is CatalogResult.Unavailable)
    }

    @Test fun archiveFailuresAreReportedPlainly() {
        assertTrue(archive("", 404) is CatalogResult.Unavailable)
        assertTrue(archive("", 429) is CatalogResult.Failed)
        assertTrue(archive("", 503) is CatalogResult.Failed)
        assertTrue(archive("<html>", 200) is CatalogResult.Failed)
        val offline = InternetArchiveSource(object : MediaHttp {
            override suspend fun get(url: String): MediaReply = throw MediaNetworkException("offline")
        })
        assertTrue(runBlocking { offline.load("bigbuck") } is CatalogResult.Failed)
    }

    @Test fun theArchiveRequestGoesToTheMetadataServiceOnly() {
        val http = FakeMediaHttp { MediaReply(200, "{}") }
        runBlocking { InternetArchiveSource(http).load("bigbuck") }
        assertEquals(listOf("https://archive.org/metadata/bigbuck"), http.urls)
        assertTrue(runBlocking { InternetArchiveSource(http).load("../x") } is CatalogResult.Unavailable)
        assertEquals(1, http.urls.size)
    }

    @Test fun nothingIsFabricatedForAnItemWithOneLowQualityFile() {
        val body = """{"metadata":{"mediatype":"movies","title":"Old film"},"files":[
            {"name":"old.mp4","source":"original","format":"MPEG4","size":"5000000"}]}"""
        val options = MediaOptionsBuilder.build((archive(body) as CatalogResult.Found).catalog)
        assertEquals(1, options.video.size)
        assertEquals("Original quality", options.video[0].qualityLabel)
        assertNull(options.durationSeconds)
        assertTrue(options.audio.isEmpty())
        assertFalse(options.video[0].resolutionKnown)
    }

    // ---------------------------------------------------------------- Wikimedia Commons answers

    private val commonsFile = """{"query":{"pages":[{"pageid":1,"title":"File:Big Buck Bunny.ogv","videoinfo":[{
        "url":"https://upload.wikimedia.org/wikipedia/commons/c/c0/Big_Buck_Bunny.ogv","size":276134947,"width":1920,"height":1080,
        "duration":596.45,"mime":"video/ogg","mediatype":"VIDEO",
        "derivatives":[
          {"src":"https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/x.ogv/x.ogv.360p.webm","type":"video/webm; codecs=\"vp8, vorbis\"","transcodekey":"360p.webm","width":640,"height":360,"bandwidth":1000000},
          {"src":"//upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/x.ogv/x.ogv.720p.vp9.webm","type":"video/webm; codecs=\"vp9, opus\"","transcodekey":"720p.vp9.webm","width":1280,"height":720,"bandwidth":2500000},
          {"src":"https://evil.example/x.webm","type":"video/webm","transcodekey":"evil","width":1280,"height":720}
        ],
        "extmetadata":{"LicenseShortName":{"value":"<a href=\"x\">CC BY 3.0</a>"}}}]}]}}"""

    private fun commonsResult(body: String, status: Int = 200): CatalogResult =
        runBlocking { CommonsSource(FakeMediaHttp { MediaReply(status, body) }).load("File:Big Buck Bunny.ogv") }

    @Test fun commonsListsTheOriginalAndTheVersionsTheSiteMade() {
        val catalog = (commonsResult(commonsFile) as CatalogResult.Found).catalog
        assertEquals("Big Buck Bunny.ogv", catalog.title)
        assertEquals(596, catalog.durationSeconds)
        assertTrue(catalog.conversionAllowed)
        assertTrue(catalog.notices.any { it.contains("License: CC BY 3.0") })
        val options = MediaOptionsBuilder.build(catalog)
        assertEquals(listOf("1080p", "720p", "360p"), options.video.map { it.qualityLabel })
        assertEquals(listOf("Ogg", "WebM", "WebM"), options.video.map { it.variant.container })
        assertEquals("vp9", options.video[1].variant.videoCodec)
        assertEquals("opus", options.video[1].variant.audioCodec)
    }

    @Test fun commonsSizesOfMadeVersionsAreEstimatesAndTheOriginalIsExact() {
        val options = MediaOptionsBuilder.build((commonsResult(commonsFile) as CatalogResult.Found).catalog)
        assertEquals(EstimatedSize(276_134_947L, true), options.video[0].size)
        val estimate = options.video[2].size!!
        assertFalse(estimate.exact)
        assertEquals(1000 * 1000L / 8 * 596, estimate.bytes)
    }

    @Test fun commonsFilesOnOtherHostsAreDropped() {
        val catalog = (commonsResult(commonsFile) as CatalogResult.Found).catalog
        assertEquals(3, catalog.variants.size)
        assertTrue(catalog.variants.all { it.url.startsWith("https://upload.wikimedia.org/") })
    }

    @Test fun commonsMissingOrNonMediaFilesAreRefused() {
        assertTrue(commonsResult("""{"query":{"pages":[{"title":"File:X.ogv","missing":true}]}}""") is CatalogResult.Unavailable)
        assertTrue(commonsResult("""{"query":{"pages":[{"title":"File:X.png","videoinfo":[{"url":"https://upload.wikimedia.org/a.png","mediatype":"BITMAP"}]}]}}""") is CatalogResult.Unavailable)
        assertTrue(commonsResult("{}") is CatalogResult.Unavailable)
        assertTrue(commonsResult("", 500) is CatalogResult.Failed)
        assertTrue(commonsResult("", 429) is CatalogResult.Failed)
        assertTrue(commonsResult("<html>") is CatalogResult.Failed)
    }

    @Test fun theCommonsRequestAsksForDerivativesOnly() {
        val http = FakeMediaHttp { MediaReply(200, "{}") }
        runBlocking { CommonsSource(http).load("File:Big Buck Bunny.ogv") }
        val url = http.urls.single()
        assertTrue(url.startsWith("https://commons.wikimedia.org/w/api.php?action=query"))
        assertTrue(url.contains("prop=videoinfo"))
        assertTrue(url.contains("viprop=derivatives%7Curl%7Csize%7Cmime%7Cmediatype%7Cextmetadata"))
        assertTrue(url.endsWith("titles=File%3ABig+Buck+Bunny.ogv"))
    }

    // ---------------------------------------------------------------- an ordinary direct file

    private fun info(media: MediaDetails?, length: Long) = LinkInfo(
        url = "https://files.example.com/a.mp4", finalUrl = "https://files.example.com/a.mp4", fileName = "a.mp4",
        mimeType = "video/mp4", contentLength = length, redirectCount = 0, insecure = false, looksLikeWebPage = false, media = media,
    )

    @Test fun aDirectFileIsExactlyOneRealOptionWithoutInventedDetails() {
        val checked = info(MediaDetails(MediaKind.Video, "MP4"), 12_345L)
        val catalog = DirectFileSource.catalogOf(checked)!!
        assertEquals(checked, catalog.verifiedInfo)
        val options = MediaOptionsBuilder.build(catalog)
        val only = options.video.single()
        assertEquals("Original quality", only.qualityLabel)
        assertNull(options.durationSeconds)
        assertEquals(EstimatedSize(12_345L, true), only.size)
        assertTrue(options.audio.isEmpty())
        assertNotNull(catalog.notices.single())
    }

    @Test fun aDirectAudioFileIsOneAudioOptionAndAnUnknownSizeStaysUnknown() {
        val catalog = DirectFileSource.catalogOf(info(MediaDetails(MediaKind.Audio, "MP3"), -1))!!
        val options = MediaOptionsBuilder.build(catalog)
        assertTrue(options.video.isEmpty())
        assertEquals("Original audio · MP3", options.audio.single().title)
        assertNull(options.audio.single().size)
    }

    @Test fun aFileThatIsNotMediaHasNoCatalog() {
        assertNull(DirectFileSource.catalogOf(info(null, 100)))
    }
}
