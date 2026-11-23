package dev.mangareader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import dev.mangareader.R

/** In-app browser: solve a Cloudflare challenge or check a site; cookies land in CookieManager, shared with OkHttp (Kotatsu: CloudFlareActivity). */
class BrowserActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled") // challenge pages need JS
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        // Show the page's real title and host (it may redirect elsewhere, e.g. an expired domain now owned by someone else).
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                toolbar.subtitle = url?.let { android.net.Uri.parse(it).host }
            }
        }
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) { toolbar.title = title }
        }
        toolbar.subtitle = android.net.Uri.parse(intent.getStringExtra(EXTRA_URL)).host
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(toolbar)
            addView(web, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (savedInstanceState == null) web.loadUrl(requireNotNull(intent.getStringExtra(EXTRA_URL)))
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    companion object {
        private const val EXTRA_URL = "url"
        fun intent(ctx: Context, url: String) = Intent(ctx, BrowserActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
