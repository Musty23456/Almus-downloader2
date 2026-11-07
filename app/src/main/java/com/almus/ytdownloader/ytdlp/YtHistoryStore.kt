package com.almus.ytdownloader.ytdlp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Keeps the finished yt-dlp downloads (last 100) so the Downloads tab still lists them after a restart. */
class YtHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("yt_history", Context.MODE_PRIVATE)

    fun load(): List<DownloadJob> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching { fromJson(array.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun save(jobs: List<DownloadJob>) {
        val array = JSONArray()
        jobs.takeLast(MAX).forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(job: DownloadJob): JSONObject = JSONObject()
        .put("id", job.id)
        .put("n", job.number)
        .put("url", job.spec.url)
        .put("title", job.spec.title)
        .put("kind", job.spec.kind.name)
        .put("height", job.spec.maxHeight ?: JSONObject.NULL)
        .put("container", job.spec.container.name)
        .put("audio", job.spec.audio.name)
        .put("thumb", job.spec.thumbnailUrl ?: JSONObject.NULL)
        .put("subs", job.spec.subtitleLangs ?: JSONObject.NULL)
        .put("state", job.state.name)
        .put("percent", job.percent.toDouble())
        .put("file", job.fileName ?: JSONObject.NULL)
        .put("uri", job.fileUri ?: JSONObject.NULL)
        .put("error", job.error ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject): DownloadJob {
        fun text(key: String): String? = if (o.isNull(key)) null else o.getString(key)
        return DownloadJob(
            id = o.getString("id"),
            number = o.getLong("n"),
            spec = DownloadSpec(
                url = o.getString("url"),
                title = o.getString("title"),
                kind = enumValueOf<MediaKind>(o.getString("kind")),
                maxHeight = if (o.isNull("height")) null else o.getInt("height"),
                container = enumValueOf<VideoContainer>(o.getString("container")),
                audio = enumValueOf<AudioFormat>(o.getString("audio")),
                thumbnailUrl = text("thumb"),
                subtitleLangs = text("subs"),
            ),
            state = enumValueOf<JobState>(o.getString("state")),
            percent = o.getDouble("percent").toFloat(),
            fileName = text("file"),
            fileUri = text("uri"),
            error = text("error"),
        )
    }

    private companion object {
        const val KEY = "finished"
        const val MAX = 100
    }
}
