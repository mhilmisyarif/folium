package dev.mangareader.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.databinding.ActivityListBinding
import dev.mangareader.databinding.ItemMangaGridBinding
import dev.mangareader.databinding.ItemMangaListBinding
import dev.mangareader.databinding.ItemTextBinding
import dev.mangareader.parser.CloudflareException
import dev.mangareader.parser.HEADER_SOURCE
import dev.mangareader.parser.Manga

/** "Lanjut: Chapter 5 (hal. 3)"; text and documents have no pages, so just the chapter/position name. */
fun Context.continueLabel(source: String, chapterName: String, page: Int): String =
    if (source == dev.mangareader.document.LOCAL_SOURCE || app.sourceOrNull(source) is dev.mangareader.parser.TextSource)
        getString(R.string.continue_text, chapterName)
    else getString(R.string.continue_chapter, chapterName, page + 1)

/** Cover, or a lettered tile when the title has none (e.g. an EPUB without a cover image). */
fun ImageView.loadCover(manga: Manga) {
    if (manga.coverUrl.isNotEmpty()) return loadImage(manga.coverUrl, manga.source).let { }
    // Through Coil (not setImageDrawable) so an older request on a recycled view is replaced, not racing us.
    val letter = LetterDrawable(context, manga.title)
    load(null) { placeholder(letter); error(letter) }
}

/** [source] lets the HTTP client add that source's Referer/UA and run its interceptor (e.g. image descrambling). */
fun ImageView.loadImage(url: String, source: String?) = load(url) {
    if (source != null && url.startsWith("http")) httpHeaders(NetworkHeaders.Builder().set(HEADER_SOURCE, source).build())
}

/** "Indonesia", "English"…; multilingual sources have no locale. */
fun Context.languageName(locale: String): String =
    if (locale.isEmpty()) getString(R.string.multilingual) else java.util.Locale(locale).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.uppercase() }

fun Context.statusName(s: dev.mangareader.data.ReadingStatus): String = getString(
    when (s) {
        dev.mangareader.data.ReadingStatus.WANT_TO_READ -> R.string.status_want
        dev.mangareader.data.ReadingStatus.READING -> R.string.status_reading
        dev.mangareader.data.ReadingStatus.COMPLETED -> R.string.status_completed
        dev.mangareader.data.ReadingStatus.ON_HOLD -> R.string.status_on_hold
        dev.mangareader.data.ReadingStatus.DROPPED -> R.string.status_dropped
    },
)

/**
 * Lets the user set or clear a title's reading status (Folium spec §4.2). Saves the manga row first because the
 * status table references it.
 */
fun androidx.appcompat.app.AppCompatActivity.chooseStatus(manga: dev.mangareader.parser.Manga, current: dev.mangareader.data.ReadingStatus?) {
    val all = dev.mangareader.data.ReadingStatus.entries
    val labels = all.map { statusName(it) } + getString(R.string.status_none)
    AlertDialog.Builder(this).setTitle(R.string.reading_status)
        .setSingleChoiceItems(labels.toTypedArray(), all.indexOf(current)) { dialog, i ->
            dialog.dismiss()
            lifecycleScope.launch {
                val dao = app.db.dao()
                if (i == all.size) {
                    dao.deleteStatus(manga.id)
                } else {
                    app.repository.saveManga(manga)
                    dao.upsertStatus(dev.mangareader.data.LibraryStatusEntity(manga.id, all[i].code))
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** False when the user turned animations off (accessibility / developer options): show final states directly. */
fun Context.animationsEnabled(): Boolean =
    android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** Items fade/slide in one after another the first time a list gets content. */
fun RecyclerView.animateItemsIn() {
    if (!context.animationsEnabled()) return
    layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(context, R.anim.layout_stagger)
    scheduleLayoutAnimation()
}

/** Soft blur for decorative cover backdrops (Android 12+; older versions just show the dimmed image). */
fun ImageView.blurBackdrop(radius: Float = 48f) {
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
    }
}

/** Base for list adapters that stagger their items in when the list first fills. */
abstract class AnimatedListAdapter<T : Any>(diff: DiffUtil.ItemCallback<T>) : ListAdapter<T, RecyclerView.ViewHolder>(diff) {
    private var recyclerView: RecyclerView? = null
    override fun onAttachedToRecyclerView(rv: RecyclerView) { recyclerView = rv }
    override fun onDetachedFromRecyclerView(rv: RecyclerView) { recyclerView = null }
    override fun onCurrentListChanged(previous: List<T>, current: List<T>) {
        if (previous.isEmpty() && current.isNotEmpty()) recyclerView?.animateItemsIn()
    }
}

fun Context.toast(text: CharSequence) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

/** Human-readable cause for network/parser failures. */
fun Context.errorMessage(e: Throwable): String = when (e) {
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> getString(R.string.err_offline)
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> getString(R.string.err_timeout)
    is javax.net.ssl.SSLException -> getString(R.string.err_ssl)
    is org.koitharu.kotatsu.parsers.exception.NotFoundException -> e.message ?: getString(R.string.err_offline)
    is org.jsoup.HttpStatusException -> getString(R.string.err_http, e.statusCode)
    is dev.mangareader.parser.CloudflareException -> getString(R.string.err_cloudflare)
    else -> e.message ?: e.toString()
}

/**
 * Error dialog. Cloudflare challenges open the browser to solve them; any failure with a known page also offers
 * "Buka di browser" (Kotatsu: ErrorResolver → open in web browser), since the site may have moved or need a check.
 */
fun Activity.showError(e: Throwable, browserUrl: String? = null, retry: () -> Unit) {
    val url = (e as? CloudflareException)?.url ?: browserUrl
    if (url == null) return toast(errorMessage(e))
    AlertDialog.Builder(this)
        .setTitle(if (e is CloudflareException) R.string.cf_title else R.string.load_failed)
        .setMessage(if (e is CloudflareException) getString(R.string.cf_message) else errorMessage(e) + "\n\n" + getString(R.string.browser_hint))
        .setPositiveButton(R.string.open_in_browser) { _, _ -> startActivity(BrowserActivity.intent(this, url)) }
        .setNeutralButton(R.string.retry) { _, _ -> retry() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** Runs [block] on the IME search action or a hardware Enter key. */
fun android.widget.TextView.onSearch(block: (String) -> Unit) = setOnEditorActionListener { v, actionId, event ->
    val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
    if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && !enter) return@setOnEditorActionListener false
    if (event == null || event.action == android.view.KeyEvent.ACTION_DOWN) block(v.text.toString().trim())
    true
}

fun RecyclerView.useGrid(): GridLayoutManager {
    val spans = (resources.configuration.screenWidthDp / 120).coerceAtLeast(3)
    return GridLayoutManager(context, spans).also { layoutManager = it }
}

// ------------------------------------------------------------------ manga lists

data class MangaItem(
    val manga: Manga,
    val subtitle: String? = null,
    /** 0..1, shown as a progress bar in list mode. */
    val progress: Float? = null,
    val badge: String? = null,
)

class MangaAdapter(
    private val grid: Boolean,
    private val onClick: (MangaItem) -> Unit,
    private val onLongClick: ((MangaItem) -> Unit)? = null,
    /** Fixed card width for horizontal rows (grid items otherwise fill their span). */
    private val itemWidthDp: Int? = null,
) : AnimatedListAdapter<MangaItem>(object : DiffUtil.ItemCallback<MangaItem>() {
    override fun areItemsTheSame(a: MangaItem, b: MangaItem) = a.manga.id == b.manga.id
    override fun areContentsTheSame(a: MangaItem, b: MangaItem) = a == b
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = if (grid) ItemMangaGridBinding.inflate(inflater, parent, false).root
        else ItemMangaListBinding.inflate(inflater, parent, false).root
        itemWidthDp?.let { view.layoutParams.width = (it * parent.resources.displayMetrics.density).toInt() }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (grid) {
            val b = ItemMangaGridBinding.bind(holder.itemView)
            b.title.text = item.manga.title
            b.cover.contentDescription = item.manga.title
            b.cover.loadCover(item.manga)
            b.badge.isVisible = item.badge != null
            b.badge.text = item.badge
        } else {
            val b = ItemMangaListBinding.bind(holder.itemView)
            b.title.text = item.manga.title
            b.cover.contentDescription = item.manga.title
            b.cover.loadCover(item.manga)
            b.subtitle.text = item.subtitle
            b.subtitle.isVisible = !item.subtitle.isNullOrEmpty()
            b.progress.isVisible = item.progress != null
            b.progress.progress = ((item.progress ?: 0f) * 100).toInt()
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick?.invoke(item); onLongClick != null }
    }
}

// ------------------------------------------------------------------ simple text rows

data class TextItem(val id: String, val title: String, val subtitle: String? = null, val icon: Int? = null)

class TextAdapter(
    private val onClick: (TextItem) -> Unit,
    private val onLongClick: ((TextItem) -> Unit)? = null,
) : ListAdapter<TextItem, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<TextItem>() {
    override fun areItemsTheSame(a: TextItem, b: TextItem) = a.id == b.id
    override fun areContentsTheSame(a: TextItem, b: TextItem) = a == b
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(ItemTextBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val b = ItemTextBinding.bind(holder.itemView)
        b.title.text = item.title
        b.subtitle.text = item.subtitle
        b.subtitle.isVisible = !item.subtitle.isNullOrEmpty()
        b.icon.isVisible = item.icon != null
        item.icon?.let { b.icon.setImageResource(it) }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick?.invoke(item); onLongClick != null }
    }
}

/** A screen with a toolbar and one list (Kotatsu reuses one such layout for most secondary screens). */
abstract class ListActivity : AppCompatActivity() {
    protected lateinit var binding: ActivityListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.refresh.isEnabled = false
    }

    /** Full-screen failure state with "Buka di browser" + "Coba lagi"; [e] null hides it. */
    protected fun showErrorState(e: Throwable?, browserUrl: String? = null, retry: () -> Unit = {}) {
        binding.error.isVisible = e != null
        if (e == null) return
        binding.empty.isVisible = false
        val url = (e as? CloudflareException)?.url ?: browserUrl
        binding.errorTitle.setText(if (e is CloudflareException) R.string.cf_title else R.string.load_failed)
        binding.errorText.text = if (e is CloudflareException) getString(R.string.cf_message)
        else errorMessage(e) + if (url != null) "\n\n" + getString(R.string.browser_hint) else ""
        binding.errorBrowser.isVisible = url != null
        binding.errorBrowser.setOnClickListener { url?.let { startActivity(BrowserActivity.intent(this, it)) } }
        binding.errorRetry.setOnClickListener { retry() }
    }

    protected fun showEmpty(empty: Boolean, text: CharSequence? = null) {
        binding.empty.isVisible = empty
        if (text != null) binding.empty.text = text
    }

    protected fun View.visibleIf(v: Boolean) { isVisible = v }
}
