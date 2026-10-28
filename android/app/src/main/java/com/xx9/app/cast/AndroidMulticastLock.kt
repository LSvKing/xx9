package com.xx9.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** WifiManager.MulticastLock；仅发现期间持有。setReferenceCounted(false)。 */
class AndroidMulticastLock(context: Context) : MulticastLockHandle {
    private val app = context.applicationContext
    private val lock: WifiManager.MulticastLock? = runCatching {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.createMulticastLock("xx9-ssdp").apply { setReferenceCounted(false) }
    }.getOrNull()

    override fun acquire() {
        runCatching { lock?.takeIf { !it.isHeld }?.acquire() }
    }

    override fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
    }

    override fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
