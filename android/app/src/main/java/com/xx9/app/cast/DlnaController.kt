package com.xx9.app.cast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DLNA AVTransport：SetAVTransportURI + Play / Pause / Stop。
 * 推送当前线路 m3u8 URL（不经服务端转发）。
 */
class DlnaController(context: Context) {
    private val scanner = DlnaScanner(context.applicationContext)
    private val tag = "Xx9Dlna"
    @Volatile var current: DlnaDevice? = null
        private set

    suspend fun scan(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        scanner.scan(10_000L)
    }

    suspend fun play(device: DlnaDevice, mediaUrl: String, title: String = "xx9"): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                soap(
                    device,
                    "SetAVTransportURI",
                    """
                    <InstanceID>0</InstanceID>
                    <CurrentURI>${xmlEsc(mediaUrl)}</CurrentURI>
                    <CurrentURIMetaData>${xmlEsc(didl(mediaUrl, title))}</CurrentURIMetaData>
                    """.trimIndent(),
                )
                soap(
                    device,
                    "Play",
                    """
                    <InstanceID>0</InstanceID>
                    <Speed>1</Speed>
                    """.trimIndent(),
                )
                current = device
            }
        }

    suspend fun pause(): Result<Unit> = transport("Pause") {
        """
        <InstanceID>0</InstanceID>
        """.trimIndent()
    }

    suspend fun resume(): Result<Unit> = transport("Play") {
        """
        <InstanceID>0</InstanceID>
        <Speed>1</Speed>
        """.trimIndent()
    }

    suspend fun stop(): Result<Unit> = transport("Stop") {
        """
        <InstanceID>0</InstanceID>
        """.trimIndent()
    }.also { current = null }

    private suspend fun transport(action: String, body: () -> String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val d = current ?: return@withContext Result.failure(IllegalStateException("未连接设备"))
            runCatching { soap(d, action, body()) }
        }

    private fun soap(device: DlnaDevice, action: String, inner: String) {
        val service = device.avTransportServiceType
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="$service">
                  $inner
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val conn = (URL(device.controlUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$service#$action\"")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(envelope) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.let { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
            Log.i(tag, "$action → $code ${resp.take(200)}")
            if (code !in 200..299) {
                error("SOAP $action HTTP $code: ${resp.take(180)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun didl(url: String, title: String): String =
        """&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="0" parentID="-1" restricted="1"&gt;&lt;dc:title&gt;${xmlEsc(title)}&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*"&gt;${xmlEsc(url)}&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"""

    private fun xmlEsc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}
