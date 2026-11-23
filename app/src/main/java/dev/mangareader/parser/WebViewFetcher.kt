package dev.mangareader.parser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Loads a page in an off-screen WebView and returns its HTML once it is no longer a Cloudflare challenge
 * (Kotatsu: core/network/webview/WebViewExecutor). Cloudflare ties cf_clearance to the browser's TLS
 * fingerprint, so a cookie copied into OkHttp is not always accepted; the WebView itself is.
 */
class WebViewFetcher(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled") // challenge pages require JS
    suspend fun fetchHtml(url: String, timeoutMs: Long = 30_000): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                val web = WebView(context.applicationContext)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.webViewClient = WebViewClient()
                // Poll instead of waiting for onPageFinished: ad-heavy pages may never "finish", but their DOM
                // is usable as soon as it is parsed. A challenge page shows first and navigates away once solved.
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        web.evaluateJavascript("document.readyState === 'loading' ? null : document.documentElement.outerHTML") { json ->
                            val html = runCatching { JSONTokener(json).nextValue() as? String }.getOrNull()
                            if (html != null && "<body" in html && !looksLikeChallenge(html) && cont.isActive) {
                                cont.resume(html)
                                main.post { web.destroy() }
                            } else {
                                main.postDelayed(this, 1_000)
                            }
                        }
                    }
                }
                cont.invokeOnCancellation { main.post { main.removeCallbacks(poll); web.destroy() } }
                web.loadUrl(url)
                main.postDelayed(poll, 1_000)
            }
        }
    }

    /** Runs [script] in a blank page with [baseUrl] as origin; raw JSON-encoded result (Kotatsu: WebViewExecutor.evaluateJs). */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun evaluateJs(baseUrl: String, script: String): String? = withTimeoutOrNull(10_000) {
        withContext(Dispatchers.Main) {
            val web = WebView(context.applicationContext)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            try {
                if (baseUrl.isNotEmpty()) {
                    suspendCancellableCoroutine { cont ->
                        web.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                        web.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
                    }
                }
                suspendCancellableCoroutine { cont ->
                    web.evaluateJavascript(script) { if (cont.isActive) cont.resume(it?.takeUnless { r -> r == "null" }) }
                }
            } finally {
                web.destroy()
            }
        }
    }
}

internal fun looksLikeChallenge(body: String): Boolean =
    "cf-chl" in body || "cf_chl" in body || "<title>Just a moment" in body
