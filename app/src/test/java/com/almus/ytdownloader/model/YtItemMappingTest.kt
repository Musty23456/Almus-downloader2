package com.almus.ytdownloader.model

import com.almus.ytdownloader.ytdlp.AudioFormat
import com.almus.ytdownloader.ytdlp.DownloadJob
import com.almus.ytdownloader.ytdlp.DownloadSpec
import com.almus.ytdownloader.ytdlp.JobState
import com.almus.ytdownloader.ytdlp.MediaKind
import com.almus.ytdownloader.ytdlp.VideoContainer
import com.almus.ytdownloader.ytdlp.YtJobIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtItemMappingTest {
    private val video = DownloadSpec("https://example.com/v", "My video", MediaKind.VIDEO, 720, VideoContainer.MP4, AudioFormat.MP3)
    private val audio = DownloadSpec("https://example.com/v", "My song", MediaKind.AUDIO, null, VideoContainer.MP4, AudioFormat.M4A)

    private fun job(spec: DownloadSpec = video, state: JobState = JobState.QUEUED, number: Long = 5, percent: Float = 0f) =
        DownloadJob(id = "j$number", number = number, spec = spec, state = state, percent = percent)

    @Test
    fun idsStayApartFromTheOtherTwoRanges() {
        val item = job().toDownloadItem()
        assertEquals(YtJobIds.BASE + 5, item.id)
        assertTrue(YtJobIds.isYt(item.id))
        assertEquals(5L, YtJobIds.numberOf(item.id))
        assertFalse(YtJobIds.isYt(-5L))
        assertFalse(YtJobIds.isYt(123_456L))
    }

    @Test
    fun runningJobShowsPercentAndChoices() {
        val item = job(state = JobState.RUNNING, percent = 42.5f).toDownloadItem().copy()
        assertEquals(DownloadStatus.Active, item.status)
        assertEquals("42% · MP4 · 720p", item.sizeLabel)
        assertEquals(0.425f, item.progress, 0.001f)
        assertTrue(item.progressKnown)
        assertNull(item.speedLabel)
    }

    @Test
    fun runningJobWithEtaShowsIt() {
        val item = DownloadJob(id = "x", number = 1, spec = video, state = JobState.RUNNING, percent = 10f, etaSeconds = 90).toDownloadItem()
        assertNotNull(item.etaLabel)
    }

    @Test
    fun finishedJobsMapToTheirStatuses() {
        val done = DownloadJob(id = "d", number = 2, spec = video, state = JobState.DONE, fileName = "real.mp4").toDownloadItem()
        assertEquals(DownloadStatus.Completed, done.status)
        assertEquals("real.mp4", done.fileName)
        assertEquals(1f, done.progress, 0f)

        val failed = DownloadJob(id = "f", number = 3, spec = video, state = JobState.FAILED, error = "ERROR: gone").toDownloadItem()
        assertEquals(DownloadStatus.Failed, failed.status)
        assertEquals("ERROR: gone", failed.errorMessage)
        assertEquals("My video", failed.fileName)

        assertEquals(DownloadStatus.Cancelled, job(state = JobState.CANCELLED).toDownloadItem().status)
        assertEquals(DownloadStatus.Queued, job(state = JobState.QUEUED).toDownloadItem().status)
        assertEquals(DownloadStatus.Active, job(state = JobState.SAVING).toDownloadItem().status)
    }

    @Test
    fun audioAndBestQualityLabels() {
        assertEquals("M4A", job(audio).toDownloadItem().sizeLabel)
        val best = video.copy(maxHeight = null)
        assertEquals("MP4 · Best", job(best).toDownloadItem().sizeLabel)
    }
}
