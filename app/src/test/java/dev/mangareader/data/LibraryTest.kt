package dev.mangareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTest {

    private fun row(id: String, title: String, lastRead: Long? = null, status: ReadingStatus? = null, fav: Long? = null, cats: String? = null,
                    source: String = "SRC") =
        LibraryRow(MangaEntity("$source:$id", id, title, "", null, null, "", source), null, null, lastRead, status?.code, fav, cats)

    private val rows = listOf(
        row("a", "Alpha", lastRead = 300),
        row("b", "beta", fav = 500, cats = "1,2"),
        row("c", "Gamma", lastRead = 100, status = ReadingStatus.COMPLETED, fav = 50, cats = "2"),
        row("d", "Delta", status = ReadingStatus.WANT_TO_READ, source = "OTHER"),
    )
    private val types = mapOf("SRC" to "MANGA", "OTHER" to "MANHWA")

    private fun ids(f: LibraryFilter) = rows.filterLibrary(f) { types[it.manga.source] }.map { it.manga.url }

    @Test fun effectiveStatus_derivesReadingFromHistoryOnly() {
        assertEquals(ReadingStatus.READING, rows[0].effectiveStatus) // history, no explicit status
        assertNull(rows[1].effectiveStatus) // favourite only
        assertEquals(ReadingStatus.COMPLETED, rows[2].effectiveStatus) // explicit wins over history
    }

    @Test fun views_collectionsAndStatus() {
        assertEquals(listOf("a", "c", "b", "d"), ids(LibraryFilter()))
        assertEquals(setOf("b", "c"), ids(LibraryFilter(view = LibraryView.Favourites)).toSet())
        assertEquals(setOf("a", "c"), ids(LibraryFilter(view = LibraryView.History)).toSet())
        assertEquals(listOf("b"), ids(LibraryFilter(view = LibraryView.Collection(1))))
        assertEquals(listOf("a"), ids(LibraryFilter(status = ReadingStatus.READING)))
        assertEquals(listOf("d"), ids(LibraryFilter(type = "MANHWA")))
    }

    @Test fun search_andSort() {
        assertEquals(listOf("b"), ids(LibraryFilter(query = "BET")))
        assertEquals(listOf("a", "b", "d", "c"), ids(LibraryFilter(sort = LibrarySort.TITLE)))
        assertEquals(listOf("b", "a", "c", "d"), ids(LibraryFilter(sort = LibrarySort.ADDED)))
    }

    @Test fun backup_keepsStatuses_andOldBackupsStillParse() {
        val data = BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(),
            listOf(LibraryStatusEntity("S:x", ReadingStatus.ON_HOLD.code, 7)))
        assertEquals(data.statuses, parseBackup(data.toJson()).statuses)
        // A pre-Folium backup has no "statuses" key.
        assertEquals(emptyList<LibraryStatusEntity>(), parseBackup("""{"version":1}""").statuses)
        // Unknown status codes (from a newer app) are skipped, not restored as garbage.
        assertEquals(emptyList<LibraryStatusEntity>(),
            parseBackup("""{"version":1,"statuses":[{"mangaId":"S:x","status":99,"updatedAt":1}]}""").statuses)
    }
}
