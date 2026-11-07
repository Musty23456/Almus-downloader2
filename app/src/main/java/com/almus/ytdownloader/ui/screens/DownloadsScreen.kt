package com.almus.ytdownloader.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.almus.ytdownloader.R
import com.almus.ytdownloader.model.DownloadFilter
import com.almus.ytdownloader.ui.components.ContentContainer
import com.almus.ytdownloader.ui.components.downloadActionsFor
import com.almus.ytdownloader.ui.components.labelRes
import com.almus.ytdownloader.ui.components.DownloadCard
import com.almus.ytdownloader.ui.components.EmptyState
import com.almus.ytdownloader.ui.components.NoticeBanner
import com.almus.ytdownloader.ui.components.ScreenHeader
import com.almus.ytdownloader.ui.state.DownloadsViewModel

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val filters = DownloadFilter.entries
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val actions = downloadActionsFor(viewModel)
    var menuOpen by remember { mutableStateOf(false) }
    val currentFilter = filters[selectedTab]
    val inTab = downloads.filter { currentFilter.matches(it.status) }
    // Only actions that would affect at least one item in the current tab are offered.
    val bulkActions = currentFilter.bulkActions.filter { action ->
        inTab.any { action.appliesTo(it.status.state) }
    }

    ContentContainer(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.downloads_title),
                subtitle = stringResource(R.string.downloads_subtitle),
            )
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 8.dp,
            ) {
                filters.forEachIndexed { index, filter ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Text(
                                text = stringResource(filter.labelRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }
            if (bulkActions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box {
                        TextButton(onClick = { menuOpen = true }) {
                            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(R.string.bulk_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            bulkActions.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(action.labelRes())) },
                                    onClick = {
                                        menuOpen = false
                                        val ids = inTab
                                            .filter { action.appliesTo(it.status.state) }
                                            .map { it.id }
                                        viewModel.requestBulk(action, ids)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            notice?.let { message ->
                NoticeBanner(
                    text = message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            Crossfade(
                targetState = filters[selectedTab],
                modifier = Modifier.weight(1f),
                label = "downloads-tab",
            ) { filter ->
                val visible = downloads.filter { filter.matches(it.status) }
                if (visible.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Download,
                        headline = stringResource(filter.emptyTitleRes),
                        message = stringResource(filter.emptyMessageRes),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items = visible, key = { it.id }) { item ->
                            DownloadCard(
                                item = item,
                                actions = actions,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}
