package com.almus.ytdownloader.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.ytdownloader.R
import com.almus.ytdownloader.download.LinkWarning
import com.almus.ytdownloader.download.LinkWarnings
import com.almus.ytdownloader.download.Permissions
import com.almus.ytdownloader.media.Destinations
import com.almus.ytdownloader.media.DownloadPlan
import com.almus.ytdownloader.media.DurationFormat
import com.almus.ytdownloader.media.FormatDetails
import com.almus.ytdownloader.media.MediaMode
import com.almus.ytdownloader.media.MediaSelection
import com.almus.ytdownloader.media.SelectionLogic
import com.almus.ytdownloader.media.SizeEstimator
import com.almus.ytdownloader.media.VariantKind
import com.almus.ytdownloader.ui.state.AddDownloadState
import com.almus.ytdownloader.ui.state.DownloadsViewModel

/**
 * Shows the Advanced Media Selection flow whenever the download flow is in one of its states: asking the source,
 * the choices, checking the chosen file, and the confirmation screen. Everything shown comes from what an authorized
 * source really offers. Nothing is saved before the person taps Download on the confirmation screen.
 */
@Composable
fun MediaFlowHost(viewModel: DownloadsViewModel) {
    val addState by viewModel.addState.collectAsStateWithLifecycle()

    // Asked only at the moment the person approves a download. Declining never blocks it.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> viewModel.confirmPlan() }
    val context = LocalContext.current

    when (val state = addState) {
        is AddDownloadState.Resolving -> BusyDialog(stringResource(R.string.ms_resolving)) { viewModel.dismissAddState() }
        is AddDownloadState.Verifying -> BusyDialog(stringResource(R.string.ms_verifying)) { viewModel.dismissAddState() }
        is AddDownloadState.Select -> MediaSelectionScreen(
            select = state,
            perDownloadFolders = viewModel.perDownloadFolders,
            pathFor = { v, d -> viewModel.previewSavePath(v, d) },
            onReview = { viewModel.reviewSelection(it) },
            onDismiss = { viewModel.dismissAddState() },
        )
        is AddDownloadState.Review -> DownloadReviewScreen(
            plan = state.plan,
            showNotificationHint = Permissions.needsNotificationPermission(context),
            onBack = { viewModel.backToSelection() },
            onConfirm = {
                val missing = Permissions.missing(context)
                if (missing.isEmpty()) viewModel.confirmPlan() else permissionLauncher.launch(missing.toTypedArray())
            },
            onDismiss = { viewModel.dismissAddState() },
        )
        else -> Unit
    }
}

@Composable
private fun BusyDialog(text: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.ms_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaSelectionScreen(
    select: AddDownloadState.Select,
    perDownloadFolders: Boolean,
    pathFor: (com.almus.ytdownloader.media.MediaVariant, com.almus.ytdownloader.media.DestinationChoice) -> String,
    onReview: (MediaSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = select.options
    val catalog = select.catalog
    var selection by remember(select) { mutableStateOf(select.initial ?: SelectionLogic.initial(options)) }
    var detailsOpen by remember(select) { mutableStateOf(false) }
    val current = selection ?: return
    val resolved = SelectionLogic.resolve(options, current)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.systemBarsPadding().fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ms_close)) }
                    Text(text = stringResource(R.string.ms_title), style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = catalog.title ?: stringResource(R.string.ms_untitled),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                InfoPill(catalog.sourceLabel)
                                InfoPill(DurationFormat.of(options.durationSeconds) ?: stringResource(R.string.ms_duration_unknown))
                                InfoPill(stringResource(R.string.ms_option_count, options.video.size + options.audio.size))
                            }
                            Text(text = catalog.basis, style = MaterialTheme.typography.bodySmall, color = muted)
                            catalog.notices.forEach { Text(text = it, style = MaterialTheme.typography.bodySmall, color = muted) }
                        }
                    }

                    if (options.video.isNotEmpty() && options.audio.isNotEmpty()) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = current.mode == MediaMode.Video,
                                onClick = { selection = SelectionLogic.withMode(options, current, MediaMode.Video) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) { Text(stringResource(R.string.ms_mode_video)) }
                            SegmentedButton(
                                selected = current.mode == MediaMode.Audio,
                                onClick = { selection = SelectionLogic.withMode(options, current, MediaMode.Audio) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) { Text(stringResource(R.string.ms_mode_audio)) }
                        }
                    }

                    if (current.mode == MediaMode.Video) {
                        Text(text = stringResource(R.string.ms_quality), style = MaterialTheme.typography.titleSmall)
                        options.video.forEach { choice ->
                            OptionCard(
                                selected = choice.id == current.choiceId,
                                onClick = { selection = SelectionLogic.withChoice(current, choice.id) },
                                title = choice.qualityLabel,
                                summary = FormatDetails.summary(choice.variant, choice.size),
                                note = if (choice.resolutionKnown) null else stringResource(R.string.ms_resolution_unknown),
                            )
                        }
                    } else {
                        Text(text = stringResource(R.string.ms_audio), style = MaterialTheme.typography.titleSmall)
                        options.audio.forEach { choice ->
                            OptionCard(
                                selected = choice.id == current.choiceId,
                                onClick = { selection = SelectionLogic.withChoice(current, choice.id) },
                                title = choice.title,
                                summary = FormatDetails.summary(choice.variant, choice.size),
                                note = choice.subtitle,
                            )
                        }
                    }

                    if (resolved != null) {
                        TextButton(onClick = { detailsOpen = !detailsOpen }) {
                            Text(stringResource(if (detailsOpen) R.string.ms_details_hide else R.string.ms_details_show))
                        }
                        if (detailsOpen) {
                            FormatDetails.rows(resolved.variant, options.durationSeconds, resolved.size).forEach { (k, v) ->
                                DetailRow(k, v)
                            }
                        }
                    }

                    Text(text = stringResource(R.string.ms_save_to), style = MaterialTheme.typography.titleSmall)
                    val kind = resolved?.variant?.kind ?: VariantKind.Video
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Destinations.options(perDownloadFolders).forEach { choice ->
                            FilterChip(
                                selected = current.destination == choice,
                                onClick = { selection = SelectionLogic.withDestination(current, choice) },
                                label = { Text(Destinations.label(choice, kind)) },
                            )
                        }
                    }
                    if (resolved != null) {
                        Text(text = pathFor(resolved.variant, current.destination), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    if (!perDownloadFolders) {
                        Text(text = stringResource(R.string.ms_folder_note), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (resolved != null) {
                            Text(
                                text = resolved.qualityLabel + " · " + resolved.formatLabel + " · " + SizeEstimator.text(resolved.size),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(onClick = { onReview(current) }, enabled = resolved != null, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ms_review))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionCard(selected: Boolean, onClick: () -> Unit, title: String, summary: String, note: String?) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) scheme.primaryContainer else scheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = summary, style = MaterialTheme.typography.bodyMedium)
                if (note != null) Text(text = note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
fun DownloadReviewScreen(
    plan: DownloadPlan,
    showNotificationHint: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val warnings = LinkWarnings.of(plan.info)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.systemBarsPadding().fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ms_back)) }
                    Text(text = stringResource(R.string.ms_review_title), style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = plan.title ?: plan.info.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            HorizontalDivider()
                            DetailRow(stringResource(R.string.ms_row_type), if (plan.kind == VariantKind.Video) "Video" else "Audio")
                            DetailRow(stringResource(R.string.ms_row_quality), plan.qualityLabel)
                            DetailRow(stringResource(R.string.ms_row_format), plan.formatLabel)
                            DetailRow(stringResource(R.string.ms_row_duration), DurationFormat.of(plan.durationSeconds) ?: stringResource(R.string.ms_duration_unknown))
                            DetailRow(stringResource(R.string.ms_row_size), SizeEstimator.text(plan.size))
                            DetailRow(stringResource(R.string.ms_row_file), plan.info.fileName)
                            DetailRow(stringResource(R.string.ms_row_source), plan.sourceLabel)
                            DetailRow(stringResource(R.string.ms_row_saved_in), plan.displayPath)
                        }
                    }
                    Text(text = plan.basis, style = MaterialTheme.typography.bodySmall, color = muted)
                    warnings.forEach { Text(text = "• " + warningText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                    if (showNotificationHint) {
                        Text(text = stringResource(R.string.ms_notification_hint), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Text(text = stringResource(R.string.ms_nothing_saved), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Surface(tonalElevation = 3.dp) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ms_back)) }
                        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ms_download)) }
                    }
                }
            }
        }
    }
}

private fun warningText(warning: LinkWarning): String = when (warning) {
    LinkWarning.Insecure -> "This link is not encrypted (http), so others on the network could see or change what you download."
    LinkWarning.WebPage -> "The server says this is a web page, not a file."
    LinkWarning.UnknownSize -> "The server did not say how big the file is."
    LinkWarning.LargeFile -> "This is a large file. Use Wi-Fi if you can."
    LinkWarning.Apk -> "This is an app installer. Only install apps from sources you trust."
    LinkWarning.MaybeLive -> "The size is unknown, so this could be a live stream that never ends."
    LinkWarning.NoResume -> "The server does not support resuming. If the connection drops, the download starts again from the beginning."
}
