package com.xx9.app.cast

import android.net.Uri
import com.xx9.app.data.Xx9Api
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 局域网 HTTP 代理（参考 qinghe CastProxy，按 xx9 裁剪）：
 * 电视从本机拉流，代理回源上游 —— 解决「上游签址 ~55min 过期后电视直拉 403」。
 *
 * - HLS playlist 改写：分片 / EXT-X-KEY / EXT-X-MAP / 变体 / 音轨字幕全部指回本代理。
 * - playlist 回源非 2xx（签名过期）→ 调 Xx9Api.video(vid) 重取同线路新鲜地址，换 URL 重试一次。
 * - 会话 token 不可猜测（/<token>/…），未知 token 一律 404；TTL 闲置回收。
 * - 无鉴权头注入（上游签址本来就公开 CDN），无本地文件服务（xx9 用不上）。
 */
object CastProxy {
    private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    private const val SESSION_TTL_MS = 45L * 60_000L
    private const val IO_BUFFER = 32 * 1024

    enum class Kind { PLAYLIST, SEGMENT, KEY, RAW }

    class Res(
        @Volatile var url: String,
        val kind: Kind,
    )

    class Session(
        val videoId: Int,
        val lineIndex: Int,
        val api: Xx9Api,
        /** `http://ip:port/<token>` — 改写后 URI 的基址。 */
        val proxyBase: String,
    ) {
        val resources = ConcurrentHashMap<String, Res>()
        val touchedAt = AtomicLong(System.currentTimeMillis())
        private val ids = AtomicInteger(0)
        fun addRes(res: Res): String = "r${ids.getAndIncrement()}".also { resources[it] = res }
        fun addResWithId(id: String, res: Res) { resources[id] = res }
    }

    data class Entry(val token: String, val url: String, val mime: String)

    /** 比 UI 长命：stop/release 不能依赖 sheet/页面协程存活。 */
    internal val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "cast-io").apply { isDaemon = true }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    @Synchronized
    private fun ensureStarted(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", 0), 64)
        server = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val conn = socket.accept()
                    pool.execute { runCatching { handle(conn) }; runCatching { conn.close() } }
                } catch (_: IOException) {
                    if (!socket.isClosed) Unit else break
                }
            }
        }).apply { isDaemon = true; name = "cast-accept"; start() }
        return socket.localPort
    }

    /** 注册一路投屏流；返回交给电视的 LAN URL。null = 拿不到本机局域网 IP（没连 Wi-Fi）。 */
    fun register(videoId: Int, lineIndex: Int, mediaUrl: String, api: Xx9Api): Entry? {
        val ip = lanAddress() ?: return null
        val port = ensureStarted()
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val hls = mediaUrl.substringBefore('?').substringBefore('#').endsWith(".m3u8", true)
        val session = Session(videoId, lineIndex, api, "http://$ip:$port/$token")
        session.addResWithId("entry", Res(mediaUrl, if (hls) Kind.PLAYLIST else Kind.RAW))
        sessions[token] = session
        evictOld()
        val path = if (hls) "media.m3u8" else "media.mp4"
        return Entry(token, "http://$ip:$port/$token/$path",
            if (hls) "application/vnd.apple.mpegurl" else "video/mp4")
    }

    fun release(token: String?) {
        token ?: return
        sessions.remove(token)
    }

    private fun evictOld() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.touchedAt.get() > SESSION_TTL_MS }
    }

    /** 电视回连本机用的 IPv4：wlan* 优先，再 site-local，再任意非回环 IPv4。 */
    fun lanAddress(): String? {
        val ifs = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
        } catch (_: Exception) { null } ?: return null
        var siteLocal: String? = null
        var fallback: String? = null
        for (ni in ifs) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses.toList()) {
                if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val ip = addr.hostAddress ?: continue
                if (ni.name.startsWith("wlan")) return ip
                if (siteLocal == null && addr.isSiteLocalAddress) siteLocal = ip
                if (fallback == null) fallback = ip
            }
        }
        return siteLocal ?: fallback
    }

    // ---------- HTTP ----------

    private fun handle(socket: Socket) {
        socket.soTimeout = 20_000
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1), 4096)
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.trim().split(Regex("\\s+"))
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep > 0) headers[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
        }
        if (method != "GET" && method != "HEAD") {
            sendSimple(output, 501, "text/plain", "not implemented".toByteArray()); return
        }
        val head = method == "HEAD"
        val path = rawTarget.substringBefore('?').substringBefore('#')
        val segs = path.trim('/').split('/').filter { it.isNotBlank() }
            .map { runCatching { Uri.decode(it) }.getOrDefault(it) }
        val session = segs.firstOrNull()?.let(sessions::get) ?: run {
            sendSimple(output, 404, "text/plain", "not found".toByteArray()); return
        }
        session.touchedAt.set(System.currentTimeMillis())
        try {
            route(session, segs.drop(1), headers["range"], output, head)
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
            // 电视中途断流是常态
        } catch (_: Throwable) {
            runCatching { sendSimple(output, 500, "text/plain", "proxy error".toByteArray()) }
        }
    }

    private fun route(session: Session, segs: List<String>, range: String?, out: OutputStream, head: Boolean) {
        val res = when {
            segs.isEmpty() -> session.resources["entry"]
            segs[0] == "media.m3u8" || segs[0] == "media.mp4" -> session.resources["entry"]
            segs.size == 2 && segs[0] in listOf("p", "k", "r") -> session.resources[segs[1]]
            else -> null
        } ?: run {
            sendSimple(out, 404, "text/plain", "not found".toByteArray()); return
        }
        when (res.kind) {
            Kind.PLAYLIST -> servePlaylist(session, res, out, head)
            Kind.SEGMENT, Kind.KEY, Kind.RAW -> serveRaw(session, res, range, out, head)
        }
    }

    private fun upstream(url: String, range: String?, head: Boolean): Request {
        val b = Request.Builder().url(url).header("Accept", "*/*")
        if (head) b.head()
        if (!range.isNullOrBlank()) b.header("Range", range)
        return b.build()
    }

    /**
     * 拉 playlist；回源失败（签址过期等）先走 /api/video/{vid} 重取同线路新地址再试一次。
     * 返回 (httpCode, playlistText)；text 为 null 即不可恢复。
     */
    private fun fetchPlaylist(session: Session, res: Res): Pair<Int, String?> {
        var code = 0
        var text: String? = null
        repeat(2) { attempt ->
            client.newCall(upstream(res.url, null, false)).execute().use { resp ->
                code = resp.code
                if (resp.isSuccessful) {
                    val finalUrl = resp.request.url.toString()
                    text = resp.body?.byteStream()?.use { readAtMost(it, MAX_PLAYLIST_BYTES) }
                        ?.toString(Charsets.UTF_8)
                    if (text?.contains("#EXTM3U") == true) {
                        // 以最终 URL 为 base 改写 —— 重定向后的相对路径才对
                        text = rewritePlaylist(session, text!!, finalUrl)
                        return code to text
                    }
                    text = null
                    return code to null            // 200 但不是 m3u8：不重试
                }
            }
            if (attempt == 0) refreshEntryUrl(session, res) else return code to null
        }
        return code to null
    }

    /** 签址过期 → 重新取详情，换回同一线路（取不到就用第一条）的 fresh URL。 */
    private fun refreshEntryUrl(session: Session, res: Res) {
        val detail = runBlocking {
            runCatching { session.api.video(session.videoId) }.getOrNull()
        } ?: return
        val fresh = detail.lines.getOrNull(session.lineIndex)?.url
            ?: detail.lines.firstOrNull()?.url
            ?: return
        if (fresh != res.url) res.url = fresh
    }

    private fun servePlaylist(session: Session, res: Res, out: OutputStream, head: Boolean) {
        val (code, text) = fetchPlaylist(session, res)
        when {
            text != null -> sendBytes(out, 200, "application/vnd.apple.mpegurl",
                text.toByteArray(Charsets.UTF_8), head)
            code == 0 -> sendSimple(out, 502, "text/plain", "upstream error".toByteArray())
            else -> sendSimple(out, code, "text/plain", "upstream $code".toByteArray())
        }
    }

    private fun serveRaw(session: Session, res: Res, range: String?, out: OutputStream, head: Boolean) {
        client.newCall(upstream(res.url, range, head)).execute().use { resp ->
            val headers = mutableListOf<Pair<String, String>>()
            resp.header("Content-Type")?.let { headers += "Content-Type" to it }
            (resp.header("Content-Length") ?: resp.body?.contentLength()?.takeIf { it >= 0 }?.toString())
                ?.let { headers += "Content-Length" to it }
            resp.header("Content-Range")?.let { headers += "Content-Range" to it }
            resp.header("Accept-Ranges")?.let { headers += "Accept-Ranges" to it }
                ?: headers.add("Accept-Ranges" to "bytes")
            sendStream(out, resp.code, headers, resp.body?.byteStream(), head)
        }
    }

    /** 改写 playlist：所有内嵌 URI（分片/KEY/MAP/变体/音轨）指回本代理。 */
    internal fun rewritePlaylist(session: Session, text: String, baseUrl: String): String {
        val proxyBase = session.proxyBase
        val out = StringBuilder(text.length + 512)
        for (rawLine in text.replace("﻿", "").split('\n')) {
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY") -> {
                    if (line.contains("METHOD=NONE") || !line.contains("URI=")) {
                        out.append(line)
                    } else {
                        val target = extractUri(line)?.let { resolveUri(baseUrl, it) }
                        if (target == null) out.append(line) else {
                            val id = session.addRes(Res(target, Kind.KEY))
                            out.append(URI_ATTR.replace(line, "URI=\"$proxyBase/k/$id\""))
                        }
                    }
                }
                line.startsWith("#EXT-X-MAP") && line.contains("URI=") -> {
                    val target = extractUri(line)?.let { resolveUri(baseUrl, it) }
                    if (target == null) out.append(line) else {
                        val id = session.addRes(Res(target, Kind.SEGMENT))
                        out.append(URI_ATTR.replace(line, "URI=\"$proxyBase/r/$id\""))
                    }
                }
                line.startsWith("#EXT-X-MEDIA") && line.contains("URI=") -> {
                    val target = extractUri(line)?.let { resolveUri(baseUrl, it) }
                    if (target == null) out.append(line) else {
                        val playlist = target.substringBefore('?').endsWith(".m3u8", true)
                        val id = session.addRes(Res(target, if (playlist) Kind.PLAYLIST else Kind.RAW))
                        out.append(URI_ATTR.replace(line,
                            "URI=\"$proxyBase/${if (playlist) "p" else "r"}/$id\""))
                    }
                }
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") && line.contains("URI=") -> {
                    val target = extractUri(line)?.let { resolveUri(baseUrl, it) }
                    if (target == null) out.append(line) else {
                        val id = session.addRes(Res(target, Kind.SEGMENT))
                        out.append(URI_ATTR.replace(line, "URI=\"$proxyBase/r/$id\""))
                    }
                }
                line.startsWith("#") || line.isBlank() -> out.append(line)
                else -> {
                    val target = resolveUri(baseUrl, line.trim())
                    val playlist = target.substringBefore('?').endsWith(".m3u8", true)
                    val id = session.addRes(Res(target, if (playlist) Kind.PLAYLIST else Kind.SEGMENT))
                    out.append("$proxyBase/${if (playlist) "p" else "r"}/$id")
                }
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun extractUri(line: String): String? =
        URI_ATTR.find(line)?.value?.removePrefix("URI=")?.trim()?.removeSurrounding("\"")

    private fun resolveUri(base: String, ref: String): String {
        val clean = ref.trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        return runCatching { URI(base).resolve(clean).toString() }.getOrDefault(clean)
    }

    // ---------- wire ----------

    private fun sendBytes(out: OutputStream, code: Int, contentType: String, body: ByteArray, head: Boolean) {
        sendStream(out, code, listOf(
            "Content-Type" to contentType,
            "Content-Length" to body.size.toString(),
            "Accept-Ranges" to "bytes",
        ), ByteArrayInputStream(body), head)
    }

    private fun sendSimple(out: OutputStream, code: Int, contentType: String, body: ByteArray) {
        sendStream(out, code, listOf(
            "Content-Type" to contentType,
            "Content-Length" to body.size.toString(),
        ), ByteArrayInputStream(body), head = false)
    }

    private fun sendStream(
        out: OutputStream,
        code: Int,
        headers: List<Pair<String, String>>,
        body: InputStream?,
        head: Boolean,
    ) {
        val reason = when (code) {
            200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"
            400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"
            501 -> "Not Implemented"; 502 -> "Bad Gateway"; else -> "OK"
        }
        val headBytes = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        synchronized(out) {
            out.write(headBytes)
            if (body != null && !head) body.copyTo(out, IO_BUFFER)
            out.flush()
        }
    }

    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(IO_BUFFER)
        var left = max
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private val URI_ATTR = Regex("""URI=(?:"[^"]*"|'[^']*'|[^,\s]+)""")
}
