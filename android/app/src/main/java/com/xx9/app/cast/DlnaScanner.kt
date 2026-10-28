package com.xx9.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * SSDP M-SEARCH → 解析 device description → 收集 AVTransport controlURL。
 * 单一协议：DLNA/UPnP（本 cut 禁止第二协议）。
 */
class DlnaScanner(private val context: Context) {
    private val tag = "Xx9Dlna"

    fun scan(timeoutMs: Long = 10_000L): List<DlnaDevice> {
        val found = ConcurrentHashMap<String, DlnaDevice>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val lock = wifi.createMulticastLock("xx9-dlna").apply { setReferenceCounted(true); acquire() }
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 800
            bind(InetSocketAddress(0))
        }
        try {
            val payload = """
                M-SEARCH * HTTP/1.1
                HOST: 239.255.255.250:1900
                MAN: "ssdp:discover"
                MX: 3
                ST: urn:schemas-upnp-org:service:AVTransport:1

            """.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName("239.255.255.250")
            // 再搜 MediaRenderer 根设备，兼容只回根的盒子
            val payloadRoot = """
                M-SEARCH * HTTP/1.1
                HOST: 239.255.255.250:1900
                MAN: "ssdp:discover"
                MX: 3
                ST: urn:schemas-upnp-org:device:MediaRenderer:1

            """.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, group, 1900))
            socket.send(DatagramPacket(payloadRoot, payloadRoot.size, group, 1900))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charset.forName("UTF-8"))
                    val location = header(text, "LOCATION") ?: continue
                    if (found.containsKey(location)) continue
                    parseDevice(location)?.let { found[location] = it }
                } catch (_: Exception) {
                    // timeout / parse — keep scanning until deadline
                }
            }
        } finally {
            runCatching { socket.close() }
            runCatching { if (lock.isHeld) lock.release() }
        }
        Log.i(tag, "scan found ${found.size}")
        return found.values.sortedBy { it.name.lowercase() }
    }

    private fun header(resp: String, name: String): String? {
        val re = Regex("""(?im)^$name:\s*(.+)\r?$""")
        return re.find(resp)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseDevice(location: String): DlnaDevice? {
        val conn = (URL(location).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
        }
        return try {
            val xml = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            val base = URL(location)
            var friendlyName = "DLNA 设备"
            var udn = location
            var controlUrl: String? = null
            var inAv = false
            var serviceType = ""

            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val p = factory.newPullParser()
            p.setInput(xml.reader())
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name?.lowercase()) {
                        "friendlyname" -> friendlyName = p.nextText().trim().ifEmpty { friendlyName }
                        "udn" -> udn = p.nextText().trim().ifEmpty { udn }
                        "service" -> {
                            inAv = false
                            serviceType = ""
                        }
                        "servicetype" -> {
                            serviceType = p.nextText().trim()
                            inAv = serviceType.contains("AVTransport", ignoreCase = true)
                        }
                        "controlurl" -> {
                            val u = p.nextText().trim()
                            if (inAv && controlUrl == null) {
                                controlUrl = resolve(base, u)
                            }
                        }
                    }
                }
                event = p.next()
            }
            val ctrl = controlUrl ?: return null
            DlnaDevice(
                id = udn,
                name = friendlyName,
                location = location,
                controlUrl = ctrl,
                avTransportServiceType = serviceType.ifEmpty {
                    "urn:schemas-upnp-org:service:AVTransport:1"
                },
            )
        } catch (e: Exception) {
            Log.w(tag, "parse $location fail: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun resolve(base: URL, path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else URL(base, path).toString()
}
