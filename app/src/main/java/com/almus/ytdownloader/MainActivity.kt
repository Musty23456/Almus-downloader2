package com.almus.ytdownloader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.almus.ytdownloader.ui.AlmusApp
import com.almus.ytdownloader.ui.state.DownloadsViewModel
import com.almus.ytdownloader.ui.theme.AlmusTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val downloadsViewModel: DownloadsViewModel by viewModels()
    private var openDownloadsRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleIntent(intent)

        // While the app is visible: sync records with the system downloader (this also
        // restores real status after a restart) and keep progress live.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadsViewModel.pollWhileVisible()
            }
        }

        setContent {
            AlmusTheme {
                AlmusApp(
                    viewModel = downloadsViewModel,
                    openDownloadsRequest = openDownloadsRequest,
                    onOpenDownloadsHandled = { openDownloadsRequest = false },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // New download engine: load saved downloads and continue unfinished ones (does nothing before Android 10).
        (application as AlmusApplication).container.engineHost.onAppVisible()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
            openDownloadsRequest = true
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "com.almus.ytdownloader.OPEN_DOWNLOADS"
    }
}
