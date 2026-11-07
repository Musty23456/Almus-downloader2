package com.almus.ytdownloader.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the download bookkeeping with a fake system downloader and an in-memory table.
 * The real DownloadManager is NOT involved; see the README for what that leaves untested.
 */
class DownloadRepositoryTest {
    private lateinit var dao: FakeDao
    private lateinit var system: FakeSystem
    private lateinit var storage: FakeStorage
    private lateinit var notifier: FakeNotifier
    private lateinit var settings: FakeSettings
    private var now = 1_000L

    private fun newRepo() = DownloadRepository(dao, system, storage, notifier, settings) { now++ }
    private lateinit var repo: DownloadRepository

    @Before fun setUp() {
        dao = FakeDao()
        system = FakeSystem()
        storage = FakeStorage()
        notifier = FakeNotifier()
        settings = FakeSettings(3)
        repo = newRepo()
    }

    private fun row(id: Long) = dao.rows.value.first { it.id == id }

    private fun started(result: StartResult): Long {
        assertTrue("expected Started but was $result", result is StartResult.Started)
        return (result as StartResult.Started).id
    }

    @Test fun successfulDownloadGoesQueuedRunningCompletedAndNotifiesOnce() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo("report.pdf", length = 2000, mime = "application/pdf")))
        val systemId = row(id).systemId
        assertNotNull(systemId)
        assertEquals(DownloadState.Queued.name, row(id).state)
        assertEquals("report.pdf", system.enqueued.single().fileName)
        assertEquals(StorageTarget.PublicDownloads, system.enqueued.single().target)

        system.snapshots[systemId!!] = SystemSnapshot(StatusMapper.STATUS_RUNNING, 0, 500, 2000, null, null)
        assertTrue("still unfinished", repo.refresh())
        assertEquals(DownloadState.Running.name, row(id).state)
        assertEquals(500, row(id).downloadedBytes)
        assertTrue(notifier.sent.isEmpty())

        system.snapshots[systemId] = SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 2000, 2000, "file:///d/report.pdf", "application/pdf")
        assertFalse("nothing unfinished any more", repo.refresh())
        assertEquals(DownloadState.Completed.name, row(id).state)
        assertEquals(2000, row(id).downloadedBytes)
        assertEquals(1, notifier.sent.size)

        repo.refresh()
        repo.onSystemDownloadComplete(systemId)
        assertEquals("announced only once", 1, notifier.sent.size)
    }

    @Test fun failedNetworkDownloadIsRecordedWithMessageAndNotified() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo()))
        val systemId = row(id).systemId!!
        system.snapshots[systemId] = SystemSnapshot(StatusMapper.STATUS_FAILED, StatusMapper.ERROR_HTTP_DATA_ERROR, 100, 1000, null, null)
        repo.refresh()
        assertEquals(DownloadState.Failed.name, row(id).state)
        assertTrue(row(id).errorMessage!!.contains("connection", ignoreCase = true))
        assertEquals(1, notifier.sent.size)
        assertEquals(DownloadState.Failed.name, notifier.sent.single().state)
    }

    @Test fun http404FromTheServerShowsUsefulMessage() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo()))
        system.snapshots[row(id).systemId!!] = SystemSnapshot(StatusMapper.STATUS_FAILED, 404, 0, -1, null, null)
        repo.refresh()
        assertTrue(row(id).errorMessage!!.contains("404"))
    }

    @Test fun statusIsRestoredByANewRepositoryAfterRestart() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo(length = 1000)))
        val systemId = row(id).systemId!!
        system.snapshots[systemId] = SystemSnapshot(StatusMapper.STATUS_RUNNING, 0, 100, 1000, null, null)
        repo.refresh()

        // "Process death": same table and same system downloader, new objects. The download finished meanwhile.
        system.snapshots[systemId] = SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 1000, 1000, "file:///d/file.zip", "application/zip")
        val restarted = newRepo()
        restarted.refresh()
        assertEquals(DownloadState.Completed.name, row(id).state)
        assertEquals(1, notifier.sent.size)
    }

    @Test fun downloadRemovedInSystemUiBecomesCancelledWithoutNotification() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo()))
        system.snapshots.remove(row(id).systemId!!)
        repo.refresh()
        assertEquals(DownloadState.Cancelled.name, row(id).state)
        assertTrue(notifier.sent.isEmpty())
    }

    @Test fun recordWithoutSystemIdIsFailedOnRefresh() = runBlocking<Unit> {
        // Simulates a crash between saving the record and queuing it.
        val id = dao.insert(
            com.almus.ytdownloader.data.DownloadEntity(
                url = "https://example.com/a.zip", fileName = "a.zip", mimeType = null, systemId = null,
                state = DownloadState.Queued.name, downloadedBytes = 0, totalBytes = -1, errorMessage = null,
                localUri = null, insecure = false, notified = false, createdAt = 1, updatedAt = 1,
            ),
        )
        repo.refresh()
        assertEquals(DownloadState.Failed.name, row(id).state)
    }

    @Test fun notEnoughSpaceIsRejectedBeforeAnythingIsQueued() = runBlocking<Unit> {
        storage.free = 500
        val result = repo.start(linkInfo(length = 2_000))
        assertTrue(result is StartResult.Rejected)
        assertTrue((result as StartResult.Rejected).message.contains("Not enough free storage"))
        assertTrue(system.enqueued.isEmpty())
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun unknownFreeSpaceDoesNotBlock() = runBlocking<Unit> {
        storage.free = -1
        started(repo.start(linkInfo(length = 9_999_999_999)))
    }

    @Test fun enqueueFailureIsReportedAndRecordedAsFailed() = runBlocking<Unit> {
        system.enqueueError = IllegalStateException("no storage")
        val result = repo.start(linkInfo())
        assertTrue(result is StartResult.Rejected)
        val only = dao.rows.value.single()
        assertEquals(DownloadState.Failed.name, only.state)
        assertTrue(only.errorMessage!!.contains("no storage"))
        assertTrue("no notification for a failure the user just saw", notifier.sent.isEmpty())
    }

    @Test fun sameNameGetsCounterButFailedNamesAreReusable() = runBlocking<Unit> {
        val first = started(repo.start(linkInfo("a.pdf")))
        // Different links that happen to share a file name (the same link would be rejected as a duplicate).
        val second = started(repo.start(linkInfo("a.pdf", url = "https://example.com/two/a.pdf")))
        assertEquals("a.pdf", row(first).fileName)
        assertEquals("a (1).pdf", row(second).fileName)

        repo.cancel(second)
        val third = started(repo.start(linkInfo("a.pdf", url = "https://example.com/three/a.pdf")))
        assertEquals("a (1).pdf", row(third).fileName)
    }

    @Test fun existingFileOnDiskIsNotOverwritten() = runBlocking<Unit> {
        storage.existing = setOf("a.pdf", "a (1).pdf")
        val id = started(repo.start(linkInfo("a.pdf")))
        assertEquals("a (2).pdf", row(id).fileName)
    }

    @Test fun legacyStorageFallbackUsesAppFolder() = runBlocking<Unit> {
        storage.target = StorageTarget.AppPrivate
        started(repo.start(linkInfo()))
        assertEquals(StorageTarget.AppPrivate, system.enqueued.single().target)
    }

    @Test fun cancelRemovesSystemDownloadAndMarksCancelled() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo()))
        val systemId = row(id).systemId!!
        repo.cancel(id)
        assertEquals(listOf(systemId), system.removed)
        assertEquals(DownloadState.Cancelled.name, row(id).state)
        repo.refresh()
        assertEquals("cancelled stays cancelled", DownloadState.Cancelled.name, row(id).state)
        assertTrue(notifier.sent.isEmpty())
    }

    @Test fun retryRequeuesFailedDownloadOnTheSameRecord() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo("a.zip")))
        system.snapshots[row(id).systemId!!] = SystemSnapshot(StatusMapper.STATUS_FAILED, 503, 0, -1, null, null)
        repo.refresh()
        val oldSystemId = row(id).systemId

        started(repo.retry(id))
        assertEquals(DownloadState.Queued.name, row(id).state)
        assertTrue(row(id).systemId != oldSystemId)
        assertNull(row(id).errorMessage)
        assertEquals(1, dao.rows.value.size)
        assertEquals("a.zip", row(id).fileName)
    }

    @Test fun retryIsRefusedForRunningAndCompletedDownloads() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo()))
        assertTrue(repo.retry(id) is StartResult.Rejected)
        val systemId = row(id).systemId!!
        system.snapshots[systemId] = SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 1000, 1000, null, null)
        repo.refresh()
        assertTrue(repo.retry(id) is StartResult.Rejected)
    }

    @Test fun removeRecordsKeepsFilesAndIgnoresUnfinishedDownloads() = runBlocking<Unit> {
        val running = started(repo.start(linkInfo("r.zip")))
        assertEquals("unfinished records cannot be removed from the list", 0, repo.removeRecords(running))
        assertTrue(dao.rows.value.any { it.id == running })
        assertTrue(system.removed.isEmpty())

        val done = started(repo.start(linkInfo("d.zip")))
        val doneSystemId = row(done).systemId!!
        system.snapshots[doneSystemId] = SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 1, 1, null, null)
        repo.refresh()
        assertEquals(1, repo.removeRecords(done))
        assertFalse("removing a record must not delete the file", doneSystemId in system.removed)
        assertTrue(dao.rows.value.none { it.id == done })
    }

    @Test fun completionBroadcastForForeignIdIsIgnored() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo()))
        system.snapshots[row(id).systemId!!] = SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 1000, 1000, null, null)
        repo.onSystemDownloadComplete(424242L) // not ours
        assertEquals(DownloadState.Queued.name, row(id).state)
        assertTrue(notifier.sent.isEmpty())
    }

    @Test fun completionBroadcastForOwnIdUpdatesAndNotifies() = runBlocking<Unit> {
        val id = started(repo.start(linkInfo()))
        val systemId = row(id).systemId!!
        system.snapshots[systemId] = SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 1000, 1000, null, null)
        repo.onSystemDownloadComplete(systemId)
        assertEquals(DownloadState.Completed.name, row(id).state)
        assertEquals(1, notifier.sent.size)
    }
}
