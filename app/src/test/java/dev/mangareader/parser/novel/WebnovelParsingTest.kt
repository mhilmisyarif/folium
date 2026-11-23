package dev.mangareader.parser.novel

import dev.mangareader.parser.Manga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against saved WebNovel pages (real structure, author text replaced by dummy text). */
class WebnovelParsingTest {
    private fun page(name: String) = javaClass.getResource("/fixtures/webnovel/$name")!!.readText()

    @Test fun ranking_listsBooksWithCovers() {
        val list = WebnovelParsing.ranking(page("ranking.html"), "WEBNOVEL")
        assertEquals(20, list.size)
        val first = list.first()
        assertEquals("Eternal Melody", first.title)
        assertTrue(first.url.matches(Regex("/book/\\d+")))
        assertTrue(first.coverUrl.startsWith("https://book-pic.webnovel.com/bookcover/"))
        assertEquals("WEBNOVEL:${first.url}", first.id)
    }

    @Test fun search_keepsResultOrder() {
        val list = WebnovelParsing.search(page("search.html"), "WEBNOVEL")
        assertTrue(list.size >= 10)
        assertEquals("The Martial Unity", list.first().title)
        assertEquals("Lord_Streak", list.first().author)
        assertTrue("Action" in list.first().tags)
    }

    @Test fun catalog_allChaptersNewestFirst_paidLabelled() {
        val m = WebnovelParsing.catalog(page("catalog.html"), Manga("/book/17013878605141805", "x", "", null, "WEBNOVEL"), "WEBNOVEL")
        assertEquals("Aimless Travels In Multiverse", m.title)
        assertEquals("TurkishJesus", m.author)
        val ch = m.chapters!!
        assertEquals(79, ch.size)
        // Newest first; volume 0 (auxiliary) comes first in reading order, so it is last here.
        assertEquals("/book/17013878605141805/45671303105017253", ch[ch.size - 2].url)
        assertEquals("Chapter 1: Worst Day Ever", ch[ch.size - 2].name)
        assertFalse(ch.any { it.name.endsWith(WebnovelParsing.PAID_LABEL) }) // this book is all free
    }

    @Test fun chapter_free_isSanitisedText() {
        val t = WebnovelParsing.chapter(page("chapter_free.html"), "https://www.webnovel.com/book/1/2")
        assertFalse(t.locked)
        assertEquals("Worst Day Ever", t.title)
        assertEquals(49, t.paragraphs.size)
        assertEquals("Paragraf uji 1 <b>tebal</b> &amp;", t.paragraphs.first()) // <script> removed, & escaped
        assertFalse(t.paragraphs.any { "script" in it })
    }

    @Test fun chapter_paid_isOnlyThePublicPreview_andLocked() {
        val t = WebnovelParsing.chapter(page("chapter_paid.html"), "https://www.webnovel.com/book/1/2")
        assertTrue(t.locked)
        assertEquals(4, t.paragraphs.size)
    }
}
