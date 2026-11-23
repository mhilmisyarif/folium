package dev.mangareader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter

// Subset of Kotatsu's Room schema (observed in classes.dex: manga, chapters, history, favourites,
// favourite_categories, bookmarks, tracks, track_logs, stats, scrobblings). Urls are relative to the source domain.

@Entity(tableName = "manga")
data class MangaEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val coverUrl: String,
    val description: String?,
    val author: String?,
    val tags: String, // newline-separated
    val source: String,
    val publicUrl: String? = null,
) {
    fun toManga() = Manga(url, title, coverUrl, description, source, author = author,
        tags = tags.lines().filter { it.isNotBlank() }, publicUrl = publicUrl)
}

fun Manga.toEntity() = MangaEntity(id, url, title, coverUrl, description, author, tags.joinToString("\n"), source, publicUrl)

@Entity(
    tableName = "chapters",
    primaryKeys = ["mangaId", "url"],
    foreignKeys = [ForeignKey(entity = MangaEntity::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE)],
)
data class ChapterEntity(
    val mangaId: String,
    val url: String,
    val name: String,
    /** Position in source order (0 = newest for most sites). */
    val position: Int,
) {
    fun toChapter() = MangaChapter(url, name)
}

@Entity(tableName = "favourite_categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Kotatsu: favourite_categories.track — include in new-chapter checks. */
    val track: Boolean = true,
)

@Entity(
    tableName = "favourites",
    primaryKeys = ["mangaId", "categoryId"],
    indices = [Index("categoryId")],
    foreignKeys = [
        ForeignKey(entity = MangaEntity::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FavouriteEntity(val mangaId: String, val categoryId: Long, val createdAt: Long = System.currentTimeMillis())

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(entity = MangaEntity::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE)],
)
data class HistoryEntity(
    @PrimaryKey val mangaId: String,
    val chapterUrl: String,
    val chapterName: String,
    val page: Int,
    /** 0..1 progress through the chapter list. */
    val percent: Float,
    val updatedAt: Long = System.currentTimeMillis(),
    /** 0..1 scroll position inside [page] (EPUB chapter); 0 for comics/PDF. Added in DB v4. */
    @androidx.room.ColumnInfo(defaultValue = "0") val offset: Float = 0f,
)

@Entity(
    tableName = "bookmarks",
    primaryKeys = ["mangaId", "chapterUrl", "page"],
    foreignKeys = [ForeignKey(entity = MangaEntity::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE)],
)
data class BookmarkEntity(
    val mangaId: String,
    val chapterUrl: String,
    val chapterName: String,
    val page: Int,
    val imageUrl: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One row per batch of new chapters found by the tracker (Kotatsu: track_logs → "Feed"). */
@Entity(
    tableName = "track_logs",
    indices = [Index("mangaId")],
    foreignKeys = [ForeignKey(entity = MangaEntity::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE)],
)
data class TrackLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mangaId: String,
    val chapters: String, // newline-separated chapter names
    val createdAt: Long = System.currentTimeMillis(),
    val unread: Boolean = true,
)

@Entity(
    tableName = "stats",
    primaryKeys = ["mangaId", "startedAt"],
    foreignKeys = [ForeignKey(entity = MangaEntity::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE)],
)
data class StatsEntity(val mangaId: String, val startedAt: Long, val durationMs: Long, val pages: Int)

@Entity(
    tableName = "scrobblings",
    primaryKeys = ["service", "mangaId"],
    foreignKeys = [ForeignKey(entity = MangaEntity::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE)],
)
data class ScrobblingEntity(
    val service: String,
    val mangaId: String,
    val targetId: String,
    val targetTitle: String,
    val lastChapter: Int = 0,
)

@Entity(tableName = "downloads", primaryKeys = ["mangaId", "chapterUrl"])
data class DownloadEntity(
    val mangaId: String,
    val chapterUrl: String,
    val chapterName: String,
    val state: Int = STATE_QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATE_QUEUED = 0
        const val STATE_DONE = 1
        const val STATE_FAILED = 2
    }
}

/** Joined rows for list screens. */
data class HistoryWithManga(
    @androidx.room.Embedded val manga: MangaEntity,
    val chapterUrl: String,
    val chapterName: String,
    val page: Int,
    val percent: Float,
    val updatedAt: Long,
)

/** Reading status chosen by the user (Folium spec §4.2). Stored apart from history/favourites so it never alters them. */
@Entity(
    tableName = "library_status",
    foreignKeys = [ForeignKey(entity = MangaEntity::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE)],
)
data class LibraryStatusEntity(
    @PrimaryKey val mangaId: String,
    /** [ReadingStatus.ordinal]-independent stable code: [ReadingStatus.code]. */
    val status: Int,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class ReadingStatus(val code: Int) {
    WANT_TO_READ(1), READING(2), COMPLETED(3), ON_HOLD(4), DROPPED(5);

    companion object {
        fun of(code: Int?): ReadingStatus? = entries.firstOrNull { it.code == code }
    }
}

/** One Library entry: anything favourited, in history, or given a status. */
data class LibraryRow(
    @androidx.room.Embedded val manga: MangaEntity,
    val chapterName: String?,
    val percent: Float?,
    val lastReadAt: Long?,
    val status: Int?,
    val favouritedAt: Long?,
    /** Comma-separated favourite category ids, null when not favourited. */
    val categoryIds: String?,
) {
    val categories: Set<Long> get() = categoryIds?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()

    /** Explicit status, else "Reading" once there is reading history (derived, never written). */
    val effectiveStatus: ReadingStatus? get() = ReadingStatus.of(status) ?: if (lastReadAt != null) ReadingStatus.READING else null
}

data class FeedItem(
    @androidx.room.Embedded val manga: MangaEntity,
    val logId: Long,
    val chapters: String,
    val createdAt: Long,
    val unread: Boolean,
)

data class BookmarkWithManga(
    @androidx.room.Embedded val bookmark: BookmarkEntity,
    val title: String,
    val source: String,
)

data class DownloadRow(
    @androidx.room.Embedded val download: DownloadEntity,
    val title: String,
)

data class MangaStats(
    @androidx.room.Embedded val manga: MangaEntity,
    val totalMs: Long,
    val totalPages: Int,
)
