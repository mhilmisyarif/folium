package dev.mangareader.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.data.DownloadEntity
import dev.mangareader.databinding.ItemSectionBinding
import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter
import dev.mangareader.parser.MangaParser
import dev.mangareader.work.DownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private fun <T> ListActivity.collect(flow: Flow<T>, block: suspend (T) -> Unit) {
    lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect { block(it) } } }
}

/** One source's catalogue with search and infinite scroll (Kotatsu: RemoteListFragment). */
class SourceActivity : ListActivity() {
    private lateinit var source: MangaParser
    private val adapter = MangaAdapter(grid = true, onClick = { startActivity(DetailsActivity.intent(this, it.manga)) })
    private var query: String? = null
    private var offset = 0
    private var endReached = false
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = app.source(requireNotNull(intent.getStringExtra(EXTRA_SOURCE)))
        binding.toolbar.title = source.name
        binding.toolbar.subtitle = source.domain
        app.markSourceUsed(source.id)
        binding.toolbar.menu.add(R.string.source_settings).apply {
            setIcon(R.drawable.ic_settings)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener { startActivity(SourceSettingsActivity.intent(this@SourceActivity, source.id)); true }
        }
        binding.searchLayout.isVisible = source.supportsSearch
        binding.search.onSearch { query = it.ifEmpty { null }; reload() }
        binding.list.useGrid()
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!rv.canScrollVertically(1)) loadMore()
            }
        })
        binding.refresh.isEnabled = true
        binding.refresh.setOnRefreshListener { reload() }
        reload()
    }

    override fun onRestart() {
        super.onRestart()
        if (binding.toolbar.subtitle != source.domain) { binding.toolbar.subtitle = source.domain; reload() }
    }

    private fun reload() {
        showErrorState(null)
        loadJob?.cancel()
        offset = 0
        endReached = false
        adapter.submitList(emptyList())
        loadMore()
    }

    private fun loadMore() {
        if (loadJob?.isActive == true || endReached) return
        binding.refresh.isRefreshing = true
        loadJob = lifecycleScope.launch {
            try {
                val page = source.getList(offset, query)
                val known = adapter.currentList.mapTo(HashSet()) { it.manga.id }
                val fresh = page.filter { it.id !in known } // sites may repeat items across pages
                endReached = fresh.isEmpty()
                offset += page.size // parsers derive their page from the raw count received
                adapter.submitList(adapter.currentList + fresh.map { MangaItem(it) })
                showEmpty(adapter.currentList.isEmpty() && endReached)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val home = "https://${source.domain}/"
                // Nothing loaded yet: a full error state offering the site in the browser (Kotatsu); later pages: a dialog.
                if (adapter.currentList.isEmpty()) showErrorState(e, home) { reload() } else showError(e, home) { loadMore() }
            } finally {
                binding.refresh.isRefreshing = false
            }
        }
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        fun intent(ctx: Context, sourceId: String) = Intent(ctx, SourceActivity::class.java).putExtra(EXTRA_SOURCE, sourceId)
    }
}

/** Searches every source in parallel; results grouped per source (Kotatsu: global search). */
class SearchActivity : ListActivity() {
    private data class Section(val source: MangaParser, val items: List<Manga>, val error: Throwable?)

    private val sections = object : ListAdapter<Section, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<Section>() {
        override fun areItemsTheSame(a: Section, b: Section) = a.source.id == b.source.id
        override fun areContentsTheSame(a: Section, b: Section) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val b = ItemSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.list.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            return object : RecyclerView.ViewHolder(b.root) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val s = getItem(position)
            val b = ItemSectionBinding.bind(holder.itemView)
            b.title.text = s.source.name
            b.subtitle.text = s.error?.let { it.message ?: it.toString() } ?: if (s.items.isEmpty()) getString(R.string.empty_list) else null
            b.subtitle.isVisible = b.subtitle.text.isNotEmpty()
            // Tapping an error offers the same recovery as elsewhere (e.g. solve a Cloudflare challenge).
            holder.itemView.setOnClickListener(s.error?.let { e -> { _: android.view.View -> showError(e, "https://${s.source.domain}/") { search(lastQuery) } } })
            val inner = MangaAdapter(grid = true, itemWidthDp = 120,
                onClick = { startActivity(DetailsActivity.intent(this@SearchActivity, it.manga)) })
            b.list.adapter = inner
            inner.submitList(s.items.map { MangaItem(it) })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra(EXTRA_KIND)?.let(ContentKind::valueOf)
        binding.toolbar.setTitle(when (kind) { ContentKind.COMIC -> R.string.search_comics; ContentKind.TEXT -> R.string.search_novels; null -> R.string.search_all })
        binding.searchLayout.isVisible = true
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = sections
        binding.search.requestFocus()
        binding.search.onSearch { if (it.isNotEmpty()) search(it) }
    }

    private var lastQuery = ""
    /** Comics or novels when opened from that space in Explore; null = every source. */
    private var kind: ContentKind? = null

    companion object {
        private const val EXTRA_KIND = "kind"
        fun intent(ctx: Context, kind: ContentKind? = null) =
            Intent(ctx, SearchActivity::class.java).apply { kind?.let { putExtra(EXTRA_KIND, it.name) } }
    }

    private fun search(q: String) {
        lastQuery = q
        binding.refresh.isRefreshing = true
        lifecycleScope.launch {
            val results = app.enabledSources.filter { it.supportsSearch && (kind == null || it.kind == kind) }.map { src ->
                async {
                    try {
                        // 60 s: a Cloudflare-protected source may need the WebView fallback after a failed direct request.
                        val items = withTimeoutOrNull(60_000) { src.getList(0, q) }
                        Section(src, items.orEmpty(), if (items == null) java.io.IOException("Timeout") else null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Section(src, emptyList(), e)
                    }
                }
            }.awaitAll()
            // Sources with results first (Kotatsu shows empty sources last).
            sections.submitList(results.sortedBy { it.items.isEmpty() })
            binding.refresh.isRefreshing = false
        }
    }
}

class BookmarksActivity : ListActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setTitle(R.string.bookmarks)
        val dao = app.db.dao()
        var rows = emptyList<dev.mangareader.data.BookmarkWithManga>()
        val adapter = MangaAdapter(
            grid = true,
            onClick = { item ->
                val b = rows.first { key(it) == item.manga.url }.bookmark
                lifecycleScope.launch {
                    val m = dao.manga(b.mangaId)?.toManga() ?: return@launch
                    startActivity(ReaderActivity.intent(this@BookmarksActivity, m, MangaChapter(b.chapterUrl, b.chapterName), b.page))
                }
            },
            onLongClick = { item ->
                val b = rows.first { key(it) == item.manga.url }.bookmark
                AlertDialog.Builder(this).setTitle(item.manga.title).setItems(arrayOf(getString(R.string.delete))) { _, _ ->
                    lifecycleScope.launch { dao.deleteBookmark(b.mangaId, b.chapterUrl, b.page) }
                }.show()
            },
        )
        binding.list.useGrid()
        binding.list.adapter = adapter
        collect(dao.observeBookmarks()) { list ->
            rows = list
            // A bookmark is shown as a pseudo-manga card whose cover is the bookmarked page.
            adapter.submitList(list.map {
                val b = it.bookmark
                MangaItem(Manga(key(it), "${it.title}\n" + getString(R.string.bookmark_position, b.chapterName, b.page + 1), b.imageUrl, null, it.source))
            })
            showEmpty(list.isEmpty())
        }
    }

    private fun key(b: dev.mangareader.data.BookmarkWithManga) = "${b.bookmark.mangaId}|${b.bookmark.chapterUrl}|${b.bookmark.page}"
}

class DownloadsActivity : ListActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setTitle(R.string.downloads)
        binding.toolbar.inflateMenu(R.menu.downloads)
        val dao = app.db.dao()
        binding.toolbar.setOnMenuItemClickListener {
            lifecycleScope.launch {
                when (it.itemId) {
                    R.id.action_retry -> { dao.retryFailedDownloads(); DownloadWorker.start(this@DownloadsActivity) }
                    R.id.action_clear -> dao.clearFailedDownloads()
                }
            }
            true
        }
        val adapter = TextAdapter(
            onClick = { item ->
                val (mangaId, _) = item.id.split('\n', limit = 2)
                lifecycleScope.launch { dao.manga(mangaId)?.let { startActivity(DetailsActivity.intent(this@DownloadsActivity, it.toManga())) } }
            },
            onLongClick = { item ->
                val (mangaId, chapterUrl) = item.id.split('\n', limit = 2)
                AlertDialog.Builder(this).setTitle(item.title).setItems(arrayOf(getString(R.string.delete_download))) { _, _ ->
                    lifecycleScope.launch {
                        dao.deleteDownload(mangaId, chapterUrl)
                        app.storage.deleteChapter(mangaId, chapterUrl)
                    }
                }.show()
            },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.header.isVisible = true
        collect(dao.observeDownloads()) { rows ->
            binding.header.text = android.text.format.Formatter.formatShortFileSize(this, app.storage.totalBytes())
            adapter.submitList(rows.map {
                val d = it.download
                val state = when (d.state) {
                    DownloadEntity.STATE_QUEUED -> getString(R.string.state_queued)
                    DownloadEntity.STATE_DONE -> getString(R.string.state_done)
                    else -> getString(R.string.state_failed, d.error.orEmpty())
                }
                TextItem("${d.mangaId}\n${d.chapterUrl}", "${it.title} — ${d.chapterName}", state,
                    if (d.state == DownloadEntity.STATE_DONE) R.drawable.ic_check else R.drawable.ic_download)
            })
            showEmpty(rows.isEmpty())
        }
    }
}

class StatsActivity : ListActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setTitle(R.string.stats)
        val adapter = MangaAdapter(grid = false, onClick = { startActivity(DetailsActivity.intent(this, it.manga)) })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.header.isVisible = true
        collect(app.db.dao().observeStats()) { rows ->
            val total = rows.sumOf { it.totalMs }
            binding.header.text = getString(R.string.total_reading_time, DateUtils.formatElapsedTime(total / 1000))
            val max = rows.maxOfOrNull { it.totalMs }?.coerceAtLeast(1) ?: 1
            adapter.submitList(rows.map {
                MangaItem(it.manga.toManga(), getString(R.string.stats_row, DateUtils.formatElapsedTime(it.totalMs / 1000), it.totalPages),
                    it.totalMs.toFloat() / max)
            })
            showEmpty(rows.isEmpty())
        }
    }
}
