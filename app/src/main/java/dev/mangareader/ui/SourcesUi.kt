package dev.mangareader.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.snackbar.Snackbar
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.databinding.ActivitySourcesCatalogBinding
import dev.mangareader.databinding.ItemSourceBinding
import dev.mangareader.parser.MangaParser
import dev.mangareader.parser.normalizeDomain
import java.util.Locale

/** Manga covers are 2:3; fixing the ratio keeps grids aligned while images load (no layout shift). */
class CoverImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ShapeableImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 3 / 2)
    }
}

/** Kotatsu's ContentType titles. */
fun Context.contentTypeName(type: String): String = when (type) {
    "MANGA" -> "Manga"
    "MANHWA" -> "Manhwa"
    "MANHUA" -> "Manhua"
    "HENTAI" -> "Hentai"
    "COMICS" -> getString(R.string.type_comics)
    "ONE_SHOT" -> "One shot"
    "DOUJINSHI" -> "Doujinshi"
    "IMAGE_SET" -> "Image set"
    "ARTIST_CG" -> "Artist CG"
    "GAME_CG" -> "Game CG"
    "NOVEL" -> "Novel"
    "PDF" -> "PDF"
    "EPUB" -> getString(R.string.type_book)
    else -> getString(R.string.other)
}

/** The two reading worlds kept apart in Explore, search and the catalogue: picture-based vs text-based. */
enum class ContentKind { COMIC, TEXT }

val MangaParser.kind get() = if (this is dev.mangareader.parser.TextSource) ContentKind.TEXT else ContentKind.COMIC

/** Reading categories offered for browsing (spec §4.1). Novels have their own space in Explore. */
val BROWSE_TYPES = listOf("MANGA", "MANHWA", "MANHUA", "COMICS", "NOVEL")
val COMIC_BROWSE_TYPES = BROWSE_TYPES - "NOVEL"

/**
 * System file picker for PDF/EPUB (spec §5: local files): the file stays where it is, read access is kept, and the
 * document opens once imported. Create as a property initialiser (it registers an activity-result callback).
 */
class DocumentImporter(private val fragment: androidx.fragment.app.Fragment) {
    private val launcher = fragment.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val ctx = fragment.requireContext().applicationContext
        ctx.toast(ctx.getString(R.string.importing))
        fragment.lifecycleScope.launch {
            try {
                val doc = dev.mangareader.document.LocalDocuments.import(ctx, uri)
                ctx.toast(ctx.getString(R.string.imported, doc.title))
                ctx.startActivity(DetailsActivity.intent(ctx, doc).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.toast(ctx.errorMessage(e))
            }
        }
    }

    fun launch() = launcher.launch(arrayOf("application/pdf", "application/epub+zip"))
}

/** One chip per browse type; tapping opens the source catalogue filtered to it. */
fun com.google.android.material.chip.ChipGroup.showBrowseTypes(types: List<String> = BROWSE_TYPES) {
    removeAllViews()
    types.forEach { type ->
        addView(com.google.android.material.chip.Chip(context).apply {
            setChipDrawable(com.google.android.material.chip.ChipDrawable.createFromAttributes(context, null, 0, R.style.Widget_App_Chip_Assist))
            text = context.contentTypeName(type)
            setOnClickListener { context.startActivity(SourcesActivity.intent(context, type)) }
        })
    }
}

/** "Manga, Indonesia" (Kotatsu: MangaSource.getSummary). */
fun Context.sourceSummary(s: MangaParser) = getString(R.string.source_summary, contentTypeName(s.contentType), languageName(s.locale))

data class SourceRow(val source: MangaParser)

private const val VIEW_ROW = 0
private const val VIEW_GRID = 1

/** Favicon + name + summary + optional trailing action (Kotatsu: item_source_catalog). */
class SourceAdapter(
    private val onClick: (MangaParser) -> Unit,
    private val onLongClick: ((MangaParser) -> Unit)? = null,
    private val action: Pair<Int, Int>? = null, // icon, content description
    private val onAction: ((MangaParser) -> Unit)? = null,
) : AnimatedListAdapter<SourceRow>(object : DiffUtil.ItemCallback<SourceRow>() {
    override fun areItemsTheSame(a: SourceRow, b: SourceRow) = a.source.id == b.source.id
    override fun areContentsTheSame(a: SourceRow, b: SourceRow) = a.source.id == b.source.id
}) {
    /** Explore "Tampilkan dalam tampilan kisi": icon tiles; the menu stays on long-press. */
    var grid = false
        @android.annotation.SuppressLint("NotifyDataSetChanged") // every row changes layout
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    override fun getItemViewType(position: Int) = if (grid) VIEW_GRID else VIEW_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = if (viewType == VIEW_GRID) dev.mangareader.databinding.ItemSourceGridBinding.inflate(inflater, parent, false).root
        else ItemSourceBinding.inflate(inflater, parent, false).root
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val s = getItem(position).source
        val ctx = holder.itemView.context
        if (holder.itemViewType == VIEW_GRID) {
            val g = dev.mangareader.databinding.ItemSourceGridBinding.bind(holder.itemView)
            g.title.text = s.name
            holder.itemView.contentDescription = listOfNotNull(s.name, ctx.sourceSummary(s), ctx.getString(R.string.broken_source).takeIf { s.isBroken })
                .joinToString(", ")
            val letter = LetterDrawable(ctx, s.name)
            g.icon.load(SourceIcon(s.id)) { placeholder(letter); error(letter) }
            holder.itemView.setOnClickListener { onClick(s) }
            holder.itemView.setOnLongClickListener((onLongClick ?: onAction)?.let { cb -> { cb(s); true } })
            return
        }
        val b = ItemSourceBinding.bind(holder.itemView)
        b.title.text = s.name
        b.summary.text = ctx.sourceSummary(s)
        b.summary.setCompoundDrawablesRelativeWithIntrinsicBounds(if (s.isBroken) R.drawable.ic_off else 0, 0, 0, 0)
        holder.itemView.contentDescription = listOfNotNull(s.name, b.summary.text, ctx.getString(R.string.broken_source).takeIf { s.isBroken })
            .joinToString(", ")
        val letter = LetterDrawable(ctx, s.name)
        b.icon.load(SourceIcon(s.id)) { placeholder(letter); error(letter) }
        holder.itemView.setOnClickListener { onClick(s) }
        holder.itemView.setOnLongClickListener(onLongClick?.let { cb -> { cb(s); true } })
        b.divider.isVisible = action != null
        b.action.isVisible = action != null
        action?.let { (icon, desc) ->
            b.action.setImageResource(icon)
            b.action.contentDescription = ctx.getString(desc) + " " + s.name
            b.action.setOnClickListener { onAction?.invoke(s) }
        }
    }
}

/** Per-source domain override (Kotatsu: source settings → ConfigKey.Domain). */
fun Activity.showDomainDialog(s: MangaParser, onChanged: () -> Unit) {
    val default = s.defaultDomain
    val input = EditText(this).apply { setText(s.domain); hint = default; setSingleLine() }
    val pad = (20 * resources.displayMetrics.density).toInt()
    val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.domain_of, s.name))
        .apply { if (s.domainPresets.size > 1) setMessage(getString(R.string.mirrors, s.domainPresets.joinToString(", "))) }
        .setView(box)
        .setPositiveButton(R.string.save) { _, _ ->
            val domain = normalizeDomain(input.text.toString())
            if (domain == null) {
                toast(getString(R.string.invalid_domain))
            } else {
                app.domainPrefs.edit { if (domain == default) remove(s.id) else putString(s.id, domain) }
                onChanged()
            }
        }
        .setNeutralButton(getString(R.string.reset_to, default)) { _, _ -> app.domainPrefs.edit { remove(s.id) }; onChanged() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/**
 * Sources not yet added, filtered by language and content type (Kotatsu: SourcesCatalogActivity).
 * Tap previews a source, "+" adds it to Explore with an undo snackbar.
 */
class SourcesActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySourcesCatalogBinding
    private val adapter = SourceAdapter(
        onClick = { startActivity(SourceActivity.intent(this, it.id)) },
        onLongClick = { startActivity(SourceSettingsActivity.intent(this, it.id)) },
        action = R.drawable.ic_add to R.string.add,
        onAction = ::add,
    )
    private var query = ""
    private var locale: String? = null // null = all languages
    /** Sources of one kind (comics or novels) when opened from that space in Explore; null = all. */
    private val pool by lazy {
        val kind = intent.getStringExtra(EXTRA_KIND)?.let(ContentKind::valueOf)
        val noNsfw = app.settings.getBoolean(dev.mangareader.KEY_NO_NSFW, false)
        app.sources.filter { (kind == null || it.kind == kind) && !(noNsfw && it.isNsfw) }
    }
    private val types = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourcesCatalogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setTitle(R.string.source_catalog)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        setupSearch()
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        // Default to the device language when sources exist for it, like Kotatsu.
        locale = Locale.getDefault().language.takeIf { lang -> pool.any { it.locale == lang } }
        intent.getStringExtra(EXTRA_TYPE)?.let { type ->
            types += type // opened from "browse by type"
            // No sources of that type in the device language: show all languages rather than an empty list.
            if (pool.none { it.contentType == type && it.locale == locale }) locale = null
        }
        renderChips()
        render()
    }

    private fun setupSearch() {
        val item = binding.toolbar.menu.add(Menu.NONE, Menu.NONE, Menu.NONE, R.string.search)
            .setIcon(R.drawable.ic_search)
            .setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM or android.view.MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val search = SearchView(this).apply {
            queryHint = getString(R.string.filter_sources)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = false
                override fun onQueryTextChange(q: String?): Boolean { this@SourcesActivity.query = q.orEmpty().trim(); render(); return true }
            })
        }
        item.actionView = search
    }

    private fun renderChips() {
        val group = binding.chips
        group.removeAllViews()
        group.addView(Chip(this).apply {
            text = locale?.let { languageName(it) } ?: getString(R.string.all_languages)
            setChipIconResource(R.drawable.ic_language)
            isChipIconVisible = true
            isCloseIconVisible = true
            setCloseIconResource(com.google.android.material.R.drawable.mtrl_dropdown_arrow)
            setOnClickListener { showLanguages(it) }
            setOnCloseIconClickListener { showLanguages(it) }
        })
        // Content types ordered by how many sources use them (Kotatsu: mapSortedByCount).
        // Selected types first so an active filter is never scrolled out of sight.
        pool.groupingBy { it.contentType }.eachCount().entries
            .sortedWith(compareBy({ it.key !in types }, { -it.value })).forEach { (type, _) ->
            group.addView(Chip(this).apply {
                setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter))
                text = contentTypeName(type)
                isCheckable = true
                isChecked = type in types
                setOnCheckedChangeListener { _, checked -> if (checked) types += type else types -= type; render() }
            })
        }
    }

    private fun showLanguages(anchor: android.view.View) {
        val langs = listOf<String?>(null) + pool.map { it.locale }.distinct().sortedBy { languageName(it).lowercase() }
        val menu = PopupMenu(this, anchor)
        langs.forEachIndexed { i, l -> menu.menu.add(Menu.NONE, i, i, l?.let { languageName(it) } ?: getString(R.string.all_languages)) }
        menu.setOnMenuItemClickListener { locale = langs[it.itemId]; renderChips(); render(); true }
        menu.show()
    }

    private fun render() {
        val enabled = app.enabledSourceIds
        val rows = pool.filter { s ->
            s.id !in enabled &&
                (locale == null || s.locale == locale) &&
                (types.isEmpty() || s.contentType in types) &&
                (query.isEmpty() || s.name.contains(query, ignoreCase = true))
        }.sortedBy { it.name.lowercase() }
        adapter.submitList(rows.map { SourceRow(it) })
        binding.empty.isVisible = rows.isEmpty()
    }

    companion object {
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_KIND = "kind"
        /** [type]: Kotatsu ContentType name to preselect, e.g. "MANHWA". */
        fun intent(ctx: Context, type: String? = null, kind: ContentKind? = null) =
            android.content.Intent(ctx, SourcesActivity::class.java).apply {
                type?.let { putExtra(EXTRA_TYPE, it) }
                kind?.let { putExtra(EXTRA_KIND, it.name) }
            }
    }

    private fun add(s: MangaParser) {
        app.enabledSourceIds = app.enabledSourceIds + s.id
        render()
        Snackbar.make(binding.root, getString(R.string.source_enabled, s.name), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { app.enabledSourceIds = app.enabledSourceIds - s.id; render() }
            .show()
    }
}
