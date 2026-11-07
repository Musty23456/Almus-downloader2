package com.almus.ytdownloader.download

/**
 * Which user operations are allowed in which state. One table used by the repository
 * (to enforce) and by the UI (to decide which buttons to show), so they cannot disagree.
 * Pure Kotlin, unit tested.
 */
object DownloadPolicy {
    /** Only items that have not started can be held: there is nothing to pause yet. */
    fun canHold(state: DownloadState) = state == DownloadState.Waiting

    fun canResume(state: DownloadState) = state == DownloadState.OnHold

    fun canCancel(state: DownloadState) = !state.isTerminal

    fun canRetry(state: DownloadState) = state == DownloadState.Failed || state == DownloadState.Cancelled

    /** Removing a record from the list is only allowed once it is finished. It never touches the file. */
    fun canRemoveRecord(state: DownloadState) = state.isTerminal

    /** Deleting the file from the device only makes sense for a completed download. */
    fun canDeleteFile(state: DownloadState) = state == DownloadState.Completed
}

/** The filter tabs on the Downloads screen, as a pure mapping from state. */
enum class StateFilter {
    All,
    Active,
    Paused,
    Completed,
    Failed,
    ;

    fun matches(state: DownloadState): Boolean = when (this) {
        All -> true
        Active -> state == DownloadState.Waiting || state == DownloadState.Queued || state == DownloadState.Running
        Paused -> state == DownloadState.Paused || state == DownloadState.OnHold
        Completed -> state == DownloadState.Completed
        Failed -> state == DownloadState.Failed || state == DownloadState.Cancelled
    }

    /** Bulk actions offered on this tab. Deleting files is only offered on the Completed tab. */
    val bulkActions: List<BulkAction>
        get() = when (this) {
            All -> listOf(BulkAction.ResumeAll, BulkAction.RetryAll, BulkAction.CancelAll, BulkAction.ClearHistory)
            Active -> listOf(BulkAction.HoldAll, BulkAction.CancelAll)
            Paused -> listOf(BulkAction.ResumeAll, BulkAction.CancelAll)
            Completed -> listOf(BulkAction.ClearHistory, BulkAction.DeleteFiles)
            Failed -> listOf(BulkAction.RetryAll, BulkAction.ClearHistory)
        }
}

/** Operations that can be applied to many downloads at once (always after a confirmation). */
enum class BulkAction(val destructive: Boolean) {
    CancelAll(true),
    HoldAll(false),
    ResumeAll(false),
    RetryAll(false),
    ClearHistory(false),
    DeleteFiles(true),
    ;

    /** True when this action may be applied to a download in [state]. Others are skipped. */
    fun appliesTo(state: DownloadState): Boolean = when (this) {
        CancelAll -> DownloadPolicy.canCancel(state)
        HoldAll -> DownloadPolicy.canHold(state)
        ResumeAll -> DownloadPolicy.canResume(state)
        RetryAll -> DownloadPolicy.canRetry(state)
        ClearHistory -> DownloadPolicy.canRemoveRecord(state)
        DeleteFiles -> DownloadPolicy.canDeleteFile(state)
    }
}
