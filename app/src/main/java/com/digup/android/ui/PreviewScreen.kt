package com.digup.android.ui

import android.content.Intent
import android.graphics.Bitmap
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.digup.android.R
import com.digup.android.data.FileKind
import com.digup.android.data.PdfExtractor
import com.digup.android.data.SearchHit
import com.digup.android.data.SourcePath
import com.digup.android.data.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    hit: SearchHit,
    onClose: () -> Unit,
    segmentText: suspend (Long) -> String?,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                hit.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back))
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                val uri = SourcePath.uri(hit.path, context)
                                if (uri == null) {
                                    Toast.makeText(context, R.string.preview_unavailable, Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                val type = context.contentResolver.getType(uri) ?: mimeFor(hit)
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, type)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                val opened = runCatching { context.startActivity(intent) }.isSuccess
                                if (!opened) Toast.makeText(context, R.string.open_failed, Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Outlined.OpenInNew, stringResource(R.string.preview_open))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                },
            ) { insets ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        when (hit.kind) {
                            FileKind.IMAGE, FileKind.SCREENSHOT -> ImagePreview(hit)
                            FileKind.VIDEO -> VideoPreview(hit)
                            FileKind.AUDIO -> AudioPreview(hit)
                            FileKind.PDF -> PdfPreview(hit)
                            FileKind.DOC, FileKind.CODE -> TextPreview(hit, segmentText)
                        }
                    }
                    hit.excerpt?.takeIf { it.isNotBlank() }?.let { ExcerptCard(it) }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(hit: SearchHit) {
    val context = LocalContext.current
    val uri = SourcePath.uri(hit.path, context)
    if (uri == null) {
        Unavailable()
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = hit.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TextPreview(hit: SearchHit, segmentText: suspend (Long) -> String?) {
    var text by remember(hit.segmentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(hit.segmentId) { text = segmentText(hit.segmentId) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
    ) {
        when (val value = text) {
            null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> SelectionContainer {
                Text(
                    text = value.ifBlank { hit.excerpt ?: "" },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun ExcerptCard(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.preview_excerpt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(excerptMarked(text), style = MaterialTheme.typography.bodySmall, maxLines = 4)
            }
        }
    }
}

@Composable
internal fun Unavailable() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.preview_unavailable))
    }
}

internal fun mimeFor(hit: SearchHit): String {
    val extension = hit.name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (hit.kind) {
        FileKind.IMAGE, FileKind.SCREENSHOT -> "image/*"
        FileKind.VIDEO -> "video/*"
        FileKind.AUDIO -> "audio/*"
        FileKind.PDF -> "application/pdf"
        else -> "*/*"
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoPreview(hit: SearchHit) {
    val context = LocalContext.current
    val uri = SourcePath.uri(hit.path, context)
    if (uri == null) {
        Unavailable()
        return
    }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
    }
}

@Composable
private fun AudioPreview(hit: SearchHit) {
    val context = LocalContext.current
    val uri = SourcePath.uri(hit.path, context)
    if (uri == null) {
        Unavailable()
        return
    }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(400)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(76.dp),
        )
        Spacer(Modifier.height(22.dp))
        Text(hit.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(18.dp))
        Slider(
            value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { player.seekTo(it.toLong()) },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position / 1000.0), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(duration / 1000.0), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        FilledTonalIconButton(onClick = {
            if (playing) player.pause() else player.play()
        }, modifier = Modifier.size(64.dp)) {
            Icon(
                imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.preview_pause else R.string.preview_play),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun PdfPreview(hit: SearchHit) {
    val context = LocalContext.current
    val total = hit.info["pages"]?.toIntOrNull() ?: 0
    var page by remember(hit.segmentId) {
        mutableStateOf((hit.loc?.roundToInt() ?: 1).coerceAtLeast(1))
    }
    var rendered by remember(hit.path, page) { mutableStateOf<Pair<Bitmap?, Boolean>>(null to false) }
    LaunchedEffect(hit.path, page) {
        rendered = null to false
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { PdfExtractor.render(context, hit.path, page, 1600) }.getOrNull()
        }
        rendered = bitmap to true
    }
    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !rendered.second -> CircularProgressIndicator()
                rendered.first != null -> Image(
                    bitmap = rendered.first!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.preview_page, page, total.coerceAtLeast(page)),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Unavailable()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { page = (page - 1).coerceAtLeast(1) }, enabled = page > 1) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = null)
                Text(stringResource(R.string.preview_previous))
            }
            Text(
                stringResource(R.string.preview_page, page, total.coerceAtLeast(page)),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onClick = { page += 1 },
                enabled = total <= 0 || page < total,
            ) {
                Text(stringResource(R.string.preview_next))
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        }
    }
}
