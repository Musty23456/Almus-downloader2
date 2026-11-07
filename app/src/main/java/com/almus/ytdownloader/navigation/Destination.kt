package com.almus.ytdownloader.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.ui.graphics.vector.ImageVector
import com.almus.ytdownloader.R

/** Routes that are not part of the bottom navigation bar. */
object Routes {
    const val Settings = "settings"
}

/** Top-level destinations shown in the bottom navigation bar. */
enum class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    YouTube("youtube", R.string.nav_youtube, Icons.Filled.SmartDisplay),
    Home("home", R.string.nav_home, Icons.Filled.Home),
    Downloads("downloads", R.string.nav_downloads, Icons.Filled.Download),
    Browser("browser", R.string.nav_browser, Icons.Filled.Language),
    Files("files", R.string.nav_files, Icons.Filled.Folder),
}
