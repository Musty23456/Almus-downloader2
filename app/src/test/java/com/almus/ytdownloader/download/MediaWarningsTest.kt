package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaWarningsTest {
    private fun media(
        length: Long = 1000,
        ranges: Boolean? = null,
        name: String = "a.mp4",
        mime: String = "video/mp4",
    ) = linkInfo(name, length, mime).copy(media = MediaFormats.describe(mime, name), acceptsRanges = ranges)

    @Test fun mediaWithSizeAndNoRangeInformationHasNoWarnings() {
        assertTrue(LinkWarnings.of(media()).isEmpty())
    }

    @Test fun mediaWithoutSizeWarnsAboutEndlessStreams() {
        assertEquals(listOf(LinkWarning.UnknownSize, LinkWarning.MaybeLive), LinkWarnings.of(media(length = -1)))
    }

    @Test fun mediaThatCannotResumeWarns() {
        assertEquals(listOf(LinkWarning.NoResume), LinkWarnings.of(media(ranges = false)))
    }

    @Test fun mediaThatCanResumeHasNoWarning() {
        assertTrue(LinkWarnings.of(media(ranges = true)).isEmpty())
    }

    @Test fun ordinaryFilesNeverGetTheMediaWarnings() {
        val zip = linkInfo("a.zip", length = -1).copy(acceptsRanges = false)
        assertEquals(listOf(LinkWarning.UnknownSize), LinkWarnings.of(zip))
    }

    @Test fun existingWarningsStillComeFirst() {
        val warnings = LinkWarnings.of(media(length = -1, ranges = false).copy(insecure = true))
        assertEquals(
            listOf(LinkWarning.Insecure, LinkWarning.UnknownSize, LinkWarning.MaybeLive, LinkWarning.NoResume),
            warnings,
        )
    }
}
