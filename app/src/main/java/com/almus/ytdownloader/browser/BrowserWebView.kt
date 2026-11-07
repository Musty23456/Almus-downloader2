package com.almus.ytdownloader.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient

/** What the web engine reports back. Implemented by the browser view model. */
interface BrowserEvents {
    fun onPageStarted(url: String?)

    fun onProgress(percent: Int)

    fun onPageFinished(url: String?, canGoBack: Boolean, canGoForward: Boolean)

    fun onHistoryChanged(url: String?, canGoBack: Boolean, canGoForward: Boolean)

    fun onTitle(title: String?)

    fun onNetworkError(url: String?, code: Int)

    fun onSslError(url: String?, primaryError: Int)

    fun onHttpStatus(status: Int)

    fun onBlocked(message: String)

    fun onOpenExternally(url: String)

    fun onRendererGone()

    fun onDownloadRequested(url: String?, mimeType: String?, contentDisposition: String?)

    fun isJavaScriptAllowed(host: String?): Boolean
}

/**
 * Builds the one WebView the browser uses, with the safest settings that still let pages load.
 *
 * Security decisions (each one is deliberate):
 * - JavaScript is OFF until the user turns it on for the current site ([applyJavaScript]).
 * - NO addJavascriptInterface anywhere: web pages get no bridge into this app.
 * - [WebViewClient.onReceivedSslError] always cancels. There is no code path that calls proceed().
 * - No file:// or content:// access, no mixed content, no geolocation, camera or microphone.
 * - Cookies follow normal first-party session rules. Third-party cookies are refused. The app never
 *   reads cookie values, and the download manager never receives them.
 * - HTTP login prompts and client certificate requests keep WebView's default: declined.
 */
object BrowserWebView {
    /** Applies the per-site JavaScript decision. Page storage (DOM storage) only makes sense with scripts, so it follows. */
    @SuppressLint("SetJavaScriptEnabled")
    fun applyJavaScript(webView: WebView, allowed: Boolean) {
        val settings = webView.settings
        if (settings.javaScriptEnabled != allowed) settings.javaScriptEnabled = allowed
        if (settings.domStorageEnabled != allowed) settings.domStorageEnabled = allowed
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, events: BrowserEvents): WebView {
        val webView = WebView(context)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        WebView.setWebContentsDebuggingEnabled(false)

        with(webView.settings) {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = Client(events)
        webView.webChromeClient = Chrome(events)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            events.onDownloadRequested(url, mimeType, contentDisposition)
        }
        return webView
    }

    /** Removes cookies, page storage, cache and history. Does not touch downloaded files. */
    fun clearBrowsingData(webView: WebView) {
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearFormData()
    }

    /** The address WebView will show after going back or forward, so JavaScript can be decided before it loads. */
    fun urlAtOffset(webView: WebView, offset: Int): String? {
        val list = webView.copyBackForwardList()
        val index = list.currentIndex + offset
        if (index < 0 || index >= list.size) return null
        return list.getItemAtIndex(index)?.url
    }

    private class Client(private val events: BrowserEvents) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (val decision = NavigationPolicy.decide(url, request.hasGesture(), request.isForMainFrame)) {
                NavDecision.LoadInApp -> {
                    // Decide JavaScript for the NEXT document before it starts loading.
                    // Frames inside a page never change the setting of the page itself.
                    if (request.isForMainFrame) {
                        applyJavaScript(view, events.isJavaScriptAllowed(HostKey.of(url)))
                    }
                    false
                }
                is NavDecision.OpenExternally -> {
                    events.onOpenExternally(decision.url)
                    true
                }
                is NavDecision.Block -> {
                    if (request.isForMainFrame) events.onBlocked(decision.message)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            // Safety net for loads that did not pass shouldOverrideUrlLoading (restored state,
            // history jumps). If the setting does not match the site, fix it and load again.
            val wanted = events.isJavaScriptAllowed(HostKey.of(url))
            if (view.settings.javaScriptEnabled != wanted && url != null) {
                view.stopLoading()
                applyJavaScript(view, wanted)
                view.loadUrl(url) // the target address, not reload(): reload would repeat the previous page
                return
            }
            events.onPageStarted(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            events.onPageFinished(url, view.canGoBack(), view.canGoForward())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            events.onHistoryChanged(url, view.canGoBack(), view.canGoForward())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) events.onNetworkError(request.url.toString(), error.errorCode)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) events.onHttpStatus(errorResponse.statusCode)
        }

        /**
         * A certificate problem ALWAYS stops the load. There is deliberately no proceed() call in this
         * app, no "continue anyway" button and no setting that could turn one on.
         */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            events.onSslError(error.url, error.primaryError)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Returning true keeps the app alive; the screen rebuilds the engine.
            events.onRendererGone()
            return true
        }
    }

    private class Chrome(private val events: BrowserEvents) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            events.onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            events.onTitle(title)
        }

        // Pages never get location, camera or microphone.
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        // No extra windows or pop-ups.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
    }
}
