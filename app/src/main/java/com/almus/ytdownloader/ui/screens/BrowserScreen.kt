package com.almus.ytdownloader.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.ytdownloader.R
import com.almus.ytdownloader.browser.BrowserError
import com.almus.ytdownloader.browser.BrowserErrorKind
import com.almus.ytdownloader.browser.BrowserUiState
import com.almus.ytdownloader.browser.BrowserWebView
import com.almus.ytdownloader.browser.HostKey
import com.almus.ytdownloader.browser.PageSecurity
import com.almus.ytdownloader.ui.components.AlmusTextField
import com.almus.ytdownloader.ui.components.ClearBrowsingDataDialog
import com.almus.ytdownloader.ui.components.ConfirmDownloadHost
import com.almus.ytdownloader.ui.components.ContentContainer
import com.almus.ytdownloader.ui.components.NoticeBanner
import com.almus.ytdownloader.ui.components.PrivacyNoticeDialog
import com.almus.ytdownloader.ui.components.ScreenHeader
import com.almus.ytdownloader.ui.state.AddDownloadState
import com.almus.ytdownloader.ui.state.BrowserCommand
import com.almus.ytdownloader.ui.state.BrowserViewModel
import com.almus.ytdownloader.ui.state.DownloadsViewModel
import kotlinx.coroutines.delay

private const val NOTICE_MS = 7_000L

/**
 * The in-app browser: an Android WebView with an address bar, back/forward/reload/stop, a loading
 * bar, error pages, per-site JavaScript, and downloads routed through the app's download manager.
 */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    downloadsViewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addState by downloadsViewModel.addState.collectAsStateWithLifecycle()
    val downloadNotice by downloadsViewModel.notice.collectAsStateWithLifecycle()

    var address by rememberSaveable { mutableStateOf(state.url.orEmpty()) }
    var addressFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // Show the real address of the page, unless the user is typing.
    LaunchedEffect(state.url) {
        if (!addressFocused) address = state.url.orEmpty()
    }

    // A page asked for a download and it passed the checks: start the usual link check + confirmation.
    // A prompt that is already open is never replaced.
    LaunchedEffect(viewModel, downloadsViewModel) {
        viewModel.downloadLinks.collect { url ->
            val current = downloadsViewModel.addState.value
            if (current is AddDownloadState.Idle || current is AddDownloadState.Error) {
                downloadsViewModel.checkLink(url)
            } else {
                viewModel.showNotice("Finish or cancel the current download prompt first.")
            }
        }
    }

    // Messages go away by themselves.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(NOTICE_MS)
            viewModel.showNotice(null)
        }
    }
    LaunchedEffect(addState) {
        if (addState is AddDownloadState.Error) {
            delay(NOTICE_MS)
            downloadsViewModel.dismissAddState()
        }
    }

    BackHandler(enabled = state.canGoBack) { viewModel.goBack() }

    if (state.privacyDialogVisible) {
        PrivacyNoticeDialog(
            // Before the first page: must be accepted. Afterwards the same dialog is read-only.
            requireAcceptance = !state.privacyAccepted,
            onAccept = viewModel::acceptPrivacyNotice,
            onDismiss = viewModel::dismissPrivacyNotice,
        )
    }
    if (confirmClear) {
        ClearBrowsingDataDialog(
            onConfirm = {
                confirmClear = false
                viewModel.requestClearBrowsingData()
            },
            onDismiss = { confirmClear = false },
        )
    }
    ConfirmDownloadHost(viewModel = downloadsViewModel)

    val bannerText = state.notice
        ?: (addState as? AddDownloadState.Error)?.message
        ?: if (addState is AddDownloadState.Checking) stringResource(R.string.browser_checking_download) else downloadNotice

    ContentContainer(modifier = modifier, maxWidth = 1000.dp) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.browser_title),
                subtitle = state.title ?: state.host ?: stringResource(R.string.browser_subtitle),
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.browser_menu),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_menu_external)) },
                                enabled = state.host != null,
                                onClick = {
                                    menuOpen = false
                                    viewModel.openCurrentPageExternally()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.javaScriptOn) R.string.browser_menu_js_off else R.string.browser_menu_js_on,
                                        ),
                                    )
                                },
                                enabled = state.host != null,
                                onClick = {
                                    menuOpen = false
                                    viewModel.setJavaScriptForCurrentSite(!state.javaScriptOn)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_menu_clear)) },
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_menu_privacy)) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.showPrivacyNotice()
                                },
                            )
                        }
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::goBack, enabled = state.canGoBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.browser_back),
                    )
                }
                IconButton(onClick = viewModel::goForward, enabled = state.canGoForward) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.browser_forward),
                    )
                }
                // One slot, two jobs: Stop while a page is loading, Reload otherwise.
                if (state.isLoading) {
                    IconButton(onClick = viewModel::stop) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.browser_stop),
                        )
                    }
                } else {
                    IconButton(onClick = viewModel::reload, enabled = state.url != null) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.browser_reload),
                        )
                    }
                }
                AlmusTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = stringResource(R.string.browser_address_hint),
                    leadingIcon = when (state.security) {
                        PageSecurity.Encrypted -> Icons.Filled.Lock
                        PageSecurity.NotEncrypted -> Icons.Filled.LockOpen
                        PageSecurity.None -> Icons.Filled.Language
                    },
                    supportingText = state.addressError,
                    isError = state.addressError != null,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    onImeAction = { viewModel.navigate(address) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp, end = 8.dp)
                        .onFocusChanged { addressFocused = it.isFocused },
                )
            }

            // Fixed-height slot so the layout does not jump when the indicator appears.
            if (state.isLoading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (state.url != null) SiteStatusLine(state)

            if (bannerText != null) {
                NoticeBanner(
                    text = bannerText,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The engine is rebuilt when it crashed (generation changes).
                key(state.engineGeneration) {
                    BrowserWebViewHost(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                }
                val error = state.error
                if (error != null) {
                    ErrorPanel(
                        error = error,
                        onRetry = viewModel::retryFailedPage,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state.url == null) {
                    StartPanel(
                        onReadPrivacy = viewModel::showPrivacyNotice,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SiteStatusLine(state: BrowserUiState) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.security) {
            PageSecurity.NotEncrypted -> Text(
                text = stringResource(R.string.browser_status_not_secure),
                style = MaterialTheme.typography.labelMedium,
                color = colors.error,
            )
            PageSecurity.Encrypted -> Text(
                text = stringResource(R.string.browser_status_secure),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            PageSecurity.None -> Unit
        }
        if (state.host != null) {
            Text(
                text = stringResource(
                    if (state.javaScriptOn) R.string.browser_status_js_on else R.string.browser_status_js_off,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.javaScriptOn) colors.tertiary else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartPanel(onReadPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.browser_start_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        for (res in listOf(
            R.string.browser_start_message,
            R.string.browser_start_downloads,
            R.string.browser_start_privacy,
        )) {
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        TextButton(onClick = onReadPrivacy) {
            Text(text = stringResource(R.string.browser_read_privacy))
        }
    }
}

@Composable
private fun ErrorPanel(
    error: BrowserError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (error.kind == BrowserErrorKind.Security) Icons.Filled.GppBad else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = error.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        error.failingUrl?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.browser_retry))
        }
    }
}

/**
 * Hosts the WebView. All commands from the view model are applied here. The WebView is destroyed when
 * the screen is left; its back/forward list is saved in the view model and restored when it returns.
 */
@Composable
private fun BrowserWebViewHost(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BrowserWebView.create(ctx, viewModel).also { created ->
                viewModel.savedWebStateFor(created)?.let { created.restoreState(it) }
                webView = created
            }
        },
        onRelease = { released ->
            viewModel.saveWebState(released)
            released.stopLoading()
            (released.parent as? ViewGroup)?.removeView(released)
            released.destroy()
        },
    )

    // Pause timers and media when the app is in the background.
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    DisposableEffect(lifecycleOwner, webView) {
        val wv = webView
        if (lifecycleOwner == null || wv == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> wv.onPause()
                    Lifecycle.Event.ON_RESUME -> wv.onResume()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        viewModel.commands.collect { command -> applyCommand(wv, command, viewModel) }
    }
}

private fun applyCommand(wv: WebView, command: BrowserCommand, viewModel: BrowserViewModel) {
    // The JavaScript decision is always made BEFORE the next document starts to load.
    fun decideJavaScriptFor(url: String?) {
        BrowserWebView.applyJavaScript(wv, viewModel.isJavaScriptAllowed(HostKey.of(url)))
    }
    when (command) {
        is BrowserCommand.Load -> {
            decideJavaScriptFor(command.url)
            wv.loadUrl(command.url)
        }
        BrowserCommand.Back -> if (wv.canGoBack()) {
            decideJavaScriptFor(BrowserWebView.urlAtOffset(wv, -1))
            wv.goBack()
        }
        BrowserCommand.Forward -> if (wv.canGoForward()) {
            decideJavaScriptFor(BrowserWebView.urlAtOffset(wv, 1))
            wv.goForward()
        }
        BrowserCommand.Reload -> {
            decideJavaScriptFor(wv.url)
            wv.reload()
        }
        BrowserCommand.Stop -> wv.stopLoading()
        BrowserCommand.ClearBrowsingData -> {
            BrowserWebView.clearBrowsingData(wv)
            BrowserWebView.applyJavaScript(wv, false)
            wv.loadUrl("about:blank")
        }
        BrowserCommand.ClearHistory -> {
            wv.clearHistory()
            viewModel.historyCleared()
        }
    }
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}
