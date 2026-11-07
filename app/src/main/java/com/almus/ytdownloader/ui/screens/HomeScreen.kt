package com.almus.ytdownloader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.almus.ytdownloader.R
import com.almus.ytdownloader.download.Permissions
import com.almus.ytdownloader.download.UrlValidation
import com.almus.ytdownloader.download.UrlValidator
import com.almus.ytdownloader.model.DownloadFilter
import com.almus.ytdownloader.model.DownloadStatus
import com.almus.ytdownloader.model.FileCategory
import com.almus.ytdownloader.navigation.Destination
import com.almus.ytdownloader.ui.components.AlmusCard
import com.almus.ytdownloader.ui.components.AlmusTextField
import com.almus.ytdownloader.ui.components.CategoryTile
import com.almus.ytdownloader.ui.components.ConfirmDownloadDialog
import com.almus.ytdownloader.ui.components.MediaFlowHost
import com.almus.ytdownloader.ui.components.LinkDetectionDialog
import com.almus.ytdownloader.ui.components.downloadActionsFor
import com.almus.ytdownloader.ui.components.DownloadCard
import com.almus.ytdownloader.ui.components.ContentContainer
import com.almus.ytdownloader.ui.components.EmptyState
import com.almus.ytdownloader.ui.components.NoticeBanner
import com.almus.ytdownloader.ui.components.ScreenHeader
import com.almus.ytdownloader.ui.components.SectionHeader
import com.almus.ytdownloader.ui.components.SummaryCard
import com.almus.ytdownloader.ui.components.isWideLayout
import com.almus.ytdownloader.ui.state.AddDownloadState
import com.almus.ytdownloader.ui.state.DownloadsViewModel
import com.almus.ytdownloader.ui.state.FilesViewModel
import com.almus.ytdownloader.ui.theme.AlmusSuccess

private data class SummaryData(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val value: Int,
)

@Composable
fun HomeScreen(
    viewModel: DownloadsViewModel,
    filesViewModel: FilesViewModel,
    onNavigate: (Destination) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    val trimmedUrl = url.trim()
    val validation = UrlValidator.validate(trimmedUrl)
    val urlValid = validation is UrlValidation.Valid
    val urlErrorText = (validation as? UrlValidation.Invalid)?.error?.message
    val urlError = trimmedUrl.isNotEmpty() && urlErrorText != null
    val checking = addState is AddDownloadState.Checking || addState is AddDownloadState.Resolving || addState is AddDownloadState.Verifying
    // Text-only recognition while typing: nothing is opened and no network is used.
    val detectionHint = remember(trimmedUrl) {
        if (urlValid) viewModel.detectLink(trimmedUrl).hint else null
    }
    val wide = isWideLayout()

    val startCheck = { if (urlValid && !checking) viewModel.checkLink(trimmedUrl) }

    // Asked only at the moment the user approves a download. Declining never blocks it.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> viewModel.confirmDownload() }

    val actions = downloadActionsFor(viewModel)

    MediaFlowHost(viewModel)

    val confirm = addState as? AddDownloadState.Confirm
    if (confirm != null) {
        ConfirmDownloadDialog(
            info = confirm.info,
            savePath = viewModel.savePathFor(confirm.info),
            sourceLine = confirm.detection?.sourceLine,
            showNotificationHint = Permissions.needsNotificationPermission(context),
            onConfirm = {
                val missing = Permissions.missing(context)
                url = ""
                if (missing.isEmpty()) {
                    viewModel.confirmDownload()
                } else {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            onDismiss = { viewModel.dismissAddState() },
        )
    }

    (addState as? AddDownloadState.Detected)?.let { detected ->
        LinkDetectionDialog(
            detection = detected.detection,
            onCheckAnyway = { viewModel.checkDetectedLinkAnyway() },
            onDismiss = { viewModel.dismissAddState() },
            onUseLink = { viewModel.checkLink(it) },
        )
    }

    val scheme = MaterialTheme.colorScheme
    val summaries = listOf(
        SummaryData(R.string.summary_total, Icons.Filled.CloudDownload, scheme.secondary, downloads.size),
        SummaryData(R.string.summary_active, Icons.Filled.Sync, scheme.primary, downloads.count { it.status.isUnfinished }),
        SummaryData(R.string.summary_completed, Icons.Filled.CheckCircle, AlmusSuccess, downloads.count { DownloadFilter.Completed.matches(it.status) }),
        SummaryData(R.string.summary_failed, Icons.Filled.ErrorOutline, scheme.error, downloads.count { DownloadFilter.Failed.matches(it.status) }),
    )
    val completed = downloads.filter { it.status == DownloadStatus.Completed }
    val recent = completed.take(3)
    val categoryCounts = FileCategory.entries.associateWith { category ->
        completed.count { it.category == category }
    }

    ContentContainer(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.home_title),
                    subtitle = stringResource(R.string.home_subtitle),
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.open_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            item {
                AlmusTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.search_label),
                    leadingIcon = Icons.Filled.Search,
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.search_clear),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    imeAction = ImeAction.Search,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            item {
                AlmusCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.add_download_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AlmusTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            if (addState is AddDownloadState.Error) viewModel.dismissAddState()
                        },
                        label = stringResource(R.string.url_label),
                        placeholder = stringResource(R.string.url_placeholder),
                        leadingIcon = Icons.Filled.Link,
                        isError = urlError,
                        supportingText = if (urlError) urlErrorText else detectionHint,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        onImeAction = { startCheck() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { startCheck() },
                        enabled = urlValid && !checking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        if (checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (checking) R.string.url_checking else R.string.start_download,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    val errorMessage = (addState as? AddDownloadState.Error)?.message
                    AnimatedVisibility(visible = errorMessage != null || notice != null) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            NoticeBanner(text = errorMessage ?: notice.orEmpty())
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.overview_title))
            }
            item {
                SummaryGrid(summaries = summaries, wide = wide)
            }

            item {
                SectionHeader(title = stringResource(R.string.quick_access_title))
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = FileCategory.entries, key = { it.name }) { category ->
                        CategoryTile(
                            category = category,
                            count = categoryCounts[category] ?: 0,
                            modifier = Modifier.width(112.dp),
                            onClick = {
                                // Open the file manager with this category already selected.
                                filesViewModel.setKind(category.kind)
                                onNavigate(Destination.Files)
                            },
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.recent_title),
                    action = {
                        TextButton(onClick = { onNavigate(Destination.Downloads) }) {
                            Text(text = stringResource(R.string.see_all))
                        }
                    },
                )
            }
            if (recent.isEmpty()) {
                item {
                    AlmusCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        EmptyState(
                            icon = Icons.Filled.DownloadDone,
                            headline = stringResource(R.string.recent_empty_title),
                            message = stringResource(R.string.recent_empty_message),
                            contentPadding = 8.dp,
                        )
                    }
                }
            } else {
                items(items = recent, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        actions = actions,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryGrid(
    summaries: List<SummaryData>,
    wide: Boolean,
) {
    val columns = if (wide) 4 else 2
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        summaries.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { summary ->
                    SummaryCard(
                        label = stringResource(summary.labelRes),
                        value = summary.value.toString(),
                        icon = summary.icon,
                        tint = summary.tint,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
