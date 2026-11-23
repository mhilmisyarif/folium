package dev.mangareader.data

import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter
import dev.mangareader.parser.MangaParser

/** Glue between parsers and the database (Kotatsu: MangaDataRepository + history/favourites repositories). */
class MangaRepository(
    private val dao: MangaDao,
    private val sources: (String) -> MangaParser,
    private val onChapterRead: (Manga, MangaChapter) -> Unit,
    /** Name for the first collection, in the app language when it is created. */
    private val defaultCategoryName: () -> String = { "Main" },
) {

    /** Cached manga + chapters, for instant/offline display. */
    suspend fun cached(mangaId: String): Pair<Manga, List<MangaChapter>>? {
        val m = dao.manga(mangaId) ?: return null
        return m.toManga() to dao.chapters(mangaId).map { it.toChapter() }
    }

    /** Fetches fresh details and persists manga + chapter list. Returns manga with chapters. */
    suspend fun refresh(manga: Manga): Manga {
        val details = sources(manga.source).getDetails(manga)
        val chapters = details.chapters.orEmpty()
        dao.upsertManga(details.toEntity())
        dao.replaceChapters(details.id, chapters.mapIndexed { i, c -> ChapterEntity(details.id, c.url, c.name, i) })
        return details
    }

    suspend fun chapters(mangaId: String): List<MangaChapter> = dao.chapters(mangaId).map { it.toChapter() }

    /** [offset]: scroll position inside a text chapter (novels); 0 for comics. */
    suspend fun saveHistory(manga: Manga, chapter: MangaChapter, page: Int, offset: Float = 0f) {
        val chapters = dao.chapters(manga.id)
        val pos = chapters.indexOfFirst { it.url == chapter.url }
        // Sources list newest first, so reading progress counts from the end.
        val percent = if (pos < 0 || chapters.isEmpty()) 0f else (chapters.size - pos).toFloat() / chapters.size
        val previous = dao.history(manga.id)
        dao.upsertManga(manga.copy(chapters = null).toEntity())
        dao.upsertHistory(HistoryEntity(manga.id, chapter.url, chapter.name, page, percent, offset = offset))
        markStarted(manga.id)
        if (previous?.chapterUrl != chapter.url) onChapterRead(manga, chapter)
    }

    /** Saves document progress (PDF page / EPUB chapter + offset) the same way as comic history. */
    suspend fun saveDocumentHistory(manga: Manga, history: HistoryEntity) {
        dao.upsertManga(manga.copy(chapters = null).toEntity())
        dao.upsertHistory(history)
        markStarted(manga.id)
    }

    /** Starting to read a title marked "Ingin dibaca" moves it to "Sedang dibaca". Other statuses are the user's choice. */
    private suspend fun markStarted(mangaId: String) {
        if (dao.status(mangaId) == ReadingStatus.WANT_TO_READ.code) dao.upsertStatus(LibraryStatusEntity(mangaId, ReadingStatus.READING.code))
    }

    suspend fun defaultCategoryId(): Long =
        dao.categories().firstOrNull()?.id ?: dao.insertCategory(CategoryEntity(title = defaultCategoryName()))

    suspend fun setFavourite(manga: Manga, categoryIds: Collection<Long>) {
        dao.upsertManga(manga.copy(chapters = null).toEntity())
        val current = dao.favouriteCategoryIds(manga.id).toSet()
        (current - categoryIds.toSet()).forEach { dao.deleteFavourite(manga.id, it) }
        (categoryIds.toSet() - current).forEach { dao.insertFavourite(FavouriteEntity(manga.id, it)) }
    }

    suspend fun saveManga(manga: Manga) = dao.upsertManga(manga.copy(chapters = null).toEntity())
}
