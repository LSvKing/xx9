package com.xx9.app.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * DLNA：SSDP 多 ST 发现 + AVTransport SOAP（对标 hongguo DlnaClient，不引 Cling）。
 * 默认直推明文 m3u8 URL；metadata 留空以提高兼容性。
 */
class DlnaClient(
    private val multicastLock: MulticastLockHandle? = null,
) {
    private val tag = "Xx9Dlna"

    suspend fun discover(timeoutMs: Long = 8_000L): List<DlnaDevice> = withContext(Dispatchers.IO) {
        multicastLock?.acquire()
        try {
            val locations = linkedSetOf<String>()
            val wifiAddress = runCatching { multicastLock?.wifiAddress() }.getOrNull()
            val socket = if (wifiAddress != null) {
                runCatching { DatagramSocket(InetSocketAddress(wifiAddress, 0)) }
                    .getOrElse { DatagramSocket() }
            } else {
                DatagramSocket()
            }
            Log.i(tag, "multicast src=${wifiAddress ?: "default"}")
            try {
                socket.soTimeout = 500
                socket.broadcast = true
                val group = InetAddress.getByName(SSDP_ADDRESS)
                var sent = 0
                for (st in SEARCH_TARGETS) {
                    val message = buildSearch(st)
                    repeat(2) {
                        runCatching {
                            socket.send(DatagramPacket(message, message.size, group, SSDP_PORT))
                            sent++
                        }
                    }
                }
                Log.i(tag, "sent $sent M-SEARCH")
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(8192)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    locationFrom(text)?.let { locations.add(it) }
                }
                Log.i(tag, "locations=${locations.size}")
            } finally {
                runCatching { socket.close() }
            }
            locations.mapNotNull { fetchDevice(it) }.distinctBy { it.controlUrl }
        } finally {
            runCatching { multicastLock?.release() }
        }
    }

    suspend fun play(device: DlnaDevice, mediaUrl: String): Boolean =
        soap(device, "SetAVTransportURI", buildString {
            append("<InstanceID>0</InstanceID>")
            append("<CurrentURI>").append(escapeXml(mediaUrl)).append("</CurrentURI>")
            append("<CurrentURIMetaData></CurrentURIMetaData>")
        }) && soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    suspend fun pause(device: DlnaDevice): Boolean =
        soap(device, "Pause", "<InstanceID>0</InstanceID>")

    suspend fun resume(device: DlnaDevice): Boolean =
        soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    suspend fun stop(device: DlnaDevice): Boolean =
        soap(device, "Stop", "<InstanceID>0</InstanceID>")

    suspend fun transportState(device: DlnaDevice): String? {
        val response = soapRequest(device, "GetTransportInfo", "<InstanceID>0</InstanceID>") ?: return null
        return Regex("<CurrentTransportState>(.*?)</CurrentTransportState>", RegexOption.DOT_MATCHES_ALL)
            .find(response)?.groupValues?.getOrNull(1)?.trim()
    }

    suspend fun positionInfo(device: DlnaDevice): TransportPosition? {
        val response = soapRequest(device, "GetPositionInfo", "<InstanceID>0</InstanceID>") ?: return null
        val position = parseDlnaTime(NODE_REL_TIME.find(response)?.groupValues?.getOrNull(1))
        val duration = parseDlnaTime(NODE_TRACK_DURATION.find(response)?.groupValues?.getOrNull(1))
        if (position < 0 || duration <= 0) return null
        return TransportPosition(position, duration)
    }

    private fun buildSearch(searchTarget: String): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: 239.255.255.250:1900\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: ").append(searchTarget).append("\r\n")
        append("\r\n")
    }.toByteArray()

    private fun locationFrom(response: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http", ignoreCase = true) }

    private fun fetchDevice(location: String): DlnaDevice? {
        val body = httpGet(location) ?: return null
        val name = NODE_FRIENDLY_NAME.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val udn = NODE_UDN.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val serviceBlock = SERVICE_BLOCK.find(body)?.value
        val control = serviceBlock
            ?.let { NODE_CONTROL_URL.find(it)?.groupValues?.getOrNull(1)?.trim() }
            .orEmpty()
        if (control.isEmpty()) return null
        val host = runCatching { URL(location).host }.getOrNull() ?: return null
        val absoluteControl = if (control.startsWith("http")) {
            control
        } else {
            val schemeEnd = location.indexOf("://") + 3
            val pathStart = location.indexOf('/', schemeEnd).let { if (it < 0) location.length else it }
            location.substring(0, pathStart) + if (control.startsWith("/")) control else "/$control"
        }
        return DlnaDevice(
            id = udn.ifBlank { absoluteControl },
            friendlyName = name.ifBlank { host },
            controlUrl = absoluteControl,
            host = host,
        )
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun soap(device: DlnaDevice, action: String, body: String): Boolean =
        soapRequest(device, action, body) != null

    private suspend fun soapRequest(device: DlnaDevice, action: String, body: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val envelope = buildString {
                    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                    append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                    append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
                    append("<s:Body>")
                    append("<u:").append(action).append(" xmlns:u=\"").append(AV_TRANSPORT).append("\">")
                    append(body)
                    append("</u:").append(action).append(">")
                    append("</s:Body></s:Envelope>")
                }
                val conn = (URL(device.controlUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                    setRequestProperty("SOAPAction", "\"$AV_TRANSPORT#$action\"")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                try {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(envelope) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val resp = stream?.let { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
                    Log.i(tag, "$action → $code ${resp.take(120)}")
                    if (code in 200..299) resp else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val USER_AGENT = "Android/UPnP/1.0 xx9/1.0"

        val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "ssdp:all",
        )

        val NODE_FRIENDLY_NAME =
            Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
        val NODE_UDN = Regex("<UDN>(.*?)</UDN>", RegexOption.DOT_MATCHES_ALL)
        val SERVICE_BLOCK = Regex(
            "<service>(?:(?!</service>).)*?${Regex.escape(AV_TRANSPORT)}(?:(?!</service>).)*?</service>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val NODE_CONTROL_URL =
            Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL)
        val NODE_REL_TIME = Regex("<RelTime>(.*?)</RelTime>", RegexOption.DOT_MATCHES_ALL)
        val NODE_TRACK_DURATION =
            Regex("<TrackDuration>(.*?)</TrackDuration>", RegexOption.DOT_MATCHES_ALL)
    }
}

internal fun parseDlnaTime(raw: String?): Long {
    val parts = raw?.trim().orEmpty().split(':')
    if (parts.size != 3) return -1
    val hours = parts[0].toLongOrNull() ?: return -1
    val minutes = parts[1].toLongOrNull() ?: return -1
    val secondPart = parts[2]
    val seconds = secondPart.substringBefore('.').toLongOrNull() ?: return -1
    val millis = secondPart.substringAfter('.', "")
        .take(3)
        .padEnd(3, '0')
        .toLongOrNull() ?: 0L
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
}
