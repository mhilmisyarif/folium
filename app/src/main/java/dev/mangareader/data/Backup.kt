package dev.mangareader.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup content (Kotatsu: backups/ — favourites, categories, history, bookmarks, source settings).
 * Scrobbler tokens are deliberately excluded.
 */
data class BackupData(
    val manga: List<MangaEntity>,
    val categories: List<CategoryEntity>,
    val favourites: List<FavouriteEntity>,
    val history: List<HistoryEntity>,
    val bookmarks: List<BookmarkEntity>,
    val domains: Map<String, String>,
    /** Added in Folium; optional in the file so older backups (and older apps) stay compatible. */
    val statuses: List<LibraryStatusEntity> = emptyList(),
)

private const val BACKUP_VERSION = 1

fun BackupData.toJson(): String = JSONObject()
    .put("version", BACKUP_VERSION)
    .put("manga", JSONArray(manga.map {
        JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("coverUrl", it.coverUrl)
            .put("description", it.description).put("author", it.author).put("tags", it.tags).put("source", it.source)
            .put("publicUrl", it.publicUrl)
    }))
    .put("categories", JSONArray(categories.map {
        JSONObject().put("id", it.id).put("title", it.title).put("createdAt", it.createdAt).put("track", it.track)
    }))
    .put("favourites", JSONArray(favourites.map {
        JSONObject().put("mangaId", it.mangaId).put("categoryId", it.categoryId).put("createdAt", it.createdAt)
    }))
    .put("history", JSONArray(history.map {
        JSONObject().put("mangaId", it.mangaId).put("chapterUrl", it.chapterUrl).put("chapterName", it.chapterName)
            .put("page", it.page).put("percent", it.percent.toDouble()).put("updatedAt", it.updatedAt)
            .put("offset", it.offset.toDouble())
    }))
    .put("bookmarks", JSONArray(bookmarks.map {
        JSONObject().put("mangaId", it.mangaId).put("chapterUrl", it.chapterUrl).put("chapterName", it.chapterName)
            .put("page", it.page).put("imageUrl", it.imageUrl).put("createdAt", it.createdAt)
    }))
    .put("domains", JSONObject(domains as Map<*, *>))
    .put("statuses", JSONArray(statuses.map {
        JSONObject().put("mangaId", it.mangaId).put("status", it.status).put("updatedAt", it.updatedAt)
    }))
    .toString()

fun parseBackup(json: String): BackupData {
    val root = JSONObject(json)
    val version = root.optInt("version", -1)
    require(version in 1..BACKUP_VERSION) { "Versi backup tidak didukung: $version" }
    fun JSONObject.strOrNull(k: String) = if (isNull(k)) null else optString(k)
    fun objects(key: String) = root.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val domains = root.optJSONObject("domains")
    return BackupData(
        manga = objects("manga").map {
            MangaEntity(it.getString("id"), it.getString("url"), it.getString("title"), it.optString("coverUrl"),
                it.strOrNull("description"), it.strOrNull("author"), it.optString("tags"), it.getString("source"), it.strOrNull("publicUrl"))
        },
        categories = objects("categories").map {
            CategoryEntity(it.getLong("id"), it.getString("title"), it.optLong("createdAt"), it.optBoolean("track", true))
        },
        favourites = objects("favourites").map { FavouriteEntity(it.getString("mangaId"), it.getLong("categoryId"), it.optLong("createdAt")) },
        history = objects("history").map {
            HistoryEntity(it.getString("mangaId"), it.getString("chapterUrl"), it.getString("chapterName"),
                it.optInt("page"), it.optDouble("percent", 0.0).toFloat(), it.optLong("updatedAt"), it.optDouble("offset", 0.0).toFloat())
        },
        bookmarks = objects("bookmarks").map {
            BookmarkEntity(it.getString("mangaId"), it.getString("chapterUrl"), it.getString("chapterName"),
                it.optInt("page"), it.optString("imageUrl"), it.optLong("createdAt"))
        },
        domains = domains?.keys()?.asSequence()?.associateWith { domains.getString(it) }.orEmpty(),
        statuses = objects("statuses").mapNotNull {
            LibraryStatusEntity(it.getString("mangaId"), it.getInt("status"), it.optLong("updatedAt"))
                .takeIf { s -> ReadingStatus.of(s.status) != null } // ignore codes from a newer app
        },
    )
}

suspend fun MangaDao.exportBackup(domains: Map<String, String>): BackupData {
    val favs = allFavourites()
    val hist = allHistory()
    val marks = allBookmarks()
    val statuses = allStatuses()
    val ids = (favs.map { it.mangaId } + hist.map { it.mangaId } + marks.map { it.mangaId } + statuses.map { it.mangaId }).toSet()
    return BackupData(ids.mapNotNull { manga(it) }, categories(), favs, hist, marks, domains, statuses)
}

/** Merges a backup into the database (existing rows with the same keys are overwritten). */
suspend fun MangaDao.restoreBackup(data: BackupData) {
    data.manga.forEach { upsertManga(it) }
    data.categories.forEach { upsertCategory(it) }
    data.favourites.forEach { insertFavourite(it) }
    data.history.forEach { upsertHistory(it) }
    data.bookmarks.forEach { upsertBookmark(it) }
    data.statuses.forEach { upsertStatus(it) }
}
