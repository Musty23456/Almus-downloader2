package com.almus.ytdownloader.ui.components

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.almus.ytdownloader.R
import com.almus.ytdownloader.download.BulkAction
import com.almus.ytdownloader.ui.state.DownloadsViewModel
import com.almus.ytdownloader.ui.state.PendingConfirm

/** Card button behaviour shared by Home, Downloads and Files. */
fun downloadActionsFor(viewModel: DownloadsViewModel) = DownloadActions(
    onCancel = { viewModel.cancel(it.id) },
    onRetry = { viewModel.retry(it.id) },
    onRemove = { viewModel.removeFromList(it.id) },
    onOpen = { viewModel.open(it.id) },
    onHold = { viewModel.hold(it.id) },
    onResume = { viewModel.resume(it.id) },
    onDelete = { viewModel.requestDeleteFile(it.id, it.fileName) },
)

@StringRes
fun BulkAction.labelRes(): Int = when (this) {
    BulkAction.CancelAll -> R.string.bulk_cancel_all
    BulkAction.HoldAll -> R.string.bulk_hold_all
    BulkAction.ResumeAll -> R.string.bulk_resume_all
    BulkAction.RetryAll -> R.string.bulk_retry_all
    BulkAction.ClearHistory -> R.string.bulk_clear_history
    BulkAction.DeleteFiles -> R.string.bulk_delete_files
}

@StringRes
private fun BulkAction.titleRes(): Int = when (this) {
    BulkAction.CancelAll -> R.string.bulk_title_cancel_all
    BulkAction.HoldAll -> R.string.bulk_title_hold_all
    BulkAction.ResumeAll -> R.string.bulk_title_resume_all
    BulkAction.RetryAll -> R.string.bulk_title_retry_all
    BulkAction.ClearHistory -> R.string.bulk_title_clear_history
    BulkAction.DeleteFiles -> R.string.bulk_title_delete_files
}

@PluralsRes
private fun BulkAction.messageRes(): Int = when (this) {
    BulkAction.CancelAll -> R.plurals.bulk_msg_cancel_all
    BulkAction.HoldAll -> R.plurals.bulk_msg_hold_all
    BulkAction.ResumeAll -> R.plurals.bulk_msg_resume_all
    BulkAction.RetryAll -> R.plurals.bulk_msg_retry_all
    BulkAction.ClearHistory -> R.plurals.bulk_msg_clear_history
    BulkAction.DeleteFiles -> R.plurals.bulk_msg_delete_files
}

@StringRes
private fun BulkAction.confirmRes(): Int = when (this) {
    BulkAction.CancelAll -> R.string.bulk_confirm_cancel_all
    BulkAction.DeleteFiles -> R.string.bulk_confirm_delete_files
    else -> R.string.bulk_confirm_generic
}

/** Asks before any bulk action or file deletion. Nothing is done until the user confirms. */
@Composable
fun ConfirmActionDialog(
    pending: PendingConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = pending.ids.size
    val action = pending.action
    val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(action.titleRes())) },
        text = {
            Text(text = pluralStringResource(action.messageRes(), count, count))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = tint),
            ) {
                Text(text = stringResource(action.confirmRes()))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.confirm_cancel))
            }
        },
    )
}
