package com.digup.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class DigUpApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
