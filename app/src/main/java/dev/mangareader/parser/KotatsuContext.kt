package dev.mangareader.parser

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser as KParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.IOException
import java.net.IDN
import java.util.Locale
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect as AndroidRect

/** Thrown when a site needs a browser (Cloudflare challenge, captcha); solve it in a WebView sharing cookies + UA. */
class CloudflareException(val url: String) : IOException("The site requires a browser check (Cloudflare): $url")

/** Kotatsu: core/parser/MangaLoaderContextImpl. */
class KotatsuContext(
    override val httpClient: OkHttpClient,
    override val cookieJar: CookieJar,
    private val userAgent: String,
    private val domainPrefs: SharedPreferences,
    private val sourcePrefs: (String) -> SharedPreferences,
    private val web: WebViewFetcher,
) : MangaLoaderContext() {

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? = evaluateJs("", script)

    override suspend fun evaluateJs(baseUrl: String, script: String): String? = web.evaluateJs(baseUrl, script)

    override fun getDefaultUserAgent() = userAgent

    /** Values from the source's settings screen, else the parser defaults (Kotatsu: SourceSettings). */
    override fun getConfig(source: MangaSource) = object : MangaSourceConfig {
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: ConfigKey<T>): T {
            val prefs = sourcePrefs(source.name)
            return when (key) {
                is ConfigKey.Domain -> domainPrefs.getString(source.name, null)?.let(::normalizeDomain) ?: key.defaultValue
                is ConfigKey.UserAgent -> prefs.getString(key.key, null)?.trim()?.takeIf { it.isNotEmpty() } ?: key.defaultValue
                is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
                is ConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
                // "" is the "Otomatis" entry (null server), as in Kotatsu.
                is ConfigKey.PreferredImageServer -> if (prefs.contains(key.key)) prefs.getString(key.key, null)?.takeIf { it.isNotEmpty() } else key.defaultValue
            } as T
        }
    }

    override fun encodeBase64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    override fun decodeBase64(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)

    override fun getPreferredLocales(): List<Locale> = LocaleListCompat.getAdjustedDefault().let { l -> (0 until l.size()).map { l[it]!! } }

    override fun requestBrowserAction(parser: KParser, url: String): Nothing = throw CloudflareException(url)

    /** Descrambles images for the few sources that shuffle tiles. */
    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
        val decoded = response.body.byteStream().use { BitmapFactory.decodeStream(it) } ?: throw IOException("Image could not be decoded")
        val source = BitmapWrapper(decoded.copy(AndroidBitmap.Config.ARGB_8888, true)).also { decoded.recycle() }
        val result = redraw(source) as BitmapWrapper
        val out = Buffer()
        result.bitmap.compress(AndroidBitmap.CompressFormat.PNG, 100, out.outputStream())
        source.bitmap.recycle()
        result.bitmap.recycle()
        return response.newBuilder().body(out.asResponseBody("image/png".toMediaType())).build()
    }

    override fun createBitmap(width: Int, height: Int): Bitmap =
        BitmapWrapper(AndroidBitmap.createBitmap(width, height, AndroidBitmap.Config.ARGB_8888))
}

private class BitmapWrapper(val bitmap: AndroidBitmap) : Bitmap {
    private val canvas by lazy { AndroidCanvas(bitmap) }
    override val width get() = bitmap.width
    override val height get() = bitmap.height
    override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) =
        canvas.drawBitmap((sourceBitmap as BitmapWrapper).bitmap, src.toAndroid(), dst.toAndroid(), null)

    private fun Rect.toAndroid() = AndroidRect(left, top, right, bottom)
}

/** Header carrying the source id on requests not made by a parser (covers, pages, downloads). */
const val HEADER_SOURCE = "X-Manga-Source"

/**
 * Adds the source's headers (UA, Referer) and runs its interceptor (Kotatsu: CommonHeadersInterceptor).
 * Parser requests are tagged with their MangaSource by the library; others carry [HEADER_SOURCE].
 */
class SourceHeadersInterceptor(private val sourceOf: (String) -> KotatsuSource?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val id = request.tag(MangaSource::class.java)?.name ?: request.header(HEADER_SOURCE)
        val source = id?.let(sourceOf) ?: return chain.proceed(request.newBuilder().removeHeader(HEADER_SOURCE).build())
        val parser = source.parser
        val headers = request.headers.newBuilder().removeAll(HEADER_SOURCE)
        parser.getRequestHeaders().forEach { (name, value) -> if (headers[name] == null) headers[name] = value }
        if (headers["Referer"] == null) headers["Referer"] = "https://${IDN.toASCII(parser.domain)}/"
        val newRequest = request.newBuilder().headers(headers.build()).build()
        return try {
            parser.intercept(ProxyChain(chain, newRequest))
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Error in interceptor: ${e.message}", e) // only IOException may leave an interceptor
        }
    }

    private class ProxyChain(private val delegate: Interceptor.Chain, private val request: Request) : Interceptor.Chain by delegate {
        override fun request() = request
    }
}

/**
 * Cloudflare ties cf_clearance to the browser's TLS fingerprint, so a challenged parser GET is retried in an
 * off-screen WebView and its HTML returned as the response (Kotatsu: CloudFlareInterceptor + WebViewExecutor).
 * Anything else that is challenged fails with [CloudflareException] so the UI can offer the browser.
 */
class CloudflareInterceptor(private val web: WebViewFetcher) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 403 && response.code != 503) return response
        val body = response.peekBody(512 * 1024).string()
        if (!isCloudflareChallenge(response.code, response.header("cf-mitigated"), body)) return response
        response.close()
        val url = request.url.toString()
        // Only parser page loads (tagged by the library) expect HTML; images would get a page instead.
        val html = if (request.method == "GET" && request.tag(MangaSource::class.java) != null) {
            runBlocking { web.fetchHtml(url) } // OkHttp worker thread, never main
        } else null
        html ?: throw CloudflareException(url)
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "text/html; charset=utf-8")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
    }
}

internal fun isCloudflareChallenge(code: Int, cfMitigated: String?, body: String): Boolean =
    (code == 403 || code == 503) && (cfMitigated == "challenge" || looksLikeChallenge(body))

/** Normalizes user input like "https://Example.com/" to "example.com"; null if it is not a host[:port]. */
fun normalizeDomain(input: String): String? {
    val d = input.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
    return d.takeIf { Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d{1,5})?$").matches(it) }
}
