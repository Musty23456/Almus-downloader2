package com.almus.ytdownloader.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.almus.ytdownloader.R
import com.almus.ytdownloader.model.DownloadItem
import com.almus.ytdownloader.model.DownloadStatus
import com.almus.ytdownloader.model.FileCategory
import com.almus.ytdownloader.ui.theme.AlmusSuccess

/** Standard rounded card. Pass [onClick] to make it tappable. */
@Composable
fun AlmusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val shape = MaterialTheme.shapes.large
    val animatedModifier = modifier.animateContentSize()
    if (onClick != null) {
        Card(onClick = onClick, modifier = animatedModifier, shape = shape, colors = colors) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier = animatedModifier, shape = shape, colors = colors) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

/** Round icon container used in cards and empty states. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    container: Color = Color.Unspecified,
    tint: Color = Color.Unspecified,
) {
    val background = if (container == Color.Unspecified) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        container
    }
    val iconTint = if (tint == Color.Unspecified) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        tint
    }
    Box(
        modifier = modifier
            .size(size)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Small colored pill used for statuses and badges. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Marks a feature as not implemented yet. */
@Composable
fun PlannedBadge(modifier: Modifier = Modifier) {
    StatusPill(
        text = stringResource(R.string.badge_planned),
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    )
}

/** Inline notice, used to state clearly that something is not available yet. */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Compact statistic card for the Home overview. */
@Composable
fun SummaryCard(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    AlmusCard(modifier = modifier) {
        IconBadge(
            icon = icon,
            size = 36.dp,
            container = tint.copy(alpha = 0.16f),
            tint = tint,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tappable file category tile (Videos, Music, ...). */
@Composable
fun CategoryTile(
    category: FileCategory,
    modifier: Modifier = Modifier,
    count: Int = 0,
    onClick: () -> Unit,
) {
    AlmusCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(icon = category.icon)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(category.labelRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (count > 0) {
                    pluralStringResource(R.plurals.cat_file_count, count, count)
                } else {
                    stringResource(R.string.cat_no_files)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DownloadStatus.accentColor(): Color = when (this) {
    DownloadStatus.Waiting, DownloadStatus.Queued, DownloadStatus.Active -> MaterialTheme.colorScheme.primary
    DownloadStatus.Paused, DownloadStatus.OnHold -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.Completed -> AlmusSuccess
    DownloadStatus.Failed -> MaterialTheme.colorScheme.error
    DownloadStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** What the buttons on a download card do. */
data class DownloadActions(
    val onCancel: (DownloadItem) -> Unit,
    val onRetry: (DownloadItem) -> Unit,
    val onRemove: (DownloadItem) -> Unit,
    val onOpen: (DownloadItem) -> Unit,
    val onHold: (DownloadItem) -> Unit,
    val onResume: (DownloadItem) -> Unit,
    val onDelete: (DownloadItem) -> Unit,
)

/** Card showing one download: file name, status, bytes, progress, error and actions. */
@Composable
fun DownloadCard(
    item: DownloadItem,
    actions: DownloadActions,
    modifier: Modifier = Modifier,
) {
    val statusColor = item.status.accentColor()
    val progress by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = tween(durationMillis = 600),
        label = "download-progress",
    )
    val showBar = item.status == DownloadStatus.Queued ||
        item.status == DownloadStatus.Active ||
        item.status == DownloadStatus.Paused ||
        item.status == DownloadStatus.Completed
    val liveDetails = listOfNotNull(item.speedLabel, item.etaLabel).joinToString(" · ")
    AlmusCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = item.category.icon)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = stringResource(item.status.labelRes),
                        color = statusColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.sizeLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.insecure) {
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(
                    text = stringResource(R.string.insecure_badge),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (showBar) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.progressKnown) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.percent_format, (progress * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.unknown_size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (liveDetails.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = liveDetails,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!item.errorMessage.isNullOrBlank() && item.status != DownloadStatus.Completed) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = item.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.status == DownloadStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            when (item.status) {
                DownloadStatus.Waiting -> {
                    CardButton(R.string.action_hold) { actions.onHold(item) }
                    CardButton(R.string.action_cancel) { actions.onCancel(item) }
                }
                DownloadStatus.Queued, DownloadStatus.Active, DownloadStatus.Paused -> {
                    // Only downloads of the new engine can really be paused.
                    if (item.canPause) CardButton(R.string.action_pause) { actions.onHold(item) }
                    CardButton(R.string.action_cancel) { actions.onCancel(item) }
                }
                DownloadStatus.OnHold -> {
                    CardButton(R.string.action_resume) { actions.onResume(item) }
                    CardButton(R.string.action_cancel) { actions.onCancel(item) }
                }
                DownloadStatus.Completed -> {
                    CardButton(R.string.action_remove) { actions.onRemove(item) }
                    CardButton(R.string.action_delete_file, destructive = true) { actions.onDelete(item) }
                    CardButton(
                        if (item.category == FileCategory.Apks) R.string.action_show_in_downloads else R.string.action_open,
                    ) { actions.onOpen(item) }
                }
                DownloadStatus.Failed, DownloadStatus.Cancelled -> {
                    CardButton(R.string.action_remove) { actions.onRemove(item) }
                    CardButton(R.string.action_retry) { actions.onRetry(item) }
                }
            }
        }
    }
}

@Composable
private fun CardButton(
    @androidx.annotation.StringRes textRes: Int,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        colors = if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Text(text = stringResource(textRes), maxLines = 1)
    }
}
