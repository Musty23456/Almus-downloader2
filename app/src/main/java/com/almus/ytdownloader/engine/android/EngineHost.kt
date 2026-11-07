package com.almus.ytdownloader.engine.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.almus.ytdownloader.data.AlmusDatabase
import com.almus.ytdownloader.download.QueueSettings
import com.almus.ytdownloader.engine.DownloadEngine
import com.almus.ytdownloader.engine.EngineRequest
import com.almus.ytdownloader.engine.EnqueueResult
import com.almus.ytdownloader.engine.EngineState
import com.almus.ytdownloader.engine.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.almus.ytdownloader.download.UrlKey
import com.almus.ytdownloader.engine.EngineItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the one [DownloadEngine] of the process and everything Android-specific around it.
 * The new engine needs Android 10 (API 29) or newer ([supported]); older devices keep using the
 * DownloadManager-based engine. Nothing here runs until the engine is first used.
 */
class EngineHost(
    context: Context,
    queueSettings: QueueSettings,
    database: AlmusDatabase,
) {
    private val appContext = context.applicationContext

    /** True on Android 10 and newer. */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    val settings = SharedPrefsEngineSettings(appContext, queueSettings)
    val notifications = EngineNotifications(appContext)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val store = RoomEngineStore(database.engineDownloadDao())
    private val _items = MutableStateFlow<List<EngineItem>>(emptyList())

    /** The new engine's downloads. Empty until the engine has been started; reading it never starts the engine. */
    val items: StateFlow<List<EngineItem>> = _items.asStateFlow()
    private val startLock = Mutex()
    private var started = false

    /** Only call when [supported] is true. */
    val engine: DownloadEngine by lazy { createEngine() }

    @SuppressLint("NewApi") // only reached when supported
    private fun createEngine(): DownloadEngine {
        check(supported) { "The new download engine needs Android 10 or newer." }
        return DownloadEngine(
            store = store,
            transport = OkHttpTransport(),
            storage = AndroidFinalStorage(appContext),
            network = AndroidNetworkMonitor(appContext),
            settings = settings,
            scope = scope,
            onFinished = { notifications.notifyFinished(it) },
        )
    }

    /** Loads saved downloads and continues interrupted ones. Safe to call many times. */
    suspend fun ensureStarted() {
        if (!supported) return
        startLock.withLock {
            if (started) return
            notifications.ensureChannel()
            engine.start()
            scope.launch {
                engine.items.collect {
                    _items.value = it
                    notifications.render(it)
                }
            }
            started = true
        }
    }

    suspend fun enqueue(request: EngineRequest): EnqueueResult {
        ensureStarted()
        val result = engine.enqueue(request)
        if (result is EnqueueResult.Added) DownloadService.start(appContext)
        return result
    }

    suspend fun pause(id: Long) {
        ensureStarted()
        engine.pause(id)
    }

    suspend fun resume(id: Long) {
        ensureStarted()
        engine.resume(id)
        DownloadService.start(appContext)
    }

    suspend fun cancel(id: Long) {
        ensureStarted()
        engine.cancel(id)
    }

    suspend fun retry(id: Long) {
        ensureStarted()
        engine.retry(id)
        DownloadService.start(appContext)
    }

    suspend fun remove(id: Long) {
        ensureStarted()
        engine.remove(id)
    }

    suspend fun deleteFile(id: Long): Boolean {
        ensureStarted()
        return engine.deleteFile(id)
    }

    /** Called when the user changes Wi-Fi only or the number of simultaneous downloads. */
    suspend fun settingsChanged() {
        if (!supported) return
        ensureStarted()
        engine.settingsChanged()
    }

    /** True when the same address is already queued, running, paused or waiting in the new engine. */
    fun isDuplicate(url: String): Boolean {
        val key = UrlKey.of(url)
        return _items.value.any { !it.download.state.isTerminal && it.download.urlKey == key }
    }

    /**
     * The app came to the screen: continue unfinished downloads. The engine is only started when the user
     * turned it on, or when it already has saved downloads, so devices that never use it are not affected.
     */
    fun onAppVisible() {
        if (!supported) return
        scope.launch {
            if (!settings.useNewEngine && store.all().isEmpty()) return@launch
            ensureStarted()
            if (engine.items.value.any { it.download.state.isBusy }) DownloadService.start(appContext)
        }
    }

    /** Reads the saved list without starting the engine (used after a restart). */
    suspend fun hasUnfinishedWork(): Boolean =
        store.all().any { it.state.isBusy || it.state == EngineState.WaitingForNetwork }
}
