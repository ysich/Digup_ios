package com.digup.android.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

object ModelFiles {
    const val REPOSITORY = "ggml-org/embeddinggemma-2-GGUF"
    const val REVISION = "bfcd298762cc34d0357ece5ebdd31791a3a374d8"

    data class ModelFile(val name: String, val bytes: Long, val sha256: String) {
        val url: String
            get() = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$name"
    }

    val text = ModelFile(
        "embeddinggemma-2-Q8_0.gguf", 309_855_456,
        "2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135",
    )
    val media = ModelFile(
        "mmproj-embeddinggemma-2-Q8_0.gguf", 554_821_024,
        "c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f",
    )
    val all = listOf(text, media)
    val totalBytes = all.sumOf { it.bytes }

    data class Available(val directory: File, val text: File, val media: File)

    fun installedDir(context: Context): File = Paths.models(context)

    fun available(context: Context): Available? {
        val folders = listOfNotNull(
            Paths.models(context),
            context.getExternalFilesDir(null)?.let { File(it, "Models") },
        )
        for (folder in folders) {
            val textFile = File(folder, text.name)
            val mediaFile = File(folder, media.name)
            if (textFile.length() == text.bytes && mediaFile.length() == media.bytes) {
                return Available(folder, textFile, mediaFile)
            }
        }
        return null
    }

    fun missing(folder: File): List<ModelFile> = all.filter { File(folder, it.name).length() != it.bytes }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(8 shl 20).use { input ->
            val buffer = ByteArray(8 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
