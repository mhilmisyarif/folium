package dev.mangareader.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import dev.mangareader.app
import dev.mangareader.data.CategoryEntity
import dev.mangareader.data.LibraryFilter
import dev.mangareader.data.LibraryRow
import dev.mangareader.data.LibrarySort
import dev.mangareader.data.LibraryView
import dev.mangareader.data.ReadingStatus
import dev.mangareader.data.filterLibrary
import dev.mangareader.document.LOCAL_SOURCE
import dev.mangareader.document.LocalDocuments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import dev.mangareader.R
import dev.mangareader.data.HistoryWithManga
import dev.mangareader.databinding.FragmentHomeBinding
import dev.mangareader.databinding.FragmentLibraryBinding
import dev.mangareader.databinding.ViewRailBinding
import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

private fun <T> Fragment.collectWhileStarted(flow: Flow<T>, block: suspend (T) -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect { block(it) } }
    }
}

/** Home (Folium spec §4.1): a personal dashboard built only from the reader's own data and its sources. */
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var recommendedFor: String? = null
    private var recommendJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentHomeBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.greeting.text = getString(greeting())
        binding.search.setOnClickListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        binding.welcomeExplore.setOnClickListener { (activity as? MainActivity)?.select(R.id.nav_explore) }
        binding.types.showBrowseTypes()
        binding.heroBackdrop.blurBackdrop()

        val continueAdapter = rail(binding.railContinue, R.string.continue_rail)
        val updatesAdapter = rail(binding.railUpdates, R.string.recently_updated) { startActivity(Intent(requireContext(), FeedActivity::class.java)) }
        rail(binding.railRecommended, R.string.recommended)

        val dao = app().db.dao()
        collectWhileStarted(dao.observeHistoryList()) { rows ->
            bindHero(rows.firstOrNull())
            binding.welcome.isVisible = rows.isEmpty()
            val rest = rows.drop(1).take(RAIL_SIZE)
            continueAdapter.submitList(rest.map { MangaItem(it.manga.toManga(), progress = it.percent) })
            binding.railContinue.root.isVisible = rest.isNotEmpty()
            rows.firstOrNull()?.let { loadRecommendations(it.manga.toManga(), rows.mapTo(HashSet()) { h -> h.manga.id }) }
        }
        collectWhileStarted(dao.observeFeed()) { rows ->
            val items = rows.distinctBy { it.manga.id }.take(RAIL_SIZE)
            updatesAdapter.submitList(items.map { MangaItem(it.manga.toManga(), badge = getString(R.string.new_chapters_count, it.chapters.lines().size)) })
            binding.railUpdates.root.isVisible = items.isNotEmpty()
        }
    }

    private fun greeting() = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 4..10 -> R.string.greeting_morning
        in 11..14 -> R.string.greeting_afternoon
        in 15..18 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }

    private fun rail(rail: ViewRailBinding, title: Int, onMore: (() -> Unit)? = null): MangaAdapter {
        rail.title.setText(title)
        rail.more.isVisible = onMore != null
        rail.more.setOnClickListener { onMore?.invoke() }
        rail.list.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        return MangaAdapter(grid = true, itemWidthDp = 116, onClick = { startActivity(DetailsActivity.intent(requireContext(), it.manga)) })
            .also { rail.list.adapter = it }
    }

    private fun bindHero(last: HistoryWithManga?) {
        binding.hero.isVisible = last != null
        if (last == null) return
        val manga = last.manga.toManga()
        binding.heroTitle.text = manga.title
        binding.heroChapter.text = requireContext().continueLabel(manga.source, last.chapterName, last.page)
        binding.heroProgress.setProgressCompat((last.percent * 100).toInt(), false)
        binding.heroCover.loadImage(manga.coverUrl, manga.source)
        binding.heroBackdrop.loadImage(manga.coverUrl, manga.source)
        binding.hero.contentDescription = getString(R.string.continue_reading_label) + ": " + manga.title + ", " + binding.heroChapter.text
        binding.hero.setOnClickListener { startActivity(DetailsActivity.intent(requireContext(), manga)) }
        binding.heroResume.setOnClickListener {
            startActivity(ReaderActivity.intent(requireContext(), manga, MangaChapter(last.chapterUrl, last.chapterName), last.page))
        }
    }

    /**
     * "Recommended" = titles the source relates to the last-read manga (real source data, spec §4.1: never invented).
     * The section stays hidden when the source has nothing, fails or is offline.
     */
    private fun loadRecommendations(seed: Manga, alreadyRead: Set<String>) {
        if (seed.id == recommendedFor) return
        recommendedFor = seed.id
        recommendJob?.cancel()
        val rail = binding.railRecommended
        recommendJob = viewLifecycleOwner.lifecycleScope.launch {
            val related = try {
                val source = runCatching { app().source(seed.source) }.getOrNull() ?: return@launch
                withTimeoutOrNull(20_000) { source.getRelated(seed) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val items = related.filter { it.id !in alreadyRead }.distinctBy { it.id }.take(RAIL_SIZE)
            (rail.list.adapter as MangaAdapter).submitList(items.map { MangaItem(it) })
            rail.subtitle.text = getString(R.string.recommended_for, seed.title)
            rail.subtitle.isVisible = true
            rail.root.isVisible = items.isNotEmpty()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recommendedFor = null
        _binding = null
    }

    private companion object {
        const val RAIL_SIZE = 12
    }
}

/**
 * Library (spec §4.2): one list of everything favourited, read or given a status. View/collection chips, status, type
 * and sort filters, local search and a grid/list toggle. Collections are the existing favourite categories.
 */
class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val settings get() = requireContext().app.settings
    private val filter = MutableStateFlow(LibraryFilter())
    private var categories: List<CategoryEntity> = emptyList()
    private var rows: List<LibraryRow> = emptyList()
    private var adapter: MangaAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentLibraryBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.downloads.setOnClickListener { startActivity(Intent(requireContext(), DownloadsActivity::class.java)) }
        binding.bookmarks.setOnClickListener { startActivity(Intent(requireContext(), BookmarksActivity::class.java)) }
        binding.stats.setOnClickListener { startActivity(Intent(requireContext(), StatsActivity::class.java)) }
        binding.search.doAfterTextChanged { text -> filter.update { it.copy(query = text?.toString().orEmpty()) } }
        val savedSort = runCatching { LibrarySort.valueOf(settings.getString(KEY_SORT, null)!!) }.getOrDefault(LibrarySort.LAST_READ)
        filter.update { it.copy(sort = savedSort) }
        setupLayout()
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.library, menu)
            override fun onMenuItemSelected(item: MenuItem) = when (item.itemId) {
                R.id.action_import -> { importer.launch(); true }
                R.id.action_layout -> { settings.edit().putBoolean(KEY_GRID, !isGrid()).apply(); setupLayout(); render(); true }
                R.id.action_collections -> { showCollectionsDialog(); true }
                R.id.action_clear_history -> { confirmClearHistory(); true }
                else -> false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val dao = requireContext().app.db.dao()
        collectWhileStarted(dao.observeCategories()) { cats -> categories = cats; renderViews() }
        collectWhileStarted(combine(dao.observeLibrary(), filter) { r, _ -> r }) { r -> rows = r; render(); renderFilters() }
    }

    private fun isGrid() = settings.getBoolean(KEY_GRID, true)

    /** Source content type for online titles, file format (PDF/EPUB) for local documents. */
    private fun typeOf(ctx: android.content.Context, row: LibraryRow): String? =
        if (row.manga.source == LOCAL_SOURCE) row.manga.tags.lines().firstOrNull { it.isNotBlank() }
        else ctx.app.sourceOrNull(row.manga.source)?.contentType

    private val importer = DocumentImporter(this)

    private fun setupLayout() {
        val grid = isGrid()
        if (grid) binding.list.useGrid() else binding.list.layoutManager = LinearLayoutManager(requireContext())
        adapter = MangaAdapter(grid = grid, onClick = { startActivity(DetailsActivity.intent(requireContext(), it.manga)) }, onLongClick = ::itemActions)
        binding.list.adapter = adapter
    }

    private fun render() {
        val ctx = context ?: return
        val f = filter.value
        val shown = rows.filterLibrary(f) { typeOf(ctx, it) }
        adapter?.submitList(shown.map { row ->
            val status = row.effectiveStatus
            val detail = listOfNotNull(status?.let { ctx.statusName(it) }, row.chapterName).joinToString(" · ")
            MangaItem(
                row.manga.toManga(),
                subtitle = detail.ifEmpty { null },
                progress = row.percent,
                // Grid: badge only for a status the user chose (Reading is the default and would be noise).
                badge = ReadingStatus.of(row.status)?.takeIf { it != ReadingStatus.READING }?.let { ctx.statusName(it) },
            )
        })
        binding.empty.isVisible = shown.isEmpty()
        binding.empty.setText(if (rows.isEmpty()) R.string.library_empty else R.string.library_no_match)
    }

    /** View chips: Semua · Favorit · Riwayat · one per collection (spec: all, favourites, history, custom collections). */
    private fun renderViews() {
        val group = binding.views
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val views = listOf(
            LibraryView.All to getString(R.string.all),
            LibraryView.Favourites to getString(R.string.favourites),
            LibraryView.History to getString(R.string.history),
        ) + categories.takeIf { it.size > 1 }.orEmpty().map { LibraryView.Collection(it.id) to it.title } // one collection = "Favorit"
        if (filter.value.view !in views.map { it.first }) filter.update { it.copy(view = LibraryView.All) }
        views.forEach { (v, title) ->
            group.addView(filterChip(title).apply { id = View.generateViewId(); tag = v; isChecked = v == filter.value.view })
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            val v = ids.firstOrNull()?.let { g.findViewById<Chip>(it).tag as LibraryView } ?: LibraryView.All
            filter.update { it.copy(view = v) }
        }
    }

    /** Dropdown chips for status, type and sort; each shows its current value. */
    private fun renderFilters() {
        val ctx = context ?: return
        val f = filter.value
        binding.filters.removeAllViews()
        binding.filters.addView(dropdownChip(f.status?.let { ctx.statusName(it) } ?: getString(R.string.all_statuses), f.status != null) {
            val options = listOf<ReadingStatus?>(null) + ReadingStatus.entries
            pick(options.map { s -> s?.let { ctx.statusName(it) } ?: getString(R.string.all_statuses) }) { i -> filter.update { it.copy(status = options[i]) } }
        })
        // Only types that occur in the Library (per source; see LibraryFilter.type).
        val types = rows.mapNotNull { typeOf(ctx, it) }.distinct().sorted()
        if (types.size > 1 || f.type != null) {
            binding.filters.addView(dropdownChip(f.type?.let { ctx.contentTypeName(it) } ?: getString(R.string.all_types), f.type != null) {
                val options = listOf<String?>(null) + types
                pick(options.map { t -> t?.let { ctx.contentTypeName(it) } ?: getString(R.string.all_types) }) { i -> filter.update { it.copy(type = options[i]) } }
            })
        }
        val sorts = listOf(LibrarySort.LAST_READ to R.string.sort_last_read, LibrarySort.TITLE to R.string.sort_title, LibrarySort.ADDED to R.string.sort_added)
        binding.filters.addView(dropdownChip(getString(sorts.first { it.first == f.sort }.second), false) {
            pick(sorts.map { getString(it.second) }) { i ->
                settings.edit().putString(KEY_SORT, sorts[i].first.name).apply()
                filter.update { it.copy(sort = sorts[i].first) }
            }
        })
    }

    private fun filterChip(title: String) = Chip(requireContext()).apply {
        setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, R.style.Widget_App_Chip))
        text = title
        isCheckable = true
    }

    private fun dropdownChip(title: String, active: Boolean, onClick: () -> Unit) = Chip(requireContext()).apply {
        setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, R.style.Widget_App_Chip))
        text = title
        isCheckable = active
        isChecked = active
        isCheckedIconVisible = false
        isCloseIconVisible = true
        setCloseIconResource(com.google.android.material.R.drawable.mtrl_dropdown_arrow)
        setOnClickListener { isChecked = active; onClick() }
        setOnCloseIconClickListener { onClick() }
    }

    private fun pick(options: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(requireContext()).setItems(options.toTypedArray()) { _, i -> onPick(i) }.show()
    }

    private fun itemActions(item: MangaItem) {
        val row = rows.firstOrNull { it.manga.id == item.manga.id } ?: return
        val actions = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.reading_status) to { (activity as? AppCompatActivity)?.chooseStatus(item.manga, ReadingStatus.of(row.status)) })
            if (row.lastReadAt != null) add(getString(R.string.remove_from_history) to {
                lifecycleScope.launch { requireContext().app.db.dao().deleteHistory(row.manga.id) }
            })
            if (item.manga.source == LOCAL_SOURCE) add(getString(R.string.remove_from_library) to { confirmRemoveDocument(item.manga) })
        }
        AlertDialog.Builder(requireContext()).setTitle(item.manga.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.show()
    }

    private fun confirmRemoveDocument(m: dev.mangareader.parser.Manga) {
        AlertDialog.Builder(requireContext()).setMessage(getString(R.string.remove_document_confirm, m.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                val ctx = requireContext().applicationContext
                lifecycleScope.launch { LocalDocuments.delete(ctx, m) }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext()).setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> lifecycleScope.launch { requireContext().app.db.dao().clearHistory() } }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---- collections (= favourite categories; "tracked" ones are checked for new chapters)

    private fun showCollectionsDialog() {
        val names = categories.map { it.title + if (it.track) " · " + getString(R.string.tracked) else "" } + getString(R.string.add_category)
        AlertDialog.Builder(requireContext()).setTitle(R.string.collections)
            .setItems(names.toTypedArray()) { _, i ->
                if (i == categories.size) editCategory(null) else categoryActions(categories[i])
            }.show()
    }

    private fun categoryActions(c: CategoryEntity) {
        val track = getString(R.string.track_category) + if (c.track) " (" + getString(R.string.tracked) + ")" else ""
        AlertDialog.Builder(requireContext()).setTitle(c.title)
            .setItems(arrayOf(getString(R.string.rename), track, getString(R.string.delete))) { _, i ->
                val dao = requireContext().app.db.dao()
                when (i) {
                    0 -> editCategory(c)
                    1 -> lifecycleScope.launch { dao.upsertCategory(c.copy(track = !c.track)) }
                    2 -> lifecycleScope.launch { dao.deleteCategory(c.id) }
                }
            }.show()
    }

    private fun editCategory(c: CategoryEntity?) {
        val input = EditText(requireContext()).apply { setText(c?.title); hint = getString(R.string.category_name); setSingleLine() }
        AlertDialog.Builder(requireContext()).setTitle(if (c == null) R.string.add_category else R.string.rename).setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                lifecycleScope.launch {
                    val dao = requireContext().app.db.dao()
                    if (c == null) dao.insertCategory(CategoryEntity(title = title)) else dao.upsertCategory(c.copy(title = title))
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter = null
        _binding = null
    }

    private companion object {
        const val KEY_GRID = "library_grid"
        const val KEY_SORT = "library_sort"
    }
}
