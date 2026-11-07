package com.almus.ytdownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state/operation table that both the repository and the UI rely on. */
class DownloadPolicyTest {
    private fun allowed(check: (DownloadState) -> Boolean) = DownloadState.entries.filter(check).toSet()

    @Test fun holdIsOnlyForItemsStillWaitingInTheQueue() {
        assertEquals(setOf(DownloadState.Waiting), allowed(DownloadPolicy::canHold))
    }

    @Test fun resumeIsOnlyForHeldItems() {
        assertEquals(setOf(DownloadState.OnHold), allowed(DownloadPolicy::canResume))
    }

    @Test fun runningTransfersCannotBeHeldBecauseTheSystemCannotPauseThem() {
        listOf(DownloadState.Queued, DownloadState.Running, DownloadState.Paused).forEach {
            assertFalse("$it", DownloadPolicy.canHold(it))
        }
    }

    @Test fun cancelIsForEverythingUnfinished() {
        assertEquals(DownloadState.unfinished.toSet(), allowed(DownloadPolicy::canCancel))
    }

    @Test fun retryIsOnlyForFailedOrCancelled() {
        assertEquals(setOf(DownloadState.Failed, DownloadState.Cancelled), allowed(DownloadPolicy::canRetry))
    }

    @Test fun recordsCanOnlyBeRemovedOnceFinished() {
        assertEquals(
            setOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled),
            allowed(DownloadPolicy::canRemoveRecord),
        )
    }

    @Test fun fileDeletionIsOnlyForCompletedDownloads() {
        assertEquals(setOf(DownloadState.Completed), allowed(DownloadPolicy::canDeleteFile))
    }

    @Test fun finishedStatesAllowNoActionThatCouldChangeTheTransfer() {
        listOf(DownloadState.Completed, DownloadState.Failed, DownloadState.Cancelled).forEach {
            assertFalse(DownloadPolicy.canHold(it))
            assertFalse(DownloadPolicy.canResume(it))
            assertFalse(DownloadPolicy.canCancel(it))
        }
    }

    @Test fun unfinishedAndTerminalStatesArePerfectComplements() {
        DownloadState.entries.forEach { assertEquals(!it.isTerminal, it in DownloadState.unfinished) }
    }

    @Test fun onlySystemOwnedStatesHoldASlot() {
        assertEquals(
            setOf(DownloadState.Queued, DownloadState.Running, DownloadState.Paused),
            DownloadState.entries.filter { it.holdsSlot }.toSet(),
        )
    }

    @Test fun pollingCoversEverythingExceptUserHolds() {
        assertEquals(DownloadState.unfinished.toSet() - DownloadState.OnHold, DownloadState.polled.toSet())
    }

    // ---- filters ----

    @Test fun everyStateAppearsInExactlyOneSpecificTab() {
        val tabs = listOf(StateFilter.Active, StateFilter.Paused, StateFilter.Completed, StateFilter.Failed)
        DownloadState.entries.forEach { state ->
            assertEquals("$state", 1, tabs.count { it.matches(state) })
        }
    }

    @Test fun allTabShowsEverything() {
        DownloadState.entries.forEach { assertTrue(StateFilter.All.matches(it)) }
    }

    @Test fun tabsMatchWhatTheUserExpects() {
        assertTrue(StateFilter.Active.matches(DownloadState.Waiting))
        assertTrue(StateFilter.Active.matches(DownloadState.Queued))
        assertTrue(StateFilter.Active.matches(DownloadState.Running))
        assertTrue(StateFilter.Paused.matches(DownloadState.Paused))
        assertTrue(StateFilter.Paused.matches(DownloadState.OnHold))
        assertTrue(StateFilter.Failed.matches(DownloadState.Cancelled))
        assertTrue(StateFilter.Completed.matches(DownloadState.Completed))
    }

    // ---- bulk actions ----

    @Test fun bulkActionsOnlyApplyToStatesThePolicyAllows() {
        DownloadState.entries.forEach { state ->
            assertEquals(DownloadPolicy.canCancel(state), BulkAction.CancelAll.appliesTo(state))
            assertEquals(DownloadPolicy.canHold(state), BulkAction.HoldAll.appliesTo(state))
            assertEquals(DownloadPolicy.canResume(state), BulkAction.ResumeAll.appliesTo(state))
            assertEquals(DownloadPolicy.canRetry(state), BulkAction.RetryAll.appliesTo(state))
            assertEquals(DownloadPolicy.canRemoveRecord(state), BulkAction.ClearHistory.appliesTo(state))
            assertEquals(DownloadPolicy.canDeleteFile(state), BulkAction.DeleteFiles.appliesTo(state))
        }
    }

    @Test fun clearingHistoryCanNeverTouchAnUnfinishedDownload() {
        DownloadState.unfinished.forEach { assertFalse(BulkAction.ClearHistory.appliesTo(it)) }
    }

    @Test fun deletingFilesIsOnlyOfferedOnTheCompletedTab() {
        StateFilter.entries.forEach { filter ->
            assertEquals(
                "$filter",
                filter == StateFilter.Completed,
                BulkAction.DeleteFiles in filter.bulkActions,
            )
        }
    }

    @Test fun destructiveActionsAreMarkedSoTheUiCanWarn() {
        assertEquals(setOf(BulkAction.CancelAll, BulkAction.DeleteFiles), BulkAction.entries.filter { it.destructive }.toSet())
    }

    @Test fun everyTabOffersAtLeastOneBulkActionThatCanApplyToItsOwnStates() {
        StateFilter.entries.forEach { filter ->
            val states = DownloadState.entries.filter { filter.matches(it) }
            filter.bulkActions.forEach { action ->
                assertTrue("$filter offers $action but no state in the tab qualifies", states.any { action.appliesTo(it) })
            }
        }
    }
}
