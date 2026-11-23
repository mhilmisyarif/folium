package dev.mangareader.parser.novel

import android.content.SharedPreferences
import dev.mangareader.parser.ChapterText
import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter
import dev.mangareader.parser.TextSource
import dev.mangareader.parser.normalizeDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.IOException

/**
 * WebNovel (webnovel.com), chosen by the product owner as the first web-novel source.
 *
 * Reads the server-rendered Next.js data (`__NEXT_DATA__`) of the public mobile pages — ranking, search, catalog and
 * chapter — the same pages a browser shows without logging in. Only public content is used: free chapters in full;
 * for paid chapters just the preview the site itself shows to everyone, marked [ChapterText.locked] with a link to
 * buy/read on the site. Encrypted or paid content is never requested or decoded.
 */
class WebnovelSource(
    private val client: OkHttpClient,
    private val domainPrefs: SharedPreferences,
) : TextSource {
    override val id = ID
    override val name = "WebNovel"
    override val locale = "en"
    override val isNsfw = false
    override val contentType = "NOVEL"
    override val isBroken = false
    override val supportsSearch = true
    override val defaultDomain = "m.webnovel.com"
    override val domain: String get() = domainPrefs.getString(id, null)?.let(::normalizeDomain) ?: defaultDomain
    override val domainPresets = listOf(defaultDomain)

    override suspend fun getList(offset: Int, query: String?): List<Manga> {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) {
            // The ranking page renders only its first page server-side; later pages need the site's private API.
            if (offset > 0) return emptyList()
            return WebnovelParsing.ranking(get("/ranking/novel/all_time/power_rank"), id)
        }
        val page = offset / SEARCH_PAGE_SIZE + 1
        val url = "https://$domain/search".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", q).addQueryParameter("pageIndex", page.toString()).build().toString()
        return WebnovelParsing.search(fetch(url), id)
    }

    override suspend fun getDetails(manga: Manga): Manga =
        WebnovelParsing.catalog(get("/book/${bookId(manga.url)}/catalog"), manga, id)

    override suspend fun getChapterText(chapter: MangaChapter): ChapterText =
        WebnovelParsing.chapter(get(chapter.url), "https://www.webnovel.com" + chapter.url)

    override suspend fun getPages(chapter: MangaChapter): List<String> = throw UnsupportedOperationException("Text source")

    /** The catalog labels paid chapters (see [WebnovelParsing.catalog]). */
    override fun isPaid(chapter: MangaChapter) =
        chapter.name.endsWith(" ${WebnovelParsing.PAID_LABEL}") || chapter.name.endsWith(" · ${WebnovelParsing.LEGACY_PAID_LABEL}")

    override suspend fun getRelated(manga: Manga): List<Manga> = emptyList()

    override fun publicUrl(manga: Manga) = "https://www.webnovel.com/book/${bookId(manga.url)}"

    private suspend fun get(path: String) = fetch("https://$domain$path")

    private suspend fun fetch(url: String): String = runInterruptible(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { rsp ->
            if (!rsp.isSuccessful) throw IOException("HTTP ${rsp.code} for $url")
            rsp.body.string()
        }
    }

    companion object {
        const val ID = "WEBNOVEL"
        private const val SEARCH_PAGE_SIZE = 20
        fun bookId(url: String) = url.removePrefix("/book/").substringBefore('/')
    }
}

/** Pure parsing of WebNovel's page data (unit-tested against saved pages). */
internal object WebnovelParsing {

    fun nextData(html: String): JSONObject {
        val json = Jsoup.parse(html).getElementById("__NEXT_DATA__")?.data() ?: throw IOException("The WebNovel page format has changed")
        return JSONObject(json).getJSONObject("props").getJSONObject("initialState").getJSONObject("entities")
    }

    /** The site's image service; the "/300/300.jpg" form seen on some pages 404s for many books. */
    @Suppress("UNUSED_PARAMETER")
    fun cover(bookId: String, updateTime: Long = 0) = "https://book-pic.webnovel.com/bookcover/$bookId?imageMogr2/thumbnail/300x"

    private fun JSONObject.tags(): List<String> {
        val arr = optJSONArray("tagInfo") ?: optJSONArray("tagInfos") ?: return listOfNotNull(optString("categoryName").takeIf { it.isNotEmpty() })
        return (0 until arr.length()).mapNotNull { i ->
            when (val t = arr.get(i)) {
                is String -> t
                is JSONObject -> t.optString("tagName").ifEmpty { t.optString("name") }
                else -> null
            }?.takeIf { it.isNotEmpty() }?.lowercase()?.replaceFirstChar { it.uppercase() }
        }.distinct()
    }

    private fun JSONObject.toManga(source: String): Manga {
        val bookId = getString("bookId")
        return Manga(
            url = "/book/$bookId",
            title = optString("bookName"),
            coverUrl = cover(bookId, optLong("coverUpdateTime")),
            description = optString("description").takeIf { it.isNotBlank() },
            source = source,
            author = optString("authorName").takeIf { it.isNotBlank() },
            tags = tags(),
        )
    }

    fun ranking(html: String, source: String): List<Manga> {
        val pages = nextData(html).getJSONObject("rankPage")
        val first = pages.getJSONObject(pages.keys().next())
        val items = first.getJSONArray("bookItems")
        return (0 until items.length()).map { items.getJSONObject(it).toManga(source) }
    }

    fun search(html: String, source: String): List<Manga> {
        val e = nextData(html)
        val books = e.getJSONObject("books")
        val results = e.getJSONObject("searchResultList")
        if (results.length() == 0) return emptyList()
        val ids = results.getJSONObject(results.keys().next()).getJSONObject("bookInfo").optJSONArray("bookItems") ?: JSONArray()
        return (0 until ids.length()).mapNotNull { books.optJSONObject(ids.getString(it))?.toManga(source) }
    }

    /** Book details + all chapters (volume order), newest first like the rest of the app. Paid ones are labelled. */
    fun catalog(html: String, manga: Manga, source: String): Manga {
        val e = nextData(html)
        val bookId = WebnovelSource.bookId(manga.url)
        val book = e.getJSONObject("books").optJSONObject(bookId) ?: throw IOException("Book not found")
        val chapters = e.getJSONObject("chapter")
        val volumes = e.getJSONObject("volume")
        val order = e.getJSONObject("catalog").getJSONObject(bookId).getJSONArray("volumeItems")
        val list = ArrayList<MangaChapter>()
        for (v in 0 until order.length()) {
            val items = volumes.optJSONObject(order.getString(v))?.optJSONArray("chapterItems") ?: continue
            for (i in 0 until items.length()) {
                val c = chapters.optJSONObject(items.getString(i)) ?: continue
                val index = c.optInt("chapterIndex")
                val title = c.optString("chapterName").ifBlank { "Chapter $index" }
                val label = if (index > 0) "Chapter $index: $title" else title
                val paid = c.optInt("isVip") > 0 && c.optInt("isAuth") == 0
                list += MangaChapter("/book/$bookId/${c.getString("chapterId")}", if (paid) "$label $PAID_LABEL" else label)
            }
        }
        val details = book.toManga(source)
        return manga.copy(
            title = details.title.ifEmpty { manga.title },
            coverUrl = details.coverUrl,
            description = details.description ?: manga.description,
            author = details.author ?: manga.author,
            tags = details.tags.ifEmpty { manga.tags },
            chapters = list.asReversed(),
        )
    }

    fun chapter(html: String, webUrl: String): ChapterText {
        val c = nextData(html).getJSONObject("chapter").let { it.getJSONObject(it.keys().next()) }
        val contents = c.optJSONArray("contents") ?: JSONArray()
        val paragraphs = (0 until contents.length()).mapNotNull { i ->
            // Site text can carry markup; keep only basic formatting, never scripts, links' JS or styles.
            Jsoup.clean(contents.getJSONObject(i).optString("content").trim(), Safelist.basic()).takeIf { it.isNotBlank() }
        }
        val locked = c.optInt("vipStatus") > 0 && c.optInt("isAuth") == 0
        return ChapterText(c.optString("chapterName"), paragraphs, locked, webUrl)
    }

    /** Marks paid chapters in their name (shown as-is in every language). */
    const val PAID_LABEL = "🔒"
    /** Label used before the app was translated; still in stored chapter lists. */
    const val LEGACY_PAID_LABEL = "Berbayar"
}
