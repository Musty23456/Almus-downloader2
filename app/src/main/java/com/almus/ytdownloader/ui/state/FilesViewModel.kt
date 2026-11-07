package com.almus.ytdownloader.ui.state

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almus.ytdownloader.AlmusApplication
import com.almus.ytdownloader.download.DownloadState
import com.almus.ytdownloader.download.ByteFormat
import com.almus.ytdownloader.files.CopyResult
import com.almus.ytdownloader.files.FileActionPolicy
import com.almus.ytdownloader.files.FileEntry
import com.almus.ytdownloader.files.FileKind
import com.almus.ytdownloader.files.FileLibrary
import com.almus.ytdownloader.files.FileListLogic
import com.almus.ytdownloader.files.FileSource
import com.almus.ytdownloader.files.FilesView
import com.almus.ytdownloader.files.FolderState
import com.almus.ytdownloader.files.SortField
import com.almus.ytdownloader.files.SortSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of the latest scan. [snapshot] stays visible while a refresh is running. */
data class ScanState(
    val snapshot: FileLibrary.Snapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class ViewParams(
    val query: String = "",
    val kind: FileKind? = null,
    val sort: SortSpec = SortSpec(SortField.Date, ascending = false),
)

data class FilesUiState(
    val loading: Boolean,
    val error: String?,
    val hasScanned: Boolean,
    val view: FilesView,
    val params: ViewParams,
    val folder: FolderState,
)

/** A destructive action waiting for the user's confirmation. Nothing is deleted before that. */
sealed interface FilesPending {
    data class DeleteFile(val entry: FileEntry) : FilesPending

    /** Removes records of vanished files from the list. The files themselves are already gone. */
    data class ClearMissing(val downloadIds: List<Long>) : FilesPending
}

class FilesViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AlmusApplication).container
    private val repository = container.repository
    private val library = container.fileLibrary
    private val actions = container.fileActions
    private val folderSettings = container.folderSettings
    private val folderLister = container.folderLister

    private val scan = MutableStateFlow(ScanState())
    private val params = MutableStateFlow(ViewParams())

    val uiState: StateFlow<FilesUiState> = combine(scan, params) { s, p ->
        FilesUiState(
            loading = s.loading,
            error = s.error,
            hasScanned = s.snapshot != null,
            view = FilesView.build(s.snapshot, p.query, p.kind, p.sort),
            params = p,
            folder = s.snapshot?.folder ?: FolderState.None,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FilesUiState(true, null, false, FilesView.Empty, ViewParams(), FolderState.None),
    )

    private val _pending = MutableStateFlow<FilesPending?>(null)
    val pending: StateFlow<FilesPending?> = _pending.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var refreshJob: Job? = null
    private var noticeJob: Job? = null

    /** The file waiting for the user to choose where its copy goes. */
    private var copyEntry: FileEntry? = null

    init {
        // Re-scan when the set of completed downloads changes (not on every progress tick).
        viewModelScope.launch {
            repository.downloads
                .map { rows -> rows.filter { it.state == DownloadState.Completed.name }.map { it.id to it.fileName } }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    // ------------------------------------------------------------------ list

    /** Re-checks every file. Also called when the screen becomes visible, to catch outside changes. */
    fun refresh() {
        refreshJob?.cancel()
        scan.update { it.copy(loading = true, error = null) }
        refreshJob = viewModelScope.launch {
            try {
                val records = repository.completed()
                val tree = folderSettings.treeUri
                val snapshot = withContext(Dispatchers.IO) { library.scan(records, tree) }
                scan.value = ScanState(snapshot = snapshot, loading = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scan.update { it.copy(loading = false, error = e.message ?: "Your files could not be read.") }
            }
        }
    }

    fun setQuery(query: String) = params.update { it.copy(query = query) }

    /** Selecting the active category again shows all categories. */
    fun setKind(kind: FileKind?) = params.update { it.copy(kind = kind) }

    fun selectKind(kind: FileKind) = params.update { it.copy(kind = if (it.kind == kind) null else kind) }

    fun setSortField(field: SortField) = params.update {
        // A new field starts with its natural direction: names ascending, the rest biggest/newest first.
        if (it.sort.field == field) it else it.copy(sort = SortSpec(field, ascending = field == SortField.Name || field == SortField.Type))
    }

    fun toggleSortDirection() = params.update { it.copy(sort = it.sort.copy(ascending = !it.sort.ascending)) }

    // ------------------------------------------------------------------ file actions

    fun open(entry: FileEntry) {
        viewModelScope.launch {
            if (!FileActionPolicy.canOpen(entry) || !stillThere(entry)) return@launch
            if (FileActionPolicy.opensInSystemDownloads(entry)) {
                // Installing is the user's decision, outside this app. Nothing is launched here.
                runCatching { getApplication<Application>().startActivity(actions.systemDownloadsIntent()) }
                showNotice("APK files are never opened or installed by this app. Install it from your Downloads only if you trust the source.")
                return@launch
            }
            val intent = actions.openIntent(entry)
            if (intent == null) {
                showNotice("This file could not be reached.")
                return@launch
            }
            launchIntent(intent, "No app on this device can open this type of file.")
        }
    }

    fun share(entry: FileEntry, chooserTitle: String) {
        viewModelScope.launch {
            if (!FileActionPolicy.canShare(entry) || !stillThere(entry)) return@launch
            val intent = actions.shareIntent(entry, chooserTitle)
            if (intent == null) {
                showNotice("This file could not be reached.")
                return@launch
            }
            launchIntent(intent, "No app is available to share this file.")
        }
    }

    /**
     * Step 1 of "Save a copy to…": remembers which file the user picked. The screen then opens the
     * system "save as" picker, where the user chooses the place and the name.
     * @return the suggested file name, or null when this file cannot be copied.
     */
    fun beginSaveCopy(entry: FileEntry): String? {
        if (!FileActionPolicy.canSaveCopy(entry)) return null
        copyEntry = entry
        return entry.name
    }

    /** Step 2: the user chose a place (or cancelled, [uri] null). The original file is never changed. */
    fun onSaveCopyPicked(uri: Uri?) {
        val entry = copyEntry
        copyEntry = null
        if (uri == null || entry == null) return
        viewModelScope.launch {
            if (!stillThere(entry)) {
                withContext(Dispatchers.IO) { actions.discard(uri) }
                return@launch
            }
            showNotice("Saving a copy of \"${entry.name}\"…")
            val result = withContext(Dispatchers.IO) {
                val outcome = actions.copyTo(entry, uri)
                if (outcome is CopyResult.Failed) actions.discard(uri)
                outcome
            }
            showNotice(
                when (result) {
                    is CopyResult.Done -> "Saved a copy of \"${entry.name}\" (${ByteFormat.format(result.bytes)}). The original is still in your downloads."
                    is CopyResult.Failed -> "The copy was not saved: ${result.message}"
                },
            )
        }
    }

    /** Removes the record from the list. The file is never touched. */
    fun removeFromList(entry: FileEntry) {
        val source = entry.source as? FileSource.Download ?: return
        viewModelScope.launch {
            repository.removeRecords(source.downloadId)
            refresh()
        }
    }

    // ------------------------------------------------------------------ confirmations

    fun requestDelete(entry: FileEntry) {
        if (FileActionPolicy.canDelete(entry)) _pending.value = FilesPending.DeleteFile(entry)
    }

    fun requestClearMissing() {
        val ids = FileListLogic.missingDownloadIds(scan.value.snapshot?.entries.orEmpty())
        if (ids.isNotEmpty()) _pending.value = FilesPending.ClearMissing(ids)
    }

    fun dismissPending() {
        _pending.value = null
    }

    fun confirmPending() {
        val pending = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch {
            when (pending) {
                is FilesPending.DeleteFile -> deleteFile(pending.entry)
                is FilesPending.ClearMissing -> {
                    val n = repository.removeRecords(pending.downloadIds)
                    showNotice("Removed $n missing item(s) from the list.")
                }
            }
            refresh()
        }
    }

    private suspend fun deleteFile(entry: FileEntry) {
        if (!stillThere(entry)) return
        when (val source = entry.source) {
            is FileSource.Download -> {
                val result = repository.deleteFiles(listOf(source.downloadId))
                showNotice(
                    if (result.done > 0) "Deleted \"${entry.name}\"." else result.messages.firstOrNull() ?: "The file could not be deleted.",
                )
            }
            is FileSource.Folder -> {
                val deleted = try {
                    withContext(Dispatchers.IO) { folderLister.delete(source.documentUri) }
                } catch (e: SecurityException) {
                    false
                }
                showNotice(
                    if (deleted) "Deleted \"${entry.name}\"." else "The file could not be deleted. You may not have permission in this folder.",
                )
            }
        }
    }

    // ------------------------------------------------------------------ folder (Storage Access Framework)

    /** The user picked a folder in the system picker: keep access to that one folder only. */
    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        val resolver = getApplication<Application>().contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            try {
                resolver.takePersistableUriPermission(uri, read or write)
            } catch (e: SecurityException) {
                // Some providers only offer read access; deleting there will be unavailable.
                resolver.takePersistableUriPermission(uri, read)
            }
        } catch (e: SecurityException) {
            showNotice("This folder cannot be kept for later access. Choose another folder.")
            return
        }
        val previous = folderSettings.treeUri
        if (previous != null && previous != uri.toString()) releasePermission(previous)
        folderSettings.treeUri = uri.toString()
        refresh()
    }

    /** Stops using the folder and gives the permission back to the system. */
    fun forgetFolder() {
        folderSettings.treeUri?.let { releasePermission(it) }
        folderSettings.treeUri = null
        refresh()
    }

    private fun releasePermission(treeUri: String) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Re-checks the file right before acting on it, so outside deletions are noticed. */
    private suspend fun stillThere(entry: FileEntry): Boolean {
        val ok = withContext(Dispatchers.IO) {
            when (val source = entry.source) {
                is FileSource.Download -> library.isStillThere(entry, repository.get(source.downloadId))
                is FileSource.Folder -> library.isStillThere(entry, null)
            }
        }
        if (!ok) {
            showNotice("This file is no longer available. The list was refreshed.")
            refresh()
        }
        return ok
    }

    private fun launchIntent(intent: Intent, noAppMessage: String) {
        try {
            getApplication<Application>().startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showNotice(noAppMessage)
        } catch (_: SecurityException) {
            showNotice("This file could not be opened.")
        }
    }

    fun showNotice(message: String) {
        _notice.value = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    private companion object {
        const val NOTICE_MS = 7_000L
    }
}
