package dev.mangareader.document

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import dev.mangareader.app
import dev.mangareader.data.LibraryStatusEntity
import dev.mangareader.data.ReadingStatus
import dev.mangareader.parser.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Local documents (Folium spec §5.2/5.3): PDFs and EPUBs the user picks with the system file picker. Stored as
 * ordinary `manga` rows with source [LOCAL_SOURCE] so history, status and the Library work unchanged; the format is
 * kept as the single tag ("PDF" / "EPUB"), which also feeds the Library type filter.
 */
const val LOCAL_SOURCE = "LOCAL"

enum class DocFormat { PDF, EPUB }

fun Manga.isLocal() = source == LOCAL_SOURCE
fun Manga.docFormat(): DocFormat? = tags.firstNotNullOfOrNull { t -> DocFormat.entries.firstOrNull { it.name == t } }

/**
 * Share of a document read, for history/Library progress. PDF: pages up to and including the one on screen, so the
 * last page is 100%. EPUB: chapters before this one plus the scroll [offset] (0..1) inside it.
 */
fun documentProgress(format: DocFormat, position: Int, offset: Float, count: Int): Float {
    if (count <= 0) return 0f
    val read = if (format == DocFormat.PDF) position + 1f else position + offset.coerceIn(0f, 1f)
    return (read / count).coerceIn(0f, 1f)
}

object LocalDocuments {

    /** Book files live under the app's own storage, one folder per document. */
    fun bookDir(context: Context, uri: String) = File(context.filesDir, "books/" + sha1(uri))

    private fun coverFile(context: Context, uri: String) = File(context.filesDir, "covers/" + sha1(uri) + ".png")

    /**
     * Imports a picked file: keeps read access across restarts, reads its metadata and cover, and adds it to the
     * Library as "Ingin dibaca". Re-importing the same file updates the entry instead of duplicating it.
     */
    suspend fun import(context: Context, uri: Uri): Manga = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Dokumen"
        val mime = resolver.getType(uri).orEmpty()
        val format = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> DocFormat.PDF
            mime == "application/epub+zip" || name.endsWith(".epub", true) -> DocFormat.EPUB
            else -> throw IOException("Format belum didukung. Pilih file PDF atau EPUB.")
        }
        val key = uri.toString()
        var title = name.substringBeforeLast('.')
        var author: String? = null
        val cover = coverFile(context, key)
        when (format) {
            DocFormat.PDF -> renderPdfCover(context, uri, cover)
            DocFormat.EPUB -> {
                val dir = bookDir(context, key)
                requireNotNull(resolver.openInputStream(uri)) { "File tidak bisa dibuka" }.use { EpubBook.extract(it, dir) }
                val book = EpubBook.open(dir)
                book.title?.let { title = it }
                author = book.author
                book.cover?.let { File(dir, it) }?.takeIf { it.isFile }?.let { src ->
                    cover.parentFile?.mkdirs(); src.copyTo(cover, overwrite = true)
                }
            }
        }
        val manga = Manga(
            url = key,
            title = title,
            coverUrl = if (cover.isFile) Uri.fromFile(cover).toString() else "",
            description = null,
            source = LOCAL_SOURCE,
            author = author,
            tags = listOf(format.name),
        )
        val app = context.app
        app.repository.saveManga(manga)
        val dao = app.db.dao()
        if (dao.history(manga.id) == null) dao.upsertStatus(LibraryStatusEntity(manga.id, ReadingStatus.WANT_TO_READ.code))
        manga
    }

    /** Removes the entry (history/status cascade), the extracted book and cover, and releases file access. */
    suspend fun delete(context: Context, manga: Manga) = withContext(Dispatchers.IO) {
        context.app.db.dao().deleteManga(manga.id)
        bookDir(context, manga.url).deleteRecursively()
        coverFile(context, manga.url).delete()
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(manga.url), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun renderPdfCover(context: Context, uri: Uri, out: File) {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("File tidak bisa dibuka")
        PdfRenderer(fd).use { pdf ->
            if (pdf.pageCount == 0) throw IOException("PDF kosong")
            pdf.openPage(0).use { page ->
                val w = 360
                val bmp = Bitmap.createBitmap(w, (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE) // PDF pages are transparent where nothing is drawn
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                out.parentFile?.mkdirs()
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
