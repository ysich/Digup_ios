package com.digup.android.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/** A thin wrapper over the bundled SQLite (3.50, FTS5 included). One connection, one thread. */
class Db(path: File) : AutoCloseable {
    val connection: SQLiteConnection
    private var depth = 0

    init {
        path.parentFile?.mkdirs()
        connection = BundledSQLiteDriver().open(path.absolutePath)
        exec("PRAGMA journal_mode = WAL")
        exec("PRAGMA synchronous = NORMAL")
        exec("PRAGMA foreign_keys = ON")
        exec("PRAGMA busy_timeout = 5000")
    }

    fun exec(sql: String) {
        connection.prepare(sql).use { it.step() }
    }

    fun query(sql: String, values: List<Any?> = emptyList(), row: (SQLiteStatement) -> Unit = {}) {
        connection.prepare(sql).use { statement ->
            bind(statement, values)
            while (statement.step()) row(statement)
        }
    }

    fun run(sql: String, values: List<Any?> = emptyList()) = query(sql, values)

    fun <T> transaction(body: () -> T): T {
        // The indexer nests small transactions (requeue, move, delete) inside one sync transaction.
        // Savepoints keep that atomic instead of failing on a second BEGIN IMMEDIATE.
        if (depth > 0) {
            val savepoint = "digup_sp_$depth"
            exec("SAVEPOINT $savepoint")
            depth++
            return try {
                val result = body()
                exec("RELEASE $savepoint")
                result
            } catch (error: Throwable) {
                runCatching { exec("ROLLBACK TO $savepoint") }
                runCatching { exec("RELEASE $savepoint") }
                throw error
            } finally {
                depth--
            }
        }
        exec("BEGIN IMMEDIATE")
        depth = 1
        return try {
            val result = body()
            exec("COMMIT")
            result
        } catch (error: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw error
        } finally {
            depth = 0
        }
    }

    fun lastInsertId(): Long {
        var value = 0L
        query("SELECT last_insert_rowid()") { value = it.getLong(0) }
        return value
    }

    override fun close() = connection.close()

    private fun bind(statement: SQLiteStatement, values: List<Any?>) {
        values.forEachIndexed { index, value ->
            val place = index + 1
            when (value) {
                null -> statement.bindNull(place)
                is Long -> statement.bindLong(place, value)
                is Int -> statement.bindLong(place, value.toLong())
                is Short -> statement.bindLong(place, value.toLong())
                is Boolean -> statement.bindLong(place, if (value) 1 else 0)
                is Double -> statement.bindDouble(place, value)
                is Float -> statement.bindDouble(place, value.toDouble())
                is String -> statement.bindText(place, value)
                is ByteArray -> statement.bindBlob(place, value)
                else -> statement.bindText(place, value.toString())
            }
        }
    }
}

fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)
fun SQLiteStatement.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
fun SQLiteStatement.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

