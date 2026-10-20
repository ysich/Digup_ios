package com.digup.android.data

import org.json.JSONObject
import java.io.File
import java.util.Locale

data class FileRecord(
    val id: Long,
    val path: String,
    val kind: FileKind,
    val size: Long,
    val mtime: Double,
    val inode: Long,
    val device: Long,
    val state: String,
    val detail: String?,
    val info: Map<String, String>,
)

data class Counts(
    val files: Int = 0,
    val done: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val vectors: Int = 0,
    val segments: Int = 0,
)

/**
 * The on-disk index: one SQLite file with files, segments (fp16 vectors) and an FTS5 table.
 * Vectors live beside the rows that describe them, so a crash can't leave them out of step.
 */
class IndexStore(val directory: File) : AutoCloseable {
    val file = File(directory, MAIN_FILE)
    val db = Db(file)

    init { migrate() }

    private fun migrate() {
        var version = 0L
        db.query("PRAGMA user_version") { version = it.getLong(0) }
        if (version >= SCHEMA_VERSION) return
        db.transaction {
            if (version == 0L) {
                db.exec("""
                    CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)
                """.trimIndent())
                db.exec("""
                    CREATE TABLE IF NOT EXISTS files(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        path TEXT NOT NULL UNIQUE,
                        kind TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        mtime REAL NOT NULL,
                        inode INTEGER NOT NULL,
                        device INTEGER NOT NULL,
                        state TEXT NOT NULL DEFAULT 'pending',
                        detail TEXT,
                        info TEXT,
                        indexed_at REAL
                    )
                """.trimIndent())
                db.exec("CREATE INDEX IF NOT EXISTS files_inode ON files(device, inode)")
                db.exec("CREATE INDEX IF NOT EXISTS files_state ON files(state, kind)")
                db.exec("""
                    CREATE TABLE IF NOT EXISTS segments(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                        kind TEXT NOT NULL,
                        modality TEXT NOT NULL,
                        loc REAL,
                        loc_end REAL,
                        excerpt TEXT,
                        vec BLOB
                    )
                """.trimIndent())
                db.exec("CREATE INDEX IF NOT EXISTS segments_file ON segments(file)")
                try {
                    db.exec("""CREATE VIRTUAL TABLE fts USING fts5(name, body, original UNINDEXED, tokenize = "porter unicode61 remove_diacritics 2 categories 'L* N* Co M*'")""")
                } catch (_: Throwable) {
                    db.exec("""CREATE VIRTUAL TABLE fts USING fts5(name, body, original UNINDEXED, tokenize = "porter unicode61 remove_diacritics 2")""")
                }
                db.exec("PRAGMA user_version = $SCHEMA_VERSION")
            }
        }
    }

    fun meta(key: String): String? {
        var value: String? = null
        db.query("SELECT value FROM meta WHERE key = ?", listOf(key)) { value = it.getText(0) }
        return value
    }

    fun setMeta(key: String, value: String?) {
        if (value == null) db.run("DELETE FROM meta WHERE key = ?", listOf(key))
        else db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            listOf(key, value))
    }

    fun files(prefixes: List<String>): List<FileRecord> {
        if (prefixes.isEmpty()) return emptyList()
        val ranges = prefixes.distinct().map { it to it + "\uFFFF" }
        val where = ranges.joinToString(" OR ") { "(path >= ? AND path < ?)" }
        val values = ranges.flatMap { listOf(it.first, it.second) }
        val out = ArrayList<FileRecord>()
        db.query("SELECT $FILE_COLUMNS FROM files WHERE $where", values) { out.add(record(it)) }
        return out
    }

    fun allFiles(): List<FileRecord> {
        val out = ArrayList<FileRecord>()
        db.query("SELECT $FILE_COLUMNS FROM files") { out.add(record(it)) }
        return out
    }

    fun add(candidate: Candidate): Long {
        db.run("""
            INSERT INTO files(path, kind, size, mtime, inode, device, state, info)
            VALUES(?, ?, ?, ?, ?, ?, 'pending', ?)
        """.trimIndent(), listOf(candidate.path, candidate.kind.sql, candidate.size, candidate.mtime,
            candidate.inode, candidate.device, JSONObject(mapOf("folder" to candidate.folder)).toString()))
        val id = db.lastInsertId()
        addNameRow(id, candidate.name, candidate.folder)
        return id
    }

    fun requeue(id: Long, candidate: Candidate) {
        db.transaction {
            deleteSegments(id, keepingName = true)
            db.run("""
                UPDATE files SET kind = ?, size = ?, mtime = ?, inode = ?, device = ?, state = 'pending',
                    detail = NULL, info = ?, indexed_at = NULL WHERE id = ?
            """.trimIndent(), listOf(candidate.kind.sql, candidate.size, candidate.mtime, candidate.inode,
                candidate.device, JSONObject(mapOf("folder" to candidate.folder)).toString(), id))
            updateNameRow(id, candidate.name, candidate.folder)
        }
    }

    fun requeue(id: Long) {
        db.transaction {
            deleteSegments(id, keepingName = true)
            db.run("UPDATE files SET state = 'pending', detail = NULL, indexed_at = NULL WHERE id = ?", listOf(id))
        }
    }

    /** Retry extraction failures without discarding the name/FTS rows that already work. */
    fun retryFailed() {
        db.transaction {
            db.run("""
                DELETE FROM fts WHERE rowid IN (
                    SELECT s.id FROM segments s JOIN files f ON f.id = s.file
                    WHERE f.state = 'failed' AND s.kind != 'name'
                )
            """.trimIndent())
            db.run("""
                DELETE FROM segments WHERE kind != 'name'
                    AND file IN (SELECT id FROM files WHERE state = 'failed')
            """.trimIndent())
            db.run("UPDATE files SET state = 'pending', detail = NULL, indexed_at = NULL WHERE state = 'failed'")
        }
    }

    fun move(id: Long, path: String, name: String, folder: String) {
        db.transaction {
            db.run("UPDATE files SET path = ? WHERE id = ?", listOf(path, id))
            updateNameRow(id, name, folder)
        }
    }

    fun delete(id: Long) {
        db.transaction {
            db.run("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file = ?)", listOf(id))
            db.run("DELETE FROM segments WHERE file = ?", listOf(id))
            db.run("DELETE FROM files WHERE id = ?", listOf(id))
        }
    }

    fun prune(prefixes: List<String>): Int {
        if (prefixes.isEmpty()) return 0
        val keep = allFiles().filter { file -> prefixes.any { file.path.startsWith(it) } }.map { it.id }.toSet()
        val gone = allFiles().filter { it.id !in keep }
        gone.forEach { delete(it.id) }
        return gone.size
    }

    fun finish(id: Long, state: String, detail: String?, info: Map<String, String>? = null) {
        if (info == null) {
            db.run("UPDATE files SET state = ?, detail = ?, indexed_at = ? WHERE id = ?",
                listOf(state, detail, System.currentTimeMillis() / 1000.0, id))
        } else {
            db.run("UPDATE files SET state = ?, detail = ?, info = ?, indexed_at = ? WHERE id = ?",
                listOf(state, detail, JSONObject(info as Map<*, *>).toString(), System.currentTimeMillis() / 1000.0, id))
        }
    }

    fun info(id: Long): MutableMap<String, String> {
        var json: String? = null
        db.query("SELECT info FROM files WHERE id = ?", listOf(id)) { json = it.textOrNull(0) }
        val out = linkedMapOf<String, String>()
        if (json != null) {
            runCatching {
                val objectValue = JSONObject(json!!)
                objectValue.keys().forEach { key -> out[key] = objectValue.optString(key) }
            }
        }
        return out
    }

    fun pending(limit: Int, only: Set<Long>? = null): List<FileRecord> {
        if (only != null && only.isEmpty()) return emptyList()
        val filter = if (only == null) "" else " AND id IN (${only.joinToString(",")})"
        val out = ArrayList<FileRecord>()
        db.query("""
            SELECT $FILE_COLUMNS FROM files WHERE state = 'pending'$filter
            ORDER BY CASE kind WHEN 'screenshot' THEN 0 WHEN 'image' THEN 1 WHEN 'pdf' THEN 2
                WHEN 'doc' THEN 3 WHEN 'audio' THEN 4 WHEN 'video' THEN 5 ELSE 6 END, mtime DESC
            LIMIT ?
        """.trimIndent(), listOf(limit.toLong())) { out.add(record(it)) }
        return out
    }

    fun partial(limit: Int = 100): List<FileRecord> {
        val out = ArrayList<FileRecord>()
        db.query("SELECT $FILE_COLUMNS FROM files WHERE state = 'partial' ORDER BY mtime DESC LIMIT ?",
            listOf(limit.toLong())) { out.add(record(it)) }
        return out
    }

    fun pendingCount(): Int {
        var count = 0
        db.query("SELECT COUNT(*) FROM files WHERE state = 'pending'") { count = it.getInt(0) }
        return count
    }

    fun counts(): Counts {
        var files = 0; var done = 0; var pending = 0; var failed = 0
        db.query("SELECT state, COUNT(*) FROM files GROUP BY state") {
            val count = it.getInt(1)
            files += count
            when (it.getText(0)) {
                "done", "partial" -> done += count
                "pending" -> pending += count
                "failed" -> failed += count
            }
        }
        var segments = 0; var vectors = 0
        db.query("SELECT COUNT(*), COUNT(vec) FROM segments WHERE kind != 'name'") {
            segments = it.getInt(0)
            vectors = it.getInt(1)
        }
        return Counts(files, done, pending, failed, vectors, segments)
    }

    fun failures(limit: Int = 8): List<String> {
        val out = ArrayList<String>()
        db.query("""
            SELECT path, kind,
                CASE WHEN state IN ('done', 'partial') THEN 'no-vector' ELSE state END,
                COALESCE(detail, 'no detail')
            FROM files
            WHERE state IN ('failed', 'skipped', 'pending')
                OR (state IN ('done', 'partial') AND NOT EXISTS (
                    SELECT 1 FROM segments s
                    WHERE s.file = files.id AND s.kind != 'name' AND s.vec IS NOT NULL
                ))
            ORDER BY CASE state
                WHEN 'failed' THEN 0
                WHEN 'skipped' THEN 1
                WHEN 'pending' THEN 3
                ELSE 2
            END, indexed_at DESC
            LIMIT ?
        """.trimIndent(), listOf(limit.toLong())) { row ->
            val path = row.getText(0)
            val name = SourcePath.name(path) ?: path.substringAfterLast('/')
            val detail = row.textOrNull(3)?.replace(Regex("\\s+"), " ")?.take(180)
            out.add("$name · ${row.getText(1)} · ${row.getText(2)} · $detail")
        }
        return out
    }

    fun addSegment(file: Long, kind: SegmentKind, modality: Modality, loc: Double? = null,
                   locEnd: Double? = null, excerpt: String? = null, text: String? = null,
                   vector: FloatArray? = null) {
        db.run("INSERT INTO segments(file, kind, modality, loc, loc_end, excerpt, vec) VALUES(?, ?, ?, ?, ?, ?, ?)",
            listOf(file, kind.sql, if (vector == null) Modality.NONE.sql else modality.sql,
                loc, locEnd, excerpt, vector?.let { Vectors.half(it) }))
        val id = db.lastInsertId()
        if (!text.isNullOrEmpty()) {
            val body = SearchText.indexText(text)
            val original = SearchText.originalIfChanged(text)
            db.run("INSERT INTO fts(rowid, name, body, original) VALUES(?, '', ?, ?)",
                listOf(id, body, original))
        }
    }

    fun textOfSegment(id: Long): String? {
        var text: String? = null
        db.query("SELECT coalesce(original, body) FROM fts WHERE rowid = ?", listOf(id)) { text = it.getText(0) }
        return text
    }

    fun resetAll() {
        db.transaction {
            db.run("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE kind != 'name')")
            db.run("DELETE FROM segments WHERE kind != 'name'")
            db.run("UPDATE files SET state = 'pending', detail = NULL, info = NULL, indexed_at = NULL")
            setMeta("fingerprint", null)
        }
    }

    val size: Long
        get() = file.length() + File(file.path + "-wal").length()

    override fun close() = db.close()

    private fun addNameRow(file: Long, name: String, folder: String) {
        db.run("INSERT INTO segments(file, kind, modality) VALUES(?, 'name', 'none')", listOf(file))
        val segment = db.lastInsertId()
        db.run("INSERT INTO fts(rowid, name, body) VALUES(?, ?, ?)",
            listOf(segment, SearchText.indexText(name), SearchText.indexText(folder)))
    }

    private fun updateNameRow(file: Long, name: String, folder: String) {
        db.run("""
            UPDATE fts SET name = ?, body = ? WHERE rowid IN
                (SELECT id FROM segments WHERE file = ? AND kind = 'name')
        """.trimIndent(), listOf(SearchText.indexText(name), SearchText.indexText(folder), file))
    }

    private fun deleteSegments(file: Long, keepingName: Boolean) {
        val filter = if (keepingName) "AND kind != 'name'" else ""
        db.run("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file = ? $filter)", listOf(file))
        db.run("DELETE FROM segments WHERE file = ? $filter", listOf(file))
    }

    private fun record(row: androidx.sqlite.SQLiteStatement): FileRecord {
        val info = linkedMapOf<String, String>()
        val json = row.textOrNull(9)
        if (json != null) {
            runCatching {
                val objectValue = JSONObject(json)
                objectValue.keys().forEach { key -> info[key] = objectValue.optString(key) }
            }
        }
        return FileRecord(
            id = row.getLong(0),
            path = row.getText(1),
            kind = FileKind.of(row.getText(2)),
            size = row.getLong(3),
            mtime = row.getDouble(4),
            inode = row.getLong(5),
            device = row.getLong(6),
            state = row.getText(7),
            detail = row.textOrNull(8),
            info = info,
        )
    }

    companion object {
        const val MAIN_FILE = "index.sqlite"
        const val SCHEMA_VERSION = 1L
        private const val FILE_COLUMNS = "id, path, kind, size, mtime, inode, device, state, detail, info"
    }
}


