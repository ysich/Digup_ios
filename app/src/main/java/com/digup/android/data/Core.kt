package com.digup.android.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

enum class FileKind(val sql: String) {
    SCREENSHOT("screenshot"), IMAGE("image"), PDF("pdf"), DOC("doc"), AUDIO("audio"), VIDEO("video"), CODE("code");

    companion object {
        fun of(value: String?): FileKind = entries.firstOrNull { it.sql == value } ?: DOC
    }
}

enum class SegmentKind(val sql: String) {
    NAME("name"), IMAGE("image"), OCR("ocr"), PAGE("page"), PAGE_IMAGE("page_image"),
    CHUNK("chunk"), AUDIO("audio"), FRAME("frame"), LINES("lines"), CELLS("cells");

    companion object {
        fun of(value: String?): SegmentKind = entries.firstOrNull { it.sql == value } ?: NAME
    }
}

enum class Modality(val sql: String) { NONE("none"), TEXT("text"), IMAGE("image"), AUDIO("audio");

    companion object {
        fun of(value: String?): Modality = entries.firstOrNull { it.sql == value } ?: NONE
    }
}

/** Settings that change the vectors; they are part of the index fingerprint. */
data class IndexOptions(
    val minImageSide: Int = 256,
    val bigPdfPages: Int = 50,
    val pdfStep: Int = 30,
    val docStep: Int = 40,
    val maxPages: Int = 5000,
    val maxChunks: Int = 5000,
    val minPageText: Int = 200,
    val photoBudget: Int = 140,
    val screenshotBudget: Int = 280,
    val pageBudget: Int = 280,
    val frameBudget: Int = 70,
    val frameInterval: Double = 3.0,
    val audioWindow: Double = 30.0,
    val audioHop: Double = 25.0,
) {
    val fingerprint: String
        get() = "photo$photoBudget shot$screenshotBudget page$pageBudget " +
            "frame$frameBudget/${frameInterval}s audio$audioWindow/$audioHop"
}

/** EmbeddingGemma 2's task prompts. Media inputs take no prompt. */
object Prompts {
    fun query(text: String) = "task: search result | query: $text"
    fun document(title: String?, text: String) = "title: ${title?.takeIf { it.isNotBlank() } ?: "none"} | text: $text"
}

data class Candidate(
    val path: String,
    val kind: FileKind,
    val size: Long,
    val mtime: Double,
    val inode: Long,
    val device: Long,
    val name: String,
    val folder: String,
    val mime: String? = null,
)

data class Embedding(val vector: FloatArray?, val error: String?)

data class SearchHit(
    val path: String,
    val kind: FileKind,
    val score: Double,
    val z: Double?,
    val cosine: Double?,
    val keywordRank: Int?,
    val segment: SegmentKind,
    val segmentId: Long,
    val loc: Double?,
    val locEnd: Double?,
    val excerpt: String?,
    val modified: Double,
    val size: Long,
    val info: Map<String, String>,
    val name: String,
    val folder: String,
)

/** media://image/<id>#<name> or media://video/<id>#<name> */
object MediaPath {
    const val PREFIX = "media://"

    fun path(isVideo: Boolean, id: Long, name: String): String {
        val kind = if (isVideo) "video" else "image"
        return "media://$kind/$id#${Uri.encode(name)}"
    }

    fun owns(path: String) = path.startsWith(PREFIX)
    fun isVideo(path: String) = path.startsWith("media://video/")
    fun id(path: String): Long? = path.removePrefix(PREFIX).substringBefore('#').substringAfterLast('/').toLongOrNull()
    fun name(path: String): String? = path.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(Uri::decode)
    fun uri(path: String): Uri? = id(path)?.let { id ->
        val collection = if (isVideo(path)) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        ContentUris.withAppendedId(collection, id)
    }
}

/** saf://<base64url(treeUri)>/<base64url(documentId)>#<name> */
object SafPath {
    const val PREFIX = "saf://"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun path(tree: Uri, documentId: String, name: String): String {
        val treePart = encoder.encodeToString(tree.toString().toByteArray(Charsets.UTF_8))
        val docPart = encoder.encodeToString(documentId.toByteArray(Charsets.UTF_8))
        return "$PREFIX$treePart/$docPart#${Uri.encode(name)}"
    }

    fun owns(path: String) = path.startsWith(PREFIX)

    fun prefix(tree: Uri): String = PREFIX + encoder.encodeToString(tree.toString().toByteArray(Charsets.UTF_8)) + "/"

    fun tree(path: String): Uri? = runCatching {
        val rest = path.removePrefix(PREFIX).substringBefore('/')
        Uri.parse(String(decoder.decode(rest), Charsets.UTF_8))
    }.getOrNull()

    fun documentId(path: String): String? = runCatching {
        val rest = path.removePrefix(PREFIX).substringAfter('/').substringBefore('#')
        String(decoder.decode(rest), Charsets.UTF_8)
    }.getOrNull()

    fun name(path: String): String? = path.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(Uri::decode)

    fun uri(path: String): Uri? {
        val tree = tree(path) ?: return null
        val document = documentId(path) ?: return null
        return DocumentsContractShim.documentUri(tree, document)
    }
}

object DocumentsContractShim {
    fun documentUri(tree: Uri, documentId: String): Uri = android.provider.DocumentsContract
        .buildDocumentUriUsingTree(tree, documentId)
}

object SourcePath {
    fun name(path: String): String? = MediaPath.name(path) ?: SafPath.name(path) ?: path.substringAfterLast('/')
    fun uri(path: String, context: Context): Uri? = when {
        MediaPath.owns(path) -> MediaPath.uri(path)
        SafPath.owns(path) -> SafPath.uri(path)
        else -> File(path).takeIf { it.exists() }?.let(Uri::fromFile)
    }

    fun folder(path: String): String = when {
        MediaPath.owns(path) -> "Photos"
        SafPath.owns(path) -> ""
        else -> File(path).parent ?: ""
    }
}

object Paths {
    fun models(context: Context): File = File(context.filesDir, "Models").apply { mkdirs() }
    fun index(context: Context): File = File(context.filesDir, "index").apply { mkdirs() }
    fun cache(context: Context): File = File(context.cacheDir, "digup").apply { mkdirs() }
}

object Vectors {
    const val DIMENSION = 768

    fun half(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putShort(floatToHalf(value))
        return buffer.array()
    }

    fun halves(blob: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(blob.size / 2)
        for (i in out.indices) out[i] = buffer.short
        return out
    }

    fun halfToFloat(half: Short): Float {
        val bits = half.toInt() and 0xFFFF
        val sign = (bits and 0x8000) shl 16
        var exponent = (bits ushr 10) and 0x1F
        var mantissa = bits and 0x03FF
        val out: Int = when {
            exponent == 0 -> {
                if (mantissa == 0) sign
                else {
                    while ((mantissa and 0x0400) == 0) {
                        mantissa = mantissa shl 1
                        exponent -= 1
                    }
                    exponent += 1
                    mantissa = mantissa and 0x03FF
                    sign or ((exponent + 112) shl 23) or (mantissa shl 13)
                }
            }
            exponent == 31 -> sign or 0x7F800000.toInt() or (mantissa shl 13)
            else -> sign or ((exponent + 112) shl 23) or (mantissa shl 13)
        }
        return Float.fromBits(out)
    }

    fun floatToHalf(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        var mantissa = bits and 0x7FFFFF
        if (exponent <= 0) {
            if (exponent < -10) return sign.toShort()
            mantissa = (mantissa or 0x800000) ushr (1 - exponent)
            return (sign or ((mantissa + 0x1000) ushr 13)).toShort()
        }
        if (exponent >= 31) return (sign or 0x7C00).toShort()
        mantissa += 0x1000
        if (mantissa and 0x800000 != 0) {
            mantissa = 0
            exponent += 1
            if (exponent >= 31) return (sign or 0x7C00).toShort()
        }
        return (sign or (exponent shl 10) or (mantissa ushr 13)).toShort()
    }
}

fun excerpt(text: String, limit: Int = 240): String {
    val start = text.take(limit * 8)
    val line = start.replace(Regex("\\s+"), " ").trim()
    return if (line.length > limit || start.length < text.length) line.take(limit) + "…" else line
}

fun fnv1a(text: String): Long {
    var value = -0x340d631b7bdddcdbL
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        value = (value xor (byte.toLong() and 0xFF)) * 0x100000001b3L
    }
    return value
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return if (index == 0) "${value.roundToInt()} ${units[index]}" else String.format("%.1f %s", value, units[index])
}

fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun sameSize(a: Long, b: Long): Boolean = a == b || (a > 0 && b > 0 && abs(a - b) <= 1)


