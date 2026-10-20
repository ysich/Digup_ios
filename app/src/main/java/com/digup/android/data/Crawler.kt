package com.digup.android.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.Locale

object Rules {
    val skippedFolderNames = setOf(
        "node_modules", "bower_components", "venv", "site-packages", "__pycache__", "DerivedData",
        "Pods", "Carthage", ".git", ".gradle", "build", "dist", "vendor", "third_party",
    )

    private val imageExtensions = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "avif", "dng", "tif", "tiff")
    private val videoExtensions = setOf("mp4", "mov", "m4v", "mkv", "webm", "3gp", "avi", "ts", "mts", "m2ts")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "aiff", "wma")
    private val docExtensions = setOf("txt", "text", "md", "markdown", "rtf", "docx", "doc", "odt", "html", "htm")
    private val codeExtensions = setOf(
        "py", "ipynb", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm", "swift", "ts", "tsx", "cts",
        "js", "jsx", "mjs", "cjs", "go", "rs", "java", "kt", "kts", "scala", "rb", "php", "cs", "fs", "sh",
        "bash", "zsh", "fish", "ps1", "lua", "pl", "r", "jl", "dart", "sql", "vue", "svelte", "css", "scss",
        "sass", "less", "json", "jsonl", "yaml", "yml", "toml", "xml", "plist", "gradle", "cmake", "mk", "lock",
        "log", "csv", "tsv", "ini", "cfg", "conf", "proto", "graphql", "tf", "nix", "zig", "hs", "ex", "exs",
        "erl", "clj", "elm", "ml", "vim", "el", "asm", "metal", "glsl", "hlsl", "wgsl", "cu", "pyi", "pyc",
    )
    private val screenshotPrefixes = listOf("Screenshot", "Screen Shot", "CleanShot", "SCR-", "截屏", "截图", "屏幕截图")

    sealed class Classification {
        data class Index(val kind: FileKind) : Classification()
        data class Skip(val reason: String) : Classification()
        data object Ignore : Classification()
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isScreenshot(name: String, folder: String): Boolean {
        val lowerFolder = folder.lowercase(Locale.ROOT)
        if (lowerFolder.contains("screenshot") || lowerFolder.contains("截屏") || lowerFolder.contains("截图")) return true
        return screenshotPrefixes.any { name.startsWith(it, ignoreCase = true) }
    }

    fun classify(name: String, mime: String?, folder: String): Classification {
        if (name.startsWith('.')) return Classification.Ignore
        val ext = extension(name)
        val screenshot = isScreenshot(name, folder)
        if (screenshot) return Classification.Index(FileKind.SCREENSHOT)
        return when {
            ext in videoExtensions || mime?.startsWith("video/") == true -> Classification.Index(FileKind.VIDEO)
            ext in imageExtensions || mime?.startsWith("image/") == true -> Classification.Index(FileKind.IMAGE)
            ext == "pdf" || mime == "application/pdf" -> Classification.Index(FileKind.PDF)
            ext in audioExtensions || mime?.startsWith("audio/") == true -> Classification.Index(FileKind.AUDIO)
            ext in docExtensions || mime?.startsWith("text/") == true -> Classification.Index(FileKind.DOC)
            ext in codeExtensions -> Classification.Skip("code or data file")
            else -> Classification.Ignore
        }
    }

    fun skipFolder(name: String): Boolean = name in skippedFolderNames
}

data class CrawlResult(
    val candidates: List<Candidate>,
    val skipped: Map<String, Int> = emptyMap(),
    val skippedFolders: Map<String, Int> = emptyMap(),
)

object MediaStoreCrawler {
    private const val DEVICE = 0x4D45444941L // "MEDIA"

    fun crawl(context: Context): CrawlResult {
        val candidates = ArrayList<Candidate>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selectedOnly = Build.VERSION.SDK_INT >= 34 && granted(
            context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        val collections = listOf(
            Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, android.Manifest.permission.READ_MEDIA_IMAGES),
            Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, android.Manifest.permission.READ_MEDIA_VIDEO),
        )
        for ((collection, isVideo, permission) in collections) {
            if (!selectedOnly && !granted(context, permission)) continue
            runCatching {
                context.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.IS_PENDING} = 0",
                    null,
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn) ?: continue
                        if (name.startsWith('.')) continue
                        val folder = cursor.getString(pathColumn)?.trimEnd('/') ?: "Photos"
                        val kind = if (isVideo) FileKind.VIDEO
                            else if (Rules.isScreenshot(name, folder)) FileKind.SCREENSHOT else FileKind.IMAGE
                        candidates.add(Candidate(
                            path = MediaPath.path(isVideo, id, name),
                            kind = kind,
                            size = cursor.getLong(sizeColumn),
                            mtime = cursor.getLong(dateColumn).toDouble(),
                            inode = id,
                            device = DEVICE,
                            name = name,
                            folder = folder.ifEmpty { "Photos" },
                            mime = cursor.getString(mimeColumn),
                        ))
                    }
                }
            }
        }
        return CrawlResult(candidates)
    }

    private fun granted(context: Context, permission: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

object SafCrawler {
    private const val MAX_FILES = 200_000

    fun crawl(context: Context, trees: List<Uri>): CrawlResult {
        val candidates = ArrayList<Candidate>()
        val skipped = HashMap<String, Int>()
        val skippedFolders = HashMap<String, Int>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        for (tree in trees) {
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val stack = ArrayDeque<Pair<String, String>>()
            stack.addLast(rootId to "")
            val visited = HashSet<String>()
            while (stack.isNotEmpty() && candidates.size < MAX_FILES) {
                val (documentId, folder) = stack.removeLast()
                if (!visited.add(documentId)) continue
                val children = runCatching {
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
                }.getOrNull() ?: continue
                runCatching {
                    resolver.query(children, projection, null, null, null)?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                        val dateColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        while (cursor.moveToNext() && candidates.size < MAX_FILES) {
                            val id = cursor.getString(idColumn) ?: continue
                            val name = cursor.getString(nameColumn) ?: continue
                            if (name.startsWith('.')) continue
                            val mime = cursor.getString(mimeColumn)
                            val childFolder = if (folder.isEmpty()) name else "$folder/$name"
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (Rules.skipFolder(name)) {
                                    skippedFolders[name] = (skippedFolders[name] ?: 0) + 1
                                } else {
                                    stack.addLast(id to childFolder)
                                }
                                continue
                            }
                            when (val classification = Rules.classify(name, mime, folder)) {
                                is Rules.Classification.Index -> candidates.add(Candidate(
                                    path = SafPath.path(tree, id, name),
                                    kind = classification.kind,
                                    size = if (cursor.isNull(sizeColumn)) 0 else cursor.getLong(sizeColumn),
                                    mtime = if (cursor.isNull(dateColumn)) 0.0 else cursor.getLong(dateColumn) / 1000.0,
                                    inode = fnv1a(SafPath.path(tree, id, name)),
                                    device = fnv1a(tree.toString()),
                                    name = name,
                                    folder = folder.ifEmpty { "Files" },
                                    mime = mime,
                                ))
                                is Rules.Classification.Skip -> skipped[classification.reason] =
                                    (skipped[classification.reason] ?: 0) + 1
                                Rules.Classification.Ignore -> Unit
                            }
                        }
                    }
                }
            }
        }
        return CrawlResult(candidates, skipped, skippedFolders)
    }
}

object Crawler {
    fun crawl(context: Context, trees: List<Uri>, includePhotos: Boolean): CrawlResult {
        val saf = if (trees.isEmpty()) CrawlResult(emptyList()) else SafCrawler.crawl(context, trees)
        val media = if (includePhotos) MediaStoreCrawler.crawl(context) else CrawlResult(emptyList())
        val skipped = HashMap<String, Int>()
        saf.skipped.forEach { (key, value) -> skipped[key] = (skipped[key] ?: 0) + value }
        media.skipped.forEach { (key, value) -> skipped[key] = (skipped[key] ?: 0) + value }
        return CrawlResult(saf.candidates + media.candidates, skipped, saf.skippedFolders)
    }

    fun prefixes(trees: List<Uri>, includePhotos: Boolean): List<String> {
        val out = ArrayList<String>()
        trees.forEach { tree -> out.add(SafPath.prefix(tree)) }
        if (includePhotos) {
            out.add("media://image/")
            out.add("media://video/")
        }
        return out
    }
}
