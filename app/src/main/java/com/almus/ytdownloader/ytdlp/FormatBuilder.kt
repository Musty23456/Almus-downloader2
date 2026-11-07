package com.almus.ytdownloader.ytdlp

/** One yt-dlp command line option; [value] is null for a plain flag. */
data class YtOption(val name: String, val value: String? = null)

/**
 * Turns a [DownloadSpec] into yt-dlp options. Pure Kotlin, so it is unit tested.
 * Same idea as the Termux script, with one fix: the fallbacks keep the height limit, so asking for 360p
 * never silently downloads 1080p.
 */
object FormatBuilder {
    const val AUDIO_SELECTOR = "ba/b"

    fun videoSelector(maxHeight: Int?, container: VideoContainer): String {
        val h = if (maxHeight == null) "" else "[height<=$maxHeight]"
        val (videoExt, audioExt) = when (container) {
            VideoContainer.MP4 -> "mp4" to "m4a"
            VideoContainer.WEBM -> "webm" to "webm"
        }
        return "bv*$h[ext=$videoExt]+ba[ext=$audioExt]/bv*$h+ba/b$h"
    }

    fun options(spec: DownloadSpec, outputDir: String): List<YtOption> {
        val common = listOf(
            YtOption("--no-playlist"),
            YtOption("--no-mtime"),
            YtOption("--trim-filenames", "120"),
            YtOption("-o", "$outputDir/%(title)s [%(id)s].%(ext)s"),
        )
        val specific = when (spec.kind) {
            MediaKind.VIDEO -> buildList {
                add(YtOption("-f", videoSelector(spec.maxHeight, spec.container)))
                add(YtOption("--merge-output-format", spec.container.ext))
                spec.subtitleLangs?.let { langs ->
                    // Manual and automatic captions, only the chosen languages, put inside the video file.
                    add(YtOption("--write-subs"))
                    add(YtOption("--write-auto-subs"))
                    add(YtOption("--sub-langs", langs))
                    add(YtOption("--embed-subs"))
                }
            }
            MediaKind.AUDIO -> when (val ext = spec.audio.ext) {
                null -> listOf(YtOption("-f", AUDIO_SELECTOR))
                "wav" -> listOf(
                    YtOption("-f", AUDIO_SELECTOR),
                    YtOption("-x"),
                    YtOption("--audio-format", "wav"),
                )
                else -> listOf(
                    YtOption("-f", AUDIO_SELECTOR),
                    YtOption("-x"),
                    YtOption("--audio-format", ext),
                    YtOption("--audio-quality", "192K"),
                )
            }
        }
        return common + specific
    }
}
