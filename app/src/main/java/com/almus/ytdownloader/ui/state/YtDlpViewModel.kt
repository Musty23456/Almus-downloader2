package com.almus.ytdownloader.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almus.ytdownloader.AlmusApplication
import com.almus.ytdownloader.R
import com.almus.ytdownloader.ytdlp.AudioFormat
import com.almus.ytdownloader.ytdlp.DownloadJob
import com.almus.ytdownloader.ytdlp.DownloadSpec
import com.almus.ytdownloader.ytdlp.MediaKind
import com.almus.ytdownloader.ytdlp.PlaylistDetails
import com.almus.ytdownloader.ytdlp.SubtitleLangs
import com.almus.ytdownloader.ytdlp.UrlInput
import com.almus.ytdownloader.ytdlp.VideoContainer
import com.almus.ytdownloader.ytdlp.VideoDetails
import com.almus.ytdownloader.ytdlp.shortError
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YtUiState(
    val input: String = "",
    val loading: Boolean = false,
    val details: VideoDetails? = null,
    val playlist: PlaylistDetails? = null,
    /** Several pasted links, downloaded together with one choice of type and quality. */
    val batch: List<String> = emptyList(),
    /** Addresses of the playlist videos that are ticked. */
    val selected: Set<String> = emptySet(),
    val error: String? = null,
    val notice: String? = null,
    val kind: MediaKind = MediaKind.VIDEO,
    /** null = best available. */
    val maxHeight: Int? = null,
    val container: VideoContainer = VideoContainer.MP4,
    val audio: AudioFormat = AudioFormat.MP3,
    val subtitles: Boolean = false,
    val subtitleLangs: String = SubtitleLangs.DEFAULT,
    val updating: Boolean = false,
    val engineMessage: String? = null,
    val cookieMessage: String? = null,
)

class YtDlpViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AlmusApplication).container
    private val client = container.ytClient
    private val manager = container.ytJobs

    val jobs: StateFlow<List<DownloadJob>> = manager.jobs
    private val cookies = container.ytCookies
    val cookiesLoaded: StateFlow<Boolean> = cookies.loaded

    private val _state = MutableStateFlow(YtUiState())
    val state: StateFlow<YtUiState> = _state.asStateFlow()

    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    fun setInput(value: String) = _state.update { it.copy(input = value, error = null) }

    fun setKind(kind: MediaKind) = _state.update { it.copy(kind = kind) }

    fun setMaxHeight(height: Int?) = _state.update { it.copy(maxHeight = height) }

    fun setContainer(value: VideoContainer) = _state.update { it.copy(container = value) }

    fun setAudio(value: AudioFormat) = _state.update { it.copy(audio = value) }

    fun setSubtitles(on: Boolean) = _state.update { it.copy(subtitles = on) }

    fun setSubtitleLangs(value: String) = _state.update { it.copy(subtitleLangs = value) }

    /** The download for one address with the choices currently on screen. */
    private fun specFor(url: String, title: String, thumbnail: String?): DownloadSpec {
        val s = _state.value
        val langs = if (s.subtitles && s.kind == MediaKind.VIDEO) SubtitleLangs.sanitize(s.subtitleLangs) ?: SubtitleLangs.DEFAULT else null
        return DownloadSpec(
            url = url,
            title = title,
            kind = s.kind,
            maxHeight = s.maxHeight,
            container = s.container,
            audio = s.audio,
            thumbnailUrl = thumbnail,
            subtitleLangs = langs,
        )
    }

    fun fetch() {
        val urls = UrlInput.extractAll(_state.value.input)
        if (urls.isEmpty()) {
            _state.update {
                it.copy(error = text(R.string.yt_error_link), details = null, playlist = null, batch = emptyList(), notice = null)
            }
            return
        }
        if (urls.size > 1) {
            // Playlist pages are listed on their own, so they are left out of a batch.
            val singles = urls.filterNot { UrlInput.isPlaylist(it) }
            val skipped = urls.size - singles.size
            _state.update {
                it.copy(
                    loading = false,
                    error = if (singles.isEmpty()) text(R.string.yt_error_link) else null,
                    notice = if (skipped > 0) text(R.string.yt_batch_skipped, skipped) else null,
                    details = null,
                    playlist = null,
                    selected = emptySet(),
                    maxHeight = null,
                    batch = singles,
                )
            }
            return
        }
        val url = urls.first()
        _state.update {
            it.copy(
                loading = true,
                error = null,
                notice = null,
                details = null,
                playlist = null,
                batch = emptyList(),
                selected = emptySet(),
                maxHeight = null,
            )
        }
        viewModelScope.launch {
            if (UrlInput.isPlaylist(url)) {
                client.fetchPlaylist(url)
                    .onSuccess { list ->
                        _state.update { it.copy(loading = false, playlist = list, selected = list.entries.map { e -> e.url }.toSet()) }
                    }
                    .onFailure { e -> _state.update { it.copy(loading = false, error = text(R.string.yt_error_fetch, shortError(e))) } }
            } else {
                client.fetchInfo(url)
                    .onSuccess { details -> _state.update { it.copy(loading = false, details = details) } }
                    .onFailure { e -> _state.update { it.copy(loading = false, error = text(R.string.yt_error_fetch, shortError(e))) } }
            }
        }
    }

    /** Queues every pasted link; titles are unknown until each file is saved, so the address stands in. */
    fun startBatch() {
        val s = _state.value
        if (s.batch.isEmpty()) return
        s.batch.forEach { url ->
            manager.enqueue(
                specFor(url, url, null),
            )
        }
        _state.update { it.copy(notice = text(R.string.yt_added_links, s.batch.size)) }
    }

    fun toggleEntry(url: String) = _state.update {
        it.copy(selected = if (url in it.selected) it.selected - url else it.selected + url)
    }

    fun selectAll() = _state.update { s -> s.copy(selected = s.playlist?.entries?.map { it.url }?.toSet() ?: emptySet()) }

    fun selectNone() = _state.update { it.copy(selected = emptySet()) }

    /** Queues every ticked playlist video, in playlist order, with the chosen quality and type. */
    fun startPlaylist() {
        val s = _state.value
        val playlist = s.playlist ?: return
        val chosen = playlist.entries.filter { it.url in s.selected }
        if (chosen.isEmpty()) return
        chosen.forEach { entry ->
            manager.enqueue(
                specFor(entry.url, entry.title, null),
            )
        }
        _state.update { it.copy(notice = text(R.string.yt_added_many, chosen.size)) }
    }

    fun startDownload() {
        val s = _state.value
        val details = s.details ?: return
        manager.enqueue(
            specFor(details.url, details.title, details.thumbnailUrl),
        )
        _state.update { it.copy(notice = text(R.string.yt_added)) }
    }

    fun cancel(id: String) = manager.cancel(id)

    fun remove(id: String) = manager.remove(id)

    fun clearFinished() = manager.clearFinished()

    /** Reads a cookies.txt the person picked. Only YouTube and Google cookies are kept. */
    fun importCookies(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            cookies.importFrom(uri)
                .onSuccess { kept -> _state.update { it.copy(cookieMessage = text(R.string.yt_cookies_imported, kept)) } }
                .onFailure { e -> _state.update { it.copy(cookieMessage = text(R.string.yt_cookies_failed, shortError(e))) } }
        }
    }

    fun clearCookies() {
        cookies.clear()
        _state.update { it.copy(cookieMessage = text(R.string.yt_cookies_removed)) }
    }

    fun updateEngine() {
        if (_state.value.updating) return
        _state.update { it.copy(updating = true, engineMessage = null) }
        viewModelScope.launch {
            client.updateEngine()
                .onSuccess { result ->
                    val message = if (result == "up_to_date") text(R.string.yt_engine_up_to_date) else text(R.string.yt_engine_updated)
                    _state.update { it.copy(updating = false, engineMessage = message) }
                }
                .onFailure { e ->
                    _state.update { it.copy(updating = false, engineMessage = text(R.string.yt_engine_failed, shortError(e))) }
                }
        }
    }
}
