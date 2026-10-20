package com.digup.android.ml

import android.graphics.Bitmap

object LlamaBridge {
    init {
        System.loadLibrary("digup_llama")
    }

    external fun nativeInit(textPath: String, mediaPath: String, threads: Int, textOnly: Boolean): Long
    external fun nativeClose(handle: Long)
    external fun nativeAudioSampleRate(handle: Long): Int
    external fun nativeEmbedTexts(handle: Long, texts: Array<String>): Array<FloatArray>
    external fun nativeEmbedBitmap(handle: Long, bitmap: Bitmap, budget: Int): FloatArray
    external fun nativeEmbedAudio(handle: Long, samples: FloatArray): FloatArray
}
