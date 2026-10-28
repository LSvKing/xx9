package com.xx9.app.cover

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * 对齐 web/app.js loadCover：img_secret==1 时直连 CDN，首字节为 key，其余 XOR，丢首字节；
 * 失败则回退 proxyUrl（/api/cover/{id}）。
 */
class CoverXorFetcher(
    private val data: CoverRequest,
    private val options: Options,
    private val client: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (data.imgSecret == "1" && !data.cdnUrl.isNullOrBlank()) {
            runCatching { fetchXor(data.cdnUrl!!) }.getOrNull()?.let { return@withContext it }
        } else if (data.imgSecret != "1" && !data.cdnUrl.isNullOrBlank()) {
            runCatching { fetchRaw(data.cdnUrl!!) }.getOrNull()?.let { return@withContext it }
        }
        val proxy = data.proxyUrl ?: return@withContext null
        return@withContext fetchRaw(proxy)
    }

    private fun fetchXor(url: String): SourceResult {
        val bytes = httpBytes(url)
        require(bytes.size >= 2)
        val k = bytes[0].toInt() and 0xff
        for (i in 1 until bytes.size) {
            bytes[i] = (bytes[i].toInt() xor k).toByte()
        }
        val body = bytes.copyOfRange(1, bytes.size)
        val mime = sniffMime(body)
        val buf = Buffer().write(body)
        return SourceResult(
            source = ImageSource(buf, options.context),
            mimeType = mime,
            dataSource = DataSource.NETWORK,
        )
    }

    private fun fetchRaw(url: String): SourceResult {
        val bytes = httpBytes(url)
        val mime = sniffMime(bytes)
        val buf = Buffer().write(bytes)
        return SourceResult(
            source = ImageSource(buf, options.context),
            mimeType = mime,
            dataSource = DataSource.NETWORK,
        )
    }

    private fun httpBytes(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "xx9-android-native/2.0")
            .header("X-XX9-App", "1")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            return resp.body?.bytes() ?: error("empty body")
        }
    }

    private fun sniffMime(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()) return "image/jpeg"
        if (b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4e.toByte()) return "image/png"
        if (b.size >= 3 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte()) return "image/gif"
        if (b.size >= 12 && b[0] == 0x52.toByte() && b[8] == 0x57.toByte()) return "image/webp"
        return "image/jpeg"
    }

    class Factory(private val client: OkHttpClient) : Fetcher.Factory<CoverRequest> {
        override fun create(data: CoverRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverXorFetcher(data, options, client)
    }
}
