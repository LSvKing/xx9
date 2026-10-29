package com.xx9.app.cast

import android.util.Log
import com.xx9.app.data.Xx9Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 投屏编排（参考 qinghe：走本机 CastProxy，不直推上游签址）。
 * - 发现 → Connecting → Playing
 * - 4s 轮询 TransportState；电视侧停/ERROR 确认后离开 Playing
 * - 会话用对象身份识别（`castSession === session`），同设备重投不会让旧 poll 释放新锁
 */
class CastManager(
    private val dlna: DlnaClient,
    private val api: Xx9Api,
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

    /** 当前投屏会话身份 + 代理 token；身份对象本身即判据。 */
    @Volatile
    private var castSession: Any? = null
    @Volatile
    private var castToken: String? = null

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
     * 经本机 CastProxy 投屏：注册 videoId+lineIndex，电视拉代理 URL，
     * 签址过期由代理回 /api/video/{vid} 换新重试。
     * @return true 若进入 Playing
     */
    suspend fun castTo(device: DlnaDevice, videoId: Int, lineIndex: Int, mediaUrl: String): Boolean {
        stopPolling()
        castSession = null
        CastProxy.release(castToken)
        castToken = null
        state = CastState.Connecting(device.friendlyName)
        sessionLock?.acquire()

        val entry = CastProxy.register(videoId, lineIndex, mediaUrl, api)
        if (entry == null) {
            sessionLock?.release()
            state = CastState.Failed("无法建立本机推送服务，请确认手机已连接 Wi‑Fi")
            return false
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching { dlna.play(device, entry.url) }.getOrDefault(false)
        }
        if (!ok) {
            CastProxy.release(entry.token)
            sessionLock?.release()
            currentDevice = null
            state = CastState.Failed("${device.friendlyName} 未接受该地址（设备兼容性问题）")
            return false
        }
        currentDevice = device
        castToken = entry.token
        val session = Any()
        castSession = session
        state = CastState.Playing(device.friendlyName)
        startPolling(device, session)
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
        castSession = null
        if (d != null) {
            withContext(Dispatchers.IO) { runCatching { dlna.stop(d) } }
        }
        currentDevice = null
        CastProxy.release(castToken)
        castToken = null
        sessionLock?.release()
        state = CastState.Idle
    }

    fun release() {
        pollJob?.cancel()
        pollJob = null
        castSession = null
        currentDevice = null
        CastProxy.release(castToken)
        castToken = null
        sessionLock?.release()
        state = CastState.Idle
        scope.coroutineContext[Job]?.cancel()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun startPolling(device: DlnaDevice, session: Any) {
        stopPolling()
        val tracked = device
        val token = castToken
        pollJob = scope.launch {
            try {
                var sawActive = false
                var consecutiveStopped = 0
                while (isActive) {
                    delay(STATE_POLL_INTERVAL_MS)
                    if (castSession !== session) break
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
                    // STOPPED/NO_MEDIA 在播过之后 → 片尾或用户停，立即结束
                    if (value in STOPPED_TRANSPORT_STATES && sawActive) break
                    // 从未播过却持续 STOPPED，或 ERROR_OCCURRED（部分机器缓冲/切码率闪报）→ 3 连确认
                    if (value in STOPPED_TRANSPORT_STATES || value == ERROR_STATE) {
                        consecutiveStopped++
                        if (consecutiveStopped >= STOPPED_CONFIRM_COUNT) break
                    } else {
                        consecutiveStopped = 0
                    }
                }
            } finally {
                // 只清自己的会话：重投同设备后，旧 poll 不得动新会话的状态/锁/token
                if (castSession === session) {
                    castSession = null
                    castToken = null
                    currentDevice = null
                    CastProxy.release(token)
                    sessionLock?.release()
                    state = CastState.Idle
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
        const val ERROR_STATE = "ERROR_OCCURRED"
    }
}
