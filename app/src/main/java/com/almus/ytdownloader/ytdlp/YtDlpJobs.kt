package com.almus.ytdownloader.ytdlp

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The queue: runs up to [MAX_PARALLEL] downloads at once and keeps their live state for the screen. */
class YtDlpJobs(
    private val context: Context,
    private val client: YtDlpClient,
    private val scope: CoroutineScope,
) {
    private val store = YtHistoryStore(context)

    // Finished downloads from earlier runs are loaded, so the history survives a restart.
    private val _jobs = MutableStateFlow(store.load())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val counter = AtomicLong(_jobs.value.maxOfOrNull { it.number } ?: 0L)

    private val slots = Semaphore(MAX_PARALLEL)
    private val running = ConcurrentHashMap<String, Job>()

    init {
        scope.launch(Dispatchers.IO) {
            jobs.map { list -> list.filter { it.isFinished } }
                .distinctUntilChanged()
                .collect { store.save(it) }
        }
    }

    fun enqueue(spec: DownloadSpec): String {
        val id = UUID.randomUUID().toString()
        _jobs.update { it + DownloadJob(id = id, number = counter.incrementAndGet(), spec = spec) }
        val coroutine = scope.launch { slots.withPermit { run(id, spec) } }
        running[id] = coroutine
        coroutine.invokeOnCompletion { running.remove(id) }
        // Keeps the process alive when the app goes to the background.
        YtDlpService.start(context)
        return id
    }

    fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.isFinished) return
        patch(id) { it.copy(state = JobState.CANCELLED) }
        client.cancel(id)
        running[id]?.cancel()
    }

    /** The queue id of the download that the Downloads tab shows under [number]. */
    fun idOf(number: Long): String? = _jobs.value.firstOrNull { it.number == number }?.id

    /** Starts a finished (failed or cancelled) download again with the same choices; the old entry is replaced. */
    fun retry(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (!job.isFinished) return
        _jobs.update { list -> list.filterNot { it.id == id } }
        enqueue(job.spec)
    }

    /** Deletes the saved file and removes the entry. Returns false when the file could not be deleted. */
    fun deleteFile(id: String): Boolean {
        val uri = _jobs.value.firstOrNull { it.id == id }?.fileUri ?: return false
        val deleted = runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
        if (deleted) _jobs.update { list -> list.filterNot { it.id == id } }
        return deleted
    }

    fun cancelAll() {
        _jobs.value.filter { !it.isFinished }.forEach { cancel(it.id) }
    }

    fun remove(id: String) {
        cancel(id)
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filterNot { it.isFinished } }
    }

    private suspend fun run(id: String, spec: DownloadSpec) {
        val dir = File(PublicExporter.workRoot(context), id)
        try {
            patch(id) { if (it.state == JobState.CANCELLED) it else it.copy(state = JobState.RUNNING) }
            val result = client.download(spec, dir, id) { percent, eta ->
                patch(id) { it.copy(percent = percent.coerceIn(0f, 100f), etaSeconds = eta) }
            }
            if (stateOf(id) == JobState.CANCELLED) return
            result.onSuccess { file ->
                patch(id) { it.copy(state = JobState.SAVING, percent = 100f) }
                val uri = withContext(Dispatchers.IO) { PublicExporter.export(context, file) }
                patch(id) {
                    it.copy(state = JobState.DONE, fileName = file.name, fileUri = uri?.toString())
                }
                // uri == null (Android 9 or older): the file stays where yt-dlp wrote it, see the finally block.
            }.onFailure { error ->
                patch(id) { it.copy(state = JobState.FAILED, error = shortError(error)) }
            }
        } finally {
            val keep = stateOf(id) == JobState.DONE && _jobs.value.firstOrNull { it.id == id }?.fileUri == null
            if (!keep) {
                withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
            }
        }
    }

    private fun stateOf(id: String): JobState? = _jobs.value.firstOrNull { it.id == id }?.state

    private fun patch(id: String, change: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    companion object {
        const val MAX_PARALLEL = 2
    }
}
