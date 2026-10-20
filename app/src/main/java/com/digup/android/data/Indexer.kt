package com.digup.android.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.digup.android.ml.Embedder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class IndexProgress(val phase: String, val done: Int, val total: Int, val path: String?) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

data class SyncSummary(
    var added: Int = 0,
    var changed: Int = 0,
    var moved: Int = 0,
    var removed: Int = 0,
    var unchanged: Int = 0,
    val queued: MutableList<Long> = ArrayList(),
)

/**
 * Keeps the index in step with MediaStore and the chosen SAF trees, then extracts and embeds
 * whatever is pending. The full model is opened lazily and freed when indexing stops.
 */
class Indexer(
    private val context: Context,
    private val store: IndexStore,
    private val options: IndexOptions = IndexOptions(),
) : AutoCloseable {
    private var embedder: Embedder? = null

    suspend fun syncAndRun(
        trees: List<Uri>,
        includePhotos: Boolean,
        retryFailed: Boolean = false,
        onProgress: suspend (IndexProgress) -> Unit,
    ): SyncSummary {
        val summary = sync(trees, includePhotos)
        if (retryFailed) store.retryFailed()
        val pending = store.pendingCount()
        val partial = store.partial(1).size
        if (pending == 0 && partial == 0) return summary

        ensureEmbedder()
        runPending(onProgress)
        finishLongFiles(onProgress)
        return summary
    }

    fun sync(trees: List<Uri>, includePhotos: Boolean): SyncSummary {
        val crawl = Crawler.crawl(context, trees, includePhotos)
        val prefixes = Crawler.prefixes(trees, includePhotos)
        val existing = store.files(prefixes)
        val byPath = existing.associateBy { it.path }
        val byInode = HashMap<String, FileRecord>()
        existing.forEach { byInode["${it.device}:${it.inode}"] = it }
        val seen = crawl.candidates.mapTo(HashSet()) { it.path }
        val summary = SyncSummary()

        store.db.transaction {
            for (candidate in crawl.candidates) {
                val row = byPath[candidate.path]
                if (row != null) {
                    if (row.size != candidate.size || abs(row.mtime - candidate.mtime) > 0.001 ||
                        row.kind != candidate.kind
                    ) {
                        store.requeue(row.id, candidate)
                        summary.changed++
                        summary.queued.add(row.id)
                    } else {
                        summary.unchanged++
                    }
                    continue
                }
                val moved = byInode["${candidate.device}:${candidate.inode}"]
                if (moved != null && moved.path !in seen) {
                    store.move(moved.id, candidate.path, candidate.name, candidate.folder)
                    summary.moved++
                    continue
                }
                val id = store.add(candidate)
                byInode["${candidate.device}:${candidate.inode}"] = FileRecord(
                    id, candidate.path, candidate.kind, candidate.size, candidate.mtime,
                    candidate.inode, candidate.device, "pending", null, emptyMap(),
                )
                summary.added++
                summary.queued.add(id)
            }
            for (row in existing) {
                if (row.path !in seen) {
                    store.delete(row.id)
                    summary.removed++
                }
            }
        }
        store.prune(prefixes)
        return summary
    }

    suspend fun finishLongFiles(onProgress: suspend (IndexProgress) -> Unit) {
        var pass = 0
        while (pass < 500) {
            currentCoroutineContext().ensureActive()
            val partials = store.partial(20)
            if (partials.isEmpty()) break
            var progressed = false
            for ((index, file) in partials.withIndex()) {
                currentCoroutineContext().ensureActive()
                val before = store.info(file.id)["read_to"]
                processFile(file)
                val after = store.info(file.id)["read_to"]
                if (before != after) progressed = true
                onProgress(IndexProgress("Finishing long files", index + 1, partials.size, file.path))
            }
            if (!progressed) break
            pass++
        }
    }

    private suspend fun runPending(onProgress: suspend (IndexProgress) -> Unit) {
        val total = store.pendingCount()
        var done = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = store.pending(40)
            if (batch.isEmpty()) break
            for (file in batch) {
                currentCoroutineContext().ensureActive()
                processFile(file)
                done++
                onProgress(IndexProgress("Indexing", done, max(total, done), file.path))
            }
        }
    }

    private suspend fun processFile(file: FileRecord) {
        try {
            when (file.kind) {
                FileKind.IMAGE, FileKind.SCREENSHOT -> image(file)
                FileKind.PDF -> pdf(file)
                FileKind.DOC -> doc(file)
                FileKind.AUDIO -> audio(file)
                FileKind.VIDEO -> video(file)
                FileKind.CODE -> store.finish(file.id, "skipped", "code isn't indexed on Android")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (outOfMemory: OutOfMemoryError) {
            throw outOfMemory
        } catch (error: Throwable) {
            val message = (error.message ?: error.toString()).take(300)
            Log.w("DigUpIndex", "${file.kind.sql} ${SourcePath.name(file.path)}: $message", error)
            val skipped = message.contains("small image") || message.contains("password-protected") ||
                message.contains("no text") || message.contains("legacy .doc") ||
                message.contains("isn't read")
            store.finish(file.id, if (skipped) "skipped" else "failed", message)
        }
    }

    private suspend fun image(file: FileRecord) {
        val screenshot = file.kind == FileKind.SCREENSHOT
        val prepared = ImageExtractor.prepare(
            context, file.path,
            maxPixel = if (screenshot) 1536 else 1024,
            minSide = options.minImageSide,
        )
        try {
            val vector = embedder().embedBitmap(prepared.bitmap,
                if (screenshot) options.screenshotBudget else options.photoBudget)
            val ocr = if (screenshot) Ocr.text(prepared.bitmap) else ""
            store.db.transaction {
                store.addSegment(file.id, SegmentKind.IMAGE, Modality.IMAGE, vector = vector)
                if (ocr.isNotEmpty()) {
                    store.addSegment(file.id, SegmentKind.OCR, Modality.TEXT, excerpt = excerpt(ocr), text = ocr)
                }
                val info = linkedMapOf(
                    "folder" to (file.info["folder"] ?: SourcePath.folder(file.path)),
                    "width" to prepared.width.toString(),
                    "height" to prepared.height.toString(),
                )
                prepared.taken?.let { info["taken"] = it }
                if (screenshot) info["ocr_chars"] = ocr.length.toString()
                store.finish(file.id, "done", null, info)
            }
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private suspend fun pdf(file: FileRecord) {
        val pagesTotal = PdfExtractor.pageCount(context, file.path)
        if (pagesTotal <= 0) {
            store.finish(file.id, "skipped", "no pages")
            return
        }
        val info = store.info(file.id)
        val maxPage = min(pagesTotal, options.maxPages)
        val first = (info["read_to"]?.toIntOrNull() ?: 0).coerceIn(0, maxPage - 1)
        val step = if (pagesTotal > options.bigPdfPages) options.pdfStep else options.maxPages
        val last = min(first + step - 1, maxPage - 1)
        val content = PdfExtractor.text(context, file.path, first, last, options.minPageText)
        val unread = last < maxPage - 1
        if (content.pages.isEmpty()) {
            store.finish(file.id, "skipped", "no pages")
            return
        }
        val title = content.title ?: (SourcePath.name(file.path) ?: file.path).substringBeforeLast('.')
        val textPages = content.pages.filter { !it.needsImage && SearchText.hasWords(it.text) }
        val vectors = if (textPages.isEmpty()) emptyMap() else {
            embedder().embedTexts(textPages.map { Prompts.document(title, it.text.take(4000)) })
                .mapIndexed { index, vector -> textPages[index].number to vector }
                .toMap()
        }
        val renderedPages = HashMap<Int, Pair<String, FloatArray>>()
        for (page in content.pages) {
            if (!page.needsImage) continue
            val bitmap = PdfExtractor.render(context, file.path, page.number, 1400) ?: continue
            try {
                val ocr = Ocr.text(bitmap)
                val vector = embedder().embedBitmap(bitmap, options.pageBudget)
                renderedPages[page.number] = ocr to vector
            } finally {
                bitmap.recycle()
            }
        }
        var scanned = info["scanned_pages"]?.toIntOrNull() ?: 0
        store.db.transaction {
            for (page in content.pages) {
                if (!page.needsImage) {
                    store.addSegment(file.id, SegmentKind.PAGE, Modality.TEXT,
                        loc = page.number.toDouble(), excerpt = excerpt(page.text), text = page.text,
                        vector = vectors[page.number])
                    continue
                }
                val rendered = renderedPages[page.number] ?: continue
                val ocr = rendered.first
                store.addSegment(file.id, SegmentKind.PAGE_IMAGE, Modality.IMAGE,
                    loc = page.number.toDouble(), excerpt = ocr.takeIf { it.isNotBlank() }?.let { excerpt(it) },
                    text = ocr.takeIf { it.isNotBlank() }, vector = rendered.second)
                scanned++
            }
            val updated = linkedMapOf(
                "folder" to (file.info["folder"] ?: SourcePath.folder(file.path)),
                "pages" to pagesTotal.toString(),
                "scanned_pages" to scanned.toString(),
            )
            if (unread) {
                updated["read_to"] = (last + 1).toString()
                updated["read_end"] = maxPage.toString()
            }
            store.finish(file.id, if (unread) "partial" else "done",
                if (unread) "read to page ${last + 1} of $pagesTotal" else null, updated)
        }
    }

    private suspend fun doc(file: FileRecord) {
        val text = DocExtractor.text(context, file.path)
        val chunks = DocExtractor.chunks(text, options.maxChunks + 1)
        if (chunks.isEmpty()) {
            store.finish(file.id, "skipped", "no text")
            return
        }
        val info = store.info(file.id)
        val total = min(chunks.size, options.maxChunks)
        val first = (info["read_to"]?.toIntOrNull() ?: 0).coerceIn(0, max(0, total - 1))
        val last = min(first + options.docStep, total)
        val slice = if (first >= chunks.size) emptyList() else chunks.subList(first, min(last, chunks.size))
        val title = (SourcePath.name(file.path) ?: file.path).substringBeforeLast('.')
        val worded = slice.indices.filter { SearchText.hasWords(slice[it]) }
        val vectors = if (worded.isEmpty()) emptyList() else {
            embedder().embedTexts(worded.map { Prompts.document(title, slice[it]) })
        }
        val byIndex = worded.zip(vectors).toMap()
        val unread = last < total
        store.db.transaction {
            for ((offset, chunk) in slice.withIndex()) {
                store.addSegment(file.id, SegmentKind.CHUNK, Modality.TEXT,
                    loc = (first + offset + 1).toDouble(), locEnd = (first + offset + 1).toDouble(),
                    excerpt = excerpt(chunk), text = chunk, vector = byIndex[offset])
            }
            val updated = linkedMapOf(
                "folder" to (file.info["folder"] ?: SourcePath.folder(file.path)),
                "chunks" to total.toString(),
                "chars" to text.length.toString(),
            )
            if (unread) updated["read_to"] = last.toString()
            store.finish(file.id, if (unread) "partial" else "done",
                if (unread) "read to chunk $last of $total" else null, updated)
        }
    }

    private suspend fun audio(file: FileRecord) {
        var windows = 0
        val duration = AudioExtractor.windows(context, file.path, options.audioWindow, options.audioHop) { window ->
            val vector = embedder().embedAudio(window.samples)
            store.addSegment(file.id, SegmentKind.AUDIO, Modality.AUDIO,
                loc = window.start, locEnd = window.end, vector = vector)
            windows++
        }
        if (windows == 0) {
            store.finish(file.id, "skipped", "silent or shorter than 2 s")
        } else {
            store.finish(file.id, "done", null, linkedMapOf(
                "folder" to (file.info["folder"] ?: SourcePath.folder(file.path)),
                "duration" to String.format("%.1f", duration),
            ))
        }
    }

    private suspend fun video(file: FileRecord) {
        var frames = 0
        val (duration, hasAudio) = VideoExtractor.keyframes(
            context, file.path, options.frameInterval, 1024,
        ) { frame ->
            try {
                val vector = embedder().embedBitmap(frame.bitmap, options.frameBudget)
                store.addSegment(file.id, SegmentKind.FRAME, Modality.IMAGE,
                    loc = frame.start, locEnd = frame.end, vector = vector)
                frames++
            } finally {
                frame.bitmap.recycle()
            }
        }
        var soundWindows = 0
        if (hasAudio) {
            AudioExtractor.windows(context, file.path, options.audioWindow, options.audioHop) { window ->
                val vector = embedder().embedAudio(window.samples)
                store.addSegment(file.id, SegmentKind.AUDIO, Modality.AUDIO,
                    loc = window.start, locEnd = window.end, vector = vector)
                soundWindows++
            }
        }
        if (frames == 0 && soundWindows == 0) {
            store.finish(file.id, "skipped", "no frames or sound")
        } else {
            store.finish(file.id, "done", null, linkedMapOf(
                "folder" to (file.info["folder"] ?: SourcePath.folder(file.path)),
                "duration" to String.format("%.1f", duration),
                "frames" to frames.toString(),
                "sound_windows" to soundWindows.toString(),
            ))
        }
    }

    private fun ensureEmbedder() {
        if (embedder != null) return
        val models = ModelFiles.available(context)
            ?: throw IllegalStateException("The embedding model isn't downloaded")
        val fingerprint = "${Embedder.VECTOR_SOURCE} | ${options.fingerprint}"
        val indexed = store.meta("fingerprint")
        if (indexed != null && indexed != fingerprint) store.resetAll()
        store.setMeta("fingerprint", fingerprint)
        store.setMeta("vector_source", Embedder.VECTOR_SOURCE)
        embedder = Embedder.open(models, textOnly = false)
    }

    private fun embedder(): Embedder = embedder ?: error("embedder not started")

    fun closeWorker() {
        embedder?.close()
        embedder = null
    }

    override fun close() {
        closeWorker()
    }
}
