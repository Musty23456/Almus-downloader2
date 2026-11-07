package com.almus.ytdownloader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.almus.ytdownloader.R
import com.almus.ytdownloader.platform.LinkDetection
import com.almus.ytdownloader.platform.LinkVerdict

/**
 * Explains a link that is recognised but cannot be downloaded: a platform page (video, post, profile ...)
 * or a restricted link. Nothing is saved from here. A page that is not restricted can still be checked
 * as an ordinary web link; a restricted one cannot.
 */
@Composable
fun LinkDetectionDialog(
    detection: LinkDetection,
    onCheckAnyway: () -> Unit,
    onDismiss: () -> Unit,
    onUseLink: (String) -> Unit = {},
) {
    val checkAnywayButton: (@Composable () -> Unit)? = if (detection.verdict == LinkVerdict.PageNotDownloadable) {
        @Composable {
            TextButton(onClick = onCheckAnyway) {
                Text(text = stringResource(R.string.detect_check_anyway))
            }
        }
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = detection.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = detection.message, style = MaterialTheme.typography.bodyMedium)
                detection.details.forEach { line ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                detection.notes.forEach { note ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = note.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.detect_close))
            }
        },
        dismissButton = checkAnywayButton,
    )
}
