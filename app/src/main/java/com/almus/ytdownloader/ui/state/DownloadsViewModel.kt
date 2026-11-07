package com.almus.ytdownloader.ui.state

import android.app.Application
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almus.ytdownloader.AlmusApplication
import com.almus.ytdownloader.download.BulkAction
import com.almus.ytdownloader.download.LinkInfo
import com.almus.ytdownloader.download.InspectResult
import com.almus.ytdownloader.media.CatalogResult
import com.almus.ytdownloader.media.DestinationChoice
import com.almus.ytdownloader.media.DirectFileSource
import com.almus.ytdownloader.media.DownloadPlan
import com.almus.ytdownloader.media.MediaCatalog
import com.almus.ytdownloader.media.MediaOptions
import com.almus.ytdownloader.media.MediaOptionsBuilder
import com.almus.ytdownloader.media.MediaSelection
import com.almus.ytdownloader.media.MediaVariant
import com.almus.ytdownloader.media.PlanBuilder
import com.almus.ytdownloader.media.SelectionLogic
import com.almus.ytdownloader.media.Destinations
import com.almus.ytdownloader.download.SaveFolders
import com.almus.ytdownloader.download.SaveLayout
import com.almus.ytdownloader.download.StartResult
import com.almus.ytdownloader.engine.EngineRequest
import com.almus.ytdownloader.engine.EngineState
import com.almus.ytdownloader.engine.EnqueueResult
import com.almus.ytdownloader.model.DownloadItem
import com.almus.ytdownloader.model.DownloadStatus
import com.almus.ytdownloader.model.FileCategory
import com.almus.ytdownloader.model.toDownloadItem
import com.almus.ytdownloader.model.toItem
import com.almus.ytdownloader.platform.IntakeResult
import com.almus.ytdownloader.platform.LinkDetection
import com.almus.ytdownloader.ytdlp.JobState
import com.almus.ytdownloader.ytdlp.YtJobIds
import com.almus.ytdownloader.ytdlp.openSavedFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of the "add a download" flow on the Home screen. */
sealed interface AddDownloadState {
    data object Idle : AddDownloadState

    data object Checking : AddDownloadState

    /** The link is reachable; waiting for the user to approve it. [detection] says which source it came from. */
    data class Confirm(val info: LinkInfo, val detection: LinkDetection? = null) : AddDownloadState

    /** A recognised platform page or a restricted link. It is explained to the user; nothing can be downloaded from it. */
    data class Detected(val detection: LinkDetection) : AddDownloadState

    data class Error(val message: String) : AddDownloadState

    /** Asking an authorized source (Internet Archive, Wikimedia Commons) which formats it really offers. */
    data object Resolving : AddDownloadState

    /**
     * The real choices of a media item: only formats the source offers. [initial] restores the person's picks when they
     * come back from the review screen.
     */
    data class Select(
        val catalog: MediaCatalog,
        val options: MediaOptions,
        val detection: LinkDetection? = null,
        val initial: MediaSelection? = null,
    ) : AddDownloadState

    /** Checking the chosen file (redirects, https, size) before the review screen. */
    data class Verifying(val select: Select) : AddDownloadState

    /** The confirmation screen. Nothing is saved before the person taps Download here. */
    data class Review(val select: Select, val selection: MediaSelection, val plan: DownloadPlan) : AddDownloadState
}

/** An action waiting for the user's confirmation. [singleName] is set when it affects one download. */
data class PendingConfirm(
    val action: BulkAction,
    val ids: List<Long>,
    val singleName: String? = null,
)

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AlmusApplication).container
    private val repository = container.repository
    private val settings = container.settings

    /** The list shown everywhere. Always derived from the database plus live speed data. */
    private val engineHost = container.engineHost
    private val ytJobs = container.ytJobs

    val downloads: StateFlow<List<DownloadItem>> = combine(repository.downloads, repository.speeds, engineHost.items, ytJobs.jobs) { rows, speeds, engine, yt ->
        // yt-dlp downloads (newest first) come first, then the new engine's (negative ids), then the older engine's.
        yt.asReversed().map { it.toDownloadItem() } + engine.map { it.toDownloadItem() } + rows.map { it.toItem(speeds[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _addState = MutableStateFlow<AddDownloadState>(AddDownloadState.Idle)
    val addState: StateFlow<AddDownloadState> = _addState.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _pending = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> = _pending.asStateFlow()

    private val _maxConcurrent = MutableStateFlow(settings.maxConcurrent)
    val maxConcurrent: StateFlow<Int> = _maxConcurrent.asStateFlow()

    private val _saveLayout = MutableStateFlow(container.saveSettings.layout)
    val saveLayout: StateFlow<SaveLayout> = _saveLayout.asStateFlow()

    /** The new download engine needs Android 10+; on older versions the switch is disabled. */
    val engineSupported: Boolean = engineHost.supported

    private val _newEngine = MutableStateFlow(engineSupported && engineHost.settings.useNewEngine)
    val newEngineEnabled: StateFlow<Boolean> = _newEngine.asStateFlow()

    private val _wifiOnly = MutableStateFlow(engineHost.settings.wifiOnly)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private var checkJob: Job? = null
    private var noticeJob: Job? = null

    // ------------------------------------------------------------------ adding

    /**
     * Step 1: detect the platform, follow short links safely, check for duplicates and ask the server.
     * Nothing is downloaded yet. [skipPlatformCheck] is used when the user chose to check a recognised
     * platform page as an ordinary link anyway; it never lifts a restriction.
     */
    fun checkLink(rawUrl: String, skipPlatformCheck: Boolean = false) {
        val current = _addState.value
        if (current is AddDownloadState.Checking || current is AddDownloadState.Resolving || current is AddDownloadState.Verifying) return
        val matched = if (skipPlatformCheck) null else container.mediaSources.match(rawUrl)
        _addState.value = if (matched != null) AddDownloadState.Resolving else AddDownloadState.Checking
        checkJob = viewModelScope.launch {
            if (matched != null) {
                _addState.value = when (val result = matched.source.load(matched.key)) {
                    is CatalogResult.Unavailable -> AddDownloadState.Error(result.message)
                    is CatalogResult.Failed -> AddDownloadState.Error(result.message)
                    is CatalogResult.Found -> selectStateFor(result.catalog, null) ?: AddDownloadState.Error("This item offers no audio or video formats that can be downloaded.")
                }
                return@launch
            }
            _addState.value = when (val result = container.linkIntake.check(rawUrl, skipPlatformCheck)) {
                is IntakeResult.Rejected -> AddDownloadState.Error(result.message)
                is IntakeResult.Detected -> AddDownloadState.Detected(result.detection)
                is IntakeResult.Ready -> {
                    val catalog = DirectFileSource.catalogOf(result.info)
                    val select = catalog?.let { selectStateFor(it, result.detection) }
                    select ?: AddDownloadState.Confirm(result.info, result.detection)
                }
            }
        }
    }

    private fun selectStateFor(catalog: MediaCatalog, detection: LinkDetection?): AddDownloadState.Select? {
        val options = MediaOptionsBuilder.build(catalog, container.audioConverters)
        if (options.isEmpty) return null
        return AddDownloadState.Select(catalog, options, detection)
    }

    /** True when the file can be put into a chosen folder per download (the new download engine). */
    val perDownloadFolders: Boolean get() = engineSupported && _newEngine.value

    /** The folder shown under "Save to" for a destination choice, before anything is checked. */
    fun previewSavePath(variant: MediaVariant, destination: DestinationChoice): String {
        val effective = if (perDownloadFolders) destination else DestinationChoice.Automatic
        val fileName = variant.url.substringBefore('?').substringAfterLast('/')
        val subfolder = Destinations.subfolder(effective, variant.kind, container.saveSettings.layout, fileName, variant.mimeType)
        return SaveFolders.displayPath(container.storage.target(), subfolder)
    }

    /** From the selection screen: check the chosen file, then show the confirmation screen. */
    fun reviewSelection(selection: MediaSelection) {
        val state = _addState.value as? AddDownloadState.Select ?: return
        val resolved = SelectionLogic.resolve(state.options, selection) ?: return
        val effective = if (perDownloadFolders) selection else selection.copy(destination = DestinationChoice.Automatic)
        fun planFor(info: LinkInfo) = PlanBuilder.build(
            catalog = state.catalog,
            resolved = resolved,
            info = info,
            destination = effective.destination,
            layout = container.saveSettings.layout,
            target = container.storage.target(),
        )
        val verified = state.catalog.verifiedInfo?.takeIf { it.url == resolved.variant.url }
        if (verified != null) {
            _addState.value = AddDownloadState.Review(state, effective, planFor(verified))
            return
        }
        _addState.value = AddDownloadState.Verifying(state)
        checkJob = viewModelScope.launch {
            _addState.value = when (val result = container.inspector.inspect(resolved.variant.url)) {
                is InspectResult.Success -> AddDownloadState.Review(state, effective, planFor(result.info))
                is InspectResult.Failure -> AddDownloadState.Error(result.message)
            }
        }
    }

    /** From the confirmation screen: go back to the choices with the picks kept. */
    fun backToSelection() {
        val state = _addState.value as? AddDownloadState.Review ?: return
        _addState.value = state.select.copy(initial = state.selection)
    }

    /** The person tapped Download on the confirmation screen. */
    fun confirmPlan() {
        val state = _addState.value as? AddDownloadState.Review ?: return
        val plan = state.plan
        if (plan.conversion != null) {
            _addState.value = AddDownloadState.Error("Converting audio is not available in this version.")
            return
        }
        _addState.value = AddDownloadState.Idle
        viewModelScope.launch { begin(plan.info, plan.subfolder) }
    }

    /** Text-only recognition of a link (no network). Used for the hint under the address field. */
    fun detectLink(rawUrl: String): LinkDetection = container.linkDetector.detect(rawUrl)

    /** From the platform dialog: check the same link as an ordinary web link. Only offered for non-restricted pages. */
    fun checkDetectedLinkAnyway() {
        val state = _addState.value as? AddDownloadState.Detected ?: return
        _addState.value = AddDownloadState.Idle
        checkLink(state.detection.url ?: state.detection.input, skipPlatformCheck = true)
    }

    /** Step 2: the user approved what the dialog showed. */
    fun confirmDownload() {
        val state = _addState.value as? AddDownloadState.Confirm ?: return
        _addState.value = AddDownloadState.Idle
        val subfolder = SaveFolders.subfolderFor(container.saveSettings.layout, state.info.fileName, state.info.mimeType)
        viewModelScope.launch { begin(state.info, subfolder) }
    }

    /** Starts an approved download. [subfolder] is used by the new engine; the older engine follows Settings. */
    private suspend fun begin(info: LinkInfo, subfolder: String?) {
        if (engineHost.supported && engineHost.settings.useNewEngine) {
            startWithEngine(info, subfolder)
            return
        }
        when (val result = repository.start(info)) {
            is StartResult.Started -> {
                showNotice(
                    if (result.queued) {
                        "Added to the queue. It starts when a download slot is free."
                    } else {
                        "Download started. Track it in the Downloads tab."
                    },
                )
                repository.refresh()
            }
            is StartResult.Rejected -> _addState.value = AddDownloadState.Error(result.message)
        }
    }

    private suspend fun startWithEngine(info: LinkInfo, subfolder: String?) {
        val request = EngineRequest(
            url = info.url,
            fileName = info.fileName,
            mimeType = info.mimeType,
            subfolder = subfolder,
            insecure = info.insecure,
            expectedSize = info.contentLength.takeIf { it > 0 },
        )
        when (val result = engineHost.enqueue(request)) {
            is EnqueueResult.Added -> showNotice("Download started. Track it in the Downloads tab.")
            is EnqueueResult.Duplicate -> _addState.value = AddDownloadState.Error("This link is already in your queue or downloading.")
            is EnqueueResult.Rejected -> _addState.value = AddDownloadState.Error(result.message)
        }
    }

    /** The folder the confirmation dialog tells the user the file will be saved in. */
    fun savePathFor(info: LinkInfo): String {
        val subfolder = SaveFolders.subfolderFor(container.saveSettings.layout, info.fileName, info.mimeType)
        return SaveFolders.displayPath(container.storage.target(), subfolder)
    }

    fun dismissAddState() {
        checkJob?.cancel()
        _addState.value = AddDownloadState.Idle
    }

    // ------------------------------------------------------------------ single-item actions

    // Ids below zero belong to the new engine (see EngineItemMapping.kt).

    private fun ytJobId(itemId: Long): String? = ytJobs.idOf(YtJobIds.numberOf(itemId))

    fun cancel(id: Long) {
        if (YtJobIds.isYt(id)) {
            ytJobId(id)?.let { ytJobs.cancel(it) }
            return
        }
        viewModelScope.launch { if (id < 0) engineHost.cancel(-id) else repository.cancel(id) }
    }

    /** Old engine: only waiting downloads can be held. New engine: a running transfer is really paused. */
    fun hold(id: Long) {
        if (YtJobIds.isYt(id)) return // yt-dlp downloads cannot be paused
        viewModelScope.launch { if (id < 0) engineHost.pause(-id) else repository.hold(id) }
    }

    fun resume(id: Long) {
        if (YtJobIds.isYt(id)) return
        viewModelScope.launch { if (id < 0) engineHost.resume(-id) else repository.resume(id) }
    }

    fun retry(id: Long) {
        if (YtJobIds.isYt(id)) {
            ytJobId(id)?.let { ytJobs.retry(it) }
            showNotice("Download restarted.")
            return
        }
        if (id < 0) {
            viewModelScope.launch {
                engineHost.retry(-id)
                showNotice("Download restarted.")
            }
            return
        }
        viewModelScope.launch {
            when (val result = repository.retry(id)) {
                is StartResult.Started -> {
                    showNotice(if (result.queued) "Added to the end of the queue." else "Download restarted.")
                    repository.refresh()
                }
                is StartResult.Rejected -> showNotice(result.message)
            }
        }
    }

    /** Removes a finished record from the list. The downloaded file stays on the device. */
    fun removeFromList(id: Long) {
        if (YtJobIds.isYt(id)) {
            ytJobId(id)?.let { ytJobs.remove(it) }
            return
        }
        viewModelScope.launch { if (id < 0) engineHost.remove(-id) else repository.removeRecords(id) }
    }

    /** Asks for confirmation before deleting one downloaded file. */
    fun requestDeleteFile(id: Long, fileName: String) {
        _pending.value = PendingConfirm(BulkAction.DeleteFiles, listOf(id), fileName)
    }

    /** Opens a finished file in another app. Only ever called from a tap. Never opens APKs. */
    fun open(id: Long) {
        if (YtJobIds.isYt(id)) {
            openYtFile(id)
            return
        }
        if (id < 0) {
            openEngineFile(-id)
            return
        }
        viewModelScope.launch {
            val entity = repository.get(id) ?: return@launch
            val item = entity.toItem()
            if (item.status != DownloadStatus.Completed) return@launch
            val app = getApplication<Application>()

            if (item.category == FileCategory.Apks) {
                // Installing is a decision for the user, outside this app.
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(intent) }
                showNotice("APK files are never opened or installed by this app. Install it from your Downloads only if you trust the source.")
                return@launch
            }

            val uri = entity.systemId?.let { container.systemDownloader.contentUriFor(it) }
            if (uri == null) {
                showNotice("The file is no longer available.")
                return@launch
            }
            val type = entity.mimeType ?: app.contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                showNotice("No app on this device can open this type of file.")
            } catch (_: SecurityException) {
                showNotice("This file could not be opened.")
            }
        }
    }

    private fun openYtFile(itemId: Long) {
        val job = ytJobs.jobs.value.firstOrNull { it.number == YtJobIds.numberOf(itemId) } ?: return
        if (job.state != JobState.DONE) return
        val uri = job.fileUri
        if (uri == null) {
            showNotice("This file is in the app's own folder (Android 9 or older), so it cannot be opened from here.")
        } else if (!openSavedFile(getApplication<Application>(), uri)) {
            showNotice("No app on this device can open this type of file.")
        }
    }

    private fun openEngineFile(engineId: Long) {
        val record = engineHost.items.value.firstOrNull { it.download.id == engineId }?.download ?: return
        if (record.state != EngineState.Completed) return
        val app = getApplication<Application>()
        if (FileCategory.from(record.fileName, record.mimeType) == FileCategory.Apks) {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            showNotice("APK files are never opened or installed by this app. Install it from your Downloads only if you trust the source.")
            return
        }
        val uri = record.finalUri?.let { Uri.parse(it) }
        if (uri == null) {
            showNotice("The file is no longer available.")
            return
        }
        val type = record.mimeType ?: app.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showNotice("No app on this device can open this type of file.")
        } catch (_: SecurityException) {
            showNotice("This file could not be opened.")
        }
    }

    // ------------------------------------------------------------------ bulk actions

    /** Asks for confirmation. Nothing happens until [confirmPending]. */
    fun requestBulk(action: BulkAction, ids: List<Long>) {
        if (ids.isEmpty()) return
        _pending.value = PendingConfirm(action, ids)
    }

    fun dismissPending() {
        _pending.value = null
    }

    fun confirmPending() {
        val pending = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch { runBulk(pending.action, pending.ids) }
    }

    private suspend fun runBulk(action: BulkAction, allIds: List<Long>) {
        // Ids below zero belong to the new engine; the rest to the older one.
        val engineIds = allIds.filter { it < 0 }.map { -it }
        val ids = allIds.filter { it in 1 until YtJobIds.BASE }
        val ytIds = allIds.filter { YtJobIds.isYt(it) }.mapNotNull { ytJobId(it) }
        when (action) {
            BulkAction.CancelAll -> {
                engineIds.forEach { engineHost.cancel(it) }
                ytIds.forEach { ytJobs.cancel(it) }
                val n = repository.cancel(ids) + engineIds.size + ytIds.size
                showNotice("Cancelled $n download(s).")
            }
            BulkAction.HoldAll -> {
                engineIds.forEach { engineHost.pause(it) }
                val n = repository.hold(ids) + engineIds.size
                showNotice(
                    "Put $n download(s) on hold." +
                        (if (ids.isNotEmpty()) " Downloads of the older engine that are already running cannot be paused." else "") +
                        (if (ytIds.isNotEmpty()) " yt-dlp downloads cannot be paused." else ""),
                )
            }
            BulkAction.ResumeAll -> {
                engineIds.forEach { engineHost.resume(it) }
                val n = repository.resume(ids) + engineIds.size
                showNotice("Put $n download(s) back in the queue.")
            }
            BulkAction.RetryAll -> {
                engineIds.forEach { engineHost.retry(it) }
                ytIds.forEach { ytJobs.retry(it) }
                val r = repository.retry(ids)
                showNotice(
                    "Retrying ${r.done + engineIds.size + ytIds.size} download(s)." +
                        if (r.skipped > 0) " Skipped ${r.skipped}: ${r.messages.firstOrNull() ?: "not eligible"}" else "",
                )
            }
            BulkAction.ClearHistory -> {
                engineIds.forEach { engineHost.remove(it) }
                ytIds.forEach { ytJobs.remove(it) }
                val n = repository.removeRecords(ids) + engineIds.size + ytIds.size
                showNotice("Removed $n item(s) from the list. The files were not deleted.")
            }
            BulkAction.DeleteFiles -> {
                val engineDeleted = engineIds.count { engineHost.deleteFile(it) } + ytIds.count { ytJobs.deleteFile(it) }
                val r = repository.deleteFiles(ids)
                showNotice(
                    "Deleted ${r.done + engineDeleted} file(s)." +
                        if (r.skipped > 0) " ${r.messages.firstOrNull() ?: "${r.skipped} could not be deleted."}" else "",
                )
            }
        }
        repository.refresh()
    }

    // ------------------------------------------------------------------ settings

    fun setMaxConcurrent(value: Int) {
        settings.maxConcurrent = value
        _maxConcurrent.value = settings.maxConcurrent
        viewModelScope.launch {
            repository.onSettingsChanged()
            engineHost.settingsChanged()
        }
    }

    /** Switches new downloads to the new download engine (beta). Downloads that already exist keep their engine. */
    fun setNewEngineEnabled(enabled: Boolean) {
        if (!engineSupported) return
        engineHost.settings.useNewEngine = enabled
        _newEngine.value = enabled
        if (enabled) engineHost.onAppVisible()
    }

    /** Used by the new engine: no transfer on mobile data. */
    fun setWifiOnly(enabled: Boolean) {
        engineHost.settings.wifiOnly = enabled
        _wifiOnly.value = enabled
        viewModelScope.launch { engineHost.settingsChanged() }
    }

    /** Applies to downloads that start from now on. Files that already exist are never moved. */
    fun setSaveLayout(layout: SaveLayout) {
        container.saveSettings.layout = layout
        _saveLayout.value = container.saveSettings.layout
    }

    // ------------------------------------------------------------------ sync

    /**
     * Keeps stored records and the queue in sync with the system while the app is visible.
     * The first pass also restores real status after a restart and removes orphaned system
     * downloads. Cancelled together with the screen's lifecycle, so nothing keeps running.
     */
    suspend fun pollWhileVisible() {
        repository.reconcile()
        while (true) {
            val needsPolling = repository.refresh()
            delay(if (needsPolling) ACTIVE_POLL_MS else IDLE_POLL_MS)
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

    fun clearNotice() {
        noticeJob?.cancel()
        _notice.value = null
    }

    private companion object {
        const val ACTIVE_POLL_MS = 1_000L
        const val IDLE_POLL_MS = 3_000L
        const val NOTICE_MS = 7_000L
    }
}
