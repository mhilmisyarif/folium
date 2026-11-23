package dev.mangareader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        MangaEntity::class, ChapterEntity::class, CategoryEntity::class, FavouriteEntity::class, HistoryEntity::class,
        BookmarkEntity::class, TrackLogEntity::class, StatsEntity::class, ScrobblingEntity::class, DownloadEntity::class,
        LibraryStatusEntity::class,
    ],
    version = 4,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MangaDao

    companion object {
        // Name differs from the v0.1 SQLiteOpenHelper file ("reader.db"), so the prototype DB is simply left unused.
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "kotatsu-lite.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        /** v2: Kotatsu parsers give each manga an absolute public url that some need again for details. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE manga ADD COLUMN publicUrl TEXT")
        }

        /** v3 (Folium Library): reading statuses in a new table; existing tables are untouched. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(
                "CREATE TABLE IF NOT EXISTS `library_status` (`mangaId` TEXT NOT NULL, `status` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`mangaId`), FOREIGN KEY(`mangaId`) REFERENCES `manga`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }

        /** v4 (Folium documents): position inside an EPUB chapter; existing rows get 0 (start of chapter). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE history ADD COLUMN offset REAL NOT NULL DEFAULT 0")
        }
    }
}

@Dao
interface MangaDao {
    // --- manga & chapters ---
    @Upsert suspend fun upsertManga(m: MangaEntity) // Upsert = UPDATE-or-INSERT, never DELETE (keeps FK children)

    @Query("SELECT * FROM manga WHERE id = :id") suspend fun manga(id: String): MangaEntity?

    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY position")
    suspend fun chapters(mangaId: String): List<ChapterEntity>

    @Query("DELETE FROM chapters WHERE mangaId = :mangaId") suspend fun deleteChapters(mangaId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChapters(c: List<ChapterEntity>)

    @Transaction
    suspend fun replaceChapters(mangaId: String, chapters: List<ChapterEntity>) {
        deleteChapters(mangaId); insertChapters(chapters)
    }

    // --- favourites ---
    @Query("SELECT * FROM favourite_categories ORDER BY createdAt") fun observeCategories(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM favourite_categories ORDER BY createdAt") suspend fun categories(): List<CategoryEntity>
    @Insert suspend fun insertCategory(c: CategoryEntity): Long
    @Upsert suspend fun upsertCategory(c: CategoryEntity)
    @Query("DELETE FROM favourite_categories WHERE id = :id") suspend fun deleteCategory(id: Long)

    @Query(
        "SELECT m.* FROM manga m JOIN favourites f ON f.mangaId = m.id " +
            "WHERE :categoryId = 0 OR f.categoryId = :categoryId GROUP BY m.id ORDER BY MAX(f.createdAt) DESC",
    )
    fun observeFavourites(categoryId: Long): Flow<List<MangaEntity>>

    @Query("SELECT categoryId FROM favourites WHERE mangaId = :mangaId") suspend fun favouriteCategoryIds(mangaId: String): List<Long>
    @Query("SELECT categoryId FROM favourites WHERE mangaId = :mangaId") fun observeFavouriteCategoryIds(mangaId: String): Flow<List<Long>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFavourite(f: FavouriteEntity)
    @Query("DELETE FROM favourites WHERE mangaId = :mangaId AND categoryId = :categoryId") suspend fun deleteFavourite(mangaId: String, categoryId: Long)
    @Query("SELECT * FROM favourites") suspend fun allFavourites(): List<FavouriteEntity>

    /** Favourites in tracked categories — the tracker's work list. */
    @Query(
        "SELECT DISTINCT m.* FROM manga m JOIN favourites f ON f.mangaId = m.id " +
            "JOIN favourite_categories c ON c.id = f.categoryId WHERE c.track = 1",
    )
    suspend fun trackedManga(): List<MangaEntity>

    // --- history ---
    @Upsert suspend fun upsertHistory(h: HistoryEntity)
    @Query("SELECT * FROM history WHERE mangaId = :mangaId") suspend fun history(mangaId: String): HistoryEntity?
    @Query("SELECT * FROM history WHERE mangaId = :mangaId") fun observeHistory(mangaId: String): Flow<HistoryEntity?>
    @Query("SELECT m.*, h.chapterUrl, h.chapterName, h.page, h.percent, h.updatedAt FROM manga m JOIN history h ON h.mangaId = m.id ORDER BY h.updatedAt DESC")
    fun observeHistoryList(): Flow<List<HistoryWithManga>>
    @Query("SELECT * FROM history") suspend fun allHistory(): List<HistoryEntity>
    @Query("DELETE FROM history WHERE mangaId = :mangaId") suspend fun deleteHistory(mangaId: String)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM manga WHERE id = :id") suspend fun deleteManga(id: String) // cascades to history, status, …

    // --- library (Folium) ---
    @Query(
        "SELECT m.*, h.chapterName AS chapterName, h.percent AS percent, h.updatedAt AS lastReadAt, s.status AS status, " +
            "(SELECT MAX(f.createdAt) FROM favourites f WHERE f.mangaId = m.id) AS favouritedAt, " +
            "(SELECT GROUP_CONCAT(f.categoryId) FROM favourites f WHERE f.mangaId = m.id) AS categoryIds " +
            "FROM manga m LEFT JOIN history h ON h.mangaId = m.id LEFT JOIN library_status s ON s.mangaId = m.id " +
            "WHERE h.mangaId IS NOT NULL OR s.mangaId IS NOT NULL OR EXISTS (SELECT 1 FROM favourites f WHERE f.mangaId = m.id)",
    )
    fun observeLibrary(): Flow<List<LibraryRow>>
    @Query("SELECT status FROM library_status WHERE mangaId = :mangaId") fun observeStatus(mangaId: String): Flow<Int?>
    @Query("SELECT status FROM library_status WHERE mangaId = :mangaId") suspend fun status(mangaId: String): Int?
    @Upsert suspend fun upsertStatus(s: LibraryStatusEntity)
    @Query("DELETE FROM library_status WHERE mangaId = :mangaId") suspend fun deleteStatus(mangaId: String)
    @Query("SELECT * FROM library_status") suspend fun allStatuses(): List<LibraryStatusEntity>

    // --- bookmarks ---
    @Upsert suspend fun upsertBookmark(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE mangaId = :mangaId AND chapterUrl = :chapterUrl AND page = :page")
    suspend fun deleteBookmark(mangaId: String, chapterUrl: String, page: Int)
    @Query("SELECT b.*, m.title, m.source FROM bookmarks b JOIN manga m ON m.id = b.mangaId ORDER BY b.createdAt DESC")
    fun observeBookmarks(): Flow<List<BookmarkWithManga>>
    @Query("SELECT * FROM bookmarks WHERE mangaId = :mangaId AND chapterUrl = :chapterUrl")
    fun observeChapterBookmarks(mangaId: String, chapterUrl: String): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks") suspend fun allBookmarks(): List<BookmarkEntity>

    // --- tracker feed ---
    @Insert suspend fun insertTrackLog(t: TrackLogEntity)
    @Query("SELECT m.*, t.id AS logId, t.chapters, t.createdAt, t.unread FROM track_logs t JOIN manga m ON m.id = t.mangaId ORDER BY t.createdAt DESC LIMIT 200")
    fun observeFeed(): Flow<List<FeedItem>>
    @Query("UPDATE track_logs SET unread = 0") suspend fun markFeedRead()
    @Query("SELECT COUNT(*) FROM track_logs WHERE unread = 1") fun observeUnreadFeed(): Flow<Int>
    @Query("DELETE FROM track_logs") suspend fun clearFeed()

    // --- stats ---
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStats(s: StatsEntity)
    @Query("SELECT m.*, SUM(s.durationMs) AS totalMs, SUM(s.pages) AS totalPages FROM stats s JOIN manga m ON m.id = s.mangaId GROUP BY m.id ORDER BY totalMs DESC")
    fun observeStats(): Flow<List<MangaStats>>
    @Query("DELETE FROM stats") suspend fun clearStats()

    // --- scrobbling ---
    @Upsert suspend fun upsertScrobbling(s: ScrobblingEntity)
    @Query("SELECT * FROM scrobblings WHERE mangaId = :mangaId") suspend fun scrobblings(mangaId: String): List<ScrobblingEntity>
    @Query("SELECT * FROM scrobblings WHERE mangaId = :mangaId") fun observeScrobblings(mangaId: String): Flow<List<ScrobblingEntity>>
    @Query("DELETE FROM scrobblings WHERE service = :service AND mangaId = :mangaId") suspend fun deleteScrobbling(service: String, mangaId: String)

    // --- downloads ---
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enqueueDownloads(d: List<DownloadEntity>)
    @Query("SELECT * FROM downloads WHERE state = 0 ORDER BY createdAt LIMIT 1") suspend fun nextDownload(): DownloadEntity?
    @Query("UPDATE downloads SET state = :state, error = :error WHERE mangaId = :mangaId AND chapterUrl = :chapterUrl")
    suspend fun setDownloadState(mangaId: String, chapterUrl: String, state: Int, error: String?)
    @Query("SELECT d.*, m.title FROM downloads d JOIN manga m ON m.id = d.mangaId ORDER BY d.state, d.createdAt DESC")
    fun observeDownloads(): Flow<List<DownloadRow>>
    @Query("SELECT chapterUrl FROM downloads WHERE mangaId = :mangaId AND state = 1") fun observeDownloadedChapters(mangaId: String): Flow<List<String>>
    @Query("DELETE FROM downloads WHERE mangaId = :mangaId AND chapterUrl = :chapterUrl") suspend fun deleteDownload(mangaId: String, chapterUrl: String)
    @Query("DELETE FROM downloads WHERE mangaId = :mangaId") suspend fun deleteDownloads(mangaId: String)
    @Query("DELETE FROM downloads WHERE state = 2") suspend fun clearFailedDownloads()
    @Query("DELETE FROM downloads") suspend fun clearAllDownloads()
    @Query("UPDATE downloads SET state = 0, error = NULL WHERE state = 2") suspend fun retryFailedDownloads()
}
