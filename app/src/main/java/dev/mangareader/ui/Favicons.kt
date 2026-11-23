package dev.mangareader.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import dev.mangareader.R
import dev.mangareader.parser.HEADER_SOURCE
import dev.mangareader.parser.KotatsuSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException

/** Coil data for a source's site icon. */
data class SourceIcon(val sourceId: String)

/**
 * Resolves the icon through the parser's getFavicons() and keeps it on disk (Kotatsu: core/parser/favicon/FaviconFetcher).
 * shortcut: icons are cached forever; clear app cache to refresh after a site redesign.
 */
class FaviconFetcher(
    private val source: KotatsuSource,
    private val client: OkHttpClient,
    private val dir: File,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val file = File(dir, source.id)
        if (!file.exists()) {
            var icons = source.parser.getFavicons()
            var lastError: IOException? = null
            while (!file.exists()) {
                val icon = icons.find(SIZE_PX) ?: throw lastError ?: IOException("Tidak ada favicon")
                try {
                    download(icon.url, file)
                } catch (e: IOException) {
                    lastError = e
                    icons -= icon
                }
            }
        }
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), null, DataSource.DISK)
    }

    private suspend fun download(url: String, file: File) = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder().url(url).header(HEADER_SOURCE, source.id).build()
        client.newCall(request).execute().use { rsp ->
            if (!rsp.isSuccessful) throw IOException("HTTP ${rsp.code}")
            dir.mkdirs()
            val tmp = File(dir, "${source.id}.tmp")
            tmp.outputStream().use { rsp.body.byteStream().copyTo(it) }
            if (!tmp.renameTo(file)) throw IOException("Gagal menyimpan favicon")
        }
    }

    class Factory(private val sourceOf: (String) -> KotatsuSource?, private val client: OkHttpClient, private val dir: File) :
        Fetcher.Factory<SourceIcon> {
        override fun create(data: SourceIcon, options: Options, imageLoader: ImageLoader) =
            sourceOf(data.sourceId)?.let { FaviconFetcher(it, client, dir) }
    }

    object IconKeyer : Keyer<SourceIcon> {
        override fun key(data: SourceIcon, options: Options) = "favicon:${data.sourceId}"
    }

    private companion object {
        const val SIZE_PX = 64
    }
}

/**
 * First letter on a muted brand colour picked from the name; shown while loading or when a site has no icon
 * (Kotatsu: FaviconDrawable). Folium avoids rainbow placeholders: forest, wood, ink, deep sage or deep rose + cream letter.
 */
class LetterDrawable(context: android.content.Context, name: String) : Drawable() {
    private val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val palette = intArrayOf(R.color.forest_green, R.color.wood_brown, R.color.ink_black, R.color.deep_sage, R.color.deep_rose)
        color = context.getColor(palette[(name.hashCode() and 0x7fffffff) % palette.size])
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.warm_cream); textAlign = Paint.Align.CENTER
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.serif_semibold)
    }

    override fun draw(canvas: Canvas) {
        val b = RectF(bounds)
        val r = b.width() * 0.2f
        canvas.drawRoundRect(b, r, r, bg)
        text.textSize = b.height() * 0.55f
        canvas.drawText(letter, b.centerX(), b.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; text.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { bg.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
