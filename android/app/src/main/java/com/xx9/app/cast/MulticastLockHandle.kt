package com.xx9.app.cast

/**
 * 多播锁抽象（对标 hongguo MulticastLockHandle）。
 * Android 默认过滤多播；不持锁 SSDP 响应会全部丢掉。
 */
interface MulticastLockHandle {
    fun acquire()
    fun release()

    /** WiFi IPv4，用于绑定 SSDP socket 源地址，避免多播从蜂窝口出去。 */
    fun wifiAddress(): String?
}
