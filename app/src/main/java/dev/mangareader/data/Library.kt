package dev.mangareader.data

/** Which part of the Library is shown (spec §4.2: all, favourites, history, custom collections). */
sealed interface LibraryView {
    data object All : LibraryView
    data object Favourites : LibraryView
    data object History : LibraryView
    data class Collection(val categoryId: Long) : LibraryView
}

enum class LibrarySort { LAST_READ, TITLE, ADDED }

data class LibraryFilter(
    val view: LibraryView = LibraryView.All,
    val status: ReadingStatus? = null,
    /** Content type: Kotatsu ContentType of the source, or the document format (PDF/EPUB); null = any. */
    val type: String? = null,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.LAST_READ,
)

/**
 * Applies [filter] to the Library rows. [typeOf] gives a row's content type (per source for online titles, since
 * kotatsu-parsers only types sources; the file format for local documents).
 */
fun List<LibraryRow>.filterLibrary(filter: LibraryFilter, typeOf: (LibraryRow) -> String?): List<LibraryRow> {
    val q = filter.query.trim()
    val result = filter {
        when (val v = filter.view) {
            LibraryView.All -> true
            LibraryView.Favourites -> it.favouritedAt != null
            LibraryView.History -> it.lastReadAt != null
            is LibraryView.Collection -> v.categoryId in it.categories
        } &&
            (filter.status == null || it.effectiveStatus == filter.status) &&
            (filter.type == null || typeOf(it) == filter.type) &&
            (q.isEmpty() || it.manga.title.contains(q, ignoreCase = true) || it.manga.author?.contains(q, ignoreCase = true) == true)
    }
    return when (filter.sort) {
        // Most recently read first; never-read entries after, newest favourite first.
        LibrarySort.LAST_READ -> result.sortedWith(compareByDescending<LibraryRow> { it.lastReadAt ?: Long.MIN_VALUE }
            .thenByDescending { it.favouritedAt ?: Long.MIN_VALUE })
        LibrarySort.TITLE -> result.sortedBy { it.manga.title.lowercase() }
        LibrarySort.ADDED -> result.sortedByDescending { it.favouritedAt ?: it.lastReadAt ?: Long.MIN_VALUE }
    }
}
