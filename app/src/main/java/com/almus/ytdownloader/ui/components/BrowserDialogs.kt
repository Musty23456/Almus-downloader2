package com.almus.ytdownloader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.almus.ytdownloader.R

/**
 * The browser's privacy notice. When [requireAcceptance] is true (first use) the user must tap
 * "I understand" before any page is loaded; otherwise it is a plain "Close" dialog.
 */
@Composable
fun PrivacyNoticeDialog(
    requireAcceptance: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.browser_privacy_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val paragraphs = listOf(
                    R.string.browser_privacy_p1,
                    R.string.browser_privacy_p2,
                    R.string.browser_privacy_p3,
                    R.string.browser_privacy_p4,
                )
                paragraphs.forEachIndexed { index, res ->
                    if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            if (requireAcceptance) {
                TextButton(onClick = onAccept) { Text(text = stringResource(R.string.browser_privacy_accept)) }
            } else {
                TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.browser_privacy_close)) }
            }
        },
        dismissButton = if (requireAcceptance) {
            { TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.confirm_cancel)) } }
        } else {
            null
        },
    )
}

@Composable
fun ClearBrowsingDataDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.browser_clear_title)) },
        text = { Text(text = stringResource(R.string.browser_clear_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(text = stringResource(R.string.browser_clear_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.confirm_cancel)) }
        },
    )
}
