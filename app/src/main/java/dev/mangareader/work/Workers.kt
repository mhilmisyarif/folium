package dev.mangareader.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.data.DownloadEntity
import dev.mangareader.data.LocalStorage
import dev.mangareader.parser.HEADER_SOURCE
import dev.mangareader.data.TrackLogEntity
import dev.mangareader.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val CHANNEL_DOWNLOADS = "downloads"
private const val CHANNEL_TRACKER = "tracker"
private const val NOTIFY_DOWNLOAD = 1
private const val NOTIFY_TRACKER = 2

fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOADS, "Unduhan", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(NotificationChannel(CHANNEL_TRACKER, "Chapter baru", NotificationManager.IMPORTANCE_DEFAULT))
}

private fun Context.canNotify() =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppIntent(tab: String? = null) = PendingIntent.getActivity(
    this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** Processes the `downloads` queue one chapter at a time (Kotatsu: DownloadWorker). */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val dao = app.db.dao()
        while (true) {
            val task = dao.nextDownload() ?: break
            setForeground(foreground(task.chapterName, 0, 0))
            try {
                download(task)
                dao.setDownloadState(task.mangaId, task.chapterUrl, DownloadEntity.STATE_DONE, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.setDownloadState(task.mangaId, task.chapterUrl, DownloadEntity.STATE_FAILED, e.message ?: e.toString())
            }
        }
        return Result.success()
    }

    private suspend fun download(task: DownloadEntity) {
        val app = applicationContext.app
        val manga = requireNotNull(app.db.dao().manga(task.mangaId)) { "Manga tidak ada di database" }.toManga()
        val parser = app.source(manga.source)
        val chapter = dev.mangareader.parser.MangaChapter(task.chapterUrl, task.chapterName)
        if (parser is dev.mangareader.parser.TextSource) {
            // Novel: one text file per chapter. Paid chapters only expose a preview, so they are not stored.
            val text = parser.getChapterText(chapter)
            if (text.locked) throw IOException(applicationContext.getString(R.string.paid_not_downloaded))
            withContext(Dispatchers.IO) { app.storage.writeChapter(task.mangaId, task.chapterUrl) { LocalStorage.writeText(it, text) } }
            setForegroundAsync(foreground(task.chapterName, 1, 1))
            return
        }
        val pages = parser.getPages(chapter)
        // Source setting "Perlambat unduhan" (Kotatsu: download slowdown).
        val slowdown = app.sourcePrefs(manga.source).getBoolean(dev.mangareader.ui.SourceSettingsFragment.KEY_SLOWDOWN, false)
        withContext(Dispatchers.IO) {
            app.storage.writeChapter(task.mangaId, task.chapterUrl) { dir ->
                pages.forEachIndexed { i, url ->
                    if (isStopped) throw CancellationException("stopped")
                    if (slowdown && i > 0) Thread.sleep(SLOWDOWN_MS)
                    val req = Request.Builder().url(url).header(HEADER_SOURCE, manga.source).build()
                    app.client.newCall(req).execute().use { rsp ->
                        if (!rsp.isSuccessful) throw IOException("HTTP ${rsp.code} halaman ${i + 1}")
                        File(dir, LocalStorage.pageFileName(i, url)).outputStream().use { out -> rsp.body!!.byteStream().copyTo(out) }
                    }
                    setForegroundAsync(foreground(task.chapterName, i + 1, pages.size))
                }
            }
        }
    }

    override suspend fun getForegroundInfo() = foreground("", 0, 0)

    private fun foreground(title: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.downloading))
            .setContentText(title)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setContentIntent(applicationContext.openAppIntent())
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFY_DOWNLOAD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFY_DOWNLOAD, n)
    }

    companion object {
        private const val SLOWDOWN_MS = 1_500L

        fun start(context: Context) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            // APPEND: a running worker drains the queue; the appended run picks up anything queued after it finished.
            WorkManager.getInstance(context).enqueueUniqueWork("downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** Periodically checks tracked favourites for new chapters (Kotatsu: TrackWorker → track_logs/Feed). */
class TrackerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val dao = app.db.dao()
        val found = mutableListOf<Pair<String, Int>>()
        for (entity in dao.trackedManga()) {
            val manga = entity.toManga()
            try {
                val known = dao.chapters(manga.id).map { it.url }.toSet()
                val fresh = app.repository.refresh(manga).chapters.orEmpty()
                val added = newChapters(known, fresh.map { it.url })
                if (added.isNotEmpty()) {
                    val names = fresh.filter { it.url in added }.map { it.name }
                    dao.insertTrackLog(TrackLogEntity(mangaId = manga.id, chapters = names.joinToString("\n")))
                    found += manga.title to names.size
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One broken source must not stop the others; it is retried next period.
            }
        }
        if (found.isNotEmpty() && applicationContext.canNotify()) notify(found)
        return Result.success()
    }

    private fun notify(found: List<Pair<String, Int>>) {
        val text = found.joinToString("\n") { (title, n) -> "$title: +$n" }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_TRACKER)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(applicationContext.getString(R.string.new_chapters_count, found.sumOf { it.second }))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(applicationContext.openAppIntent(MainActivity.TAB_FEED))
            .build()
        @Suppress("MissingPermission") // checked by canNotify()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFY_TRACKER, n)
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean, intervalHours: Long) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) return wm.cancelUniqueWork("tracker").let { }
            val req = PeriodicWorkRequestBuilder<TrackerWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            wm.enqueueUniquePeriodicWork("tracker", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "tracker-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<TrackerWorker>().build(),
            )
        }
    }
}

/** Chapters in [fresh] not in [known]. With no known chapters yet, nothing is "new" (first sync just records them). */
internal fun newChapters(known: Set<String>, fresh: List<String>): Set<String> =
    if (known.isEmpty()) emptySet() else fresh.filterTo(LinkedHashSet()) { it !in known }
