package com.digup.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.AudioFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ExtractError(message: String) : Exception(message)

data class PreparedImage(val bitmap: Bitmap, val width: Int, val height: Int, val taken: String?)

object ImageExtractor {
    fun prepare(context: Context, path: String, maxPixel: Int, minSide: Int): PreparedImage {
        val uri = SourcePath.uri(path, context) ?: throw ExtractError("no such file")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw ExtractError("unreadable image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw ExtractError("unreadable image")
        if (max(width, height) < minSide) throw ExtractError("small image ${width}×$height")

        var sample = 1
        while (max(width, height) / sample > maxPixel * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw ExtractError("can't decode image")

        val scale = maxPixel.toFloat() / max(bitmap.width, bitmap.height)
        if (scale < 1f) {
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        var taken: String? = null
        val matrix = Matrix()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                }
            }
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return PreparedImage(bitmap, width, height, taken)
    }
}

object Ocr {
    private val latin: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    suspend fun text(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val lines = LinkedHashSet<String>()
        // Latin covers most screenshots; Chinese is common enough to always add. Running every
        // script recognizer here was several times slower and pulled in all ML Kit models.
        for (recognizer in listOf(latin, chinese)) {
            runCatching {
                val result = suspendCancellableCoroutine { continuation ->
                    recognizer.process(image)
                        .addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { error -> continuation.resumeWith(Result.failure(error)) }
                }
                result.textBlocks.forEach { block -> block.lines.forEach { line -> lines.add(line.text) } }
            }
        }
        return lines.joinToString("\n")
    }

    fun close() {
        runCatching { latin.close() }
        runCatching { chinese.close() }
    }
}

data class PdfPage(val number: Int, val text: String, val needsImage: Boolean)
data class PdfText(val pageCount: Int, val pages: List<PdfPage>, val title: String?)

object PdfExtractor {
    fun pageCount(context: Context, path: String): Int {
        val uri = SourcePath.uri(path, context) ?: throw ExtractError("no such file")
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { document ->
                if (document.isEncrypted) throw ExtractError("password-protected")
                return document.numberOfPages
            }
        }
        throw ExtractError("unreadable PDF")
    }

    fun text(context: Context, path: String, from: Int, to: Int, minText: Int): PdfText {
        val uri = SourcePath.uri(path, context) ?: throw ExtractError("no such file")
        val input = context.contentResolver.openInputStream(uri) ?: throw ExtractError("unreadable PDF")
        input.use { stream -> PDDocument.load(stream).use { document ->
            if (document.isEncrypted) throw ExtractError("password-protected")
            val pageCount = document.numberOfPages
            if (pageCount == 0) return PdfText(0, emptyList(), null)
            val start = from.coerceIn(0, pageCount - 1)
            val end = to.coerceIn(start, pageCount - 1)
            val stripper = PDFTextStripper().apply {
                startPage = start + 1
                endPage = end + 1
                sortByPosition = true
            }
            val raw = runCatching { stripper.getText(document) }.getOrDefault("")
            val pieces = raw.split('\u000C')
            val pages = ArrayList<PdfPage>()
            for (number in start..end) {
                val text = pieces.getOrNull(number - start)?.trim().orEmpty()
                val needsImage = text.length < minText || looksGarbled(text)
                pages.add(PdfPage(number + 1, if (needsImage) "" else text, needsImage))
            }
            val title = runCatching { document.documentInformation?.title?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            return PdfText(pageCount, pages, title)
        } }
    }

    fun render(context: Context, path: String, pageNumber: Int, pixels: Int): Bitmap? {
        val uri = SourcePath.uri(path, context) ?: return null
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return descriptor.use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (pageNumber < 1 || pageNumber > renderer.pageCount) {
                    null
                } else {
                    renderer.openPage(pageNumber - 1).use { page ->
                        val width = pixels
                        val height = max(1, (pixels.toDouble() * page.height / page.width).roundToInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }

    private fun looksGarbled(text: String): Boolean {
        var vowelSigns = 0
        var misplaced = 0
        var previous = ' '
        for (scalar in text.codePoints().toArray()) {
            if (isIndicVowelSign(scalar)) {
                vowelSigns++
                if (!isIndicConsonantOrNukta(previous.code)) misplaced++
            }
            previous = scalar.toChar()
        }
        return vowelSigns >= 10 && misplaced * 20 >= vowelSigns
    }

    private fun isIndicVowelSign(value: Int): Boolean = when (value) {
        in 0x09BE..0x09C4, 0x09C7, 0x09C8, 0x09CB, 0x09CC, 0x09D7, 0x09E2, 0x09E3 -> true
        0x093A, 0x093B, in 0x093E..0x094C, 0x094E, 0x094F, in 0x0955..0x0957, 0x0962, 0x0963 -> true
        else -> false
    }

    private fun isIndicConsonantOrNukta(value: Int): Boolean = when (value) {
        in 0x0995..0x09B9, 0x09BC, 0x09CE, 0x09DC, 0x09DD, 0x09DF, 0x09F0, 0x09F1 -> true
        in 0x0915..0x0939, 0x093C, in 0x0958..0x095F, in 0x0979..0x097F -> true
        else -> false
    }
}

object DocExtractor {
    private val htmlBlocks = Regex("(?is)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)\\b[^>]*>")
    private val tags = Regex("<[^>]+>")
    private val spaces = Regex("[ \\t\\u00A0]+")

    fun text(context: Context, path: String): String {
        val uri = SourcePath.uri(path, context) ?: throw ExtractError("no such file")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ExtractError("unreadable document")
        val ext = Rules.extension(SourcePath.name(path) ?: path)
        return when (ext) {
            "docx" -> docxText(bytes)
            "odt" -> odtText(bytes)
            "rtf" -> rtfText(decode(bytes))
            "html", "htm" -> htmlText(decode(bytes))
            "doc" -> throw ExtractError("legacy .doc isn't read on Android")
            else -> decode(bytes)
        }
    }

    fun chunks(text: String, limit: Int, size: Int = 1800, overlap: Int = 200): List<String> {
        val clean = text.replace(spaces, " ").replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n").trim()
        val chunks = ArrayList<String>()
        var start = 0
        while (start < clean.length && chunks.size < limit) {
            var end = min(start + size, clean.length)
            if (end < clean.length) {
                val space = clean.lastIndexOfAny(charArrayOf(' ', '\n', '\t', '\r'), end - 1)
                if (space > start + size / 2) end = space
            }
            val chunk = clean.substring(start, end).trim()
            if (chunk.isNotEmpty()) chunks.add(chunk)
            if (end >= clean.length) break
            var next = max(start, end - overlap)
            while (next < end && !clean[next].isWhitespace()) next++
            start = if (next < end) next else end
        }
        return chunks
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    private fun docxText(bytes: ByteArray): String {
        val xml = zipEntry(bytes, "word/document.xml") ?: throw ExtractError("docx has no document.xml")
        return cleanXml(xml
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("<w:tab[^>]*/>"), "\t")
            .replace(Regex("<w:br[^>]*/>"), "\n"))
    }

    private fun odtText(bytes: ByteArray): String {
        val xml = zipEntry(bytes, "content.xml") ?: throw ExtractError("odt has no content.xml")
        return cleanXml(xml
            .replace(Regex("</text:p>"), "\n")
            .replace(Regex("</text:h>"), "\n")
            .replace(Regex("<text:tab[^>]*/>"), "\t")
            .replace(Regex("<text:line-break[^>]*/>"), "\n"))
    }

    private fun zipEntry(bytes: ByteArray, wanted: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == wanted) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return null
    }

    private fun cleanXml(xml: String): String = unescape(tags.replace(xml, " "))
        .replace(spaces, " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()

    private fun htmlText(html: String): String {
        var text = html
        for (pattern in listOf(
            Regex("(?is)<script\\b.*?</script\\s*>"),
            Regex("(?is)<style\\b.*?</style\\s*>"),
            Regex("(?s)<!--.*?-->"),
            Regex("(?is)<head\\b.*?</head\\s*>"),
        )) text = pattern.replace(text, " ")
        text = htmlBlocks.replace(text, "\n")
        text = tags.replace(text, " ")
        return unescape(text).replace(spaces, " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()
    }

    private fun rtfText(rtf: String): String {
        var text = rtf.replace(Regex("\\\\'([0-9a-fA-F]{2})")) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        text = text.replace(Regex("\\\\par[d]?"), "\n")
        text = text.replace(Regex("\\\\tab"), "\t")
        text = text.replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
        text = text.replace(Regex("[{}]"), "")
        return text.replace(spaces, " ").trim()
    }

    private fun unescape(text: String): String {
        var out = text
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
        val numeric = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
        out = numeric.replace(out) { match ->
            val value = match.groupValues[1]
            val code = if (value.startsWith("x")) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else " "
        }
        return out
    }
}

data class AudioWindow(val samples: FloatArray, val start: Double, val end: Double)

object AudioExtractor {
    private const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    suspend fun windows(
        context: Context,
        path: String,
        window: Double,
        hop: Double,
        minimum: Double = 2.0,
        onWindow: suspend (AudioWindow) -> Unit,
    ): Double {
        val uri = SourcePath.uri(path, context) ?: throw ExtractError("no such file")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = index
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return 0.0
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0.0
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val windowSamples = (window * TARGET_RATE).roundToInt()
            val hopSamples = (hop * TARGET_RATE).roundToInt()
            var buffer = FloatArray(windowSamples * 2)
            var count = 0
            var offset = 0L

            suspend fun emit(size: Int) {
                if (size <= 0) return
                var sum = 0.0
                for (i in 0 until size) sum += buffer[i] * buffer[i]
                if (sqrt(sum / size) > 0.003) {
                    onWindow(AudioWindow(buffer.copyOfRange(0, size), offset.toDouble() / TARGET_RATE,
                        (offset + size).toDouble() / TARGET_RATE))
                }
            }

            fun append(values: FloatArray) {
                if (count + values.size > buffer.size) {
                    buffer = buffer.copyOf(max(buffer.size, (count + values.size) * 2))
                }
                System.arraycopy(values, 0, buffer, count, values.size)
                count += values.size
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    outputIndex >= 0 -> {
                        val output = codec.getOutputBuffer(outputIndex)!!
                        if (info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val mono = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val floats = output.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                    val frames = floats.remaining() / channels
                                    FloatArray(frames) { frame ->
                                        var sum = 0f
                                        for (channel in 0 until channels) sum += floats.get(frame * channels + channel)
                                        sum / channels
                                    }
                                }
                                else -> {
                                    val shorts = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                    val frames = shorts.remaining() / channels
                                    FloatArray(frames) { frame ->
                                        var sum = 0f
                                        for (channel in 0 until channels) sum += shorts.get(frame * channels + channel) / 32768f
                                        sum / channels
                                    }
                                }
                            }
                            val resampled = if (sampleRate == TARGET_RATE) mono else resample(mono, sampleRate, TARGET_RATE)
                            append(resampled)
                            while (count >= windowSamples) {
                                emit(windowSamples)
                                System.arraycopy(buffer, hopSamples, buffer, 0, count - hopSamples)
                                count -= hopSamples
                                offset += hopSamples
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            val covered = if (offset == 0L) 0 else windowSamples - hopSamples
            if (count - covered >= (minimum * TARGET_RATE).roundToInt()) emit(count)
            return runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDouble()?.div(1000.0) ?: 0.0
                }
            }.getOrDefault((offset + count).toDouble() / TARGET_RATE)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (input.isEmpty()) return input
        val ratio = from.toDouble() / to
        val length = max(1, (input.size / ratio).roundToInt())
        return FloatArray(length) { index ->
            val position = index * ratio
            val first = position.toInt().coerceIn(0, input.lastIndex)
            val second = (first + 1).coerceAtMost(input.lastIndex)
            val fraction = (position - first).toFloat()
            input[first] * (1f - fraction) + input[second] * fraction
        }
    }
}

data class VideoFrame(val bitmap: Bitmap, val start: Double, val end: Double)

object VideoExtractor {
    suspend fun keyframes(
        context: Context,
        path: String,
        interval: Double,
        maxPixel: Int,
        maxFrames: Int = 1200,
        onFrame: suspend (VideoFrame) -> Unit,
    ): Pair<Double, Boolean> {
        val uri = SourcePath.uri(path, context) ?: throw ExtractError("no such file")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()?.div(1000.0) ?: 0.0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (duration <= 0.0) return 0.0 to hasAudio
            val step = max(interval, duration / maxFrames)
            var time = min(0.5, duration / 2)
            var lastHash: Long? = null
            var pending: VideoFrame? = null
            while (time < duration) {
                currentCoroutineContext().ensureActive()
                val micros = (time * 1_000_000).toLong()
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPixel, maxPixel)
                        ?: retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                if (frame != null) {
                    val hash = averageHash(frame)
                    if (lastHash == null || java.lang.Long.bitCount(hash xor lastHash) > 4) {
                        lastHash = hash
                        val previous = pending
                        if (previous != null) {
                            onFrame(previous.copy(end = time))
                            previous.bitmap.recycle()
                        }
                        pending = VideoFrame(frame, time, duration)
                    } else {
                        frame.recycle()
                    }
                }
                time += step
            }
            pending?.let { onFrame(it) }
            return duration to hasAudio
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun averageHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        if (small !== bitmap) small.recycle()
        var total = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            total += (r * 30 + g * 59 + b * 11) / 100
        }
        val mean = total / 64
        var hash = 0L
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val gray = ((pixel shr 16 and 0xFF) * 30 + (pixel shr 8 and 0xFF) * 59 + (pixel and 0xFF) * 11) / 100
            if (gray > mean) hash = hash or (1L shl index)
        }
        return hash
    }
}


