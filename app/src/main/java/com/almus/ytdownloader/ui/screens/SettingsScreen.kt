package com.almus.ytdownloader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.ytdownloader.BuildConfig
import com.almus.ytdownloader.R
import com.almus.ytdownloader.ui.components.AlmusCard
import com.almus.ytdownloader.ui.components.AlmusTextField
import com.almus.ytdownloader.ui.components.ContentContainer
import com.almus.ytdownloader.ui.components.ScreenHeader
import com.almus.ytdownloader.ui.components.SectionHeader
import com.almus.ytdownloader.download.QueueSettings
import com.almus.ytdownloader.download.SaveLayout
import com.almus.ytdownloader.ui.state.DownloadsViewModel

/** Settings. Only the dark theme is active; every other option is a clearly marked placeholder. */
@Composable
fun SettingsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxConcurrent by viewModel.maxConcurrent.collectAsStateWithLifecycle()
    val saveLayout by viewModel.saveLayout.collectAsStateWithLifecycle()
    val newEngine by viewModel.newEngineEnabled.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val engineSupported = viewModel.engineSupported
    ContentContainer(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(
                title = stringResource(R.string.settings_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )

            // Appearance
            SectionHeader(title = stringResource(R.string.settings_appearance))
            SettingsCard {
                ThemeOptionRow(
                    title = stringResource(R.string.theme_dark),
                    description = stringResource(R.string.theme_dark_desc),
                    selected = true,
                    enabled = true,
                )
                SettingsDivider()
                ThemeOptionRow(
                    title = stringResource(R.string.theme_light),
                    description = stringResource(R.string.settings_coming_soon),
                    selected = false,
                    enabled = false,
                )
                SettingsDivider()
                ThemeOptionRow(
                    title = stringResource(R.string.theme_system),
                    description = stringResource(R.string.settings_coming_soon),
                    selected = false,
                    enabled = false,
                )
            }

            // Downloads
            SectionHeader(title = stringResource(R.string.settings_downloads))
            SettingsCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.pref_location),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.pref_location_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = saveLayout == SaveLayout.Single,
                            onClick = { viewModel.setSaveLayout(SaveLayout.Single) },
                            label = { Text(text = stringResource(R.string.pref_layout_single)) },
                        )
                        FilterChip(
                            selected = saveLayout == SaveLayout.ByMedia,
                            onClick = { viewModel.setSaveLayout(SaveLayout.ByMedia) },
                            label = { Text(text = stringResource(R.string.pref_layout_by_media)) },
                        )
                    }
                }
                SettingsDivider()
                SettingRow(
                    title = stringResource(R.string.pref_new_engine),
                    description = stringResource(if (engineSupported) R.string.pref_new_engine_desc else R.string.pref_new_engine_unsupported),
                    enabled = engineSupported,
                ) { Switch(checked = newEngine, onCheckedChange = { viewModel.setNewEngineEnabled(it) }, enabled = engineSupported) }
                SettingsDivider()
                SettingRow(
                    title = stringResource(R.string.pref_wifi_only),
                    description = stringResource(R.string.pref_wifi_desc),
                    enabled = engineSupported,
                ) { Switch(checked = wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) }, enabled = engineSupported) }
                SettingsDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.pref_concurrent),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.pref_concurrent_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (QueueSettings.MIN..QueueSettings.MAX).forEach { n ->
                            FilterChip(
                                selected = maxConcurrent == n,
                                onClick = { viewModel.setMaxConcurrent(n) },
                                label = { Text(text = n.toString()) },
                            )
                        }
                    }
                }
                SettingsDivider()
                SettingRow(
                    title = stringResource(R.string.pref_notifications),
                    description = stringResource(R.string.pref_notifications_desc),
                    enabled = false,
                ) { Switch(checked = false, onCheckedChange = null, enabled = false) }
            }

            // About
            SectionHeader(title = stringResource(R.string.settings_about))
            SettingsCard {
                SettingRow(title = stringResource(R.string.about_app)) {
                    ValueText(stringResource(R.string.about_app_value))
                }
                SettingsDivider()
                SettingRow(title = stringResource(R.string.about_version)) {
                    ValueText(BuildConfig.VERSION_NAME)
                }
                SettingsDivider()
                SettingRow(title = stringResource(R.string.about_stage)) {
                    ValueText(stringResource(R.string.about_stage_value))
                }
                SettingsDivider()
                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    AlmusCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit,
) {
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun ThemeOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
) {
    SettingRow(
        title = title,
        description = description,
        enabled = enabled,
        modifier = Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = {},
        ),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}
