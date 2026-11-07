package com.almus.ytdownloader.ui.state

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almus.ytdownloader.browser.AddressResolver
import com.almus.ytdownloader.browser.AddressResult
import com.almus.ytdownloader.browser.BrowserEvents
import com.almus.ytdownloader.browser.BrowserReducer
import com.almus.ytdownloader.browser.BrowserUiState
import com.almus.ytdownloader.browser.BrowserWebView
import com.almus.ytdownloader.browser.DownloadPromptThrottle
import com.almus.ytdownloader.browser.DownloadRoute
import com.almus.ytdownloader.browser.DownloadRouting
import com.almus.ytdownloader.browser.ExternalOpen
import com.almus.ytdownloader.browser.HostKey
import com.almus.ytdownloader.browser.JavaScriptPolicy
import com.almus.ytdownloader.browser.SharedPrefsBrowserStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Something the screen must do to the WebView. The view model never holds the WebView itself. */
sealed interface BrowserCommand {
    data class Load(val url: String) : BrowserCommand

    data object Back : BrowserCommand

    data object Forward : BrowserCommand

    data object Reload : BrowserCommand

    data object Stop : BrowserCommand

    /** Remove cookies, storage, cache and history, then show the start page. */
    data object ClearBrowsingData : BrowserCommand

    /** Second half of clearing: the blank page is shown, so the history can be emptied now. */
    data object ClearHistory : BrowserCommand
}

/**
 * State and decisions of the in-app browser. Lives as long as the activity, so the page survives
 * switching tabs and rotating the phone: the screen saves the WebView state here when it leaves
 * the screen and restores it when it comes back.
 */
class BrowserViewModel(application: Application) : AndroidViewModel(application), BrowserEvents {
    private val store = SharedPrefsBrowserStore(application)
    private val jsPolicy = JavaScriptPolicy(store)
    private val throttle = DownloadPromptThrottle(clock = { SystemClock.elapsedRealtime() })

    private val _state = MutableStateFlow(BrowserUiState(privacyAccepted = store.privacyNoticeAccepted))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<BrowserCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<BrowserCommand> = _commands.asSharedFlow()

    /** Download links found by pages that passed the checks. The screen hands them to the download flow. */
    private val _downloadLinks = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val downloadLinks: SharedFlow<String> = _downloadLinks.asSharedFlow()

    private var savedWebState: Bundle? = null
    private var skipNextSave = false
    private var pendingAddress: String? = null

    /** Set while "clear browsing data" is loading the blank page, so history can be emptied afterwards. */
    var clearHistoryPending = false
        private set

    // ------------------------------------------------------------------ screen -> view model

    /** The user submitted the address bar. */
    fun navigate(input: String) {
        when (val result = AddressResolver.resolve(input)) {
            is AddressResult.Invalid -> update { BrowserReducer.addressError(it, result.message) }
            is AddressResult.Navigate -> {
                if (!_state.value.privacyAccepted) {
                    // Nothing is loaded before the user has seen the privacy notice.
                    pendingAddress = result.url
                    update { BrowserReducer.privacyDialog(BrowserReducer.addressError(it, null), true) }
                } else {
                    update { BrowserReducer.addressError(it, null) }
                    _commands.tryEmit(BrowserCommand.Load(result.url))
                }
            }
        }
    }

    fun acceptPrivacyNotice() {
        store.privacyNoticeAccepted = true
        update { BrowserReducer.privacyAccepted(it) }
        pendingAddress?.let { _commands.tryEmit(BrowserCommand.Load(it)) }
        pendingAddress = null
    }

    fun dismissPrivacyNotice() {
        pendingAddress = null
        update { BrowserReducer.privacyDialog(it, false) }
    }

    fun showPrivacyNotice() {
        update { BrowserReducer.privacyDialog(it, true) }
    }

    fun goBack() {
        _commands.tryEmit(BrowserCommand.Back)
    }

    fun goForward() {
        _commands.tryEmit(BrowserCommand.Forward)
    }

    fun reload() {
        _commands.tryEmit(BrowserCommand.Reload)
    }

    fun stop() {
        update { BrowserReducer.stopped(it) }
        _commands.tryEmit(BrowserCommand.Stop)
    }

    /** Retry after an error: reload the address that failed. */
    fun retryFailedPage() {
        val url = _state.value.error?.failingUrl ?: _state.value.url
        if (url != null) _commands.tryEmit(BrowserCommand.Load(url)) else reload()
    }

    /** Opens the current page in the system browser. Only called from a menu tap. */
    fun openCurrentPageExternally() {
        val url = _state.value.url
        if (url == null || !ExternalOpen.open(getApplication<Application>(), url)) {
            showNotice("No browser app could open this page.")
        }
    }

    /** Turns JavaScript on or off for the host of the current page, then reloads it. */
    fun setJavaScriptForCurrentSite(enabled: Boolean) {
        val host = _state.value.host ?: return
        if (!jsPolicy.setAllowed(host, enabled)) {
            showNotice("JavaScript permissions are full. Clear browsing data to reset them.")
            return
        }
        update { it }
        reload()
    }

    fun requestClearBrowsingData() {
        clearHistoryPending = true
        jsPolicy.clearAll()
        savedWebState = null
        update { BrowserReducer.reset(it) }
        _commands.tryEmit(BrowserCommand.ClearBrowsingData)
    }

    /** The screen calls this after it emptied the history of the blank page. */
    fun historyCleared() {
        clearHistoryPending = false
        update { BrowserReducer.pageFinished(it, null, canGoBack = false, canGoForward = false) }
    }

    fun showNotice(message: String?) {
        update { BrowserReducer.notice(it, message) }
    }

    // ------------------------------------------------------------------ web state survives tab switches

    fun saveWebState(webView: WebView) {
        if (skipNextSave) {
            skipNextSave = false
            return
        }
        if (clearHistoryPending) {
            // The screen was left in the middle of clearing: keep nothing.
            clearHistoryPending = false
            savedWebState = null
            return
        }
        val bundle = Bundle()
        webView.saveState(bundle)
        savedWebState = bundle
    }

    /** The state saved by the last screen, or null. Applies the JavaScript decision for it first. */
    fun savedWebStateFor(webView: WebView): Bundle? {
        val bundle = savedWebState ?: return null
        BrowserWebView.applyJavaScript(
            webView,
            isJavaScriptAllowed(HostKey.of(_state.value.url)),
        )
        return bundle
    }

    // ------------------------------------------------------------------ BrowserEvents (web engine -> state)

    override fun onPageStarted(url: String?) = update { BrowserReducer.pageStarted(it, url) }

    override fun onProgress(percent: Int) = update { BrowserReducer.progress(it, percent) }

    override fun onPageFinished(url: String?, canGoBack: Boolean, canGoForward: Boolean) {
        update { BrowserReducer.pageFinished(it, url, canGoBack, canGoForward) }
        if (clearHistoryPending && HostKey.isBlank(url)) _commands.tryEmit(BrowserCommand.ClearHistory)
    }

    override fun onHistoryChanged(url: String?, canGoBack: Boolean, canGoForward: Boolean) =
        update { BrowserReducer.historyChanged(it, url, canGoBack, canGoForward) }

    override fun onTitle(title: String?) = update { BrowserReducer.title(it, title) }

    override fun onNetworkError(url: String?, code: Int) = update { BrowserReducer.networkError(it, url, code) }

    override fun onSslError(url: String?, primaryError: Int) = update { BrowserReducer.sslError(it, url, primaryError) }

    override fun onHttpStatus(status: Int) = update { BrowserReducer.httpStatus(it, status) }

    override fun onBlocked(message: String) = showNotice(message)

    override fun onOpenExternally(url: String) {
        if (!ExternalOpen.open(getApplication<Application>(), url)) showNotice("No app on this phone can open that link.")
    }

    override fun onRendererGone() {
        skipNextSave = true
        savedWebState = null
        update { BrowserReducer.rendererGone(it) }
    }

    override fun isJavaScriptAllowed(host: String?): Boolean = jsPolicy.isAllowed(host)

    /**
     * The page produced a download. It is checked (type, size) and shown in the usual confirmation
     * dialog by the download flow, so nothing is saved until the user taps Download.
     */
    override fun onDownloadRequested(url: String?, mimeType: String?, contentDisposition: String?) {
        when (val route = DownloadRouting.route(url, mimeType, contentDisposition)) {
            is DownloadRoute.Unsupported -> showNotice(route.message)
            is DownloadRoute.ToManager -> {
                if (!throttle.tryAcquire()) {
                    showNotice("This page is starting downloads too quickly. Only the first was offered.")
                } else {
                    viewModelScope.launch { _downloadLinks.emit(route.url) }
                }
            }
        }
    }

    private fun update(transform: (BrowserUiState) -> BrowserUiState) {
        val next = transform(_state.value)
        _state.value = next.copy(javaScriptOn = jsPolicy.isAllowed(HostKey.of(next.url)))
    }
}
