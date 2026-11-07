package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFormatsTest {
    private fun video(format: String) = MediaDetails(MediaKind.Video, format)

    private fun audio(format: String) = MediaDetails(MediaKind.Audio, format)

    @Test fun commonVideoTypesAreRecognisedFromTheServerType() {
        assertEquals(video("MP4"), MediaFormats.describe("video/mp4", "clip.mp4"))
        assertEquals(video("WebM"), MediaFormats.describe("video/webm", "clip.webm"))
        assertEquals(video("Matroska"), MediaFormats.describe("video/x-matroska", null))
        assertEquals(video("QuickTime"), MediaFormats.describe("video/quicktime", null))
        assertEquals(video("MP4"), MediaFormats.describe("VIDEO/MP4; codecs=avc1", null))
    }

    @Test fun commonAudioTypesAreRecognisedFromTheServerType() {
        assertEquals(audio("MP3"), MediaFormats.describe("audio/mpeg", "song.mp3"))
        assertEquals(audio("M4A"), MediaFormats.describe("audio/x-m4a", null))
        assertEquals(audio("M4A"), MediaFormats.describe("audio/mp4", null))
        assertEquals(audio("FLAC"), MediaFormats.describe("audio/flac", null))
        assertEquals(audio("WAV"), MediaFormats.describe("audio/x-wav", null))
        assertEquals(audio("Opus"), MediaFormats.describe("audio/opus", null))
        assertEquals(audio("WebM"), MediaFormats.describe("audio/webm", null))
    }

    @Test fun genericOrMissingTypeFallsBackToTheFileExtension() {
        assertEquals(video("MP4"), MediaFormats.describe("application/octet-stream", "clip.mp4"))
        assertEquals(video("MP4"), MediaFormats.describe(null, "CLIP.MP4"))
        assertEquals(audio("MP3"), MediaFormats.describe("binary/octet-stream", "a.mp3"))
        assertEquals(audio("M4A"), MediaFormats.describe(null, "a.m4a"))
    }

    @Test fun aServerThatSaysWebPageIsNotTrustedToBeMedia() {
        assertNull(MediaFormats.describe("text/html", "video.mp4"))
        assertNull(MediaFormats.describe("text/plain", "song.mp3"))
        assertNull(MediaFormats.describe("application/pdf", "clip.mp4"))
    }

    @Test fun nonMediaAndUnknownAreNotMedia() {
        assertNull(MediaFormats.describe("application/pdf", "a.pdf"))
        assertNull(MediaFormats.describe("application/zip", "a.zip"))
        assertNull(MediaFormats.describe("application/octet-stream", "data.bin"))
        assertNull(MediaFormats.describe(null, "noextension"))
        assertNull(MediaFormats.describe(null, null))
    }

    @Test fun oggDependsOnTheExtension() {
        assertEquals(video("Ogg"), MediaFormats.describe("application/ogg", "clip.ogv"))
        assertEquals(audio("Ogg"), MediaFormats.describe("application/ogg", "song.ogg"))
        assertEquals(audio("Ogg"), MediaFormats.describe("application/ogg", null))
    }

    @Test fun newerExtensionsAreKnown() {
        listOf("m4v", "ogv", "mpg", "mpeg", "wmv", "flv").forEach { ext ->
            assertEquals(ext, MediaKind.Video, MediaFormats.describe(null, "x.$ext")?.kind)
        }
        listOf("opus", "oga", "weba", "mka", "wma", "aif", "aiff", "amr", "flac", "wav", "aac").forEach { ext ->
            assertEquals(ext, MediaKind.Audio, MediaFormats.describe(null, "x.$ext")?.kind)
        }
    }

    @Test fun unknownMediaSubtypeIsShownInAHarmlessWay() {
        assertEquals(video("FOOBAR.BAZ+X"), MediaFormats.describe("video/x-foo_bar.baz+x", null))
        val long = MediaFormats.describe("audio/" + "a".repeat(80), null)
        assertEquals(24, long?.format?.length)
        // Only letters, digits and + . - survive, so nothing odd can reach the screen from a server.
        assertNull(MediaFormats.describe("video/<>", null))
    }
}
