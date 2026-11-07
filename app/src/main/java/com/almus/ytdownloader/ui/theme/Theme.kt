package com.almus.ytdownloader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AlmusDarkColorScheme = darkColorScheme(
    primary = AlmusPrimary,
    onPrimary = AlmusOnPrimary,
    primaryContainer = AlmusPrimaryContainer,
    onPrimaryContainer = AlmusOnPrimaryContainer,
    secondary = AlmusSecondary,
    onSecondary = AlmusOnSecondary,
    tertiary = AlmusTertiary,
    onTertiary = AlmusOnTertiary,
    background = AlmusBackground,
    onBackground = AlmusOnSurface,
    surface = AlmusSurface,
    onSurface = AlmusOnSurface,
    surfaceVariant = AlmusSurfaceContainerHigh,
    onSurfaceVariant = AlmusOnSurfaceVariant,
    surfaceContainer = AlmusSurfaceContainer,
    surfaceContainerHigh = AlmusSurfaceContainerHigh,
    outline = AlmusOutline,
    outlineVariant = AlmusOutlineVariant,
    error = AlmusError,
    onError = AlmusOnError,
)

private val AlmusShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** ALMUS YOUTUBE DOWNLOADER is dark-only: one consistent theme regardless of system setting. */
@Composable
fun AlmusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AlmusDarkColorScheme,
        typography = AlmusTypography,
        shapes = AlmusShapes,
        content = content,
    )
}
