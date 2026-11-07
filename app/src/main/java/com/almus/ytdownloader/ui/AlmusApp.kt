package com.almus.ytdownloader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.almus.ytdownloader.navigation.Destination
import com.almus.ytdownloader.navigation.Routes
import com.almus.ytdownloader.ui.components.ConfirmActionDialog
import com.almus.ytdownloader.ui.screens.BrowserScreen
import com.almus.ytdownloader.ui.screens.DownloadsScreen
import com.almus.ytdownloader.ui.screens.FilesScreen
import com.almus.ytdownloader.ui.screens.HomeScreen
import com.almus.ytdownloader.ui.screens.SettingsScreen
import com.almus.ytdownloader.ui.screens.YouTubeScreen
import com.almus.ytdownloader.ui.state.BrowserViewModel
import com.almus.ytdownloader.ui.state.DownloadsViewModel
import com.almus.ytdownloader.ui.state.FilesViewModel

private const val ENTER_MS = 220
private const val EXIT_MS = 160

@Composable
fun AlmusApp(
    viewModel: DownloadsViewModel,
    openDownloadsRequest: Boolean = false,
    onOpenDownloadsHandled: () -> Unit = {},
) {
    val filesViewModel: FilesViewModel = composeViewModel()
    // Activity-scoped, so the open page survives switching tabs and rotating the phone.
    val browserViewModel: BrowserViewModel = composeViewModel()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != Routes.Settings

    val navigateTo: (Destination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
    val openSettings: () -> Unit = {
        navController.navigate(Routes.Settings) {
            launchSingleTop = true
        }
    }

    // One place for every "are you sure?" (bulk actions and file deletion).
    val pendingConfirm by viewModel.pendingConfirm.collectAsStateWithLifecycle()
    pendingConfirm?.let { pending ->
        ConfirmActionDialog(
            pending = pending,
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissPending,
        )
    }

    // A notification tap asks to show the Downloads screen.
    LaunchedEffect(openDownloadsRequest) {
        if (openDownloadsRequest) {
            navigateTo(Destination.Downloads)
            onOpenDownloadsHandled()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(tween(ENTER_MS)) + slideInVertically(tween(ENTER_MS)) { it },
                exit = fadeOut(tween(EXIT_MS)) + slideOutVertically(tween(EXIT_MS)) { it },
            ) {
                AlmusBottomBar(currentRoute = currentRoute, onSelect = navigateTo)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.YouTube.route,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            enterTransition = { fadeIn(tween(ENTER_MS)) },
            exitTransition = { fadeOut(tween(EXIT_MS)) },
            popEnterTransition = { fadeIn(tween(ENTER_MS)) },
            popExitTransition = { fadeOut(tween(EXIT_MS)) },
        ) {
            composable(Destination.YouTube.route) { YouTubeScreen() }
            composable(Destination.Home.route) {
                HomeScreen(viewModel = viewModel, filesViewModel = filesViewModel, onNavigate = navigateTo, onOpenSettings = openSettings)
            }
            composable(Destination.Downloads.route) { DownloadsScreen(viewModel = viewModel) }
            composable(Destination.Browser.route) {
                BrowserScreen(viewModel = browserViewModel, downloadsViewModel = viewModel)
            }
            composable(Destination.Files.route) { FilesScreen(viewModel = filesViewModel) }
            composable(Routes.Settings) {
                SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun AlmusBottomBar(
    currentRoute: String?,
    onSelect: (Destination) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    NavigationBar(containerColor = colors.surfaceContainer) {
        Destination.entries.forEach { destination ->
            val label = stringResource(destination.labelRes)
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination) },
                icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.onPrimaryContainer,
                    selectedTextColor = colors.primary,
                    indicatorColor = colors.primaryContainer,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant,
                ),
            )
        }
    }
}
