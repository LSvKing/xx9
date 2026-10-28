package com.xx9.app.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

class AndroidCastSessionLock(context: Context) : CastSessionLock {
    private val app = context.applicationContext
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquire() {
        if (wakeLock != null) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xx9:cast").apply {
            setReferenceCounted(false)
            acquire(LOCK_TIMEOUT_MS)
        }
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "xx9:cast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun release() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private companion object {
        const val LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000
    }
}
