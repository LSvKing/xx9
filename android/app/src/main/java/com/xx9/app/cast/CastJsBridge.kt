package com.xx9.app.cast

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.xx9.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web → 原生投屏桥。JS 侧名：XX9Cast
 */
class CastJsBridge(
    private val activity: MainActivity,
    private val controller: DlnaController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var lastDevices: List<DlnaDevice> = emptyList()

    @JavascriptInterface
    fun isNative(): Boolean = true

    @JavascriptInterface
    fun openServerSetup() {
        main.post { activity.openServerSetup() }
    }

    @JavascriptInterface
    fun scan(callbackId: String) {
        scope.launch {
            val result = runCatching { controller.scan() }
            lastDevices = result.getOrDefault(emptyList())
            val arr = JSONArray()
            for (d in lastDevices) {
                arr.put(
                    JSONObject()
                        .put("id", d.id)
                        .put("name", d.name)
                        .put("location", d.location),
                )
            }
            if (result.isSuccess) {
                emit(callbackId, ok = true, data = arr)
            } else {
                emit(callbackId, ok = false, error = result.exceptionOrNull()?.message ?: "扫描失败")
            }
        }
    }

    @JavascriptInterface
    fun play(deviceId: String, url: String, title: String, callbackId: String) {
        scope.launch {
            val device = lastDevices.find { it.id == deviceId }
                ?: controller.current?.takeIf { it.id == deviceId }
            if (device == null) {
                emit(callbackId, ok = false, error = "设备无效，请重新扫描")
                return@launch
            }
            val r = controller.play(device, url, title.ifBlank { "xx9" })
            if (r.isSuccess) emit(callbackId, ok = true)
            else {
                val msg = r.exceptionOrNull()?.message.orEmpty()
                val hint = when {
                    msg.contains("701") || msg.contains("format", true) ||
                        msg.contains("716") || url.contains(".m3u8") && msg.contains("500") ->
                        "HLS_UNSUPPORTED"
                    else -> msg.ifBlank { "投屏失败" }
                }
                emit(callbackId, ok = false, error = hint)
            }
        }
    }

    @JavascriptInterface
    fun pause(callbackId: String) {
        scope.launch {
            val r = controller.pause()
            if (r.isSuccess) emit(callbackId, ok = true)
            else emit(callbackId, ok = false, error = "UNSUPPORTED_PAUSE")
        }
    }

    @JavascriptInterface
    fun resume(callbackId: String) {
        scope.launch {
            val r = controller.resume()
            if (r.isSuccess) emit(callbackId, ok = true)
            else emit(callbackId, ok = false, error = "UNSUPPORTED_PAUSE")
        }
    }

    @JavascriptInterface
    fun stop(callbackId: String) {
        scope.launch {
            controller.stop()
            emit(callbackId, ok = true)
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun emit(callbackId: String, ok: Boolean, data: Any? = null, error: String? = null) {
        val payload = JSONObject()
            .put("id", callbackId)
            .put("ok", ok)
            .put("data", data)
            .put("error", error)
            .toString()
        val js = "window.xx9CastCallback && window.xx9CastCallback($payload)"
        main.post {
            activity.findViewById<WebView>(com.xx9.app.R.id.web)
                ?.evaluateJavascript(js, null)
        }
    }

    companion object {
        const val NAME = "XX9Cast"
    }
}
