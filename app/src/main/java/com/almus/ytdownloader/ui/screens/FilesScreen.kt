package com.almus.ytdownloader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.ytdownloader.R
import com.almus.ytdownloader.download.ByteFormat
import com.almus.ytdownloader.files.AccessProblem
import com.almus.ytdownloader.files.FileActionPolicy
import com.almus.ytdownloader.files.FileEntry
import com.almus.ytdownloader.files.FileSource
import com.almus.ytdownloader.files.FileStatus
import com.almus.ytdownloader.files.FolderState
import com.almus.ytdownloader.files.SortField
import com.almus.ytdownloader.model.FileCategory
import com.almus.ytdownloader.ui.components.AlmusCard
import com.almus.ytdownloader.ui.components.AlmusTextField
import com.almus.ytdownloader.ui.components.ContentContainer
import com.almus.ytdownloader.ui.components.EmptyState
import com.almus.ytdownloader.ui.components.ErrorState
import com.almus.ytdownloader.ui.components.IconBadge
import com.almus.ytdownloader.ui.components.LoadingState
import com.almus.ytdownloader.ui.components.NoticeBanner
import com.almus.ytdownloader.ui.components.ScreenHeader
import com.almus.ytdownloader.ui.components.StatusPill
import com.almus.ytdownloader.ui.state.FilesPending
import com.almus.ytdownloader.ui.state.FilesUiState
import com.almus.ytdownloader.ui.state.FilesViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val chooserTitle = stringResource(R.string.files_share_chooser)
    var headerMenuOpen by remember { mutableStateOf(false) }

    // The user picks ONE folder in the system picker; the app gets access to that folder only.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onFolderPicked(uri)
    }

    // "Save a copy to…": the system "save as" screen lets the user pick any place, including SD cards and cloud folders.
    val saveCopyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> viewModel.onSaveCopyPicked(uri) }

    // Files can be moved or deleted outside the app, so check again whenever the screen is shown.
    LifecycleStartEffect(Unit) {
        viewModel.refresh()
        onStopOrDispose { }
    }

    pending?.let { FilesConfirmDialog(it, onConfirm = viewModel::confirmPending, onDismiss = viewModel::dismissPending) }

    ContentContainer(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.files_title),
                subtitle = stringResource(R.string.files_subtitle),
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.files_refresh))
                    }
                    Box {
                        IconButton(onClick = { headerMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.files_more))
                        }
                        DropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_choose_folder)) },
                                onClick = {
                                    headerMenuOpen = false
                                    folderPicker.launch(null)
                                },
                            )
                            if (state.folder !is FolderState.None) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_forget_folder)) },
                                    onClick = {
                                        headerMenuOpen = false
                                        viewModel.forgetFolder()
                                    },
                                )
                            }
                            if (state.view.missingCount > 0) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_clear_missing)) },
                                    onClick = {
                                        headerMenuOpen = false
                                        viewModel.requestClearMissing()
                                    },
                                )
                            }
                        }
                    }
                },
            )
            if (state.loading && state.hasScanned) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                !state.hasScanned && state.error != null ->
                    ErrorState(message = state.error.orEmpty(), onRetry = { viewModel.refresh() })
                !state.hasScanned -> LoadingState()
                else -> FileList(
                    state = state,
                    notice = notice,
                    chooserTitle = chooserTitle,
                    viewModel = viewModel,
                    onPickFolder = { folderPicker.launch(null) },
                    onLaunchSaveCopy = { name -> saveCopyPicker.launch(name) },
                )
            }
        }
    }
}

@Composable
private fun FileList(
    state: FilesUiState,
    notice: String?,
    chooserTitle: String,
    viewModel: FilesViewModel,
    onPickFolder: () -> Unit,
    onLaunchSaveCopy: (String) -> Unit,
) {
    val view = state.view
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        notice?.let { message -> item(key = "notice") { NoticeBanner(text = message) } }
        state.error?.let { message -> item(key = "error") { NoticeBanner(text = message) } }

        item(key = "search") {
            AlmusTextField(
                value = state.params.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.files_search_hint),
                leadingIcon = Icons.Filled.Search,
                trailingIcon = if (state.params.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.files_clear_search))
                        }
                    }
                } else {
                    null
                },
            )
        }

        item(key = "categories") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    FilterChip(
                        selected = state.params.kind == null,
                        onClick = { viewModel.setKind(null) },
                        label = { Text(stringResource(R.string.files_all_chip, view.counts.values.sum())) },
                    )
                }
                items(items = FileCategory.entries, key = { it.name }) { category ->
                    FilterChip(
                        selected = state.params.kind == category.kind,
                        onClick = { viewModel.selectKind(category.kind) },
                        label = {
                            Text(
                                stringResource(R.string.files_category_chip, stringResource(category.labelRes), view.counts[category.kind] ?: 0),
                            )
                        },
                    )
                }
            }
        }

        item(key = "sort") { SortRow(state = state, viewModel = viewModel) }

        item(key = "folder") { FolderCard(folder = state.folder, onPick = onPickFolder, onForget = viewModel::forgetFolder) }

        when {
            view.access == AccessProblem.StorageDenied -> item(key = "denied") {
                AlmusCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = Icons.Filled.Lock,
                        headline = stringResource(R.string.files_denied_title),
                        message = stringResource(R.string.files_denied_message),
                        contentPadding = 8.dp,
                    )
                    TextButton(onClick = onPickFolder, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.files_choose_folder))
                    }
                }
            }
            view.libraryEmpty -> item(key = "empty") {
                AlmusCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = Icons.Filled.FolderOpen,
                        headline = stringResource(R.string.files_empty_title),
                        message = stringResource(R.string.files_empty_message),
                        contentPadding = 16.dp,
                    )
                }
            }
            view.noMatches -> item(key = "nomatches") {
                AlmusCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        headline = stringResource(R.string.files_no_matches_title),
                        message = stringResource(R.string.files_no_matches_message),
                        contentPadding = 16.dp,
                    )
                }
            }
            else -> items(items = view.visible, key = { it.key }) { entry ->
                FileRow(
                    entry = entry,
                    onOpen = { viewModel.open(entry) },
                    onShare = { viewModel.share(entry, chooserTitle) },
                    onSaveCopy = { viewModel.beginSaveCopy(entry)?.let { name -> onLaunchSaveCopy(name) } },
                    onDelete = { viewModel.requestDelete(entry) },
                    onRemove = { viewModel.removeFromList(entry) },
                )
            }
        }
    }
}

@Composable
private fun SortRow(state: FilesUiState, viewModel: FilesViewModel) {
    var open by remember { mutableStateOf(false) }
    val sort = state.params.sort
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { open = true }) {
                Text(stringResource(R.string.files_sort_by, stringResource(sort.field.labelRes())))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SortField.entries.forEach { field ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(field.labelRes()),
                                color = if (field == sort.field) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            open = false
                            viewModel.setSortField(field)
                        },
                    )
                }
            }
        }
        IconButton(onClick = { viewModel.toggleSortDirection() }) {
            Icon(
                imageVector = if (sort.ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = stringResource(
                    if (sort.ascending) R.string.files_sort_ascending else R.string.files_sort_descending,
                ),
            )
        }
    }
}

private fun SortField.labelRes(): Int = when (this) {
    SortField.Name -> R.string.files_sort_name
    SortField.Date -> R.string.files_sort_date
    SortField.Size -> R.string.files_sort_size
    SortField.Type -> R.string.files_sort_type
}

@Composable
private fun FolderCard(folder: FolderState, onPick: () -> Unit, onForget: () -> Unit) {
    AlmusCard(modifier = Modifier.fillMaxWidth()) {
        when (folder) {
            FolderState.None -> {
                Text(stringResource(R.string.files_folder_none_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.files_folder_none_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onPick) { Text(stringResource(R.string.files_choose_folder)) }
            }
            is FolderState.Granted -> {
                Text(stringResource(R.string.files_folder_granted_title, folder.label), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = pluralStringResource(R.plurals.files_folder_count, folder.fileCount, folder.fileCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = onPick) { Text(stringResource(R.string.files_change_folder)) }
                    TextButton(onClick = onForget) { Text(stringResource(R.string.files_forget_folder)) }
                }
            }
            FolderState.Denied, FolderState.Gone, is FolderState.Failed -> {
                val message = when (folder) {
                    FolderState.Denied -> stringResource(R.string.files_folder_denied)
                    FolderState.Gone -> stringResource(R.string.files_folder_gone)
                    is FolderState.Failed -> folder.message
                    else -> ""
                }
                Text(stringResource(R.string.files_folder_problem_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = onPick) { Text(stringResource(R.string.files_choose_again)) }
                    TextButton(onClick = onForget) { Text(stringResource(R.string.files_forget_folder)) }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSaveCopy: () -> Unit,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val category = FileCategory.of(entry.kind)
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val meta = listOfNotNull(
        entry.sizeBytes.takeIf { it >= 0 }?.let { ByteFormat.format(it) },
        entry.modifiedMs.takeIf { it > 0 }?.let { dateFormat.format(Date(it)) },
        entry.mimeType,
    ).joinToString(" · ")

    AlmusCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (FileActionPolicy.canOpen(entry)) onOpen else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = category.icon)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val problem = when (entry.status) {
                    FileStatus.Available -> null
                    FileStatus.Missing -> R.string.files_status_missing
                    FileStatus.NoAccess -> R.string.files_status_no_access
                    FileStatus.Error -> R.string.files_status_error
                }
                if (problem != null || entry.source is FileSource.Folder) {
                    Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (problem != null) {
                            StatusPill(text = stringResource(problem), color = MaterialTheme.colorScheme.error)
                        }
                        if (entry.source is FileSource.Folder) {
                            StatusPill(text = stringResource(R.string.files_status_not_in_history), color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.files_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (FileActionPolicy.canOpen(entry)) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (FileActionPolicy.opensInSystemDownloads(entry)) R.string.action_show_in_downloads else R.string.action_open,
                                    ),
                                )
                            },
                            onClick = { menuOpen = false; onOpen() },
                        )
                    }
                    if (FileActionPolicy.canShare(entry)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share)) },
                            onClick = { menuOpen = false; onShare() },
                        )
                    }
                    if (FileActionPolicy.canSaveCopy(entry)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_save_copy)) },
                            onClick = { menuOpen = false; onSaveCopy() },
                        )
                    }
                    if (FileActionPolicy.canRemoveFromList(entry)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_remove_from_list)) },
                            onClick = { menuOpen = false; onRemove() },
                        )
                    }
                    if (FileActionPolicy.canDelete(entry)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete_file), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesConfirmDialog(pending: FilesPending, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (pending) {
        is FilesPending.DeleteFile -> {
            val fromFolder = pending.entry.source is FileSource.Folder
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.files_delete_title)) },
                text = {
                    Text(
                        stringResource(
                            if (fromFolder) R.string.files_delete_message_folder else R.string.files_delete_message,
                            pending.entry.name,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = onConfirm,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.bulk_confirm_delete_files)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) } },
            )
        }
        is FilesPending.ClearMissing -> {
            val count = pending.downloadIds.size
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.files_clear_missing_title)) },
                text = { Text(pluralStringResource(R.plurals.files_clear_missing_message, count, count)) },
                confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.bulk_confirm_generic)) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) } },
            )
        }
    }
}
