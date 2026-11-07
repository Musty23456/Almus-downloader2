package com.almus.ytdownloader.media

import com.almus.ytdownloader.download.LinkInfo
import com.almus.ytdownloader.download.MediaDetails
import com.almus.ytdownloader.download.MediaKind
import com.almus.ytdownloader.download.SaveFolders
import com.almus.ytdownloader.download.SaveLayout
import com.almus.ytdownloader.download.StorageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOptionsTest {
    private fun video(id: String, w: Int?, h: Int?, container: String = "MP4", size: Long? = null, kbps: Int? = null, url: String = "https://files.example.com/$id.mp4") =
        MediaVariant(id, VariantKind.Video, container, "video/mp4", url, widthPx = w, heightPx = h, sizeBytes = size, bitrateKbps = kbps)

    private fun audio(id: String, container: String, original: Boolean = false, kbps: Int? = null, size: Long? = null) =
        MediaVariant(id, VariantKind.Audio, container, null, "https://files.example.com/$id", bitrateKbps = kbps, sizeBytes = size, isOriginal = original)

    private fun catalog(vararg variants: MediaVariant, duration: Int? = 600, conversion: Boolean = false) = MediaCatalog(
        sourceId = "test", sourceLabel = "Test source", basis = "basis", title = "Title", durationSeconds = duration,
        pageUrl = null, variants = variants.toList(), conversionAllowed = conversion,
    )

    // ---------------------------------------------------------------- quality labels

    @Test fun theLadderHasTheEightNamedQualities() {
        assertEquals(
            listOf("144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p"),
            VideoRung.entries.map { it.label },
        )
    }

    @Test fun labelsAreTheRealShortSideOfTheFrame() {
        assertEquals("1080p", VideoQualities.label(video("a", 1920, 1080)))
        assertEquals("720p", VideoQualities.label(video("a", 1280, 720)))
        assertEquals("2160p", VideoQualities.label(video("a", 3840, 2160)))
        assertEquals("1080p", VideoQualities.label(video("a", 1080, 1920))) // portrait
        assertEquals("360p", VideoQualities.label(video("a", null, 360)))
        assertEquals(VideoRung.P1440, VideoQualities.rungOf(video("a", 2560, 1440)))
    }

    @Test fun aSizeThatIsNotOnTheLadderIsShownAsItReallyIsNeverRoundedUp() {
        val v = video("a", 1920, 800)
        assertNull(VideoQualities.rungOf(v))
        assertEquals("800p", VideoQualities.label(v))
        assertEquals("288p", VideoQualities.label(video("b", 512, 288)))
    }

    @Test fun anUnstatedResolutionIsNeverGivenARung() {
        val v = video("a", null, null)
        assertNull(VideoQualities.rungOf(v))
        assertEquals("Original quality", VideoQualities.label(v))
        assertFalse(MediaOptionsBuilder.build(catalog(v)).video.single().resolutionKnown)
    }

    // ---------------------------------------------------------------- only what is offered

    @Test fun onlyTheQualitiesTheSourceOffersAreListed() {
        val options = MediaOptionsBuilder.build(catalog(video("a", 1920, 1080), video("b", 640, 360), video("c", 1920, 800)))
        assertEquals(listOf("1080p", "800p", "360p"), options.video.map { it.qualityLabel })
        // Rungs nobody offered (144p, 240p, 480p, 720p, 1440p, 2160p) are simply absent.
        assertEquals(setOf(VideoRung.P1080, VideoRung.P360), options.video.mapNotNull { it.rung }.toSet())
        assertTrue(options.audio.isEmpty())
    }

    @Test fun nothingIsAddedToACatalogThatOffersOneFile() {
        val options = MediaOptionsBuilder.build(catalog(video("a", null, null, size = 1000)))
        assertEquals(1, options.video.size)
        assertTrue(options.audio.isEmpty())
    }

    @Test fun duplicateAddressesAreListedOnce() {
        val one = video("a", 1280, 720, url = "https://x.example/f.mp4")
        val two = video("b", 1280, 720, url = "https://x.example/f.mp4")
        assertEquals(1, MediaOptionsBuilder.build(catalog(one, two)).video.size)
    }

    @Test fun sameQualityInTwoFormatsGivesTwoChoicesInAStableOrder() {
        val options = MediaOptionsBuilder.build(catalog(video("w", 1280, 720, "WebM", url = "https://x.example/w"), video("m", 1280, 720, "MP4", url = "https://x.example/m")))
        assertEquals(listOf("MP4", "WebM"), options.video.map { it.variant.container })
    }

    // ---------------------------------------------------------------- audio

    @Test fun audioIsOriginalFirstThenTheFormatsTheSourceProvidesItself() {
        val options = MediaOptionsBuilder.build(
            catalog(audio("mp3", "MP3", kbps = 128), audio("flac", "FLAC", original = true), audio("m4a", "M4A", kbps = 256)),
        )
        assertEquals(listOf("Original audio · FLAC", "M4A audio", "MP3 audio"), options.audio.map { it.title })
        assertTrue(options.audio.all { it.conversion == null })
        assertTrue(options.audio[0].subtitle.contains("No conversion"))
    }

    @Test fun noConversionChoiceAppearsWithoutAConverter() {
        val options = MediaOptionsBuilder.build(catalog(audio("flac", "FLAC", original = true), conversion = true))
        assertEquals(1, options.audio.size)
    }

    private class Mp3Converter : AudioConverter {
        override val target = AudioTarget.Mp3
        override fun supports(variant: MediaVariant) = variant.container == "FLAC"
    }

    @Test fun aConversionChoiceAppearsOnlyWithAConverterAndASourceThatAllowsProcessing() {
        val cat = { allowed: Boolean -> catalog(audio("flac", "FLAC", original = true), conversion = allowed) }
        assertEquals(1, MediaOptionsBuilder.build(cat(false), listOf(Mp3Converter())).audio.size)
        val withConverter = MediaOptionsBuilder.build(cat(true), listOf(Mp3Converter())).audio
        assertEquals(2, withConverter.size)
        assertEquals(ConversionPlan(AudioTarget.Mp3, "flac"), withConverter[1].conversion)
        assertEquals("Convert to MP3", withConverter[1].title)
    }

    @Test fun noConversionIsOfferedWhenTheSourceAlreadyProvidesThatFormat() {
        val cat = catalog(audio("flac", "FLAC", original = true), audio("mp3", "MP3"), conversion = true)
        assertEquals(2, MediaOptionsBuilder.build(cat, listOf(Mp3Converter())).audio.size)
    }

    // ---------------------------------------------------------------- sizes and durations

    @Test fun aStatedSizeIsExactAndAnEstimateIsLabelledAsOne() {
        val exact = SizeEstimator.of(video("a", 1280, 720, size = 5_000_000), 600)!!
        assertTrue(exact.exact)
        assertEquals(5_000_000L, exact.bytes)
        val estimate = SizeEstimator.of(video("b", 1280, 720, kbps = 1000), 600)!!
        assertFalse(estimate.exact)
        assertEquals(75_000_000L, estimate.bytes)
        assertTrue(estimate.text.startsWith("about "))
        assertTrue(estimate.text.endsWith("(estimate)"))
    }

    @Test fun withoutASizeOrBitrateAndDurationNothingIsGuessed() {
        assertNull(SizeEstimator.of(video("a", 1280, 720), 600))
        assertNull(SizeEstimator.of(video("a", 1280, 720, kbps = 1000), null))
        assertNull(SizeEstimator.of(video("a", 1280, 720, kbps = 0), 600))
        assertEquals("Size not stated", SizeEstimator.text(null))
    }

    @Test fun durationsAreWrittenAsMinutesOrHours() {
        assertEquals("0:05", DurationFormat.of(5))
        assertEquals("9:56", DurationFormat.of(596))
        assertEquals("1:02:03", DurationFormat.of(3723))
        assertNull(DurationFormat.of(0))
        assertNull(DurationFormat.of(null))
    }

    @Test fun formatDetailsListOnlyWhatIsKnown() {
        val full = video("a", 1920, 1080, size = 1000, kbps = 5000).copy(videoCodec = "avc1", audioCodec = "aac", hasAudio = true, isOriginal = true)
        val rows = FormatDetails.rows(full, 600, SizeEstimator.of(full, 600)).toMap()
        assertEquals("1920 × 1080", rows["Frame size"])
        assertEquals("avc1", rows["Video codec"])
        assertEquals("5000 kbps", rows["Bitrate"])
        assertEquals("Has sound", rows["Sound"])
        assertEquals("10:00", rows["Duration"])
        assertEquals("Original upload", rows["Version"])
        val bare = FormatDetails.rows(video("b", null, null), null, null).toMap()
        assertEquals(setOf("Type", "Format", "MIME type", "File size"), bare.keys)
        assertEquals("Size not stated", bare["File size"])
    }

    // ---------------------------------------------------------------- selection

    @Test fun theStartIsTheBestVideoThenAudio() {
        val both = MediaOptionsBuilder.build(catalog(video("lo", 640, 360), video("hi", 1920, 1080), audio("m", "MP3")))
        assertEquals(MediaSelection(MediaMode.Video, "hi"), SelectionLogic.initial(both))
        val onlyAudio = MediaOptionsBuilder.build(catalog(audio("m", "MP3")))
        assertEquals(MediaSelection(MediaMode.Audio, "m"), SelectionLogic.initial(onlyAudio))
        assertNull(SelectionLogic.initial(MediaOptionsBuilder.build(catalog())))
    }

    @Test fun switchingModeSelectsTheFirstChoiceOfThatMode() {
        val options = MediaOptionsBuilder.build(catalog(video("hi", 1920, 1080), audio("m", "MP3")))
        val s = SelectionLogic.withMode(options, SelectionLogic.initial(options)!!, MediaMode.Audio)
        assertEquals(MediaSelection(MediaMode.Audio, "m"), s)
        val noAudio = MediaOptionsBuilder.build(catalog(video("hi", 1920, 1080)))
        val unchanged = SelectionLogic.initial(noAudio)!!
        assertEquals(unchanged, SelectionLogic.withMode(noAudio, unchanged, MediaMode.Audio))
    }

    @Test fun anUnknownChoiceDoesNotResolve() {
        val options = MediaOptionsBuilder.build(catalog(video("hi", 1920, 1080)))
        assertNull(SelectionLogic.resolve(options, MediaSelection(MediaMode.Video, "nope")))
        assertNull(SelectionLogic.resolve(options, MediaSelection(MediaMode.Audio, "hi")))
        assertEquals("1080p", SelectionLogic.resolve(options, MediaSelection(MediaMode.Video, "hi"))!!.qualityLabel)
    }

    // ---------------------------------------------------------------- destination and plan

    @Test fun withoutPerDownloadFoldersOnlyAutomaticIsOffered() {
        assertEquals(listOf(DestinationChoice.Automatic), Destinations.options(false))
        assertEquals(3, Destinations.options(true).size)
    }

    @Test fun destinationsResolveToRealFolders() {
        assertNull(Destinations.subfolder(DestinationChoice.MainFolder, VariantKind.Video, SaveLayout.ByMedia, "a.mp4", "video/mp4"))
        assertEquals(SaveFolders.VIDEO, Destinations.subfolder(DestinationChoice.TypeFolder, VariantKind.Video, SaveLayout.Single, "a.mp4", "video/mp4"))
        assertEquals(SaveFolders.AUDIO, Destinations.subfolder(DestinationChoice.TypeFolder, VariantKind.Audio, SaveLayout.Single, "a.mp3", "audio/mpeg"))
        assertEquals(
            SaveFolders.subfolderFor(SaveLayout.ByMedia, "a.mp4", "video/mp4"),
            Destinations.subfolder(DestinationChoice.Automatic, VariantKind.Video, SaveLayout.ByMedia, "a.mp4", "video/mp4"),
        )
        assertEquals("Video folder", Destinations.label(DestinationChoice.TypeFolder, VariantKind.Video))
        assertEquals("Audio folder", Destinations.label(DestinationChoice.TypeFolder, VariantKind.Audio))
    }

    private fun info(length: Long) = LinkInfo(
        url = "https://files.example.com/a.mp4", finalUrl = "https://files.example.com/a.mp4", fileName = "a.mp4",
        mimeType = "video/mp4", contentLength = length, redirectCount = 0, insecure = false, looksLikeWebPage = false,
        media = MediaDetails(MediaKind.Video, "MP4"),
    )

    @Test fun theCheckedSizeWinsOverTheCatalogsFigure() {
        val cat = catalog(video("a", 1280, 720, kbps = 1000, url = "https://files.example.com/a.mp4"))
        val resolved = SelectionLogic.resolve(MediaOptionsBuilder.build(cat), MediaSelection(MediaMode.Video, "a"))!!
        val checked = PlanBuilder.build(cat, resolved, info(9_000_000), DestinationChoice.MainFolder, SaveLayout.ByMedia, StorageTarget.PublicDownloads)
        assertEquals(EstimatedSize(9_000_000, true), checked.size)
        assertNull(checked.subfolder)
        val unknown = PlanBuilder.build(cat, resolved, info(-1), DestinationChoice.TypeFolder, SaveLayout.Single, StorageTarget.PublicDownloads)
        assertFalse(unknown.size!!.exact)
        assertEquals(SaveFolders.VIDEO, unknown.subfolder)
        assertTrue(unknown.displayPath.contains(SaveFolders.VIDEO))
        assertEquals("720p", unknown.qualityLabel)
        assertEquals("MP4", unknown.formatLabel)
    }
}
