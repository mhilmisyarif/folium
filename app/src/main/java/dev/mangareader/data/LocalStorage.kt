package dev.mangareader.data

import dev.mangareader.parser.ChapterText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Downloaded chapters on disk: <root>/<sha1(mangaId)>/<sha1(chapterUrl)>/0001.jpg ... (comics) or
 * .../chapter.json (novel text).
 * A ".complete" marker is written last, so interrupted downloads are never read as complete.
 */
class LocalStorage(private val root: File) {

    fun chapterDir(mangaId: String, chapterUrl: String) = File(File(root, sha1(mangaId)), sha1(chapterUrl))

    fun isDownloaded(mangaId: String, chapterUrl: String) = File(chapterDir(mangaId, chapterUrl), MARKER).isFile

    /** file:// page urls of a completed download, or null. */
    fun localPages(mangaId: String, chapterUrl: String): List<String>? {
        val dir = chapterDir(mangaId, chapterUrl)
        if (!File(dir, MARKER).isFile) return null
        return dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.name != TEXT_FILE }.orEmpty()
            .sortedBy { it.name }.map { it.toURI().toString() }
    }

    /** Text of a completed novel-chapter download, or null. */
    fun localText(mangaId: String, chapterUrl: String): ChapterText? {
        val dir = chapterDir(mangaId, chapterUrl)
        if (!File(dir, MARKER).isFile) return null
        val f = File(dir, TEXT_FILE).takeIf { it.isFile } ?: return null
        val o = JSONObject(f.readText())
        val ps = o.getJSONArray("paragraphs")
        return ChapterText(o.getString("title"), (0 until ps.length()).map { ps.getString(it) }, o.optBoolean("locked"), o.optString("webUrl"))
    }

    fun deleteChapter(mangaId: String, chapterUrl: String) = chapterDir(mangaId, chapterUrl).deleteRecursively()

    fun deleteManga(mangaId: String) = File(root, sha1(mangaId)).deleteRecursively()

    fun totalBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Writes pages via [write] into a temp dir, then atomically publishes it. */
    inline fun writeChapter(mangaId: String, chapterUrl: String, write: (dir: File) -> Unit) {
        val dir = chapterDir(mangaId, chapterUrl)
        val tmp = File(dir.parentFile, dir.name + ".tmp")
        tmp.deleteRecursively()
        check(tmp.mkdirs()) { "Cannot create folder $tmp" }
        try {
            write(tmp)
            File(tmp, MARKER).createNewFile()
            dir.deleteRecursively()
            check(tmp.renameTo(dir)) { "Cannot move $tmp" }
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            throw e
        }
    }

    companion object {
        const val MARKER = ".complete"
        const val TEXT_FILE = "chapter.json"

        /** Writes a novel chapter (already-sanitised paragraphs) into a [writeChapter] directory. */
        fun writeText(dir: File, t: ChapterText) = File(dir, TEXT_FILE).writeText(
            JSONObject().put("title", t.title).put("paragraphs", JSONArray(t.paragraphs)).put("locked", t.locked)
                .put("webUrl", t.webUrl).toString(),
        )

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Page file name keeping order and a sane extension. */
        fun pageFileName(index: Int, url: String): String {
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") } ?: "jpg"
            return "%04d.%s".format(index + 1, ext)
        }
    }
}
