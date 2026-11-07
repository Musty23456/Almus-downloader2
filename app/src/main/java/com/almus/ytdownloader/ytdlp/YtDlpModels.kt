package com.almus.ytdownloader.ytdlp

enum class MediaKind { VIDEO, AUDIO }

enum class VideoContainer(val ext: String) { MP4("mp4"), WEBM("webm") }

/** [ext] is null for [ORIGINAL]: the audio is saved as YouTube serves it, without conversion. */
enum class AudioFormat(val label: String, val ext: String?) {
    MP3("MP3", "mp3"),
    M4A("M4A", "m4a"),
    WAV("WAV", "wav"),
    ORIGINAL("Original", null),
}

/** What the link points to, as reported by yt-dlp. [heights] are the video heights on offer, tallest first. */
data class VideoDetails(
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val heights: List<Int>,
)

/** One video inside a playlist, read without downloading anything. */
data class PlaylistEntry(val url: String, val title: String, val durationSeconds: Long?)

data class PlaylistDetails(val url: String, val title: String, val entries: List<PlaylistEntry>)

/** One download the person asked for. [maxHeight] null means the best quality available. */
data class DownloadSpec(
    val url: String,
    val title: String,
    val kind: MediaKind,
    val maxHeight: Int?,
    val container: VideoContainer,
    val audio: AudioFormat,
    val thumbnailUrl: String? = null,
    /** Comma-separated language codes to embed as subtitles (video only), or null for none. */
    val subtitleLangs: String? = null,
)

enum class JobState { QUEUED, RUNNING, SAVING, DONE, FAILED, CANCELLED }

data class DownloadJob(
    val id: String,
    /** Counts up for every download; the Downloads tab uses it to build a numeric id. */
    val number: Long = 0,
    val spec: DownloadSpec,
    val state: JobState = JobState.QUEUED,
    val percent: Float = 0f,
    val etaSeconds: Long = 0,
    val fileName: String? = null,
    val fileUri: String? = null,
    val error: String? = null,
) {
    val isFinished: Boolean get() = state == JobState.DONE || state == JobState.FAILED || state == JobState.CANCELLED
}

/**
 * The Downloads tab identifies items by a Long: positive for the older engine, negative for the new engine,
 * and from [BASE] upwards for yt-dlp downloads.
 */
object YtJobIds {
    const val BASE = 1L shl 40

    fun isYt(itemId: Long): Boolean = itemId >= BASE

    fun toItemId(number: Long): Long = BASE + number

    fun numberOf(itemId: Long): Long = itemId - BASE
}
