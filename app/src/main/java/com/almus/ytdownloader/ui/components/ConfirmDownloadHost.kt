package com.almus.ytdownloader.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.ytdownloader.download.Permissions
import com.almus.ytdownloader.ui.state.AddDownloadState
import com.almus.ytdownloader.ui.state.DownloadsViewModel

/**
 * Shows the download confirmation dialog whenever the download flow is waiting for the user's approval.
 * Used by the browser, so a link found on a web page goes through exactly the same check and the
 * same "Download?" question as a link pasted on Home. Nothing is saved before the user taps Download.
 */
@Composable
fun ConfirmDownloadHost(viewModel: DownloadsViewModel) {
    val context = LocalContext.current
    val addState by viewModel.addState.collectAsStateWithLifecycle()

    // Asked only at the moment the user approves a download. Declining never blocks it.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> viewModel.confirmDownload() }

    (addState as? AddDownloadState.Detected)?.let { detected ->
        LinkDetectionDialog(
            detection = detected.detection,
            onCheckAnyway = { viewModel.checkDetectedLinkAnyway() },
            onDismiss = { viewModel.dismissAddState() },
            onUseLink = { viewModel.checkLink(it) },
        )
    }

    MediaFlowHost(viewModel)

    val confirm = addState as? AddDownloadState.Confirm ?: return
    ConfirmDownloadDialog(
        info = confirm.info,
        savePath = viewModel.savePathFor(confirm.info),
        sourceLine = confirm.detection?.sourceLine,
        showNotificationHint = Permissions.needsNotificationPermission(context),
        onConfirm = {
            val missing = Permissions.missing(context)
            if (missing.isEmpty()) {
                viewModel.confirmDownload()
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        },
        onDismiss = { viewModel.dismissAddState() },
    )
}
