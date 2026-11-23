package dev.mangareader

import dev.mangareader.data.BackupData
import dev.mangareader.data.BookmarkEntity
import dev.mangareader.data.CategoryEntity
import dev.mangareader.data.FavouriteEntity
import dev.mangareader.data.HistoryEntity
import dev.mangareader.data.LocalStorage
import dev.mangareader.data.MangaEntity
import dev.mangareader.data.parseBackup
import dev.mangareader.data.toJson
import dev.mangareader.scrobbling.chapterNumber
import dev.mangareader.scrobbling.parseAniListSearch
import dev.mangareader.work.newChapters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FeatureLogicTest {

    @Test fun backup_roundTrip() {
        val data = BackupData(
            manga = listOf(MangaEntity("S:/m/", "/m/", "Judul", "https://c/1.jpg", null, "Penulis", "Action\nFantasy", "S")),
            categories = listOf(CategoryEntity(3, "Baca", 10, track = false)),
            favourites = listOf(FavouriteEntity("S:/m/", 3, 11)),
            history = listOf(HistoryEntity("S:/m/", "/m/ch-1/", "Ch 1", 4, 0.5f, 12)),
            bookmarks = listOf(BookmarkEntity("S:/m/", "/m/ch-1/", "Ch 1", 2, "https://i/2.jpg", 13)),
            domains = mapOf("S" to "baru.com"),
        )
        assertEquals(data, parseBackup(data.toJson()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun backup_rejectsUnknownVersion() {
        parseBackup("""{"version":99}""")
    }

    @Test fun tracker_firstSyncReportsNothingThenOnlyNew() {
        assertEquals(emptySet<String>(), newChapters(emptySet(), listOf("a", "b")))
        assertEquals(setOf("c"), newChapters(setOf("a", "b"), listOf("c", "a", "b")))
        assertEquals(emptySet<String>(), newChapters(setOf("a"), listOf("a")))
    }

    @Test fun chapterNumber_parsesCommonNames() {
        assertEquals(12, chapterNumber("Ch. 12 – Start [en]"))
        assertEquals(308, chapterNumber("Chapter 308"))
        assertEquals(5, chapterNumber("Vol.2 Chapter 5.5"))
        assertEquals(7, chapterNumber("Episode 7"))
        assertNull(chapterNumber("Prolog"))
    }

    @Test fun pageFileName_keepsOrderAndExtension() {
        assertEquals("0001.png", LocalStorage.pageFileName(0, "https://x/a/1.PNG?token=1"))
        assertEquals("0010.jpg", LocalStorage.pageFileName(9, "https://x/a/page"))
        assertEquals("0002.webp", LocalStorage.pageFileName(1, "https://x/2.webp"))
    }

    @Test fun localStorage_onlyCompleteChaptersAreReadable() {
        val root = Files.createTempDirectory("dl").toFile()
        val storage = LocalStorage(root)
        assertNull(storage.localPages("m", "/c1/"))
        storage.writeChapter("m", "/c1/") { dir ->
            File(dir, "0002.jpg").writeText("b"); File(dir, "0001.jpg").writeText("a")
        }
        val pages = storage.localPages("m", "/c1/")!!
        assertEquals(listOf("0001.jpg", "0002.jpg"), pages.map { it.substringAfterLast('/') })
        // A failed write leaves no partial chapter behind.
        runCatching { storage.writeChapter("m", "/c2/") { dir -> File(dir, "0001.jpg").writeText("x"); error("network") } }
        assertNull(storage.localPages("m", "/c2/"))
        assertTrue(storage.isDownloaded("m", "/c1/"))
        root.deleteRecursively()
    }

    @Test fun aniList_searchParsing() {
        val data = JSONObject("""{"Page":{"media":[
            {"id":30013,"title":{"romaji":"One Piece","english":null},"coverImage":{"medium":"https://c/op.jpg"}},
            {"id":1,"title":{"romaji":"Shingeki","english":"Attack on Titan"},"coverImage":null}]}}""")
        val r = parseAniListSearch(data)
        assertEquals("30013", r[0].id)
        assertEquals("One Piece", r[0].title)
        assertEquals("Attack on Titan", r[1].title)
    }
}
