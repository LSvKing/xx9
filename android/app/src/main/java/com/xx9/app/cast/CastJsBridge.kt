package com.xx9.app.cast

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.xx9.app.MainActivity
import com.xx9.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Web → 原生投屏桥 XX9Cast（R1：状态机 + 轮询由 CastManager 负责）。 */
class CastJsBridge(
    private val activity: MainActivity,
    private val manager: CastManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    init {
        // 状态变化推给前端
        // CastManager.onState 在构造时已传入；此处再包一层 emitState
    }

    @JavascriptInterface
    fun isNative(): Boolean = true

    @JavascriptInterface
    fun openServerSetup() {
        main.post { activity.openServerSetup() }
    }

    @JavascriptInterface
    fun getState(callbackId: String) {
        emit(callbackId, ok = true, data = stateJson(manager.state))
    }

    @JavascriptInterface
    fun scan(callbackId: String) {
        scope.launch {
            val devices = runCatching { manager.discoverDevices() }
            if (devices.isFailure) {
                emit(
                    callbackId,
                    ok = false,
                    error = devices.exceptionOrNull()?.message ?: "扫描失败",
                    data = stateJson(manager.state),
                )
                return@launch
            }
            val arr = JSONArray()
            for (d in devices.getOrDefault(emptyList())) {
                arr.put(
                    JSONObject()
                        .put("id", d.id)
                        .put("name", d.friendlyName)
                        .put("host", d.host)
                        .put("controlUrl", d.controlUrl),
                )
            }
            emit(callbackId, ok = true, data = arr, extraState = manager.state)
        }
    }

    @JavascriptInterface
    fun play(deviceId: String, url: String, title: String, callbackId: String) {
        scope.launch {
            val device = manager.findDevice(deviceId)
            if (device == null) {
                emit(callbackId, ok = false, error = "设备无效，请重新扫描")
                return@launch
            }
            if (url.isBlank()) {
                emit(callbackId, ok = false, error = "当前没有可推送的播放地址")
                return@launch
            }
            val ok = manager.castTo(device, url)
            if (ok) {
                emit(callbackId, ok = true, data = stateJson(manager.state))
            } else {
                val msg = manager.state.messageOrNull() ?: "投屏失败"
                val hint = if (url.contains(".m3u8", ignoreCase = true) &&
                    msg.contains("未接受")
                ) "HLS_UNSUPPORTED" else msg
                emit(callbackId, ok = false, error = hint, data = stateJson(manager.state))
            }
        }
    }

    @JavascriptInterface
    fun pause(callbackId: String) {
        scope.launch {
            val ok = manager.pause()
            if (ok) emit(callbackId, ok = true)
            else emit(callbackId, ok = false, error = "UNSUPPORTED_PAUSE")
        }
    }

    @JavascriptInterface
    fun resume(callbackId: String) {
        scope.launch {
            val ok = manager.resume()
            if (ok) emit(callbackId, ok = true)
            else emit(callbackId, ok = false, error = "UNSUPPORTED_PAUSE")
        }
    }

    @JavascriptInterface
    fun stop(callbackId: String) {
        scope.launch {
            manager.stop()
            emit(callbackId, ok = true, data = stateJson(manager.state))
        }
    }

    fun notifyState(state: CastState) {
        val payload = JSONObject()
            .put("type", "state")
            .put("state", stateJson(state))
            .toString()
        main.post {
            activity.findViewById<WebView>(R.id.web)
                ?.evaluateJavascript(
                    "window.xx9CastOnState && window.xx9CastOnState($payload)",
                    null,
                )
        }
    }

    fun shutdown() {
        manager.release()
        scope.cancel()
    }

    private fun stateJson(state: CastState): JSONObject =
        JSONObject()
            .put("label", state.label())
            .put("deviceName", state.deviceNameOrNull())
            .put("message", state.messageOrNull())

    private fun emit(
        callbackId: String,
        ok: Boolean,
        data: Any? = null,
        error: String? = null,
        extraState: CastState? = null,
    ) {
        val payload = JSONObject()
            .put("id", callbackId)
            .put("ok", ok)
            .put("data", data)
            .put("error", error)
            .put("state", stateJson(extraState ?: manager.state))
            .toString()
        val js = "window.xx9CastCallback && window.xx9CastCallback($payload)"
        main.post {
            activity.findViewById<WebView>(R.id.web)?.evaluateJavascript(js, null)
        }
    }

    companion object {
        const val NAME = "XX9Cast"
    }
}
