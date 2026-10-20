package com.digup.android.ml

import android.graphics.Bitmap
import com.digup.android.data.ModelFiles

/** One llama.cpp process, one input at a time. Free it when indexing ends. */
class Embedder private constructor(private var handle: Long, val textOnly: Boolean) : AutoCloseable {

    val audioSampleRate: Int
        get() = if (handle == 0L) 0 else LlamaBridge.nativeAudioSampleRate(handle)

    fun embedTexts(texts: List<String>): List<FloatArray> {
        if (handle == 0L) error("embedder is closed")
        if (texts.isEmpty()) return emptyList()
        return LlamaBridge.nativeEmbedTexts(handle, texts.toTypedArray()).toList()
    }

    fun embedBitmap(bitmap: Bitmap, budget: Int): FloatArray {
        if (handle == 0L) error("embedder is closed")
        return LlamaBridge.nativeEmbedBitmap(handle, bitmap, budget)
    }

    fun embedAudio(samples: FloatArray): FloatArray {
        if (handle == 0L) error("embedder is closed")
        return LlamaBridge.nativeEmbedAudio(handle, samples)
    }

    override fun close() {
        if (handle != 0L) {
            LlamaBridge.nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        fun open(models: ModelFiles.Available, textOnly: Boolean): Embedder {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val handle = LlamaBridge.nativeInit(
                models.text.absolutePath,
                if (textOnly) "" else models.media.absolutePath,
                threads,
                textOnly,
            )
            check(handle != 0L) { "llama.cpp couldn't load the model" }
            return Embedder(handle, textOnly)
        }

        const val VECTOR_SOURCE = "google/embeddinggemma-2@bfcd298 llama.cpp-vectors1 text-q8_0+mmproj-q8_0"
    }
}
