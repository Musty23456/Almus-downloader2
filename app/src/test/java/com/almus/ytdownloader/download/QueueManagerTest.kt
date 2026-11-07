package com.almus.ytdownloader.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Queue behaviour with a fake system downloader: concurrency, order, hold, cancel, retry, duplicates. */
class QueueManagerTest {
    private lateinit var dao: FakeDao
    private lateinit var system: FakeSystem
    private lateinit var storage: FakeStorage
    private lateinit var notifier: FakeNotifier
    private lateinit var settings: FakeSettings
    private lateinit var repo: DownloadRepository
    private var now = 10_000L

    @Before fun setUp() {
        dao = FakeDao()
        system = FakeSystem()
        storage = FakeStorage()
        notifier = FakeNotifier()
        settings = FakeSettings(2)
        repo = DownloadRepository(dao, system, storage, notifier, settings) { now++ }
    }

    private fun url(n: Int) = "https://example.com/file$n.zip"
    private fun info(n: Int) = linkInfo(name = "file$n.zip", url = url(n))
    private fun row(id: Long) = dao.rows.value.first { it.id == id }
    private fun stateOf(id: Long) = DownloadState.fromName(row(id).state)

    private suspend fun add(n: Int): Long {
        val result = repo.start(info(n))
        assertTrue("expected Started but was $result", result is StartResult.Started)
        return (result as StartResult.Started).id
    }

    private fun finish(id: Long) {
        system.snapshots[row(id).systemId!!] = SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 1000, 1000, null, null)
    }

    private fun fail(id: Long, reason: Int = 503) {
        system.snapshots[row(id).systemId!!] = SystemSnapshot(StatusMapper.STATUS_FAILED, reason, 0, -1, null, null)
    }

    private fun run(id: Long) {
        system.snapshots[row(id).systemId!!] = SystemSnapshot(StatusMapper.STATUS_RUNNING, 0, 100, 1000, null, null)
    }

    // ---------------------------------------------------------------- concurrency and order

    @Test fun neverMoreThanTheLimitIsHandedToTheSystem() = runBlocking<Unit> {
        val ids = (1..4).map { add(it) }
        assertEquals(2, system.enqueued.size)
        assertEquals(DownloadState.Queued, stateOf(ids[0]))
        assertEquals(DownloadState.Queued, stateOf(ids[1]))
        assertEquals(DownloadState.Waiting, stateOf(ids[2]))
        assertEquals(DownloadState.Waiting, stateOf(ids[3]))
        assertNull(row(ids[2]).systemId)
    }

    @Test fun startResultTellsWhetherTheDownloadWaits() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        assertEquals(false, (repo.start(info(1)) as StartResult.Started).queued)
        assertEquals(true, (repo.start(info(2)) as StartResult.Started).queued)
    }

    @Test fun waitingDownloadsStartInTheOrderTheyWereAdded() = runBlocking<Unit> {
        val ids = (1..4).map { add(it) }
        finish(ids[0])
        repo.refresh()
        assertEquals(url(3), system.enqueued[2].url)
        finish(ids[1])
        repo.refresh()
        assertEquals(url(4), system.enqueued[3].url)
        assertEquals(4, system.enqueued.size)
    }

    @Test fun runningCountStaysWithinTheLimitWhileDownloadsFinish() = runBlocking<Unit> {
        val ids = (1..5).map { add(it) }
        repeat(5) {
            val live = dao.rows.value.count { DownloadState.fromName(it.state).holdsSlot }
            assertTrue("slots in use: $live", live <= 2)
            dao.rows.value.filter { DownloadState.fromName(it.state).holdsSlot }.forEach { finish(it.id) }
            repo.refresh()
        }
        assertTrue(ids.all { stateOf(it) == DownloadState.Completed })
        assertEquals(5, system.enqueued.size)
    }

    @Test fun raisingTheLimitStartsMoreImmediately() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val ids = (1..3).map { add(it) }
        assertEquals(1, system.enqueued.size)
        settings.maxConcurrent = 3
        repo.onSettingsChanged()
        assertEquals(3, system.enqueued.size)
        assertTrue(ids.all { stateOf(it) == DownloadState.Queued })
    }

    @Test fun loweringTheLimitNeverCancelsRunningDownloads() = runBlocking<Unit> {
        settings.maxConcurrent = 3
        val ids = (1..3).map { add(it) }
        settings.maxConcurrent = 1
        repo.onSettingsChanged()
        val fourth = add(4)
        assertTrue(system.removed.isEmpty())
        assertEquals(DownloadState.Waiting, stateOf(fourth))

        finish(ids[0])
        repo.refresh()
        assertEquals("two are still running, so the limit of 1 is still exceeded", DownloadState.Waiting, stateOf(fourth))
        finish(ids[1]); finish(ids[2])
        repo.refresh()
        assertEquals(DownloadState.Queued, stateOf(fourth))
    }

    @Test fun limitIsClampedToTheSupportedRange() = runBlocking<Unit> {
        settings.maxConcurrent = 0
        add(1); add(2)
        assertEquals("a limit below 1 behaves like 1", 1, system.enqueued.size)
        settings.maxConcurrent = 99
        repo.onSettingsChanged()
        assertEquals(2, system.enqueued.size)
    }

    // ---------------------------------------------------------------- hold / resume

    @Test fun onlyWaitingDownloadsCanBeHeld() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val running = add(1)
        val waiting = add(2)
        assertEquals(0, repo.hold(running))
        assertEquals(DownloadState.Queued, stateOf(running))
        assertEquals(1, repo.hold(waiting))
        assertEquals(DownloadState.OnHold, stateOf(waiting))
    }

    @Test fun heldDownloadDoesNotStartUntilResumed() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val first = add(1)
        val held = add(2)
        repo.hold(held)
        finish(first)
        repo.refresh()
        assertEquals(DownloadState.OnHold, stateOf(held))
        assertEquals(1, system.enqueued.size)

        assertEquals(1, repo.resume(held))
        assertEquals(DownloadState.Queued, stateOf(held))
        assertEquals(2, system.enqueued.size)
    }

    @Test fun resumedDownloadKeepsItsPlaceInTheQueue() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val first = add(1)
        val second = add(2)
        val third = add(3)
        repo.hold(second)
        repo.resume(second)
        finish(first)
        repo.refresh()
        assertEquals("second was added before third", DownloadState.Queued, stateOf(second))
        assertEquals(DownloadState.Waiting, stateOf(third))
    }

    @Test fun resumeIgnoresDownloadsThatAreNotOnHold() = runBlocking<Unit> {
        val id = add(1)
        assertEquals(0, repo.resume(id))
        assertEquals(DownloadState.Queued, stateOf(id))
    }

    @Test fun heldDownloadsDoNotOccupySlots() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        add(1)
        val held = add(2)
        repo.hold(held)
        val third = add(3)
        assertEquals(DownloadState.Waiting, stateOf(third))
    }

    // ---------------------------------------------------------------- cancel

    @Test fun cancellingARunningDownloadStartsTheNextWaitingOne() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val first = add(1)
        val second = add(2)
        val firstSystemId = row(first).systemId!!
        assertEquals(1, repo.cancel(first))
        assertEquals(listOf(firstSystemId), system.removed)
        assertEquals(DownloadState.Cancelled, stateOf(first))
        assertEquals(DownloadState.Queued, stateOf(second))
    }

    @Test fun cancellingAWaitingDownloadNeverTouchesTheSystem() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        add(1)
        val waiting = add(2)
        assertEquals(1, repo.cancel(waiting))
        assertTrue(system.removed.isEmpty())
        assertEquals(DownloadState.Cancelled, stateOf(waiting))
    }

    @Test fun cancellingAHeldDownloadWorks() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        add(1)
        val held = add(2)
        repo.hold(held)
        assertEquals(1, repo.cancel(held))
        assertEquals(DownloadState.Cancelled, stateOf(held))
    }

    @Test fun finishedDownloadsCannotBeCancelled() = runBlocking<Unit> {
        val id = add(1)
        finish(id)
        repo.refresh()
        assertEquals(0, repo.cancel(id))
        assertEquals(DownloadState.Completed, stateOf(id))
        assertTrue("the finished file must not be deleted", system.removed.isEmpty())
    }

    // ---------------------------------------------------------------- retry

    @Test fun retriedDownloadGoesToTheEndOfTheQueue() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val a = add(1)
        val b = add(2)
        val c = add(3)
        fail(a)
        repo.refresh() // a failed, b starts
        assertEquals(DownloadState.Queued, stateOf(b))

        assertTrue(repo.retry(a) is StartResult.Started)
        assertEquals(DownloadState.Waiting, stateOf(a))

        finish(b)
        repo.refresh()
        assertEquals("c was waiting before the retried a", DownloadState.Queued, stateOf(c))
        assertEquals(DownloadState.Waiting, stateOf(a))

        finish(c)
        repo.refresh()
        assertEquals(DownloadState.Queued, stateOf(a))
        assertEquals(listOf(url(1), url(2), url(3), url(1)), system.enqueued.map { it.url })
    }

    @Test fun retryIsRejectedWhileTheSameLinkIsAlreadyQueued() = runBlocking<Unit> {
        val a = add(1)
        fail(a)
        repo.refresh()
        add(1) // a new entry for the same link is allowed because the old one failed
        val result = repo.retry(a)
        assertTrue(result is StartResult.Rejected)
        assertTrue((result as StartResult.Rejected).message.contains("already"))
        assertEquals(DownloadState.Failed, stateOf(a))
    }

    @Test fun retryAllSkipsDownloadsThatAreNotFailedOrCancelled() = runBlocking<Unit> {
        val a = add(1)
        val b = add(2)
        fail(a)
        repo.refresh()
        val result = repo.retry(listOf(a, b))
        assertEquals(1, result.done)
        assertEquals(1, result.skipped)
    }

    // ---------------------------------------------------------------- duplicates

    @Test fun sameLinkIsRejectedWhileItIsUnfinished() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        add(1)
        val waiting = add(2)
        listOf(
            url(1),
            "HTTPS://EXAMPLE.com/file1.zip",
            "https://example.com:443/file1.zip#section",
            url(2),
        ).forEach {
            assertTrue("$it should be a duplicate", repo.isDuplicate(it))
            assertTrue(repo.start(linkInfo(name = "x.zip", url = it)) is StartResult.Rejected)
        }
        repo.hold(waiting)
        assertTrue("a held download still counts", repo.isDuplicate(url(2)))
        assertEquals(2, dao.rows.value.size)
    }

    @Test fun differentLinksAndQueriesAreNotDuplicates() = runBlocking<Unit> {
        add(1)
        assertFalse(repo.isDuplicate("https://example.com/file1.zip?x=1"))
        assertFalse(repo.isDuplicate("https://example.com/File1.zip"))
        assertFalse(repo.isDuplicate("https://other.example.com/file1.zip"))
    }

    @Test fun sameLinkCanBeDownloadedAgainAfterItFinished() = runBlocking<Unit> {
        val first = add(1)
        finish(first)
        repo.refresh()
        assertFalse(repo.isDuplicate(url(1)))
        val second = add(1)
        assertEquals("file1.zip", row(first).fileName)
        assertEquals("file1 (1).zip", row(second).fileName)
    }

    @Test fun cancelledLinkCanBeAddedAgain() = runBlocking<Unit> {
        val id = add(1)
        repo.cancel(id)
        assertFalse(repo.isDuplicate(url(1)))
    }

    // ---------------------------------------------------------------- failures inside the queue

    @Test fun oneFailedHandOffDoesNotBlockTheRest() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val first = add(1)
        val second = add(2)
        val third = add(3)
        finish(first)
        system.failNextEnqueues = 1
        repo.refresh() // second fails to start, third takes the free slot
        assertEquals(DownloadState.Failed, stateOf(second))
        assertEquals(DownloadState.Queued, stateOf(third))
        assertEquals("a failed hand-off must not use up the slot", 2, system.enqueued.size)
    }

    @Test fun failedHandOffInTheBackgroundIsAnnounced() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val first = add(1)
        val second = add(2)
        finish(first)
        system.failNextEnqueues = 1
        repo.refresh()
        assertEquals(DownloadState.Failed, stateOf(second))
        assertTrue(notifier.sent.any { it.id == second })
    }

    @Test fun waitingDownloadFailsWhenSpaceRanOutBeforeItStarts() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val first = add(1)
        val big = repo.start(linkInfo(name = "big.zip", length = 5_000, url = "https://example.com/big.zip"))
        val bigId = (big as StartResult.Started).id
        storage.free = 1_000
        finish(first)
        repo.refresh()
        assertEquals(DownloadState.Failed, stateOf(bigId))
        assertTrue(row(bigId).errorMessage!!.contains("Not enough free storage"))
    }

    // ---------------------------------------------------------------- restart / orphans

    @Test fun queueSurvivesRestartAndContinues() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val first = add(1)
        val second = add(2)
        // "process death": new repository, same table and system
        val restarted = DownloadRepository(dao, system, storage, notifier, settings) { now++ }
        finish(first)
        restarted.reconcile()
        assertEquals(DownloadState.Completed, stateOf(first))
        assertEquals(DownloadState.Queued, stateOf(second))
    }

    @Test fun waitingAndHeldItemsAreLeftAloneByReconcile() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        add(1)
        val waiting = add(2)
        val held = add(3)
        repo.hold(held)
        repo.reconcile()
        assertEquals(DownloadState.Waiting, stateOf(waiting))
        assertEquals(DownloadState.OnHold, stateOf(held))
    }

    @Test fun orphanedUnfinishedSystemDownloadsAreRemovedButFinishedOnesAreNot() = runBlocking<Unit> {
        val id = add(1)
        system.snapshots[900] = SystemSnapshot(StatusMapper.STATUS_RUNNING, 0, 5, 100, null, null)
        system.snapshots[901] = SystemSnapshot(StatusMapper.STATUS_PAUSED, 0, 5, 100, null, null)
        system.snapshots[902] = SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 100, 100, null, null)
        repo.reconcile()
        assertEquals(setOf(900L, 901L), system.removed.toSet())
        assertNotNull("finished files stay", system.snapshots[902])
        assertNotNull("referenced download stays", system.snapshots[row(id).systemId!!])
    }

    @Test fun crashBetweenHandOffAndSavingIsRecoveredWithoutAStrayDownload() = runBlocking<Unit> {
        // The app died after the system accepted the download (id 700) but before the id was saved.
        system.snapshots[700] = SystemSnapshot(StatusMapper.STATUS_RUNNING, 0, 50, 100, null, null)
        val id = dao.insert(entityOf(url = url(1), name = "file1.zip", state = DownloadState.Queued, systemId = null))
        repo.reconcile()
        assertEquals(DownloadState.Failed, stateOf(id))
        assertTrue("stray system download removed", 700L in system.removed)

        assertTrue(repo.retry(id) is StartResult.Started)
        assertEquals("only one live system download for the link", 1, system.unfinishedIds().size)
    }

    @Test fun reconcileOnEmptyTableDoesNothing() = runBlocking<Unit> {
        assertFalse(repo.reconcile())
        assertTrue(system.removed.isEmpty())
    }

    // ---------------------------------------------------------------- history vs files

    @Test fun clearingHistoryNeverDeletesFiles() = runBlocking<Unit> {
        val ids = (1..2).map { add(it) }
        finish(ids[0])
        fail(ids[1])
        repo.refresh()
        assertEquals(2, repo.removeRecords(ids))
        assertTrue(system.removed.isEmpty())
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun deletingFilesRemovesOnlyCompletedOnesFromTheSystem() = runBlocking<Unit> {
        val done = add(1)
        val running = add(2)
        finish(done)
        run(running)
        repo.refresh()
        val doneSystemId = row(done).systemId!!

        val result = repo.deleteFiles(listOf(done, running))
        assertEquals(1, result.done)
        assertEquals(1, result.skipped)
        assertEquals(listOf(doneSystemId), system.removed)
        assertTrue(dao.rows.value.none { it.id == done })
        assertTrue(dao.rows.value.any { it.id == running })
    }

    @Test fun recordIsKeptWhenAndroidNoLongerListsTheFile() = runBlocking<Unit> {
        val id = add(1)
        finish(id)
        repo.refresh()
        system.snapshots.remove(row(id).systemId!!) // removed behind our back
        val result = repo.deleteFiles(listOf(id))
        assertEquals(0, result.done)
        assertEquals(1, result.skipped)
        assertTrue(result.messages.single().contains("Files app"))
        assertTrue(dao.rows.value.any { it.id == id })
    }

    // ---------------------------------------------------------------- bulk

    @Test fun bulkCancelCancelsEverythingUnfinishedAndOnlyRemovesStartedOnesFromTheSystem() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val ids = (1..3).map { add(it) }
        repo.hold(ids[2])
        assertEquals(3, repo.cancel(ids))
        assertEquals(1, system.removed.size)
        assertTrue(ids.all { stateOf(it) == DownloadState.Cancelled })
    }

    @Test fun bulkHoldAndResume() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val ids = (1..4).map { add(it) }
        assertEquals(3, repo.hold(ids)) // the running one is skipped
        assertEquals(DownloadState.Queued, stateOf(ids[0]))
        assertEquals(3, repo.resume(ids))
        assertTrue(ids.drop(1).all { stateOf(it) == DownloadState.Waiting })
    }
}
