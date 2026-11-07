package com.almus.ytdownloader.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtDlpFormatTest {
    @Test
    fun acceptsPlainAndSchemeLessLinks() {
        assertEquals("https://youtu.be/abc", UrlInput.normalize("  https://youtu.be/abc "))
        assertEquals("https://youtu.be/abc", UrlInput.normalize("youtu.be/abc"))
    }

    @Test
    fun rejectsTextThatIsNotALink() {
        assertNull(UrlInput.normalize(""))
        assertNull(UrlInput.normalize("hello world"))
        assertNull(UrlInput.normalize("localhost"))
        assertNull(UrlInput.normalize("https://.com"))
    }

    @Test
    fun formatsDurations() {
        assertEquals("0:09", formatDuration(9))
        assertEquals("3:05", formatDuration(185))
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("0:00", formatDuration(-5))
    }

    @Test
    fun shortErrorKeepsLastUsefulLine() {
        assertEquals("ERROR: Video unavailable", shortError(RuntimeException("WARNING: x\nERROR: Video unavailable\n")))
    }
}

class SummarizeTest {
    private val spec = DownloadSpec("https://example.com/v", "t", MediaKind.VIDEO, null, VideoContainer.MP4, AudioFormat.MP3)

    private fun job(state: JobState, percent: Float = 0f) = DownloadJob(id = state.name + percent, spec = spec, state = state, percent = percent)

    @Test
    fun noWorkingJobsGivesZero() {
        assertEquals(ActiveSummary(0, 0), summarize(listOf(job(JobState.DONE, 100f), job(JobState.FAILED))))
        assertEquals(ActiveSummary(0, 0), summarize(emptyList()))
    }

    @Test
    fun averagesOnlyWorkingJobs() {
        val s = summarize(listOf(job(JobState.RUNNING, 20f), job(JobState.QUEUED, 0f), job(JobState.DONE, 100f)))
        assertEquals(2, s.active)
        assertEquals(10, s.percent)
    }
}

class ExtractAllTest {
    @Test
    fun splitsOnSpacesLinesAndCommasAndDropsRepeats() {
        val raw = "https://youtu.be/a\nyoutu.be/b, https://youtu.be/a   not a link"
        assertEquals(listOf("https://youtu.be/a", "https://youtu.be/b"), UrlInput.extractAll(raw))
    }

    @Test
    fun emptyAndJunkGiveNothing() {
        assertEquals(emptyList<String>(), UrlInput.extractAll("  \n "))
        assertEquals(emptyList<String>(), UrlInput.extractAll("hello world"))
    }
}
