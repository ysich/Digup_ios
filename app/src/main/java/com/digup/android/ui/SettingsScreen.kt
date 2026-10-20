package com.digup.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.digup.android.R
import com.digup.android.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onClose: () -> Unit,
    onPickFolder: () -> Unit,
    onRemoveFolder: (android.net.Uri) -> Unit,
    onPhotosToggle: (Boolean) -> Unit,
    onUpdateIndex: () -> Unit,
    onRebuildIndex: () -> Unit,
    onClearIndex: () -> Unit,
    onFreeSearchModel: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold) },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back))
                            }
                        },
                        actions = {
                            TextButton(onClick = onClose) { Text(stringResource(R.string.done)) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                },
            ) { insets ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SettingsCard(stringResource(R.string.section_folders)) {
                            state.sources.forEach { source ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        source.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { onRemoveFolder(source.uri) }) {
                                        Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.remove))
                                    }
                                }
                            }
                            if (state.sources.isEmpty()) {
                                Text(
                                    stringResource(R.string.no_folders),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onPickFolder) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.add_folder))
                            }
                        }
                    }
                    item {
                        SettingsCard(stringResource(R.string.section_photos)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.photos_toggle),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = state.photosEnabled,
                                    onCheckedChange = onPhotosToggle,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.photos_toggle_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        SettingsCard(stringResource(R.string.section_index)) {
                            StatLine(stringResource(R.string.index_size), state.indexSizeText)
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            StatLine(stringResource(R.string.count_files), state.counts.files.toString())
                            StatLine(stringResource(R.string.count_pending), state.counts.pending.toString())
                            StatLine(stringResource(R.string.count_failed), state.counts.failed.toString())
                            StatLine(stringResource(R.string.count_vectors), state.counts.vectors.toString())
                            state.indexSummary?.let { summary ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.index_complete, summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.progress?.let { progress ->
                                if (state.isIndexing) {
                                    Spacer(Modifier.height(10.dp))
                                    IndexBanner(progress)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onUpdateIndex,
                                enabled = state.modelReady && state.hasSource && !state.isIndexing,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.update_index)) }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onRebuildIndex,
                                enabled = state.modelReady && state.hasSource && !state.isIndexing,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.rebuild_index)) }
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = { confirmClear = true },
                                enabled = !state.isIndexing && (state.indexBytes > 0 || state.counts.files > 0),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(R.string.clear_index),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    item {
                        SettingsCard(stringResource(R.string.section_model)) {
                            StatLine(
                                stringResource(R.string.model_status),
                                stringResource(if (state.modelReady) R.string.model_ready else R.string.model_not_downloaded),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.model_caption),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.modelReady) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = onFreeSearchModel) {
                                    Text(stringResource(R.string.free_search_model))
                                }
                            }
                        }
                    }
                    item {
                        SettingsCard(stringResource(R.string.section_diagnostics)) {
                            Text(
                                text = stringResource(
                                    R.string.diagnostics_counts,
                                    state.counts.files,
                                    state.counts.done,
                                    state.counts.pending,
                                    state.counts.failed,
                                    state.counts.vectors,
                                    state.counts.segments,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            state.indexIssue?.let { issue ->
                                Text(
                                    text = issue,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (state.failures.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.no_failures),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                state.failures.forEach { failure ->
                                    Text(
                                        text = failure,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        SettingsCard(stringResource(R.string.section_privacy)) {
                            Text(
                                stringResource(R.string.privacy_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_confirm_title)) },
            text = { Text(stringResource(R.string.clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearIndex()
                }) { Text(stringResource(R.string.clear_index), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
