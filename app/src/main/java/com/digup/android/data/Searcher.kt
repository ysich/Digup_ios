package com.digup.android.data

import androidx.sqlite.SQLiteStatement
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Hybrid search: EmbeddingGemma vectors for meaning, FTS5 for words. */
class Searcher(private val store: IndexStore, loadVectors: Boolean = true) {

    private var halves = ShortArray(0)
    private var rowFile = LongArray(0)
    private var rowSegment = LongArray(0)
    private var rowModality = arrayOf<Modality>()
    private var rowSetsScale = BooleanArray(0)
    private var rowCount = 0
    private var fileKind: Map<Long, FileKind> = emptyMap()
    var vectorSource: String? = null
        private set
    var vectorsLoaded = false
        private set

    init {
        reloadFiles()
        if (loadVectors) reload()
    }

    fun reload() {
        vectorsLoaded = false
        halves = ShortArray(0)
        rowFile = LongArray(0)
        rowSegment = LongArray(0)
        rowModality = emptyArray()
        rowSetsScale = BooleanArray(0)
        rowCount = 0
        reloadFiles()

        var rows = 0
        store.db.query("SELECT COUNT(*) FROM segments WHERE vec IS NOT NULL") { rows = it.getInt(0) }
        if (rows == 0) { vectorsLoaded = true; return }
        halves = ShortArray(rows * Vectors.DIMENSION)
        rowFile = LongArray(rows)
        rowSegment = LongArray(rows)
        rowModality = Array(rows) { Modality.NONE }
        rowSetsScale = BooleanArray(rows) { true }
        val steps = IndexOptions()
        store.db.query("""
            SELECT s.id, s.file, s.modality, s.vec, s.kind, s.loc, f.kind,
                CAST(json_extract(f.info, '$.pages') AS INTEGER)
            FROM segments s JOIN files f ON f.id = s.file
            WHERE s.vec IS NOT NULL ORDER BY s.id
        """.trimIndent()) { row ->
            if (rowCount >= rows) return@query
            val blob = row.getBlob(3)
            if (blob.size != Vectors.DIMENSION * 2) return@query
            val vector = Vectors.halves(blob)
            System.arraycopy(vector, 0, halves, rowCount * Vectors.DIMENSION, vector.size)
            rowSegment[rowCount] = row.getLong(0)
            rowFile[rowCount] = row.getLong(1)
            rowModality[rowCount] = Modality.of(row.textOrNull(2))
            val kind = row.textOrNull(4)
            val place = row.doubleOrNull(5) ?: 0.0
            val fileKindValue = row.textOrNull(6)
            val pages = if (row.isNull(7)) 0 else row.getInt(7)
            val later = if (fileKindValue == FileKind.PDF.sql) {
                kind != SegmentKind.CHUNK.sql && pages > steps.bigPdfPages && place > steps.pdfStep
            } else {
                kind == SegmentKind.CHUNK.sql && place > steps.docStep
            }
            rowSetsScale[rowCount] = !later
            rowCount++
        }
        vectorsLoaded = true
    }

    fun releaseVectors() {
        halves = ShortArray(0)
        rowFile = LongArray(0)
        rowSegment = LongArray(0)
        rowModality = emptyArray()
        rowSetsScale = BooleanArray(0)
        rowCount = 0
        vectorsLoaded = false
    }

    private fun reloadFiles() {
        val kinds = HashMap<Long, FileKind>()
        store.db.query("SELECT id, kind FROM files") { kinds[it.getLong(0)] = FileKind.of(it.textOrNull(1)) }
        fileKind = kinds
        vectorSource = store.meta("vector_source")
    }

    fun search(text: String, queryVector: FloatArray?, limit: Int = 30, zGate: Double = 2.0): List<SearchHit> {
        if (queryVector != null && !vectorsLoaded) reload()
        val terms = terms(text)
        val allowed = { file: Long -> fileKind.containsKey(file) }

        var best: Map<Long, Triple<Double, Double, Int>> = emptyMap()
        if (queryVector != null && queryVector.size == Vectors.DIMENSION && rowCount > 0) {
            val scores = score(queryVector)
            val meaning = meaning(scores)
            val map = HashMap<Long, Triple<Double, Double, Int>>()
            for (row in 0 until rowCount) {
                if (!allowed(rowFile[row])) continue
                val z = meaning[row]
                val existing = map[rowFile[row]]
                if (existing == null || z > existing.first) {
                    map[rowFile[row]] = Triple(z, scores[row].toDouble(), row)
                }
            }
            best = map
        }

        val patterns = terms.mapIndexed { index, term ->
            SearchText.pattern(term, (index == terms.lastIndex) || SearchText.inflects(term))
        }.toMutableList()
        for (index in terms.indices) {
            if (terms[index].length >= 3 && !patterns[index].endsWith("*") && !isIndexed(patterns[index])) {
                patterns[index] = SearchText.pattern(terms[index], true)
            }
        }

        data class WordMatch(val file: Long, val segment: Long, val snippet: String?)
        val words = ArrayList<WordMatch>()
        val coverage = HashMap<Long, Double>()
        if (patterns.isNotEmpty()) {
            val seen = HashSet<Long>()
            store.db.query("""
                SELECT s.file, s.id, snippet(fts, 1, '«', '»', '…', 12), fts.original
                FROM fts JOIN segments s ON s.id = fts.rowid
                WHERE fts MATCH ? ORDER BY bm25(fts, 3.0, 1.0) LIMIT 1000
            """.trimIndent(), listOf(patterns.joinToString(" OR "))) { row ->
                val file = row.getLong(0)
                if (words.size < 100 && allowed(file) && seen.add(file)) {
                    val original = row.textOrNull(3)
                    val snippet = original?.let { SearchText.snippet(it, terms) } ?: row.textOrNull(2)
                    words.add(WordMatch(file, row.getLong(1), snippet))
                }
            }
            if (words.isNotEmpty()) {
                val files = words.joinToString(",") { it.file.toString() }
                for (pattern in patterns) {
                    store.db.query("""
                        SELECT DISTINCT s.file FROM fts JOIN segments s ON s.id = fts.rowid
                        WHERE fts MATCH ? AND s.file IN ($files)
                    """.trimIndent(), listOf(pattern)) {
                        coverage[it.getLong(0)] = (coverage[it.getLong(0)] ?: 0.0) + 1.0 / patterns.size
                    }
                }
            }
        }

        val lookup = isLookup(terms)
        val (meaningWeight, wordsWeight) = if (lookup) 0.5 to 4.0 else 1.0 to 2.0
        val hinted = kindHints(text)
        val candidates = HashSet<Long>()
        // A z-score can't exceed sqrt(n - 1) for n vectors. With the fixed 2.0 gate a folder of
        // four images could never return a semantic match, even when the top image was the right one.
        // Scale the gate for small libraries, then keep a few nearest files as a recall fallback.
        val scaleRows = (0 until rowCount).count { rowSetsScale[it] }
        val effectiveGate = min(zGate, max(0.6, sqrt(max(scaleRows - 1, 1).toDouble()) * 0.6))
        best.forEach { (file, value) -> if (value.first >= effectiveGate) candidates.add(file) }
        val strongMeaning = best.values.count { it.first >= effectiveGate }
        if (best.isNotEmpty() && strongMeaning < SEMANTIC_FALLBACK_LIMIT && (strongMeaning == 0 || words.isEmpty())) {
            best.entries
                .sortedByDescending { it.value.first }
                .take(min(limit, SEMANTIC_FALLBACK_LIMIT))
                .forEach { candidates.add(it.key) }
        }
        coverage.forEach { (file, share) -> if (share >= 0.5) candidates.add(file) }
        val scored = HashMap<Long, Double>()
        for (file in candidates) {
            var score = meaningWeight * max(best[file]?.first ?: 0.0, 0.0) +
                wordsWeight * (coverage[file] ?: 0.0).pow(1.5)
            if (fileKind[file]?.let { it in hinted } == true) score += 1.0
            scored[file] = score
        }

        val wordRank = HashMap<Long, Pair<Int, WordMatch>>()
        words.forEachIndexed { index, match -> wordRank.putIfAbsent(match.file, (index + 1) to match) }
        val hits = ArrayList<SearchHit>()
        for ((file, score) in scored.entries.sortedWith(
            compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key }
        ).take(limit)) {
            val semantic = best[file]
            val keyword = wordRank[file]
            val useMeaning = semantic != null && (semantic.first >= effectiveGate || keyword == null)
            val segmentId = if (useMeaning) rowSegment[semantic!!.third] else keyword?.second?.segment ?: continue
            var path = ""
            var kind = FileKind.DOC
            var modified = 0.0
            var size = 0L
            val info = linkedMapOf<String, String>()
            store.db.query("SELECT path, kind, mtime, size, info FROM files WHERE id = ?", listOf(file)) { row ->
                path = row.getText(0)
                kind = FileKind.of(row.textOrNull(1))
                modified = row.getDouble(2)
                size = row.getLong(3)
                val json = row.textOrNull(4)
                if (json != null) {
                    runCatching {
                        val objectValue = JSONObject(json)
                        objectValue.keys().forEach { key -> info[key] = objectValue.optString(key) }
                    }
                }
            }
            var segmentKind = SegmentKind.NAME
            var loc: Double? = null
            var locEnd: Double? = null
            var storedExcerpt: String? = null
            store.db.query("SELECT kind, loc, loc_end, excerpt FROM segments WHERE id = ?", listOf(segmentId)) { row ->
                segmentKind = SegmentKind.of(row.textOrNull(0))
                loc = row.doubleOrNull(1)
                locEnd = row.doubleOrNull(2)
                storedExcerpt = row.textOrNull(3)
            }
            var snippet = keyword?.second?.snippet?.takeIf { it.contains('«') }
            if (snippet == null && storedExcerpt == null && loc == null) {
                val matched = keyword?.second
                if (matched != null && matched.snippet?.contains('«') == true) {
                    var matchedKind: String? = null
                    store.db.query("SELECT kind FROM segments WHERE id = ?", listOf(matched.segment)) {
                        matchedKind = it.textOrNull(0)
                    }
                    if (matchedKind != SegmentKind.NAME.sql) snippet = matched.snippet?.let { excerpt(it, 400) }
                }
            }
            hits.add(SearchHit(
                path = path,
                kind = kind,
                score = score,
                z = semantic?.first,
                cosine = semantic?.second,
                keywordRank = keyword?.first,
                segment = segmentKind,
                segmentId = segmentId,
                loc = loc,
                locEnd = locEnd,
                excerpt = snippet ?: storedExcerpt,
                modified = modified,
                size = size,
                info = info,
                name = SourcePath.name(path) ?: path.substringAfterLast('/'),
                folder = info["folder"] ?: SourcePath.folder(path),
            ))
        }
        return hits
    }

    fun textOfSegment(id: Long): String? = store.textOfSegment(id)

    private fun score(query: FloatArray): FloatArray {
        val scores = FloatArray(rowCount)
        for (row in 0 until rowCount) {
            var dot = 0f
            val base = row * Vectors.DIMENSION
            for (i in 0 until Vectors.DIMENSION) {
                dot += Vectors.halfToFloat(halves[base + i]) * query[i]
            }
            scores[row] = dot
        }
        return scores
    }

    private fun meaning(scores: FloatArray): DoubleArray {
        val sums = HashMap<Modality, Double>()
        val counts = HashMap<Modality, Int>()
        var total = 0.0
        var count = 0
        for (row in 0 until rowCount) {
            if (!rowSetsScale[row]) continue
            val modality = rowModality[row]
            sums[modality] = (sums[modality] ?: 0.0) + scores[row]
            counts[modality] = (counts[modality] ?: 0) + 1
            total += scores[row]
            count++
        }
        val all = total / max(count, 1)
        val means = HashMap<Modality, Double>()
        for (modality in Modality.entries) {
            val n = counts[modality] ?: 0
            means[modality] = if (n >= 20) (sums[modality] ?: 0.0) / n else all
        }
        val centered = DoubleArray(rowCount) { row -> scores[row] - (means[rowModality[row]] ?: 0.0) }
        var sum = 0.0
        var scaledCount = 0
        for (row in 0 until rowCount) if (rowSetsScale[row]) { sum += centered[row]; scaledCount++ }
        val mean = sum / max(scaledCount, 1)
        var squares = 0.0
        for (row in 0 until rowCount) {
            if (rowSetsScale[row]) squares += (centered[row] - mean) * (centered[row] - mean)
        }
        val deviation = max(sqrt(squares / max(scaledCount, 1)), 0.02)
        return DoubleArray(rowCount) { row -> (centered[row] - mean) / deviation }
    }

    private fun isIndexed(pattern: String): Boolean {
        var found = false
        store.db.query("SELECT 1 FROM fts WHERE fts MATCH ? LIMIT 1", listOf(pattern)) { found = true }
        return found
    }

    companion object {
        private const val SEMANTIC_FALLBACK_LIMIT = 5

        val stopwords = setOf(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "with", "and", "or", "my", "me", "that", "this",
            "these", "those", "from", "about", "where", "what", "which", "who", "when", "is", "was", "are", "were",
            "be", "it", "its", "some", "any", "find", "show", "file", "files", "photo", "photos", "picture", "pictures",
            "image", "images", "screenshot", "screenshots", "video", "videos", "pdf", "document", "documents",
            "recording", "এবং", "ও", "এই", "সেই", "একটি", "আমার", "থেকে", "জন্য", "সাথে", "মধ্যে", "কোথায়", "কোন",
            "কী", "কি", "যে", "ছবি", "স্ক্রিনশট", "ভিডিও", "ফাইল", "পিডিএফ", "في", "من", "على", "الى", "عن", "مع",
            "هذا", "هذه", "ذلك", "التي", "الذي", "اين", "ما", "صورة", "صور", "لقطة", "شاشة", "فيديو", "ملف", "مستند",
            "图片", "照片", "图像", "截图", "视频", "文件", "文档", "录音", "歌曲", "找到", "显示", "这张", "那张",
            "一个", "我的", "里面", "当中",
        )

        fun terms(text: String): List<String> {
            val seen = HashSet<String>()
            return SearchText.normalized(text).lowercase()
                .split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
                .filter { it.isNotEmpty() && it.codePointCount(0, it.length) >= 2 &&
                    it !in stopwords && seen.add(it) }
        }

        fun ftsQuery(text: String): String? {
            val terms = terms(text)
            if (terms.isEmpty()) return null
            return terms.mapIndexed { index, term ->
                SearchText.pattern(term, index == terms.lastIndex || SearchText.inflects(term))
            }.joinToString(" OR ")
        }

        fun isLookup(terms: List<String>): Boolean = terms.isNotEmpty() && terms.all {
            it.any(Char::isDigit) && (it.any(Char::isLetter) || it.length >= 4)
        }

        fun strongCount(hits: List<SearchHit>, ratio: Double = 0.5): Int {
            val top = hits.firstOrNull()?.score ?: return hits.size
            if (top <= 0) return hits.size
            return hits.takeWhile { it.score >= top * ratio }.size
        }

        private val kindWords = mapOf(
            "screenshot" to FileKind.SCREENSHOT, "screenshots" to FileKind.SCREENSHOT,
            "photo" to FileKind.IMAGE, "photos" to FileKind.IMAGE, "picture" to FileKind.IMAGE,
            "pictures" to FileKind.IMAGE, "image" to FileKind.IMAGE, "images" to FileKind.IMAGE,
            "pdf" to FileKind.PDF, "pdfs" to FileKind.PDF, "paper" to FileKind.PDF, "papers" to FileKind.PDF,
            "document" to FileKind.DOC, "documents" to FileKind.DOC, "note" to FileKind.DOC, "notes" to FileKind.DOC,
            "video" to FileKind.VIDEO, "videos" to FileKind.VIDEO, "clip" to FileKind.VIDEO, "movie" to FileKind.VIDEO,
            "audio" to FileKind.AUDIO, "song" to FileKind.AUDIO, "podcast" to FileKind.AUDIO, "voice" to FileKind.AUDIO,
            "sound" to FileKind.AUDIO, "截图" to FileKind.SCREENSHOT, "图片" to FileKind.IMAGE, "照片" to FileKind.IMAGE,
            "图像" to FileKind.IMAGE, "视频" to FileKind.VIDEO, "文档" to FileKind.DOC, "文件" to FileKind.DOC,
            "录音" to FileKind.AUDIO, "音频" to FileKind.AUDIO, "歌曲" to FileKind.AUDIO,
            "স্ক্রিনশট" to FileKind.SCREENSHOT, "ছবি" to FileKind.IMAGE, "পিডিএফ" to FileKind.PDF,
            "নোট" to FileKind.DOC, "ভিডিও" to FileKind.VIDEO, "অডিও" to FileKind.AUDIO, "গান" to FileKind.AUDIO,
            "لقطة" to FileKind.SCREENSHOT, "صورة" to FileKind.IMAGE, "صور" to FileKind.IMAGE,
            "مستند" to FileKind.DOC, "ملاحظات" to FileKind.DOC, "فيديو" to FileKind.VIDEO, "صوت" to FileKind.AUDIO,
            "تسجيل" to FileKind.AUDIO,
        )

        fun kindHints(text: String): Set<FileKind> {
            val words = SearchText.normalized(text).lowercase()
                .split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
            return words.mapNotNull { word ->
                kindWords[word] ?: kindWords.entries.firstOrNull { (key, _) ->
                    SearchText.inflects(key) && key.codePointCount(0, key.length) >= 3 && word.startsWith(key)
                }?.value
            }.toSet()
        }
    }
}
