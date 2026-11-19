package com.jpsums.subscout

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/** One screen: the SubScout page (assets/index.html) with a bridge to the phone. */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (!night) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        webView.setBackgroundColor(if (night) 0xFF121110.toInt() else 0xFFF4F1EA.toInt())
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.addJavascriptInterface(Bridge(this), "SubNative")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            /** The page never leaves the app: any real link opens in the browser instead. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                if (u.scheme == "https") try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.toString()))) } catch (_: Exception) { }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) { pageReady = true }
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    fun runJs(code: String) {
        runOnUiThread { try { webView.evaluateJavascript(code, null) } catch (_: Exception) { } }
    }

    /** Tells the page something happened: 'resume', or the result of a slow job. */
    fun emit(ev: String, id: String, msg: String) =
        runJs("window.SSNative && window.SSNative(${JSONObject.quote(ev)}, ${JSONObject.quote(id)}, ${JSONObject.quote(msg)})")

    override fun onResume() {
        super.onResume()
        if (pageReady) emit("resume", "", "")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!pageReady) { moveTaskToBack(true); return }
        webView.evaluateJavascript("(window.SSBack && window.SSBack()) ? 1 : 0") { v ->
            if (v == null || !v.contains("1")) moveTaskToBack(true)
        }
    }
}
