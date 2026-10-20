package com.digup.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the two pinned GGUF files with HTTP ranges, resumes partial files, verifies SHA-256. */
class ModelDownloader(private val context: Context) {

    suspend fun download(onProgress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        val folder = ModelFiles.installedDir(context).apply { mkdirs() }
        val verified = mutableSetOf<String>()
        for (model in ModelFiles.all) {
            val target = File(folder, model.name)
            if (target.length() == model.bytes) {
                verified.add(model.name)
                continue
            }
            downloadOne(model, target, verified, folder, onProgress)
        }
        check(ModelFiles.available(context) != null) { "The model files aren't complete" }
    }

    private suspend fun downloadOne(
        model: ModelFiles.ModelFile,
        target: File,
        verified: MutableSet<String>,
        folder: File,
        onProgress: (Double) -> Unit,
    ) {
        val part = File(target.path + ".part")
        var offset = if (part.exists()) part.length() else 0L
        if (offset > model.bytes) { part.delete(); offset = 0 }
        if (offset == model.bytes) {
            val hash = ModelFiles.sha256(part)
            if (hash.equals(model.sha256, ignoreCase = true)) {
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("can't move ${model.name} into place")
                verified.add(model.name)
                onProgress((verified.sumOf { name -> ModelFiles.all.first { it.name == name }.bytes }.toDouble() /
                    ModelFiles.totalBytes).coerceIn(0.0, 1.0))
                return
            }
            part.delete()
            offset = 0
        }
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "DigUp-Android/1.0")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        connection.connect()
        var response = connection.responseCode
        if (offset > 0 && response == HttpURLConnection.HTTP_OK) {
            part.delete()
            offset = 0
            connection.disconnect()
            return downloadOne(model, target, verified, folder, onProgress)
        }
        if (response == 416) {
            connection.disconnect()
            part.delete()
            throw IOException("${model.name}: the server rejected the resume offset; try again")
        }
        if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            throw IOException("HTTP $response for ${model.name}")
        }
        val totalDone = verified.sumOf { name -> ModelFiles.all.first { it.name == name }.bytes }
        var written = offset
        val buffer = ByteArray(1 shl 20)
        var lastReport = 0L
        try {
            connection.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(((totalDone + written).toDouble() / ModelFiles.totalBytes).coerceIn(0.0, 1.0))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (written != model.bytes) {
            throw IOException("${model.name}: expected ${model.bytes} bytes, got $written")
        }
        if (part.length() != model.bytes) throw IOException("${model.name}: incomplete download")
        val hash = ModelFiles.sha256(part)
        if (!hash.equals(model.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("${model.name}: checksum mismatch")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("can't move ${model.name} into place")
        verified.add(model.name)
        onProgress(((verified.sumOf { name -> ModelFiles.all.first { it.name == name }.bytes }).toDouble() /
            ModelFiles.totalBytes).coerceIn(0.0, 1.0))
    }
}
