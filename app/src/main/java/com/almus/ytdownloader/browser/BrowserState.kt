package com.almus.ytdownloader.browser

/** Everything the browser screen shows. Immutable; changed only through [BrowserReducer]. */
data class BrowserUiState(
    /** Address of the page shown, or null when the start page is shown. */
    val url: String? = null,
    val title: String? = null,
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: BrowserError? = null,
    /** Short message above the page (blocked link, HTTP status, download problem). */
    val notice: String? = null,
    /** Problem with what the user typed in the address bar. */
    val addressError: String? = null,
    /** JavaScript is on for the host of [url]. */
    val javaScriptOn: Boolean = false,
    val privacyDialogVisible: Boolean = false,
    /** The user has read the privacy notice (first use). */
    val privacyAccepted: Boolean = false,
    /** Changes whenever the web engine has to be rebuilt (after a crash). */
    val engineGeneration: Int = 0,
) {
    val host: String? get() = HostKey.of(url)
    val security: PageSecurity get() = PageSecurity.of(url)
}

enum class PageSecurity {
    /** https:// */
    Encrypted,

    /** http:// */
    NotEncrypted,

    /** Start page or no web address. */
    None,
    ;

    companion object {
        fun of(url: String?): PageSecurity {
            val text = url?.trim()?.lowercase() ?: return None
            return when {
                text.startsWith("https://") -> Encrypted
                text.startsWith("http://") -> NotEncrypted
                else -> None
            }
        }
    }
}

/** Pure state transitions. Each function maps the old state to the new one, so they are easy to unit test. */
object BrowserReducer {
    /** The blank page is the "start page": it has no address. */
    private fun clean(url: String?): String? = if (HostKey.isBlank(url)) null else url

    fun pageStarted(s: BrowserUiState, url: String?): BrowserUiState = s.copy(
        url = clean(url),
        title = null,
        isLoading = !HostKey.isBlank(url),
        progress = 0,
        error = null,
        notice = null,
        addressError = null,
    )

    fun progress(s: BrowserUiState, percent: Int): BrowserUiState {
        val p = percent.coerceIn(0, 100)
        return s.copy(progress = p, isLoading = if (p >= 100) false else s.isLoading)
    }

    /** Called when the page finished or when history changed, so the buttons always match the engine. */
    fun pageFinished(s: BrowserUiState, url: String?, canGoBack: Boolean, canGoForward: Boolean): BrowserUiState =
        s.copy(
            url = clean(url),
            isLoading = false,
            progress = 100,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
        )

    fun historyChanged(s: BrowserUiState, url: String?, canGoBack: Boolean, canGoForward: Boolean): BrowserUiState =
        s.copy(url = clean(url), canGoBack = canGoBack, canGoForward = canGoForward)

    fun title(s: BrowserUiState, title: String?): BrowserUiState =
        s.copy(title = title?.takeIf { it.isNotBlank() && !HostKey.isBlank(it) })

    /** The user pressed Stop, or the engine stopped on its own. */
    fun stopped(s: BrowserUiState): BrowserUiState = s.copy(isLoading = false)

    /** A load error for the main page. A more specific security error already shown is kept. */
    fun networkError(s: BrowserUiState, failingUrl: String?, code: Int): BrowserUiState {
        val existing = s.error
        if (existing != null && existing.kind == BrowserErrorKind.Security && existing.failingUrl == failingUrl) {
            return s.copy(isLoading = false)
        }
        return s.copy(isLoading = false, error = BrowserErrors.network(code, failingUrl))
    }

    fun sslError(s: BrowserUiState, failingUrl: String?, primaryError: Int): BrowserUiState =
        s.copy(isLoading = false, error = BrowserErrors.ssl(primaryError, failingUrl))

    fun rendererGone(s: BrowserUiState): BrowserUiState = s.copy(
        isLoading = false,
        canGoBack = false,
        canGoForward = false,
        error = BrowserErrors.rendererGone(s.url),
        engineGeneration = s.engineGeneration + 1,
    )

    /** A status of 400 or more on the main page. The page is still shown; the user is told why it may look empty. */
    fun httpStatus(s: BrowserUiState, status: Int): BrowserUiState {
        val message = BrowserErrors.httpStatus(status) ?: return s
        return s.copy(notice = message)
    }

    fun notice(s: BrowserUiState, message: String?): BrowserUiState = s.copy(notice = message)

    fun addressError(s: BrowserUiState, message: String?): BrowserUiState = s.copy(addressError = message)

    fun privacyDialog(s: BrowserUiState, visible: Boolean): BrowserUiState = s.copy(privacyDialogVisible = visible)

    /** Back to the start page (after clearing browsing data). */
    fun reset(s: BrowserUiState): BrowserUiState =
        BrowserUiState(engineGeneration = s.engineGeneration, privacyAccepted = s.privacyAccepted)

    fun privacyAccepted(s: BrowserUiState): BrowserUiState = s.copy(privacyAccepted = true, privacyDialogVisible = false)
}
