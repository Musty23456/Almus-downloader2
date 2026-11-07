package com.almus.ytdownloader.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.almus.ytdownloader.R
import com.almus.ytdownloader.download.Permissions
import com.almus.ytdownloader.ui.components.AlmusCard
import com.almus.ytdownloader.ui.components.AlmusTextField
import com.almus.ytdownloader.ui.components.ContentContainer
import com.almus.ytdownloader.ui.components.RemoteThumbnail
import com.almus.ytdownloader.ui.components.ScreenHeader
import com.almus.ytdownloader.ui.components.SectionHeader
import com.almus.ytdownloader.ui.state.YtDlpViewModel
import com.almus.ytdownloader.ui.state.YtUiState
import com.almus.ytdownloader.ytdlp.AudioFormat
import com.almus.ytdownloader.ytdlp.DownloadJob
import com.almus.ytdownloader.ytdlp.JobState
import com.almus.ytdownloader.ytdlp.MediaKind
import com.almus.ytdownloader.ytdlp.PlaylistDetails
import com.almus.ytdownloader.ytdlp.PlaylistEntry
import com.almus.ytdownloader.ytdlp.VideoContainer
import com.almus.ytdownloader.ytdlp.VideoDetails
import com.almus.ytdownloader.ytdlp.formatDuration
import com.almus.ytdownloader.ytdlp.openSavedFile

/** Qualities offered for a whole playlist (the real list differs per video, so these are limits, not promises). */
private val PLAYLIST_HEIGHTS = listOf(1080, 720, 480, 360)

/** Paste a link (one video or a playlist), pick quality and type, download. Downloads run through yt-dlp. */
@Composable
fun YouTubeScreen(
    modifier: Modifier = Modifier,
    viewModel: YtDlpViewModel = composeViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val cookiesLoaded by viewModel.cookiesLoaded.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Declining the notification permission never blocks a download; it only hides the progress notification.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    val withPermission: (() -> Unit) -> Unit = { action ->
        if (Permissions.needsNotificationPermission(context)) {
            pending = action
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
    val side = Modifier.padding(horizontal = 16.dp)

    ContentContainer(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.yt_title),
                    subtitle = stringResource(R.string.yt_subtitle),
                )
            }
            item {
                AlmusCard(modifier = side) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AlmusTextField(
                            value = state.input,
                            onValueChange = viewModel::setInput,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.yt_link_label),
                            placeholder = stringResource(R.string.yt_link_placeholder),
                            isError = state.error != null,
                            supportingText = state.error,
                            singleLine = false,
                            maxLines = 4,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                            onImeAction = viewModel::fetch,
                            trailingIcon = {
                                TextButton(
                                    onClick = {
                                        clipboard.getText()?.text?.let { viewModel.setInput(it) }
                                    },
                                ) { Text(text = stringResource(R.string.yt_paste)) }
                            },
                        )
                        Button(
                            onClick = viewModel::fetch,
                            enabled = !state.loading && state.input.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(if (state.loading) R.string.yt_checking else R.string.yt_check))
                        }
                    }
                }
            }
            state.details?.let { details ->
                item {
                    OptionsCard(
                        state = state,
                        details = details,
                        viewModel = viewModel,
                        onDownload = { withPermission(viewModel::startDownload) },
                        modifier = side,
                    )
                }
            }
            if (state.batch.isNotEmpty()) {
                item {
                    BatchCard(
                        state = state,
                        viewModel = viewModel,
                        onDownload = { withPermission(viewModel::startBatch) },
                        modifier = side,
                    )
                }
            }
            state.playlist?.let { playlist ->
                item {
                    PlaylistCard(
                        state = state,
                        playlist = playlist,
                        viewModel = viewModel,
                        onDownload = { withPermission(viewModel::startPlaylist) },
                        modifier = side,
                    )
                }
                items(playlist.entries, key = { it.url }) { entry ->
                    EntryRow(
                        entry = entry,
                        checked = entry.url in state.selected,
                        onToggle = { viewModel.toggleEntry(entry.url) },
                        modifier = side,
                    )
                }
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.yt_jobs),
                    action = {
                        if (jobs.any { it.isFinished }) {
                            TextButton(onClick = viewModel::clearFinished) {
                                Text(text = stringResource(R.string.yt_clear_finished))
                            }
                        }
                    },
                )
            }
            if (jobs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.yt_jobs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = side,
                    )
                }
            } else {
                items(jobs, key = { it.id }) { job -> JobCard(job = job, viewModel = viewModel, modifier = side) }
                item {
                    Text(
                        text = stringResource(R.string.yt_keep_open),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = side,
                    )
                }
            }
            item { EngineCard(state = state, cookiesLoaded = cookiesLoaded, viewModel = viewModel, modifier = side) }
        }
    }
}

@Composable
private fun OptionsCard(
    state: YtUiState,
    details: VideoDetails,
    viewModel: YtDlpViewModel,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlmusCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (details.thumbnailUrl != null) {
                RemoteThumbnail(url = details.thumbnailUrl, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            Text(
                text = details.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(details.uploader, details.durationSeconds?.let { formatDuration(it) })
                .joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FormatChips(state = state, heights = details.heights, viewModel = viewModel)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.yt_download))
            }
            state.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BatchCard(
    state: YtUiState,
    viewModel: YtDlpViewModel,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlmusCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = LocalContext.current.resources.getQuantityString(R.plurals.yt_batch_count, state.batch.size, state.batch.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FormatChips(state = state, heights = PLAYLIST_HEIGHTS, viewModel = viewModel)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.yt_download_links, state.batch.size))
            }
            state.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    state: YtUiState,
    playlist: PlaylistDetails,
    viewModel: YtDlpViewModel,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlmusCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = LocalContext.current.resources.getQuantityString(R.plurals.yt_playlist_count, playlist.entries.size, playlist.entries.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::selectAll) { Text(text = stringResource(R.string.yt_select_all)) }
                TextButton(onClick = viewModel::selectNone) { Text(text = stringResource(R.string.yt_select_none)) }
            }
            FormatChips(state = state, heights = PLAYLIST_HEIGHTS, viewModel = viewModel)
            Button(
                onClick = onDownload,
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.yt_download_selected, state.selected.size))
            }
            state.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: PlaylistEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.durationSeconds?.let {
                Text(
                    text = formatDuration(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Video or audio, and the quality and file type that go with it. Shared by single videos and playlists. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatChips(
    state: YtUiState,
    heights: List<Int>,
    viewModel: YtDlpViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.kind == MediaKind.VIDEO,
                onClick = { viewModel.setKind(MediaKind.VIDEO) },
                label = { Text(text = stringResource(R.string.yt_kind_video)) },
            )
            FilterChip(
                selected = state.kind == MediaKind.AUDIO,
                onClick = { viewModel.setKind(MediaKind.AUDIO) },
                label = { Text(text = stringResource(R.string.yt_kind_audio)) },
            )
        }
        if (state.kind == MediaKind.VIDEO) {
            OptionLabel(stringResource(R.string.yt_quality))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.maxHeight == null,
                    onClick = { viewModel.setMaxHeight(null) },
                    label = { Text(text = stringResource(R.string.yt_best)) },
                )
                heights.forEach { height ->
                    FilterChip(
                        selected = state.maxHeight == height,
                        onClick = { viewModel.setMaxHeight(height) },
                        label = { Text(text = "${height}p") },
                    )
                }
            }
            OptionLabel(stringResource(R.string.yt_container))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoContainer.entries.forEach { option ->
                    FilterChip(
                        selected = state.container == option,
                        onClick = { viewModel.setContainer(option) },
                        label = { Text(text = option.ext.uppercase()) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.subtitles,
                    onClick = { viewModel.setSubtitles(!state.subtitles) },
                    label = { Text(text = stringResource(R.string.yt_subtitles)) },
                )
            }
            if (state.subtitles) {
                AlmusTextField(
                    value = state.subtitleLangs,
                    onValueChange = viewModel::setSubtitleLangs,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.yt_subtitles_langs),
                    supportingText = stringResource(R.string.yt_subtitles_note),
                )
            }
        } else {
            OptionLabel(stringResource(R.string.yt_audio_format))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioFormat.entries.forEach { option ->
                    FilterChip(
                        selected = state.audio == option,
                        onClick = { viewModel.setAudio(option) },
                        label = { Text(text = option.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun JobCard(
    job: DownloadJob,
    viewModel: YtDlpViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AlmusCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val status = when (job.state) {
                JobState.QUEUED -> stringResource(R.string.yt_state_queued)
                JobState.RUNNING -> {
                    val eta = if (job.etaSeconds > 0) " · ${formatDuration(job.etaSeconds)}" else ""
                    stringResource(R.string.yt_state_running) + " · ${job.percent.toInt()}%" + eta
                }
                JobState.SAVING -> stringResource(R.string.yt_state_saving)
                JobState.DONE -> stringResource(
                    if (job.fileUri != null) R.string.yt_state_done else R.string.yt_state_done_app_folder,
                )
                JobState.FAILED -> stringResource(R.string.yt_state_failed) + (job.error?.let { ": $it" } ?: "")
                JobState.CANCELLED -> stringResource(R.string.yt_state_cancelled)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (job.spec.thumbnailUrl != null) {
                    RemoteThumbnail(url = job.spec.thumbnailUrl, modifier = Modifier.size(width = 96.dp, height = 54.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = job.fileName ?: job.spec.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (job.state == JobState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (job.state) {
                JobState.RUNNING -> LinearProgressIndicator(
                    progress = { job.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                JobState.SAVING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!job.isFinished) {
                    TextButton(onClick = { viewModel.cancel(job.id) }) {
                        Text(text = stringResource(R.string.yt_cancel))
                    }
                }
                val uri = job.fileUri
                if (job.state == JobState.DONE && uri != null) {
                    TextButton(onClick = { openSavedFile(context, uri) }) {
                        Text(text = stringResource(R.string.yt_open))
                    }
                }
                if (job.isFinished) {
                    TextButton(onClick = { viewModel.remove(job.id) }) {
                        Text(text = stringResource(R.string.yt_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineCard(
    state: YtUiState,
    cookiesLoaded: Boolean,
    viewModel: YtDlpViewModel,
    modifier: Modifier = Modifier,
) {
    AlmusCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.yt_engine),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.yt_engine_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = viewModel::updateEngine, enabled = !state.updating) {
                Text(text = stringResource(if (state.updating) R.string.yt_updating else R.string.yt_update))
            }
            state.engineMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) viewModel.importCookies(uri)
            }
            Text(
                text = stringResource(R.string.yt_cookies_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.yt_cookies_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(if (cookiesLoaded) R.string.yt_cookies_on else R.string.yt_cookies_off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(text = stringResource(R.string.yt_cookies_import))
                }
                if (cookiesLoaded) {
                    TextButton(onClick = viewModel::clearCookies) {
                        Text(text = stringResource(R.string.yt_cookies_remove))
                    }
                }
            }
            state.cookieMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
