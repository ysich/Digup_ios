package com.digup.android

import android.content.Context
import android.net.Uri
import com.digup.android.data.Counts
import com.digup.android.data.IndexOptions
import com.digup.android.data.IndexProgress
import com.digup.android.data.IndexStore
import com.digup.android.data.Indexer
import com.digup.android.data.ModelFiles
import com.digup.android.data.Ocr
import com.digup.android.data.Paths
import com.digup.android.data.Prompts
import com.digup.android.data.SearchHit
import com.digup.android.data.Searcher
import com.digup.android.ml.Embedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class IndexOutcome(val summary: String, val counts: Counts, val bytes: Long)

/**
 * The Android host for the index and models. Indexing and searching each own a SQLite connection,
 * as the iOS engine does: WAL lets a search read while the indexer writes. Model work is serialized
 * because llama.cpp contexts are not thread-safe.
 */
class DigUpEngine(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val indexMutex = Mutex()
    private val searchMutex = Mutex()
    private val modelMutex = Mutex()
    private val searchGeneration = AtomicInteger(0)
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null
    private var queryEmbedder: Embedder? = null
    @Volatile private var lastSearchAt = 0L
    @Volatile var isIndexing = false
        private set

    suspend fun index(
        trees: List<Uri>,
        includePhotos: Boolean,
        rebuild: Boolean = false,
        retryFailed: Boolean = false,
        onProgress: suspend (IndexProgress) -> Unit,
    ): IndexOutcome = indexMutex.withLock {
        withContext(Dispatchers.Default) {
            searchGeneration.incrementAndGet()
            modelMutex.withLock {
                closeQueryEmbedder()
                isIndexing = true
            }
            try {
                val models = ModelFiles.available(app)
                    ?: throw IllegalStateException("The embedding model isn't downloaded")
                val directory = Paths.index(app)
                if (rebuild) deleteDirectory(directory)
                val store = IndexStore(directory)
                try {
                    val indexer = Indexer(app, store, IndexOptions())
                    try {
                        val summary = indexer.syncAndRun(trees, includePhotos, retryFailed, onProgress)
                        val counts = store.counts()
                        val text = buildString {
                            append("${summary.added} added, ${summary.changed} changed")
                            if (summary.moved > 0) append(", ${summary.moved} moved")
                            if (summary.removed > 0) append(", ${summary.removed} removed")
                            append(", ${summary.unchanged} unchanged")
                        }
                        IndexOutcome(text, counts, store.size)
                    } finally {
                        indexer.close()
                    }
                } finally {
                    store.close()
                }
            } finally {
                isIndexing = false
            }
        }
    }

    /**
     * Embeds the query and searches. During the first index the query model is intentionally not
     * loaded: keyword matches still work, and both models together would not fit comfortably.
     */
    suspend fun search(text: String): List<SearchHit> {
        val query = text.trim()
        if (query.isEmpty()) return emptyList()
        val ticket = searchGeneration.incrementAndGet()
        return searchMutex.withLock {
            withContext(Dispatchers.Default) {
                if (ticket != searchGeneration.get()) return@withContext emptyList()
                val store = IndexStore(Paths.index(app))
                try {
                    val searcher = Searcher(store, loadVectors = false)
                    var vectors = 0
                    store.db.query("SELECT COUNT(*) FROM segments WHERE vec IS NOT NULL") {
                        vectors = it.getInt(0)
                    }
                    var vector: FloatArray? = null
                    if (!isIndexing && vectors > 0) {
                        val models = ModelFiles.available(app)
                        if (models != null) {
                            vector = modelMutex.withLock {
                                if (isIndexing) {
                                    null
                                } else {
                                    val embedder = queryEmbedder ?: Embedder.open(models, textOnly = true)
                                        .also { queryEmbedder = it }
                                    embedder.embedTexts(listOf(Prompts.query(query))).firstOrNull()
                                }
                            }
                        }
                    }
                    if (ticket != searchGeneration.get()) return@withContext emptyList()
                    val hits = searcher.search(query, vector, limit = 30)
                    lastSearchAt = System.currentTimeMillis()
                    scheduleIdleClose()
                    hits
                } finally {
                    store.close()
                }
            }
        }
    }

    fun cancelSearches() {
        searchGeneration.incrementAndGet()
    }

    suspend fun counts(): Counts = withContext(Dispatchers.Default) {
        runCatching {
            val store = IndexStore(Paths.index(app))
            try { store.counts() } finally { store.close() }
        }.getOrDefault(Counts())
    }

    suspend fun failures(): List<String> = withContext(Dispatchers.Default) {
        runCatching {
            val store = IndexStore(Paths.index(app))
            try { store.failures() } finally { store.close() }
        }.getOrDefault(emptyList())
    }

    suspend fun segmentText(id: Long): String? = withContext(Dispatchers.Default) {
        runCatching {
            val store = IndexStore(Paths.index(app))
            try { store.textOfSegment(id) } finally { store.close() }
        }.getOrNull()
    }

    fun indexBytes(): Long = sizeOf(Paths.index(app))

    suspend fun resetIndex() = indexMutex.withLock {
        withContext(Dispatchers.Default) {
            searchGeneration.incrementAndGet()
            modelMutex.withLock { closeQueryEmbedder() }
            deleteDirectory(Paths.index(app))
            Paths.index(app).mkdirs()
        }
    }

    fun closeSearchModel() {
        idleScope.launch {
            searchMutex.withLock { modelMutex.withLock { closeQueryEmbedder() } }
        }
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = idleScope.launch {
            delay(QUERY_MODEL_IDLE_MS)
            searchMutex.withLock {
                modelMutex.withLock {
                    if (System.currentTimeMillis() - lastSearchAt >= QUERY_MODEL_IDLE_MS) closeQueryEmbedder()
                }
            }
        }
    }

    private fun closeQueryEmbedder() {
        idleJob?.cancel()
        queryEmbedder?.close()
        queryEmbedder = null
    }

    override fun close() {
        searchGeneration.incrementAndGet()
        idleScope.launch {
            modelMutex.withLock { closeQueryEmbedder() }
        }
        runCatching { Ocr.close() }
    }

    companion object {
        private const val QUERY_MODEL_IDLE_MS = 180_000L

        /** IndexStore doesn't own the directory, so size is summed here (main file + WAL). */
        fun sizeOf(directory: File): Long {
            val main = File(directory, IndexStore.MAIN_FILE)
            return main.length() + File(main.path + "-wal").length() +
                File(main.path + "-shm").length()
        }

        fun deleteDirectory(directory: File) {
            if (!directory.exists()) return
            directory.listFiles()?.forEach { child ->
                if (child.isDirectory) deleteDirectory(child) else child.delete()
            }
            directory.delete()
        }
    }
}
