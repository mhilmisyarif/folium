package dev.mangareader.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.data.DownloadEntity
import dev.mangareader.data.HistoryEntity
import dev.mangareader.data.ScrobblingEntity
import dev.mangareader.databinding.ActivityDetailsBinding
import dev.mangareader.databinding.HeaderDetailsBinding
import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter
import dev.mangareader.work.DownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Kotatsu-style details: cover header, actions, tags, description, chapter list and a Read/Continue FAB. */
class DetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailsBinding
    private lateinit var header: HeaderDetailsBinding
    private lateinit var manga: Manga
    private var chapters: List<MangaChapter> = emptyList()
    private var history: HistoryEntity? = null
    private val parser by lazy { app.source(manga.source) }
    private val dao by lazy { app.db.dao() }
    private val chaptersAdapter = TextAdapter(onClick = { open(MangaChapter(it.id, it.title), 0) }, onLongClick = ::chapterActions)
    private val chaptersFlow = kotlinx.coroutines.flow.MutableStateFlow<List<MangaChapter>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manga = requireNotNull(IntentCompat.getSerializableExtra(intent, EXTRA_MANGA, Manga::class.java))
        binding = ActivityDetailsBinding.inflate(layoutInflater)
        binding.list.layoutManager = LinearLayoutManager(this) // must exist before inflating into the list
        header = HeaderDetailsBinding.inflate(layoutInflater, binding.list, false)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }
        binding.list.adapter = ConcatAdapter(SingleViewAdapter(header.root), chaptersAdapter)

        header.description.setOnClickListener { header.description.maxLines = if (header.description.maxLines == 4) Int.MAX_VALUE else 4 }
        header.btnFavourite.isCheckable = true
        header.btnFavourite.setOnClickListener { chooseCategories() }
        header.btnTracking.setOnClickListener { TrackingDialogs(this, manga).show() }
        header.btnDownload.setOnClickListener { chooseDownload() }
        header.btnBrowser.setOnClickListener { startActivity(BrowserActivity.intent(this, parser.publicUrl(manga))) }
        binding.read.setOnClickListener {
            val h = history
            if (h != null) open(MangaChapter(h.chapterUrl, h.chapterName), h.page)
            else chapters.lastOrNull()?.let { open(it, 0) } // sources list newest first
        }
        bindManga()
        observe()
        load()
    }

    private fun bindManga() {
        binding.toolbar.title = manga.title
        header.title.text = manga.title
        header.author.text = manga.author
        header.author.isVisible = !manga.author.isNullOrBlank()
        header.source.text = parser.name
        header.cover.loadImage(manga.coverUrl, manga.source)
        header.backdrop.loadImage(manga.coverUrl, manga.source)
        header.backdrop.blurBackdrop()
        header.description.text = manga.description.orEmpty()
        header.tags.removeAllViews()
        manga.tags.forEach { tag ->
            header.tags.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                setChipDrawable(com.google.android.material.chip.ChipDrawable.createFromAttributes(context, null, 0, R.style.Widget_App_Chip_Assist))
                text = tag
                isClickable = false
                // "Tandai genre berbahaya" (Kotatsu tags_warnings)
                if (app.settings.getBoolean("tags_warnings", true) && isWarningTag(tag)) {
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError))
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError),
                    )
                }
            })
        }
    }

    private fun <T> collect(flow: Flow<T>, block: suspend (T) -> Unit) {
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect { block(it) } } }
    }

    private fun observe() {
        collect(dao.observeFavouriteCategoryIds(manga.id)) { header.btnFavourite.isChecked = it.isNotEmpty() }
        // Explicit status, else "Reading" once there is history (same rule as the Library).
        collect(combine(dao.observeStatus(manga.id), dao.observeHistory(manga.id)) { s, h ->
            dev.mangareader.data.ReadingStatus.of(s) to (dev.mangareader.data.ReadingStatus.of(s) ?: if (h != null) dev.mangareader.data.ReadingStatus.READING else null)
        }) { (explicit, effective) ->
            header.btnStatus.text = effective?.let { getString(R.string.status_label, statusName(it)) } ?: getString(R.string.add_to_library)
            header.btnStatus.setOnClickListener { chooseStatus(manga, explicit) }
        }
        collect(dao.observeScrobblings(manga.id)) { links ->
            header.trackingText.isVisible = links.isNotEmpty()
            header.trackingText.text = getString(R.string.tracking_linked, links.joinToString { l ->
                (app.scrobblers.firstOrNull { it.id == l.service }?.name ?: l.service) + " (${l.targetTitle})"
            })
        }
        collect(combine(chaptersFlow, dao.observeHistory(manga.id), dao.observeDownloadedChapters(manga.id)) { c, h, d -> Triple(c, h, d.toSet()) }) { (list, h, downloaded) ->
            history = h
            binding.read.text = getString(if (h != null) R.string.continue_reading else R.string.read)
            header.progressText.text = h?.let { continueLabel(manga.source, it.chapterName, it.page) }
            header.progressText.isVisible = h != null
            header.chaptersTitle.text = getString(R.string.chapters_count, list.size)
            chaptersAdapter.submitList(list.map {
                val current = it.url == h?.chapterUrl
                TextItem(it.url, it.name, if (current) getString(R.string.continue_reading) else null,
                    when {
                        it.url in downloaded -> R.drawable.ic_check
                        current -> R.drawable.ic_play
                        else -> null
                    })
            })
        }
    }

    private fun load() {
        binding.loading.isVisible = true
        lifecycleScope.launch {
            app.repository.cached(manga.id)?.let { (m, c) ->
                if (c.isNotEmpty()) { manga = m; setChapters(c); bindManga() }
            }
            try {
                val fresh = app.repository.refresh(manga)
                manga = fresh.copy(chapters = null)
                setChapters(fresh.chapters.orEmpty())
                bindManga()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (chapters.isNotEmpty()) toast(getString(R.string.offline_cached)) else showError(e, parser.publicUrl(manga)) { load() }
            } finally {
                binding.loading.isVisible = false
            }
        }
    }

    private fun setChapters(list: List<MangaChapter>) {
        chapters = list
        chaptersFlow.value = list
    }

    private fun open(chapter: MangaChapter, page: Int) = startActivity(ReaderActivity.intent(this, manga, chapter, page))

    private fun onMenu(id: Int) {
        when (id) {
            R.id.action_share -> startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${manga.title}\n${parser.publicUrl(manga)}"), null))
            R.id.action_delete_downloads -> lifecycleScope.launch {
                dao.deleteDownloads(manga.id)
                app.storage.deleteManga(manga.id)
            }
        }
    }

    private fun chooseCategories() = lifecycleScope.launch {
        app.repository.defaultCategoryId() // ensures at least one category exists
        val cats = dao.categories()
        val selected = dao.favouriteCategoryIds(manga.id).toSet()
        val checked = cats.map { it.id in selected }.toBooleanArray()
        AlertDialog.Builder(this@DetailsActivity).setTitle(R.string.add_to_favourites)
            .setMultiChoiceItems(cats.map { it.title }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.save) { _, _ ->
                lifecycleScope.launch { app.repository.setFavourite(manga, cats.filterIndexed { i, _ -> checked[i] }.map { it.id }) }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseDownload() {
        if (chapters.isEmpty()) return
        AlertDialog.Builder(this).setTitle(R.string.download)
            .setItems(arrayOf(getString(R.string.download_all), getString(R.string.download_unread))) { _, i ->
                val readPos = chapters.indexOfFirst { it.url == history?.chapterUrl }
                // Newest first: unread = chapters before (newer than) the one being read, plus that one.
                val list = (if (i == 1 && readPos >= 0) chapters.subList(0, readPos + 1) else chapters).filterNot(::isPaid)
                if (list.isEmpty()) toast(getString(R.string.nothing_free_to_download)) else enqueue(list.reversed())
            }.show()
    }

    private fun enqueue(list: List<MangaChapter>) = lifecycleScope.launch {
        app.repository.saveManga(manga)
        val now = System.currentTimeMillis()
        // createdAt increases so the queue keeps reading order.
        dao.enqueueDownloads(list.mapIndexed { i, c -> DownloadEntity(manga.id, c.url, c.name, createdAt = now + i) })
        DownloadWorker.start(this@DetailsActivity)
        toast(getString(R.string.queued_n, list.size))
    }

    /** Novel chapters behind a paywall: only a preview exists, so they are not downloadable. */
    private fun isPaid(c: MangaChapter) = (parser as? dev.mangareader.parser.TextSource)?.isPaid(c) == true

    private fun chapterActions(item: TextItem) {
        val chapter = MangaChapter(item.id, item.title)
        if (isPaid(chapter) && !app.storage.isDownloaded(manga.id, chapter.url)) return toast(getString(R.string.paid_not_downloaded))
        val downloaded = app.storage.isDownloaded(manga.id, chapter.url)
        AlertDialog.Builder(this).setTitle(item.title)
            .setItems(arrayOf(getString(if (downloaded) R.string.delete_download else R.string.download_chapter))) { _, _ ->
                if (downloaded) lifecycleScope.launch {
                    dao.deleteDownload(manga.id, chapter.url)
                    app.storage.deleteChapter(manga.id, chapter.url)
                } else enqueue(listOf(chapter))
            }.show()
    }

    companion object {
        private const val EXTRA_MANGA = "manga"
        /** Local PDFs/EPUBs have no source details: they open straight in the document reader. */
        fun intent(ctx: Context, m: Manga): Intent =
            if (m.source == dev.mangareader.document.LOCAL_SOURCE) DocumentActivity.intent(ctx, m)
            else Intent(ctx, DetailsActivity::class.java).putExtra(EXTRA_MANGA, m.copy(chapters = null))
    }
}

/** Link a manga to tracking services (Kotatsu: ScrobblingSelectorSheet). */
class TrackingDialogs(private val activity: AppCompatActivity, private val manga: Manga) {
    private val app = activity.app

    fun show() = activity.lifecycleScope.launch {
        val services = app.scrobblers.filter { it.isAuthorized }
        if (services.isEmpty()) return@launch activity.toast(activity.getString(R.string.tracking_none))
        val links = app.db.dao().scrobblings(manga.id).associateBy { it.service }
        val labels = services.map { s -> s.name + (links[s.id]?.let { " — ${it.targetTitle}" } ?: "") }
        AlertDialog.Builder(activity).setTitle(R.string.choose_service)
            .setItems(labels.toTypedArray()) { _, i ->
                val s = services[i]
                if (links[s.id] != null) {
                    AlertDialog.Builder(activity).setTitle(s.name)
                        .setItems(arrayOf(activity.getString(R.string.unlink), activity.getString(R.string.search_hint))) { _, j ->
                            if (j == 0) activity.lifecycleScope.launch { app.db.dao().deleteScrobbling(s.id, manga.id) } else search(s)
                        }.show()
                } else search(s)
            }.show()
    }

    private fun search(s: dev.mangareader.scrobbling.Scrobbler) = activity.lifecycleScope.launch {
        try {
            app.repository.saveManga(manga)
            val results = s.search(manga.title)
            if (results.isEmpty()) return@launch activity.toast(activity.getString(R.string.empty_list))
            AlertDialog.Builder(activity).setTitle(s.name)
                .setItems(results.map { it.title }.toTypedArray()) { _, i ->
                    val r = results[i]
                    activity.lifecycleScope.launch { app.db.dao().upsertScrobbling(ScrobblingEntity(s.id, manga.id, r.id, r.title)) }
                }.show()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            activity.toast(e.message ?: e.toString())
        }
    }
}
