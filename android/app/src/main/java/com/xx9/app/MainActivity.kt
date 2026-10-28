package com.xx9.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.xx9.app.cast.AndroidCastSessionLock
import com.xx9.app.cast.AndroidMulticastLock
import com.xx9.app.cast.CastJsBridge
import com.xx9.app.cast.CastManager
import com.xx9.app.cast.DlnaClient

/**
 * 加载已配置 Base URL 的 web/；AMEND-1：带 xx9app=1，前端免密建会话。
 * 投屏：AMEND-2 R1 — MulticastLock + 多 ST + CastState + Transport 轮询；仅 DLNA。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var loading: ProgressBar
    private lateinit var castBridge: CastJsBridge
    private lateinit var castManager: CastManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = Prefs.baseUrl(this)
        if (base.isEmpty()) {
            startActivity(Intent(this, ServerSetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        loading = findViewById(R.id.loading)

        val multicast = AndroidMulticastLock(this)
        val sessionLock = AndroidCastSessionLock(this)
        val dlna = DlnaClient(multicast)
        // bridge 需先占位引用；用 lateinit 循环：先建 manager 再 bridge
        lateinit var bridge: CastJsBridge
        castManager = CastManager(
            dlna = dlna,
            sessionLock = sessionLock,
            onState = { state -> bridge.notifyState(state) },
        )
        bridge = CastJsBridge(this, castManager)
        castBridge = bridge

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString xx9-android-app/1.0"
        }
        web.addJavascriptInterface(castBridge, CastJsBridge.NAME)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading.visibility = View.GONE
                view?.evaluateJavascript(
                    """
                    (function(){
                      window.XX9_NATIVE = true;
                      window.XX9_APP = true;
                      if (window.xx9CastNativeReady) window.xx9CastNativeReady();
                      document.addEventListener('click', function(e){
                        var t = e.target;
                        if (t && (t.id === 'server-btn' || t.getAttribute('data-xx9-server') === '1')) {
                          e.preventDefault();
                          XX9Cast.openServerSetup();
                        }
                      }, true);
                    })();
                    """.trimIndent(),
                    null,
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        if (intent.getBooleanExtra(EXTRA_CLEAR_SESSION, false)) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        web.loadUrl(base.trimEnd('/') + "/?xx9app=1")
    }

    override fun onDestroy() {
        if (::castBridge.isInitialized) castBridge.shutdown()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    fun openServerSetup() {
        startActivity(
            Intent(this, ServerSetupActivity::class.java)
                .putExtra(ServerSetupActivity.EXTRA_FORCE, true),
        )
    }

    companion object {
        const val EXTRA_CLEAR_SESSION = "clear_session"
    }
}
