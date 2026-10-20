package com.digup.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.digup.android.R
import com.digup.android.SourceRoot
import com.digup.android.UiState
import com.digup.android.data.ModelFiles
import com.digup.android.data.formatBytes
import kotlin.math.roundToInt

@Composable
fun OnboardingScreen(
    state: UiState,
    onDownloadModel: () -> Unit,
    onIncludePhotos: () -> Unit,
    onStopPhotos: () -> Unit,
    onPickFolder: () -> Unit,
    onStartIndex: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Hero() }
        item {
            SectionCard(stringResource(R.string.onboarding_model_title)) {
                if (state.modelReady) {
                    CheckLine(
                        text = stringResource(R.string.model_ready),
                        icon = Icons.Outlined.CheckCircle,
                    )
                } else {
                    Text(
                        stringResource(R.string.model_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onDownloadModel, enabled = !state.downloading) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.downloading) R.string.downloading else R.string.download_model))
                    }
                    if (state.downloading) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(
                                R.string.model_download_size,
                                formatBytes((ModelFiles.totalBytes * state.downloadProgress).toLong()),
                                formatBytes(ModelFiles.totalBytes),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.model_download_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.onboarding_photos_title)) {
                Text(
                    stringResource(R.string.photos_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    state.photosEnabled -> {
                        CheckLine(
                            text = stringResource(R.string.photos_included),
                            icon = Icons.Outlined.CheckCircle,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onStopPhotos) {
                            Text(stringResource(R.string.photos_stop))
                        }
                    }
                    else -> Button(onClick = onIncludePhotos) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.photos_include))
                    }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.onboarding_folders_title)) {
                Text(
                    stringResource(R.string.folders_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onPickFolder) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.choose_folders))
                }
                if (state.sources.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.no_folders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    state.sources.forEach { source -> FolderLine(source) }
                }
            }
        }
        if (state.hasSource) {
            item {
                SectionCard(stringResource(R.string.onboarding_index_title)) {
                    Button(onClick = onStartIndex, enabled = state.modelReady && !state.isIndexing) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.start_indexing))
                    }
                    state.progress?.let { progress ->
                        if (state.isIndexing) {
                            Spacer(Modifier.height(12.dp))
                            IndexBanner(progress)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.index_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero() {
    Column {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun CheckLine(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FolderLine(source: SourceRoot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(source.label, style = MaterialTheme.typography.bodyMedium)
    }
}
