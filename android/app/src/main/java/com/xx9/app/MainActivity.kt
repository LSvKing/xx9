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
import com.xx9.app.cast.CastJsBridge
import com.xx9.app.cast.DlnaController

/**
 * 加载已配置 Base URL 的 web/；AMEND-1：带 xx9app=1，前端免密建会话。
 * 投屏经 [CastJsBridge] → DLNA/UPnP AVTransport（单一协议）。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var loading: ProgressBar
    private lateinit var castBridge: CastJsBridge

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
        castBridge = CastJsBridge(this, DlnaController(this))

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
                // 标记原生桥已就绪（cast.js 轮询）；服务器入口
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
                val u = request?.url ?: return false
                // 同主机内导航交给 WebView；外链忽略
                return false
            }
        }

        if (intent.getBooleanExtra(EXTRA_CLEAR_SESSION, false)) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        // query 触发 App 免密路径（web/app.js + /api/app/session）
        web.loadUrl(base.trimEnd('/') + "/?xx9app=1")
    }

    override fun onDestroy() {
        castBridge.shutdown()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
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
