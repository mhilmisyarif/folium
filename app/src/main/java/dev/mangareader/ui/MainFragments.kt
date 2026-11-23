package dev.mangareader.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.data.CategoryEntity
import dev.mangareader.databinding.FragmentListBinding
import dev.mangareader.databinding.HeaderExploreBinding
import dev.mangareader.work.TrackerWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Shared plumbing: list binding, an optional toolbar menu, and Flow collection tied to the view lifecycle. */
abstract class ListFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    protected val binding get() = _binding!!
    protected open val menuRes: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentListBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        menuRes?.let { res ->
            requireActivity().addMenuProvider(object : MenuProvider {
                override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(res, menu)
                override fun onMenuItemSelected(item: MenuItem) = onMenuItem(item.itemId)
            }, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }
    }

    protected open fun onMenuItem(id: Int) = false

    protected fun <T> Flow<T>.collectWhileStarted(block: suspend (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { collect { block(it) } }
        }
    }

    protected fun openDetails(item: MangaItem) = startActivity(DetailsActivity.intent(requireContext(), item.manga))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ExploreFragment : ListFragment() {
    private val sourcesAdapter = SourceAdapter(
        onClick = { startActivity(SourceActivity.intent(requireContext(), it.id)) },
        onLongClick = ::sourceMenu,
        action = R.drawable.ic_more to R.string.more_options, // visible alternative to long-press
        onAction = ::sourceMenu,
    )
    private val emptyAdapter = TextAdapter(onClick = { openCatalog() })
    private val importer = DocumentImporter(this)
    private var header: HeaderExploreBinding? = null

    /** Comics or novels & books: each space has its own sources, search and catalogue (remembered). */
    private var kind: ContentKind
        get() = runCatching { ContentKind.valueOf(app().settings.getString(KEY_KIND, null)!!) }.getOrDefault(ContentKind.COMIC)
        set(value) = app().settings.edit().putString(KEY_KIND, value.name).apply()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Grid layout either way; rows simply span the full width (must exist before inflating into the list).
        val spans = (resources.configuration.screenWidthDp / 96).coerceAtLeast(3)
        binding.list.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), spans).apply {
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (sourcesAdapter.grid && position in 1..sourcesAdapter.itemCount) 1 else spans
            }
        }
        val h = HeaderExploreBinding.inflate(layoutInflater, binding.list, false).also { header = it }
        h.searchAll.setOnClickListener { startActivity(SearchActivity.intent(requireContext(), kind)) }
        h.catalog.setOnClickListener { openCatalog() }
        h.importDoc.setOnClickListener { importer.launch() }
        h.kind.check(if (kind == ContentKind.TEXT) R.id.kind_text else R.id.kind_comic)
        h.kind.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { kind = if (id == R.id.kind_text) ContentKind.TEXT else ContentKind.COMIC; render() }
        }
        binding.list.adapter = ConcatAdapter(SingleViewAdapter(h.root), sourcesAdapter, emptyAdapter)
    }

    override fun onDestroyView() {
        header = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val k = kind
        val text = k == ContentKind.TEXT
        header?.let { h ->
            h.searchLabel.setText(if (text) R.string.search_novels_hint else R.string.search_comics_hint)
            h.searchAll.contentDescription = h.searchLabel.text
            h.types.isVisible = !text // novel sources are not split by type
            if (!text) h.types.showBrowseTypes(COMIC_BROWSE_TYPES)
            h.importDoc.isVisible = text
        }
        sourcesAdapter.grid = app().settings.getBoolean("sources_grid", true)
        val enabled = app().enabledSources.filter { it.kind == k }
        sourcesAdapter.submitList(enabled.map { SourceRow(it) })
        val empty = if (text) R.string.no_novel_sources_enabled else R.string.no_comic_sources_enabled
        emptyAdapter.submitList(if (enabled.isEmpty()) listOf(TextItem(CATALOG, getString(R.string.source_catalog), getString(empty), R.drawable.ic_add)) else emptyList())
    }

    /** Kotatsu: long-press a source in Explore → settings / disable. */
    private fun sourceMenu(s: dev.mangareader.parser.MangaParser) {
        val activity = requireActivity()
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(s.name)
            .setItems(arrayOf(getString(R.string.source_settings), getString(R.string.disable))) { _, which ->
                when (which) {
                    0 -> startActivity(SourceSettingsActivity.intent(activity, s.id))
                    1 -> { app().enabledSourceIds = app().enabledSourceIds - s.id; render() }
                }
            }
            .show()
    }

    private fun openCatalog() = startActivity(SourcesActivity.intent(requireContext(), kind = kind))

}

private const val CATALOG = "__catalog"
private const val KEY_KIND = "explore_kind"

class FeedFragment : ListFragment() {
    override val menuRes = R.menu.feed
    private val adapter = MangaAdapter(grid = false, onClick = ::openDetails)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.empty.text = getString(R.string.empty_list) + "\n" + getString(R.string.track_category)
        app().db.dao().observeFeed().collectWhileStarted { rows ->
            // Rows are newest first; show one entry per manga (its latest update).
            adapter.submitList(rows.distinctBy { it.manga.id }.map {
                val names = it.chapters.lines()
                val ago = DateUtils.getRelativeTimeSpanString(it.createdAt)
                MangaItem(
                    it.manga.toManga(),
                    getString(R.string.new_chapters_count, names.size) + " · $ago\n" + names.take(3).joinToString(", "),
                    badge = if (it.unread) "•" else null,
                )
            })
            binding.empty.isVisible = rows.isEmpty()
        }
    }

    override fun onStop() {
        super.onStop()
        app().appScope.launch { app().db.dao().markFeedRead() }
    }

    override fun onMenuItem(id: Int): Boolean = when (id) {
        R.id.action_check -> {
            TrackerWorker.runNow(requireContext()); requireContext().toast(getString(R.string.checking_updates)); true
        }
        R.id.action_clear_feed -> { lifecycleScope.launch { app().db.dao().clearFeed() }; true }
        else -> false
    }
}

internal fun Fragment.app() = requireContext().app

/** Wraps a fixed header view so it can be combined with lists in a ConcatAdapter. */
class SingleViewAdapter(private val view: View) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun getItemViewType(position: Int) = view.hashCode()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {}
    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) = Unit
}
