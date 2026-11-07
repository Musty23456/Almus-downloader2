package com.almus.ytdownloader.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatBuilderTest {
    private fun spec(
        kind: MediaKind,
        height: Int? = null,
        container: VideoContainer = VideoContainer.MP4,
        audio: AudioFormat = AudioFormat.MP3,
    ) = DownloadSpec("https://example.com/v", "t", kind, height, container, audio)

    @Test
    fun mp4SelectorKeepsHeightLimitInEveryFallback() {
        val s = FormatBuilder.videoSelector(360, VideoContainer.MP4)
        assertEquals("bv*[height<=360][ext=mp4]+ba[ext=m4a]/bv*[height<=360]+ba/b[height<=360]", s)
    }

    @Test
    fun bestQualityHasNoHeightLimit() {
        assertEquals("bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b", FormatBuilder.videoSelector(null, VideoContainer.MP4))
    }

    @Test
    fun webmAsksForWebmAudioToo() {
        assertEquals("bv*[height<=720][ext=webm]+ba[ext=webm]/bv*[height<=720]+ba/b[height<=720]", FormatBuilder.videoSelector(720, VideoContainer.WEBM))
    }

    @Test
    fun videoOptionsMergeIntoChosenContainer() {
        val o = FormatBuilder.options(spec(MediaKind.VIDEO, 1080, VideoContainer.WEBM), "/out")
        assertTrue(o.contains(YtOption("--merge-output-format", "webm")))
        assertTrue(o.contains(YtOption("--no-playlist")))
        assertEquals("/out/%(title)s [%(id)s].%(ext)s", o.first { it.name == "-o" }.value)
    }

    @Test
    fun mp3AddsExtractionAndQuality() {
        val o = FormatBuilder.options(spec(MediaKind.AUDIO, audio = AudioFormat.MP3), "/out")
        assertTrue(o.contains(YtOption("-x")))
        assertTrue(o.contains(YtOption("--audio-format", "mp3")))
        assertTrue(o.contains(YtOption("--audio-quality", "192K")))
    }

    @Test
    fun wavHasNoQualityOption() {
        val o = FormatBuilder.options(spec(MediaKind.AUDIO, audio = AudioFormat.WAV), "/out")
        assertTrue(o.contains(YtOption("--audio-format", "wav")))
        assertNull(o.firstOrNull { it.name == "--audio-quality" })
    }

    @Test
    fun originalAudioIsNotConverted() {
        val o = FormatBuilder.options(spec(MediaKind.AUDIO, audio = AudioFormat.ORIGINAL), "/out")
        assertNull(o.firstOrNull { it.name == "-x" })
        assertEquals("ba/b", o.first { it.name == "-f" }.value)
    }
}

class SubtitleOptionsTest {
    private fun video(langs: String?) =
        DownloadSpec("https://example.com/v", "t", MediaKind.VIDEO, null, VideoContainer.MP4, AudioFormat.MP3, null, langs)

    @Test
    fun noSubtitleOptionsByDefault() {
        val o = FormatBuilder.options(video(null), "/out")
        assertNull(o.firstOrNull { it.name == "--embed-subs" })
        assertNull(o.firstOrNull { it.name == "--sub-langs" })
    }

    @Test
    fun subtitlesAreEmbeddedForTheChosenLanguages() {
        val o = FormatBuilder.options(video("en,ha"), "/out")
        assertTrue(o.contains(YtOption("--write-subs")))
        assertTrue(o.contains(YtOption("--write-auto-subs")))
        assertTrue(o.contains(YtOption("--sub-langs", "en,ha")))
        assertTrue(o.contains(YtOption("--embed-subs")))
    }

    @Test
    fun audioDownloadsNeverGetSubtitleOptions() {
        val spec = DownloadSpec("https://example.com/v", "t", MediaKind.AUDIO, null, VideoContainer.MP4, AudioFormat.MP3, null, "en")
        assertNull(FormatBuilder.options(spec, "/out").firstOrNull { it.name == "--embed-subs" })
    }

    @Test
    fun sanitizerKeepsOnlyPlainLanguageCodes() {
        assertEquals("en,ha", SubtitleLangs.sanitize(" en, ha ;en"))
        assertEquals("pt-BR,en.*", SubtitleLangs.sanitize("pt-BR en.*"))
        assertNull(SubtitleLangs.sanitize("  "))
        // Anything that looks like an option is dropped, so text typed here can never become a yt-dlp option.
        assertEquals("rm", SubtitleLangs.sanitize("--exec rm"))
        assertEquals("en,rm", SubtitleLangs.sanitize("en;rm -rf"))
        assertNull(SubtitleLangs.sanitize("a/b \"x\" y\$z"))
    }
}
