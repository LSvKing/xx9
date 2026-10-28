package com.xx9.app.cast

/** 投屏会话保活：WakeLock + WifiLock（轮询与局域网控制不断）。 */
interface CastSessionLock {
    fun acquire()
    fun release()
}
