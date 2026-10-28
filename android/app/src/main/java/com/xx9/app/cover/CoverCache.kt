package com.xx9.app.cover

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

object CoverCache {
    @Volatile private var loader: ImageLoader? = null
    @Volatile private var http: OkHttpClient? = null

    fun httpClient(): OkHttpClient =
        http ?: OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
            .also { http = it }

    fun imageLoader(context: Context): ImageLoader {
        loader?.let { return it }
        val appCtx = context.applicationContext
        val diskDir = File(appCtx.cacheDir, "coil_cover_cache")
        val built = ImageLoader.Builder(appCtx)
            .okHttpClient { httpClient() }
            .components { add(CoverXorFetcher.Factory(httpClient())) }
            .memoryCache {
                MemoryCache.Builder(appCtx).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskDir)
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
        loader = built
        return built
    }
}
