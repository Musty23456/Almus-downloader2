package com.almus.ytdownloader.ytdlp

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The only class that touches the youtubedl-android library (yt-dlp + Python + ffmpeg bundled).
 * Keeping it in one place means a library change is a one-file fix.
 */
class YtDlpClient(private val context: Context, private val cookies: CookieStore) {
    private val initLock = Mutex()

    @Volatile
    private var initialized = false

    /** Unpacks yt-dlp, Python and ffmpeg on first use (a few seconds, once per install). Safe to call often. */
    suspend fun ensureReady(): Result<Unit> = initLock.withLock {
        if (initialized) {
            Result.success(Unit)
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    YoutubeDL.getInstance().init(context)
                    FFmpeg.getInstance().init(context)
                    Unit
                }
            }.onSuccess { initialized = true }
        }
    }

    /** Reads the title, creator, length and the video heights on offer. Downloads nothing. */
    suspend fun fetchInfo(url: String): Result<VideoDetails> {
        ensureReady().onFailure { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                addCookies(request)
                val info = YoutubeDL.getInstance().getInfo(request)
                val heights = (info.formats ?: emptyList())
                    .filter { f ->
                        val codec = f.vcodec
                        codec != null && codec != "none" && f.height > 0
                    }
                    .map { it.height }
                    .distinct()
                    .sortedDescending()
                val seconds = info.duration.toLong()
                VideoDetails(
                    url = url,
                    title = info.title ?: url,
                    uploader = info.uploader,
                    durationSeconds = if (seconds > 0) seconds else null,
                    thumbnailUrl = info.thumbnail,
                    heights = heights,
                )
            }
        }
    }

    /** Lists the videos of a playlist without opening each one (fast). Downloads nothing. */
    suspend fun fetchPlaylist(url: String): Result<PlaylistDetails> {
        ensureReady().onFailure { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = YoutubeDLRequest(url)
                request.addOption("--flat-playlist")
                addCookies(request)
                request.addOption("--print", PlaylistParser.PRINT_TEMPLATE)
                val response = YoutubeDL.getInstance().execute(request)
                PlaylistParser.parse(url, response.out) ?: error("No videos found in this playlist")
            }
        }
    }

    /**
     * Downloads into [outputDir] and returns the finished file. [onProgress] gets percent (0..100) and the
     * remaining seconds. [processId] is what [cancel] uses to stop it.
     */
    suspend fun download(
        spec: DownloadSpec,
        outputDir: File,
        processId: String,
        onProgress: (Float, Long) -> Unit,
    ): Result<File> {
        ensureReady().onFailure { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                outputDir.mkdirs()
                val request = YoutubeDLRequest(spec.url)
                addCookies(request)
                FormatBuilder.options(spec, outputDir.absolutePath).forEach { option ->
                    val value = option.value
                    if (value == null) request.addOption(option.name) else request.addOption(option.name, value)
                }
                YoutubeDL.getInstance().execute(
                    request,
                    processId,
                    callback = { progress, eta, _ -> onProgress(progress, eta) },
                )
                findResult(outputDir) ?: error("yt-dlp finished but no file was produced")
            }
        }
    }

    /** Adds the optional sign-in cookies. The path is never logged. */
    private fun addCookies(request: YoutubeDLRequest) {
        cookies.path()?.let { request.addOption("--cookies", it) }
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    /** Updates the bundled yt-dlp (YouTube changes often, so an old yt-dlp stops working). */
    suspend fun updateEngine(): Result<String> {
        ensureReady().onFailure { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                if (status.toString().contains("ALREADY", ignoreCase = true)) "up_to_date" else "updated"
            }
        }
    }

    /** The finished file is the biggest one that is not a partial download or a leftover piece of a merge. */
    private fun findResult(dir: File): File? {
        val piece = Regex("\\.f\\d+\\.")
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !piece.containsMatchIn(it.name) }
            ?.maxByOrNull { it.length() }
    }
}
