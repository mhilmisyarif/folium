package dev.mangareader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private val container = "META-INF/container.xml" to """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    @Test fun epub3_navTitles_spineOrder_cover() {
        val bytes = zip(
            container,
            "OEBPS/content.opf" to """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>Laskar Pelangi</dc:title><dc:creator>Andrea Hirata</dc:creator></metadata>
                <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="c1" href="text/bab%201.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="notes" href="text/notes.xhtml" media-type="application/xhtml+xml"/>
                  <item id="img" href="img/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="notes" linear="no"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/nav.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><nav>
                <ol><li><a href="text/bab%201.xhtml">Sepuluh Murid</a></li><li><a href="text/c2.xhtml#top">  Hujan
                Pertama </a></li></ol></nav></body></html>""",
            "OEBPS/text/bab 1.xhtml" to "<html/>",
            "OEBPS/text/c2.xhtml" to "<html/>",
            "OEBPS/img/cover.jpg" to "jpg",
        )
        val dir = File(tmp.root, "book")
        EpubBook.extract(bytes.inputStream(), dir)
        val book = EpubBook.open(dir)
        assertEquals("Laskar Pelangi", book.title)
        assertEquals("Andrea Hirata", book.author)
        assertEquals("OEBPS/img/cover.jpg", book.cover)
        // linear="no" items are skipped; titles come from the nav, whitespace collapsed; hrefs are decoded.
        assertEquals(listOf(EpubBook.Chapter("OEBPS/text/bab 1.xhtml", "Sepuluh Murid"), EpubBook.Chapter("OEBPS/text/c2.xhtml", "Hujan Pertama")),
            book.chapters)
        assertTrue(File(dir, book.chapters[0].href).isFile)
    }

    @Test fun epub2_ncxTitles_metaCover_fallbackNames() {
        val bytes = zip(
            container,
            "OEBPS/content.opf" to """<?xml version="1.0"?>
                <package version="2.0"><metadata><dc:title xmlns:dc="x">Old Book</dc:title><meta name="cover" content="cv"/></metadata>
                <manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  <item id="a" href="a.html" media-type="application/xhtml+xml"/><item id="b" href="b.html" media-type="application/xhtml+xml"/>
                  <item id="cv" href="cover.png" media-type="image/png"/></manifest>
                <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OEBPS/toc.ncx" to """<?xml version="1.0"?><ncx><navMap><navPoint><navLabel><text>Prolog</text></navLabel><content src="a.html"/></navPoint></navMap></ncx>""",
            "OEBPS/a.html" to "<html/>", "OEBPS/b.html" to "<html/>", "OEBPS/cover.png" to "png",
        )
        val dir = File(tmp.root, "old")
        EpubBook.extract(bytes.inputStream(), dir)
        val book = EpubBook.open(dir)
        assertEquals("Old Book", book.title)
        assertEquals("OEBPS/cover.png", book.cover)
        assertEquals(listOf("Prolog", "Chapter 2"), book.chapters.map { it.title })
    }

    @Test fun extract_rejectsZipSlip_andLeavesNothingBehind() {
        val bytes = zip(container, "../../evil.txt" to "x")
        val dir = File(tmp.root, "bad")
        try {
            EpubBook.extract(bytes.inputStream(), dir)
            throw AssertionError("zip slip not rejected")
        } catch (e: IOException) {
            assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
            assertFalse(File(dir, ".complete").exists())
        }
    }

    @Test fun normalize_resolvesDotSegmentsAndEscapes() {
        assertEquals("OEBPS/img/a b.png", EpubBook.normalize("OEBPS/text/../img/./a%20b.png"))
        assertEquals("c1+2.xhtml", EpubBook.normalize("c1+2.xhtml"))
    }
}
