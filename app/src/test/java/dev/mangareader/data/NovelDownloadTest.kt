package dev.mangareader.data

import dev.mangareader.parser.ChapterText
import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter
import dev.mangareader.parser.novel.WebnovelParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun text_roundTrip_onlyWhenComplete() {
        val storage = LocalStorage(tmp.root)
        val text = ChapterText("Bab 1", listOf("Satu <b>dua</b>", "Tiga &amp; empat"), false, "https://www.webnovel.com/book/1/2")
        assertNull(storage.localText("WEBNOVEL:/book/1", "/book/1/2"))
        storage.writeChapter("WEBNOVEL:/book/1", "/book/1/2") { LocalStorage.writeText(it, text) }
        assertEquals(text, storage.localText("WEBNOVEL:/book/1", "/book/1/2"))
        assertTrue(storage.isDownloaded("WEBNOVEL:/book/1", "/book/1/2"))
        // A text download is never mistaken for comic pages.
        assertEquals(emptyList<String>(), storage.localPages("WEBNOVEL:/book/1", "/book/1/2"))
    }

    @Test fun paidChapters_areRecognisedFromTheCatalogLabel() {
        val html = javaClass.getResource("/fixtures/webnovel/catalog.html")!!.readText()
        val chapters = WebnovelParsing.catalog(html, Manga("/book/17013878605141805", "x", "", null, "WEBNOVEL"), "WEBNOVEL").chapters!!
        val paid = MangaChapter("/book/1/2", "Chapter 9: Something ${WebnovelParsing.PAID_LABEL}")
        // Same rule as WebnovelSource.isPaid (the class needs Android prefs, so the rule is checked on the label).
        fun isPaid(c: MangaChapter) = c.name.endsWith(" ${WebnovelParsing.PAID_LABEL}")
        assertTrue(isPaid(paid))
        assertTrue(chapters.none(::isPaid)) // this book is free
    }
}
