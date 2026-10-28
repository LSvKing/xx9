package com.xx9.app

import android.app.Application
import com.xx9.app.cast.AndroidCastSessionLock
import com.xx9.app.cast.AndroidMulticastLock
import com.xx9.app.cast.CastManager
import com.xx9.app.cast.DlnaClient
import com.xx9.app.data.Xx9Api

class Xx9App : Application() {
    lateinit var api: Xx9Api
        private set
    lateinit var castManager: CastManager
        private set

    override fun onCreate() {
        super.onCreate()
        api = Xx9Api { Prefs.baseUrl(this) }
        val multicast = AndroidMulticastLock(this)
        val sessionLock = AndroidCastSessionLock(this)
        castManager = CastManager(
            dlna = DlnaClient(multicast),
            sessionLock = sessionLock,
        )
    }
}
