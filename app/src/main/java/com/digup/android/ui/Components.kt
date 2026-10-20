package com.digup.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.digup.android.data.FileKind
import com.digup.android.data.IndexProgress
import com.digup.android.data.SearchHit
import com.digup.android.data.SourcePath
import com.digup.android.data.formatTime
import kotlin.math.roundToInt

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.SCREENSHOT -> Icons.Outlined.CropFree
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.DOC -> Icons.Outlined.Description
    FileKind.AUDIO -> Icons.Outlined.GraphicEq
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.CODE -> Icons.Outlined.Code
}

fun SearchHit.folderLabel(): String {
    if (folder.isBlank()) return "Files"
    val trimmed = folder.trimEnd('/')
    return trimmed.substringAfterLast('/').ifBlank { trimmed }
}

fun SearchHit.locationLabel(): String? {
    val place = loc ?: return null
    return when (kind) {
        FileKind.PDF -> "p. ${place.roundToInt()}"
        FileKind.AUDIO, FileKind.VIDEO -> formatTime(place)
        else -> null
    }
}

@Composable
fun RelatedThumbnail(hit: SearchHit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = SourcePath.uri(hit.path, context)
    val image = hit.kind == FileKind.IMAGE || hit.kind == FileKind.SCREENSHOT || hit.kind == FileKind.VIDEO
    Box(
        modifier = modifier
            .size(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null && image) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .apply { if (hit.kind == FileKind.VIDEO) videoFrameMillis(1_000) }
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(62.dp),
            )
        } else {
            Icon(
                imageVector = hit.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
fun ResultRow(hit: SearchHit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RelatedThumbnail(hit)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = hit.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val location = hit.locationLabel()
                    if (location != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = location,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = hit.folderLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.excerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = excerptMarked(excerpt),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun excerptMarked(raw: String): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var index = 0
        while (index < raw.length) {
            val open = raw.indexOf('«', index)
            if (open < 0) {
                append(raw.substring(index))
                break
            }
            append(raw.substring(index, open))
            val close = raw.indexOf('»', open + 1)
            if (close < 0) {
                append(raw.substring(open))
                break
            }
            pushStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold))
            append(raw.substring(open + 1, close))
            pop()
            index = close + 1
        }
    }
}

@Composable
fun IndexBanner(progress: IndexProgress, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = progress.phase,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (progress.total > 0) {
                    Text(
                        text = "${progress.done}/${progress.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val path = progress.path
            if (!path.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = SourcePath.name(path) ?: path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, icon: ImageVector = Icons.Outlined.Image) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(18.dp)
                    .size(34.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
