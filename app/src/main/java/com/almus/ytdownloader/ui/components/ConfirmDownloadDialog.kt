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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.almus.ytdownloader.R
import com.almus.ytdownloader.download.ByteFormat
import com.almus.ytdownloader.download.LinkInfo
import com.almus.ytdownloader.download.LinkWarning
import com.almus.ytdownloader.download.LinkWarnings
import com.almus.ytdownloader.download.MediaKind

/**
 * Shows what the link check found and asks the user to approve it.
 * Nothing is downloaded until [onConfirm] is called.
 */
@Composable
fun ConfirmDownloadDialog(
    info: LinkInfo,
    showNotificationHint: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Folder the file will be saved in, e.g. "Download/ALMUS/Video". Not shown when null. */
    savePath: String? = null,
    /** Where the link came from, e.g. "Source: direct link". Not shown when null. */
    sourceLine: String? = null,
) {
    val host = runCatching { java.net.URL(info.finalUrl).host }.getOrDefault("")
    val warnings = LinkWarnings.of(info)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.confirm_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = info.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                DetailLine(stringResource(R.string.confirm_from, host))
                if (sourceLine != null) DetailLine(sourceLine)
                DetailLine(
                    if (info.contentLength >= 0) {
                        stringResource(R.string.confirm_size, ByteFormat.format(info.contentLength))
                    } else {
                        stringResource(R.string.confirm_size_unknown)
                    },
                )
                DetailLine(
                    stringResource(
                        R.string.confirm_type,
                        info.mimeType ?: stringResource(R.string.confirm_type_unknown),
                    ),
                )
                info.media?.let { media ->
                    DetailLine(
                        stringResource(
                            if (media.kind == MediaKind.Video) R.string.confirm_media_video else R.string.confirm_media_audio,
                            media.format,
                        ),
                    )
                }
                if (info.redirectCount > 0) {
                    DetailLine(stringResource(R.string.confirm_redirects, info.redirectCount))
                }
                if (savePath != null) {
                    DetailLine(stringResource(R.string.confirm_save_to, savePath))
                }
                if (info.media != null) {
                    DetailLine(stringResource(R.string.confirm_as_is))
                    if (info.acceptsRanges == true) DetailLine(stringResource(R.string.confirm_can_resume))
                }
                warnings.forEach { warning ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(warning.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (showNotificationHint) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.confirm_notification_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.confirm_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.confirm_cancel))
            }
        },
    )
}

@Composable
private fun DetailLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun LinkWarning.messageRes(): Int = when (this) {
    LinkWarning.Insecure -> R.string.warn_insecure
    LinkWarning.WebPage -> R.string.warn_web_page
    LinkWarning.UnknownSize -> R.string.warn_unknown_size
    LinkWarning.LargeFile -> R.string.warn_large_file
    LinkWarning.Apk -> R.string.warn_apk
    LinkWarning.MaybeLive -> R.string.warn_maybe_live
    LinkWarning.NoResume -> R.string.warn_no_resume
}
