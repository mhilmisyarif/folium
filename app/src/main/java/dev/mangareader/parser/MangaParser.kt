package dev.mangareader.parser

import java.io.Serializable

// App-side model. Sources come from Kotatsu's parser library (see KotatsuSource), adapted to this shape.
// Like Kotatsu, urls are stored relative to the domain so a domain change keeps favourites/history working.
data class Manga(
    val url: String,
    val title: String,
    val coverUrl: String,
    val description: String?,
    val source: String,
    val chapters: List<MangaChapter>? = null,
    val author: String? = null,
    val tags: List<String> = emptyList(),
    /** Absolute web page as given by the parser; some parsers need it again in getDetails. */
    val publicUrl: String? = null,
) : Serializable {
    val id: String get() = "$source:$url"
}

data class MangaChapter(val url: String, val name: String) : Serializable

interface MangaParser {
    /** Stable id (Kotatsu MangaParserSource name); stored with favourites/history and domain overrides. */
    val id: String
    val name: String
    /** ISO language code, empty for multilingual sources. */
    val locale: String
    val isNsfw: Boolean
    /** Kotatsu ContentType name: MANGA, MANHWA, HENTAI, COMICS… */
    val contentType: String
    /** Flagged as not working by the parser library; still listed (marked) like in Kotatsu. */
    val isBroken: Boolean
    val supportsSearch: Boolean
    /** User-editable in "Sumber & domain"; overrides live in SharedPreferences "domains". */
    val defaultDomain: String
    /** Domain in use: the user's override if valid, else [defaultDomain]. */
    val domain: String
    /** Known mirrors offered in the domain dialog (first = default). */
    val domainPresets: List<String>

    /** [offset] = number of items already received; an empty list means no more items. */
    suspend fun getList(offset: Int, query: String?): List<Manga>
    /** Returns [manga] with description/cover refreshed and [Manga.chapters] filled, newest first. */
    suspend fun getDetails(manga: Manga): Manga
    /** Titles the source itself relates to [manga] (Kotatsu: getRelatedManga); empty when it has none. */
    suspend fun getRelated(manga: Manga): List<Manga>
    /** Absolute image urls, in reading order. */
    suspend fun getPages(chapter: MangaChapter): List<String>

    /** Web page of the manga, for "open in browser" and sharing. */
    fun publicUrl(manga: Manga): String
}

/**
 * A source whose chapters are text (web novels) instead of page images. Chapters open in the text reader;
 * [getPages] is not used for these sources.
 */
interface TextSource : MangaParser {
    suspend fun getChapterText(chapter: MangaChapter): ChapterText

    /** Paid chapters are not downloaded: only a public preview of them is available. */
    fun isPaid(chapter: MangaChapter): Boolean = false
}

/**
 * One chapter's text. [paragraphs] are already sanitised HTML fragments (safe to render). [locked] = a paid chapter
 * of which the site publicly shows only a preview; the rest is never fetched — [webUrl] opens it on the site.
 */
data class ChapterText(val title: String, val paragraphs: List<String>, val locked: Boolean, val webUrl: String)
