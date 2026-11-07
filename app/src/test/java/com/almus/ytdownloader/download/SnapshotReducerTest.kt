package com.almus.ytdownloader.download

import com.almus.ytdownloader.data.DownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotReducerTest {
    private fun entity(state: DownloadState = DownloadState.Queued, total: Long = -1) = DownloadEntity(
        id = 1,
        url = "https://example.com/a.zip",
        fileName = "a.zip",
        mimeType = "application/zip",
        systemId = 10,
        state = state.name,
        downloadedBytes = 0,
        totalBytes = total,
        errorMessage = null,
        localUri = null,
        insecure = false,
        notified = false,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun snap(
        status: Int,
        reason: Int = 0,
        done: Long = 0,
        total: Long = -1,
        uri: String? = null,
        mime: String? = null,
    ) = SystemSnapshot(status, reason, done, total, uri, mime)

    @Test fun runningUpdatesProgressAndTotal() {
        val r = SnapshotReducer.reduce(entity(), snap(StatusMapper.STATUS_RUNNING, done = 500, total = 2000), now = 5)
        assertEquals(DownloadState.Running.name, r.state)
        assertEquals(500, r.downloadedBytes)
        assertEquals(2000, r.totalBytes)
        assertEquals(5, r.updatedAt)
        assertNull(r.errorMessage)
    }

    @Test fun unknownTotalKeepsEarlierKnownTotal() {
        val r = SnapshotReducer.reduce(
            entity(DownloadState.Running, total = 2000),
            snap(StatusMapper.STATUS_RUNNING, done = 10, total = -1),
            now = 2,
        )
        assertEquals(2000, r.totalBytes)
    }

    @Test fun unknownTotalStaysUnknown() {
        val r = SnapshotReducer.reduce(entity(), snap(StatusMapper.STATUS_RUNNING, done = 10, total = -1), now = 2)
        assertEquals(-1, r.totalBytes)
        assertEquals(10, r.downloadedBytes)
    }

    @Test fun completedFillsInTotalAndLocation() {
        val r = SnapshotReducer.reduce(
            entity(DownloadState.Running),
            snap(StatusMapper.STATUS_SUCCESSFUL, done = 2000, total = 2000, uri = "file:///x/a.zip", mime = "application/zip"),
            now = 9,
        )
        assertEquals(DownloadState.Completed.name, r.state)
        assertEquals(2000, r.downloadedBytes)
        assertEquals("file:///x/a.zip", r.localUri)
    }

    @Test fun failedUsesHttpMessage() {
        val r = SnapshotReducer.reduce(entity(), snap(StatusMapper.STATUS_FAILED, reason = 404), now = 3)
        assertEquals(DownloadState.Failed.name, r.state)
        assertTrue(r.errorMessage!!.contains("404"))
    }

    @Test fun failedUsesSystemErrorMessage() {
        val r = SnapshotReducer.reduce(
            entity(),
            snap(StatusMapper.STATUS_FAILED, reason = StatusMapper.ERROR_INSUFFICIENT_SPACE),
            now = 3,
        )
        assertTrue(r.errorMessage!!.contains("space"))
    }

    @Test fun pausedCarriesReason() {
        val r = SnapshotReducer.reduce(
            entity(DownloadState.Running),
            snap(StatusMapper.STATUS_PAUSED, reason = StatusMapper.PAUSED_WAITING_FOR_NETWORK, done = 100),
            now = 4,
        )
        assertEquals(DownloadState.Paused.name, r.state)
        assertTrue(r.errorMessage!!.contains("network"))
    }

    @Test fun genericMimeFromSystemDoesNotOverwriteKnownMime() {
        val r = SnapshotReducer.reduce(
            entity(),
            snap(StatusMapper.STATUS_RUNNING, mime = "application/octet-stream"),
            now = 1,
        )
        assertEquals("application/zip", r.mimeType)
    }

    @Test fun finishedRecordsNeverChange() {
        listOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled).forEach { state ->
            val e = entity(state)
            assertSame(e, SnapshotReducer.reduce(e, snap(StatusMapper.STATUS_RUNNING, done = 5), now = 99))
            assertSame(e, SnapshotReducer.reduce(e, null, now = 99))
        }
    }

    @Test fun missingSystemRecordMarksUnfinishedDownloadCancelled() {
        val r = SnapshotReducer.reduce(entity(DownloadState.Running), null, now = 7)
        assertEquals(DownloadState.Cancelled.name, r.state)
        assertEquals(SnapshotReducer.REMOVED_MESSAGE, r.errorMessage)
    }
}
