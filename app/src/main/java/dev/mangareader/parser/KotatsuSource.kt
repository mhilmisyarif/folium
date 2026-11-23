package dev.mangareader.parser

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.koitharu.kotatsu.parsers.model.Manga as KManga
import org.koitharu.kotatsu.parsers.model.MangaChapter as KChapter

/** One Kotatsu parser (org.koitharu.kotatsu.parsers, same build as Kotatsu 9.4.1) behind the app's [MangaParser]. */
class KotatsuSource(
    val source: MangaParserSource,
    private val context: MangaLoaderContext,
    private val mirrors: MirrorSwitcher? = null,
) : MangaParser {

    /** Created on first use: building all ~1200 parsers up front would slow startup for nothing. */
    val parser by lazy { context.newParserInstance(source) }

    override val id: String = source.name
    override val name: String get() = source.title
    override val locale: String get() = source.locale
    override val isNsfw get() = source.contentType == ContentType.HENTAI
    override val contentType: String get() = source.contentType.name
    override val isBroken get() = source.isBroken
    override val supportsSearch get() = parser.filterCapabilities.isSearchSupported
    override val defaultDomain: String get() = parser.configKeyDomain.defaultValue
    override val domain: String get() = parser.domain
    override val domainPresets get() = parser.configKeyDomain.presetValues.toList()

    /** Options this parser declares for its settings screen (domain, user agent, image server …). */
    val configKeys: List<org.koitharu.kotatsu.parsers.config.ConfigKey<*>> get() = ArrayList<org.koitharu.kotatsu.parsers.config.ConfigKey<*>>().also(parser::onCreateConfig)

    /** Full chapter objects from the last getDetails; the app only stores url + name. */
    private val chapterCache = ConcurrentHashMap<String, KChapter>()

    override suspend fun getList(offset: Int, query: String?): List<Manga> = withMirrors { list(offset, query) }

    private suspend fun list(offset: Int, query: String?): List<Manga> {
        val q = query?.takeIf { it.isNotBlank() }
        val orders = parser.availableSortOrders
        val order = if (q != null && SortOrder.RELEVANCE in orders) SortOrder.RELEVANCE else orders.first()
        return parser.getList(offset, order, MangaListFilter(query = q)).map { it.toApp() }
    }

    override suspend fun getDetails(manga: Manga): Manga = withMirrors { details(manga) }

    private suspend fun details(manga: Manga): Manga {
        val details = parser.getDetails(manga.toKotatsu())
        val all = details.chapters.orEmpty()
        val languages = context.getPreferredLocales().flatMap { listOf(it.getDisplayLanguage(it), it.getDisplayLanguage(Locale.ENGLISH)) }
        val branch = preferredBranch(all.groupingBy { it.branch }.eachCount(), languages)
        val chapters = all.filter { it.branch == branch }
        chapters.forEach { chapterCache[it.url] = it }
        // Kotatsu lists oldest first; the app (and most sites) newest first.
        return details.toApp().copy(chapters = chapters.asReversed().map { MangaChapter(it.url, it.displayName()) })
    }

    override suspend fun getRelated(manga: Manga): List<Manga> =
        parser.getRelatedManga(manga.toKotatsu()).map { it.toApp() }

    override suspend fun getPages(chapter: MangaChapter): List<String> = withMirrors { pages(chapter) }

    private suspend fun pages(chapter: MangaChapter): List<String> {
        val k = chapterCache[chapter.url]
            ?: KChapter(parser.generateUid(chapter.url), chapter.name, 0f, 0, chapter.url, null, 0L, null, source)
        val pages = parser.getPages(k)
        // Most parsers return final urls; some resolve each page with a request, so do a few at a time.
        val limit = Semaphore(4)
        return coroutineScope { pages.map { p -> async { limit.withPermit { parser.getPageUrl(p) } } }.awaitAll() }
    }

    /**
     * "Pilih mirror otomatis" (Kotatsu MirrorSwitcher): when a request fails on one of the parser's own mirrors, try the
     * others and keep the first that works. A custom domain set by the user is never replaced.
     */
    private suspend fun <T> withMirrors(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            val m = mirrors
            val current = domain
            val others = domainPresets - current
            if (e is kotlinx.coroutines.CancellationException || e is CloudflareException || m == null || !m.isEnabled ||
                current !in domainPresets || others.isEmpty() || id in m.blacklist
            ) throw e
            m.mutex.withLock {
                for (mirror in others) {
                    m.setDomain(id, mirror, defaultDomain)
                    try {
                        return block()
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        m.setDomain(id, current, defaultDomain)
                        throw c
                    } catch (_: Exception) {
                        // next mirror
                    }
                }
                m.setDomain(id, current, defaultDomain)
                m.blacklist += id // once per app run, as in Kotatsu
            }
            throw e
        }
    }

    /** Manga behind a web link to this source (Kotatsu: LinkResolver), or null when the link is not a title page. */
    suspend fun resolveLink(link: okhttp3.HttpUrl): Manga? = context.newLinkResolver(link).getManga()?.toApp()

    override fun publicUrl(manga: Manga) = manga.publicUrl ?: manga.url.toAbsoluteUrl(parser.domain)

    private fun KManga.toApp() = Manga(
        url = url,
        title = title,
        coverUrl = (largeCoverUrl ?: coverUrl).orEmpty(),
        description = description,
        source = this@KotatsuSource.id,
        author = authors.firstOrNull(),
        tags = tags.map { it.title },
        publicUrl = publicUrl,
    )

    private fun Manga.toKotatsu() = KManga(
        id = parser.generateUid(url),
        title = title,
        altTitles = emptySet(),
        url = url,
        publicUrl = publicUrl(this),
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = coverUrl.takeIf { it.isNotEmpty() },
        tags = emptySet(),
        state = null,
        authors = setOfNotNull(author),
        description = description,
        source = this@KotatsuSource.source,
    )

    @Suppress("DEPRECATION") // `name` is the library's own fallback ("Vol 1 Chapter 2") for untitled chapters
    private fun KChapter.displayName() = name
}

/**
 * Chapters of multi-translation sources come in branches (usually a language name). Picks the branch matching
 * the user's languages, else the largest.
 * shortcut: other branches are hidden; add a branch picker in Details if users need other translations.
 */
internal fun preferredBranch(branches: Map<String?, Int>, languages: List<String>): String? {
    if (branches.size <= 1) return branches.keys.firstOrNull()
    return branches.keys.firstOrNull { b -> b != null && languages.any { it.isNotEmpty() && b.contains(it, ignoreCase = true) } }
        ?: branches.maxBy { it.value }.key
}

/** Shared state of automatic mirror switching (Kotatsu: MirrorSwitcher). */
class MirrorSwitcher(private val settings: android.content.SharedPreferences, private val domainPrefs: android.content.SharedPreferences) {
    val isEnabled get() = settings.getBoolean("mirror_switching", false)
    val mutex = kotlinx.coroutines.sync.Mutex()
    val blacklist: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun setDomain(sourceId: String, domain: String, default: String) {
        domainPrefs.edit().apply { if (domain == default) remove(sourceId) else putString(sourceId, domain) }.apply()
    }
}
