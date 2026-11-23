package dev.mangareader.document

import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal EPUB 2/3 reader model (Folium spec §5.2): spine order, chapter titles, metadata and cover, read from an
 * already-extracted book directory. Pure JVM (java.util.zip + javax.xml) so it is unit-testable.
 */
class EpubBook private constructor(
    val dir: File,
    val title: String?,
    val author: String?,
    /** Cover image path relative to [dir], if declared. */
    val cover: String?,
    val chapters: List<Chapter>,
) {
    /** [href] is relative to [dir], URL-decoded, without fragment. */
    data class Chapter(val href: String, val title: String)

    companion object {
        /**
         * Extracts an EPUB (zip) into [dir], refusing entries that would escape it ("zip slip") and capping the
         * total size. Writes to a temp dir and renames, so a half-extracted book is never used.
         */
        fun extract(input: InputStream, dir: File, maxBytes: Long = 512L * 1024 * 1024) {
            if (File(dir, ".complete").exists()) return
            val tmp = File(dir.parentFile, dir.name + ".tmp").apply { deleteRecursively(); mkdirs() }
            val root = tmp.canonicalFile
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(root, entry.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw IOException("Entri EPUB tidak valid: ${entry.name}")
                    if (entry.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IOException("EPUB terlalu besar")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            File(root, ".complete").writeText("1")
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("Gagal menyimpan EPUB")
        }

        fun open(dir: File): EpubBook {
            val container = parse(File(dir, "META-INF/container.xml"))
            val opfPath = container.byTag("rootfile").firstOrNull()?.getAttribute("full-path")
                ?: throw IOException("EPUB tanpa rootfile")
            val opfFile = File(dir, opfPath)
            val opfDir = opfPath.substringBeforeLast('/', "")
            fun resolve(href: String) = normalize(if (opfDir.isEmpty()) href else "$opfDir/$href")
            val opf = parse(opfFile)

            val manifest = opf.byTag("item").associate { it.getAttribute("id") to it }
            val title = opf.byTag("dc:title", "title").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val author = opf.byTag("dc:creator", "creator").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

            // Cover: EPUB3 properties="cover-image", else EPUB2 <meta name="cover" content="id">.
            val coverItem = manifest.values.firstOrNull { "cover-image" in it.getAttribute("properties").split(' ') }
                ?: opf.byTag("meta").firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")?.let { manifest[it] }
            val cover = coverItem?.getAttribute("href")?.let(::resolve)

            val titles = tocTitles(dir, manifest.values.toList(), opf, ::resolve)
            val spine = opf.byTag("itemref").mapNotNull { ref ->
                if (ref.getAttribute("linear") == "no") return@mapNotNull null
                manifest[ref.getAttribute("idref")]?.getAttribute("href")?.let(::resolve)
            }
            if (spine.isEmpty()) throw IOException("EPUB tanpa isi (spine kosong)")
            val chapters = spine.mapIndexed { i, href -> Chapter(href, titles[href] ?: "Bab ${i + 1}") }
            return EpubBook(dir, title, author, cover, chapters)
        }

        /** Chapter titles from the EPUB3 nav document or the EPUB2 NCX, keyed by resolved href. */
        private fun tocTitles(dir: File, items: List<Element>, opf: org.w3c.dom.Document, resolve: (String) -> String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            val nav = items.firstOrNull { "nav" in it.getAttribute("properties").split(' ') }
            if (nav != null) {
                val navHref = resolve(nav.getAttribute("href"))
                val navDir = navHref.substringBeforeLast('/', "")
                runCatching {
                    parse(File(dir, navHref)).byTag("a").forEach { a ->
                        val href = a.getAttribute("href").substringBefore('#').takeIf { it.isNotEmpty() } ?: return@forEach
                        val target = normalize(if (navDir.isEmpty()) href else "$navDir/$href")
                        val text = a.textContent.trim().replace(Regex("\\s+"), " ")
                        if (text.isNotEmpty()) result.putIfAbsent(target, text)
                    }
                }
            }
            if (result.isEmpty()) {
                val ncxId = opf.byTag("spine").firstOrNull()?.getAttribute("toc")
                val ncx = items.firstOrNull { it.getAttribute("id") == ncxId || it.getAttribute("media-type") == "application/x-dtbncx+xml" }
                if (ncx != null) {
                    val ncxHref = resolve(ncx.getAttribute("href"))
                    val ncxDir = ncxHref.substringBeforeLast('/', "")
                    runCatching {
                        parse(File(dir, ncxHref)).byTag("navPoint").forEach { p ->
                            val label = p.byTag("text").firstOrNull()?.textContent?.trim().orEmpty()
                            val src = p.byTag("content").firstOrNull()?.getAttribute("src")?.substringBefore('#') ?: return@forEach
                            if (label.isNotEmpty()) result.putIfAbsent(normalize(if (ncxDir.isEmpty()) src else "$ncxDir/$src"), label)
                        }
                    }
                }
            }
            return result
        }

        private fun parse(file: File): org.w3c.dom.Document {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // No DTDs or external entities: EPUB files are untrusted input.
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isExpandEntityReferences = false
            }
            return file.inputStream().use { f.newDocumentBuilder().parse(it) }
        }

        private fun org.w3c.dom.Node.byTag(vararg names: String): List<Element> {
            val doc = (this as? org.w3c.dom.Document)?.documentElement ?: this as Element
            val all = doc.getElementsByTagName("*")
            return (0 until all.length).map { all.item(it) as Element }.filter { e ->
                names.any { n -> e.tagName == n || e.tagName.substringAfter(':') == n }
            }
        }

        /** Resolves "a/../b" and percent-escapes inside the book. */
        internal fun normalize(path: String): String {
            val parts = ArrayDeque<String>()
            URLDecoder.decode(path.replace("+", "%2B"), "UTF-8").split('/').forEach {
                when (it) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(it)
                }
            }
            return parts.joinToString("/")
        }
    }
}
