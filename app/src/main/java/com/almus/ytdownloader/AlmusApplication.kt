package com.almus.ytdownloader

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.almus.ytdownloader.data.AlmusDatabase
import com.almus.ytdownloader.data.Migrations
import com.almus.ytdownloader.download.AndroidDownloadNotifier
import com.almus.ytdownloader.download.AndroidStorageInfo
import com.almus.ytdownloader.download.AndroidSystemDownloader
import com.almus.ytdownloader.download.DownloadRepository
import com.almus.ytdownloader.download.LinkInspector
import com.almus.ytdownloader.download.SharedPrefsQueueSettings
import com.almus.ytdownloader.download.SharedPrefsSaveSettings
import com.almus.ytdownloader.files.AndroidFileActions
import com.almus.ytdownloader.media.AudioConverter
import com.almus.ytdownloader.media.MediaSources
import com.almus.ytdownloader.media.android.OkHttpMediaHttp
import com.almus.ytdownloader.media.sources.CommonsSource
import com.almus.ytdownloader.media.sources.InternetArchiveSource
import com.almus.ytdownloader.files.AndroidFileProbe
import com.almus.ytdownloader.files.DocumentsFolderLister
import com.almus.ytdownloader.files.FileLibrary
import com.almus.ytdownloader.engine.android.EngineHost
import com.almus.ytdownloader.files.SharedPrefsFolderSettings
import com.almus.ytdownloader.platform.LinkIntake
import com.almus.ytdownloader.platform.SafeRedirectResolver
import com.almus.ytdownloader.platform.SmartLinkDetector
import com.almus.ytdownloader.ytdlp.CookieStore
import com.almus.ytdownloader.ytdlp.YtDlpClient
import com.almus.ytdownloader.ytdlp.YtDlpJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Simple manual dependency container shared by the UI and the broadcast receiver. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notifier = AndroidDownloadNotifier(appContext)
    val systemDownloader = AndroidSystemDownloader(appContext)
    val inspector = LinkInspector()
    val settings = SharedPrefsQueueSettings(appContext)
    val saveSettings = SharedPrefsSaveSettings(appContext)
    val storage = AndroidStorageInfo(appContext)
    val folderSettings = SharedPrefsFolderSettings(appContext)
    val folderLister = DocumentsFolderLister(appContext)
    val fileActions = AndroidFileActions(appContext, systemDownloader)
    val fileLibrary = FileLibrary(AndroidFileProbe(appContext, systemDownloader), folderLister)

    private val database: AlmusDatabase =
        Room.databaseBuilder(appContext, AlmusDatabase::class.java, "almus.db")
            .addMigrations(Migrations.V1_TO_V2)
            .build()

    val repository = DownloadRepository(
        dao = database.downloadDao(),
        system = systemDownloader,
        storage = storage,
        notifier = notifier,
        settings = settings,
        saveSettings = saveSettings,
    )

    /** The new download engine (Android 10+). Nothing starts until it is used; see [EngineHost.supported]. */
    val engineHost = EngineHost(appContext, settings, database)

    /** Link detection: recognises the platform, follows short links safely, then asks the server. Saves nothing. */
    val linkDetector = SmartLinkDetector()
    private val redirectResolver = SafeRedirectResolver()
    val linkIntake = LinkIntake(
        detector = linkDetector,
        resolve = { redirectResolver.resolve(it) },
        inspect = { inspector.inspect(it) },
        isDuplicate = { repository.isDuplicate(it) || engineHost.isDuplicate(it) },
    )

    /**
     * Authorized sources that publish several real versions of a file (qualities and formats) for download: the
     * Internet Archive and Wikimedia Commons. Their own public metadata services are the only place formats come
     * from; nothing is guessed. Addresses of these sources go through the media selection screen.
     */
    val mediaSources = MediaSources(
        listOf(
            InternetArchiveSource(OkHttpMediaHttp()),
            CommonsSource(OkHttpMediaHttp()),
        ),
    )

    /**
     * On-device audio converters. None ships: Android has no MP3 encoder and none could be added, so no conversion
     * choice is ever shown. An implementation added here makes the conversion choices appear for files whose
     * source allows processing.
     */
    val audioConverters: List<AudioConverter> = emptyList()

    /** yt-dlp engine (youtubedl-android) and its download queue. */
    val ytCookies = CookieStore(appContext)
    val ytClient = YtDlpClient(appContext, ytCookies)
    val ytJobs = YtDlpJobs(appContext, ytClient, applicationScope)
}

class AlmusApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.notifier.ensureChannel()
        // Unpack yt-dlp, Python and ffmpeg in the background so the first download does not wait for it.
        container.applicationScope.launch { container.ytClient.ensureReady() }
    }
}
