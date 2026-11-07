package com.almus.ytdownloader.download

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusMapperTest {
    /**
     * Guards against typos in the copied numbers. The constants on the right-hand side are
     * inlined by the compiler from the real android.jar (compileSdk 35).
     */
    @Test fun copiedConstantsMatchAndroidDownloadManager() {
        assertEquals(DownloadManager.STATUS_PENDING, StatusMapper.STATUS_PENDING)
        assertEquals(DownloadManager.STATUS_RUNNING, StatusMapper.STATUS_RUNNING)
        assertEquals(DownloadManager.STATUS_PAUSED, StatusMapper.STATUS_PAUSED)
        assertEquals(DownloadManager.STATUS_SUCCESSFUL, StatusMapper.STATUS_SUCCESSFUL)
        assertEquals(DownloadManager.STATUS_FAILED, StatusMapper.STATUS_FAILED)

        assertEquals(DownloadManager.PAUSED_WAITING_TO_RETRY, StatusMapper.PAUSED_WAITING_TO_RETRY)
        assertEquals(DownloadManager.PAUSED_WAITING_FOR_NETWORK, StatusMapper.PAUSED_WAITING_FOR_NETWORK)
        assertEquals(DownloadManager.PAUSED_QUEUED_FOR_WIFI, StatusMapper.PAUSED_QUEUED_FOR_WIFI)
        assertEquals(DownloadManager.PAUSED_UNKNOWN, StatusMapper.PAUSED_UNKNOWN)

        assertEquals(DownloadManager.ERROR_UNKNOWN, StatusMapper.ERROR_UNKNOWN)
        assertEquals(DownloadManager.ERROR_FILE_ERROR, StatusMapper.ERROR_FILE_ERROR)
        assertEquals(DownloadManager.ERROR_UNHANDLED_HTTP_CODE, StatusMapper.ERROR_UNHANDLED_HTTP_CODE)
        assertEquals(DownloadManager.ERROR_HTTP_DATA_ERROR, StatusMapper.ERROR_HTTP_DATA_ERROR)
        assertEquals(DownloadManager.ERROR_TOO_MANY_REDIRECTS, StatusMapper.ERROR_TOO_MANY_REDIRECTS)
        assertEquals(DownloadManager.ERROR_INSUFFICIENT_SPACE, StatusMapper.ERROR_INSUFFICIENT_SPACE)
        assertEquals(DownloadManager.ERROR_DEVICE_NOT_FOUND, StatusMapper.ERROR_DEVICE_NOT_FOUND)
        assertEquals(DownloadManager.ERROR_CANNOT_RESUME, StatusMapper.ERROR_CANNOT_RESUME)
        assertEquals(DownloadManager.ERROR_FILE_ALREADY_EXISTS, StatusMapper.ERROR_FILE_ALREADY_EXISTS)
    }

    @Test fun mapsSystemStatusToState() {
        assertEquals(DownloadState.Queued, StatusMapper.toState(StatusMapper.STATUS_PENDING))
        assertEquals(DownloadState.Running, StatusMapper.toState(StatusMapper.STATUS_RUNNING))
        assertEquals(DownloadState.Paused, StatusMapper.toState(StatusMapper.STATUS_PAUSED))
        assertEquals(DownloadState.Completed, StatusMapper.toState(StatusMapper.STATUS_SUCCESSFUL))
        assertEquals(DownloadState.Failed, StatusMapper.toState(StatusMapper.STATUS_FAILED))
    }

    @Test fun unknownStatusIsTreatedAsFailed() {
        assertEquals(DownloadState.Failed, StatusMapper.toState(0))
        assertEquals(DownloadState.Failed, StatusMapper.toState(999))
    }

    @Test fun terminalStatesAreCompletedFailedCancelled() {
        assertTrue(DownloadState.Completed.isTerminal)
        assertTrue(DownloadState.Failed.isTerminal)
        assertTrue(DownloadState.Cancelled.isTerminal)
        DownloadState.unfinished.forEach { assertTrue(!it.isTerminal) }
    }

    @Test fun stateNamesRoundTripAndUnknownNamesFail() {
        DownloadState.entries.forEach { assertEquals(it, DownloadState.fromName(it.name)) }
        assertEquals(DownloadState.Failed, DownloadState.fromName("SomethingNew"))
        assertEquals(DownloadState.Failed, DownloadState.fromName(null))
    }

    @Test fun httpFailureReasonsGiveSpecificMessages() {
        assertTrue(StatusMapper.failureMessage(404).contains("not found", ignoreCase = true))
        assertTrue(StatusMapper.failureMessage(404).contains("404"))
        assertTrue(StatusMapper.failureMessage(403).contains("Access denied"))
        assertTrue(StatusMapper.failureMessage(401).contains("login"))
        assertTrue(StatusMapper.failureMessage(500).contains("Server error"))
        assertTrue(StatusMapper.failureMessage(503).contains("503"))
        assertTrue(StatusMapper.failureMessage(429).contains("Too many requests"))
    }

    @Test fun systemErrorCodesGiveSpecificMessages() {
        assertTrue(StatusMapper.failureMessage(StatusMapper.ERROR_INSUFFICIENT_SPACE).contains("space"))
        assertTrue(StatusMapper.failureMessage(StatusMapper.ERROR_HTTP_DATA_ERROR).contains("connection", ignoreCase = true))
        assertTrue(StatusMapper.failureMessage(StatusMapper.ERROR_TOO_MANY_REDIRECTS).contains("redirect"))
        assertTrue(StatusMapper.failureMessage(StatusMapper.ERROR_FILE_ERROR).contains("saved"))
        assertTrue(StatusMapper.failureMessage(StatusMapper.ERROR_DEVICE_NOT_FOUND).contains("Storage"))
        assertTrue(StatusMapper.failureMessage(StatusMapper.ERROR_CANNOT_RESUME).contains("resume"))
    }

    @Test fun unknownReasonStillGivesAMessage() {
        assertTrue(StatusMapper.failureMessage(0).isNotBlank())
        assertTrue(StatusMapper.failureMessage(-5).isNotBlank())
        assertTrue(StatusMapper.failureMessage(1234).isNotBlank())
    }

    @Test fun pausedReasons() {
        assertTrue(StatusMapper.pausedMessage(StatusMapper.PAUSED_WAITING_FOR_NETWORK)!!.contains("network"))
        assertTrue(StatusMapper.pausedMessage(StatusMapper.PAUSED_QUEUED_FOR_WIFI)!!.contains("Wi-Fi"))
        assertTrue(StatusMapper.pausedMessage(StatusMapper.PAUSED_WAITING_TO_RETRY)!!.contains("Retrying"))
        assertNull(StatusMapper.pausedMessage(StatusMapper.PAUSED_UNKNOWN))
    }

    @Test fun byteFormatting() {
        assertEquals("0 B", ByteFormat.format(0))
        assertEquals("1023 B", ByteFormat.format(1023))
        assertEquals("1.0 KB", ByteFormat.format(1024))
        assertEquals("1.5 MB", ByteFormat.format(1_572_864))
        assertEquals("2.0 GB", ByteFormat.format(2L * 1024 * 1024 * 1024))
        assertEquals("?", ByteFormat.format(-1))
        assertEquals("1.0 KB of 2.0 KB", ByteFormat.progressLabel(1024, 2048))
        assertEquals("1.0 KB", ByteFormat.progressLabel(1024, -1))
    }
}
