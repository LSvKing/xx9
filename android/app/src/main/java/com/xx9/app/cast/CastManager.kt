package com.xx9.app.cast

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 投屏编排（对标 hongguo CastManager，精简：无连播 / 无 LocalMediaServer）。
 * - 发现 → Connecting → Playing
 * - 4s 轮询 TransportState；电视侧停后 ≤12s（3×4s）离开 Playing
 */
class CastManager(
    private val dlna: DlnaClient,
    private val sessionLock: CastSessionLock? = null,
    private val onState: (CastState) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tag = "Xx9Cast"

    @Volatile
    var state: CastState = CastState.Idle
        private set(value) {
            field = value
            onState(value)
        }

    @Volatile
    var currentDevice: DlnaDevice? = null
        private set

    private var pollJob: Job? = null
    private var lastDevices: List<DlnaDevice> = emptyList()

    suspend fun discoverDevices(): List<DlnaDevice> {
        state = CastState.Discovering
        val result = runCatching { dlna.discover() }
        lastDevices = result.getOrDefault(emptyList())
        if (result.isFailure) {
            state = CastState.Failed("设备搜索失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
        } else if (state is CastState.Discovering) {
            state = CastState.Idle
        }
        return lastDevices
    }

    fun findDevice(id: String): DlnaDevice? =
        lastDevices.find { it.id == id || it.controlUrl == id }
            ?: currentDevice?.takeIf { it.id == id || it.controlUrl == id }

    /**
     * 直推当前线路 URL（明文 m3u8/http）。
     * @return true 若进入 Playing
     */
    suspend fun castTo(device: DlnaDevice, mediaUrl: String): Boolean {
        stopPolling()
        state = CastState.Connecting(device.friendlyName)
        sessionLock?.acquire()
        val ok = withContext(Dispatchers.IO) {
            runCatching { dlna.play(device, mediaUrl) }.getOrDefault(false)
        }
        if (!ok) {
            sessionLock?.release()
            currentDevice = null
            state = CastState.Failed("${device.friendlyName} 未接受该地址（设备兼容性问题）")
            return false
        }
        currentDevice = device
        state = CastState.Playing(device.friendlyName)
        startPolling(device)
        return true
    }

    suspend fun pause(): Boolean {
        val d = currentDevice ?: return false
        val ok = withContext(Dispatchers.IO) { runCatching { dlna.pause(d) }.getOrDefault(false) }
        return ok
    }

    suspend fun resume(): Boolean {
        val d = currentDevice ?: return false
        return withContext(Dispatchers.IO) { runCatching { dlna.resume(d) }.getOrDefault(false) }
    }

    suspend fun stop() {
        val d = currentDevice
        stopPolling()
        if (d != null) {
            withContext(Dispatchers.IO) { runCatching { dlna.stop(d) } }
        }
        currentDevice = null
        sessionLock?.release()
        state = CastState.Idle
    }

    fun release() {
        pollJob?.cancel()
        pollJob = null
        currentDevice = null
        sessionLock?.release()
        state = CastState.Idle
        scope.coroutineContext[Job]?.cancel()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun startPolling(device: DlnaDevice) {
        stopPolling()
        val tracked = device
        pollJob = scope.launch {
            try {
                var sawActive = false
                var consecutiveStopped = 0
                while (isActive) {
                    delay(STATE_POLL_INTERVAL_MS)
                    if (currentDevice?.controlUrl != tracked.controlUrl) break
                    val transport = withContext(Dispatchers.IO) {
                        runCatching { dlna.transportState(tracked) }.getOrNull()
                    } ?: continue
                    val value = transport.uppercase()
                    Log.i(tag, "transport=$value")
                    if (value in ACTIVE_TRANSPORT_STATES) {
                        sawActive = true
                        consecutiveStopped = 0
                        continue
                    }
                    if (value !in STOPPED_TRANSPORT_STATES) continue
                    consecutiveStopped++
                    if (sawActive) {
                        // 电视侧停：退出投屏中（不区分片尾/用户停，xx9 无连播）
                        currentDevice = null
                        state = CastState.Idle
                        break
                    }
                    // 从未 PLAYING 却持续 STOPPED → ≤12s 清态
                    if (consecutiveStopped >= STOPPED_CONFIRM_COUNT) {
                        currentDevice = null
                        state = CastState.Idle
                        break
                    }
                }
            } finally {
                if (currentDevice?.controlUrl == tracked.controlUrl) {
                    sessionLock?.release()
                } else if (currentDevice == null) {
                    sessionLock?.release()
                }
            }
        }
    }

    companion object {
        const val STATE_POLL_INTERVAL_MS = 4_000L
        /** 3 × 4s = 12s，满足 AC-4「电视停后 ≤12s 离开投屏中」 */
        const val STOPPED_CONFIRM_COUNT = 3
        val ACTIVE_TRANSPORT_STATES = setOf("PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING")
        val STOPPED_TRANSPORT_STATES = setOf("STOPPED", "NO_MEDIA_PRESENT")
    }
}
