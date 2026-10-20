package com.digup.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** The model files added to the APK at build time, copied to app storage on first launch. */
object BundledModels {
    private const val ASSET_DIR = "models"
    private const val MARKER = "$ASSET_DIR/bundled.txt"
    private const val BUFFER = 1 shl 20

    fun available(context: Context): Boolean = runCatching {
        context.assets.open(MARKER).close()
        true
    }.getOrDefault(false)

    fun installed(context: Context): Boolean = ModelFiles.available(context) != null

    suspend fun install(context: Context, onProgress: (Float) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (!available(context)) return@withContext false
            if (installed(context)) {
                onProgress(1f)
                return@withContext true
            }

            val targetDir = Paths.models(context).apply { mkdirs() }
            val required = ModelFiles.all.sumOf { model ->
                val target = File(targetDir, model.name)
                if (target.isFile && target.length() == model.bytes) 0L else model.bytes
            }
            val usable = targetDir.usableSpace
            if (usable in 1 until required + (64L shl 20)) {
                error("not enough free space to install the bundled model")
            }
            val total = ModelFiles.totalBytes
            var complete = 0L
            for (model in ModelFiles.all) {
                val target = File(targetDir, model.name)
                if (target.isFile && target.length() == model.bytes) {
                    complete += model.bytes
                    onProgress((complete.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    continue
                }

                val part = File(targetDir, "${model.name}.part")
                part.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                var reportedStep = -1L
                context.assets.open("$ASSET_DIR/${model.name}").use { input ->
                    FileOutputStream(part).buffered(BUFFER).use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            val step = written / (8L shl 20)
                            if (step != reportedStep || written == model.bytes) {
                                reportedStep = step
                                onProgress(((complete + written).toDouble() / total).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                if (written != model.bytes) {
                    part.delete()
                    error("${model.name} has $written bytes, expected ${model.bytes}")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != model.sha256) {
                    part.delete()
                    error("${model.name} failed its SHA-256 check")
                }

                if (target.exists() && !target.delete()) error("can't replace ${model.name}")
                if (!part.renameTo(target)) error("can't install ${model.name}")
                complete += model.bytes
                onProgress((complete.toDouble() / total).toFloat().coerceIn(0f, 1f))
            }

            val ready = installed(context)
            if (ready) onProgress(1f)
            ready
        }
}
