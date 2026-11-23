package dev.mangareader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.data.HistoryEntity
import dev.mangareader.databinding.ActivityDocumentBinding
import dev.mangareader.databinding.SheetDocumentSettingsBinding
import dev.mangareader.document.DocFormat
import dev.mangareader.document.EpubBook
import dev.mangareader.document.LocalDocuments
import dev.mangareader.document.LOCAL_SOURCE
import dev.mangareader.document.docFormat
import dev.mangareader.parser.ChapterText
import dev.mangareader.parser.MangaChapter
import dev.mangareader.parser.TextSource
import dev.mangareader.parser.Manga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Reader for local documents (Folium spec §5.2–5.3). One screen, one engine per format:
 * - PDF: Android's PdfRenderer; continuous fit-width scrolling or one page at a time with pinch/double-tap zoom.
 * - EPUB: chapters rendered in a WebView with the reader's font, size, spacing, margins and background. The book is
 *   served from a private origin (https://book.local/); file access and every other network request are refused,
 *   so scripts inside an untrusted EPUB cannot read other files or reach the network.
 * Progress goes to the existing history table (page or chapter + scroll offset), like comics.
 */
class DocumentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentBinding
    private lateinit var manga: Manga
    private lateinit var format: DocFormat
    private val prefs get() = app.settings

    // PDF
    private var pfd: ParcelFileDescriptor? = null
    private var pdf: PdfRenderer? = null
    private var pageSizes: List<Pair<Int, Int>> = emptyList()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1) // PdfRenderer allows one page open at a time
    private val bitmaps = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    // EPUB
    private var book: EpubBook? = null

    // Online novel (TextSource): chapters in reading order, current chapter's text
    private var novel: TextSource? = null
    private var novelChapters: List<MangaChapter> = emptyList()
    private var novelText: ChapterText? = null
    /** Rendered page of the current chapter, served at https://novel.local/chapter (see serve()). */
    @Volatile private var novelPage: String = ""
    private var novelJob: Job? = null
    private var chapterIndex = 0
    private var pendingOffset = 0f

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(e.x); return true }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manga = requireNotNull(IntentCompat.getSerializableExtra(intent, EXTRA_MANGA, Manga::class.java))
        novel = app.sourceOrNull(manga.source) as? TextSource
        // Online novels use the same text path as EPUB (renderer, settings, progress); only the chapter source differs.
        format = if (novel != null) DocFormat.EPUB else manga.docFormat() ?: DocFormat.PDF
        binding = ActivityDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ReaderAppearance.apply(this, binding.root, ReaderProfile.TEXT)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = manga.title
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_toc -> if (format == DocFormat.EPUB) showToc() else askPage()
                R.id.action_settings -> showSettings()
                R.id.action_edit -> editInfo()
            }
            true
        }
        binding.toolbar.menu.findItem(R.id.action_toc)?.title = getString(if (format == DocFormat.EPUB) R.string.table_of_contents else R.string.go_to_page)
        binding.toolbar.menu.findItem(R.id.action_edit)?.isVisible = manga.source == LOCAL_SOURCE // metadata editing is for imported files
        binding.slider.addOnChangeListener { _, value, fromUser -> if (fromUser) goTo(value.toInt()) }
        binding.prev.setOnClickListener { goTo(position() - 1) }
        binding.next.setOnClickListener { goTo(position() + 1) }
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = onPositionShown(position)
        })
        setBarsVisible(false)

        lifecycleScope.launch {
            val saved = app.db.dao().history(manga.id)
            val startPage = savedInstanceState?.getInt(KEY_POSITION) ?: saved?.page ?: 0
            pendingOffset = saved?.offset ?: 0f
            try {
                when {
                    novel != null -> openNovel(intent.getStringExtra(EXTRA_CHAPTER) ?: saved?.chapterUrl, saved)
                    format == DocFormat.PDF -> openPdf(startPage)
                    else -> openEpub(startPage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.loading.isVisible = false
                AlertDialog.Builder(this@DocumentActivity).setTitle(R.string.load_failed)
                    .setMessage(getString(R.string.document_missing) + "\n\n" + errorMessage(e))
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isOnBar(ev)) tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isOnBar(ev: MotionEvent) = listOf(binding.topBar, binding.bottomBar).any { bar ->
        bar.isVisible && Rect().also { bar.getGlobalVisibleRect(it) }.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    /** Centre toggles the bars; in PDF page mode the outer thirds turn pages. */
    private fun onTap(x: Float) {
        val w = binding.root.width
        if (format == DocFormat.PDF && pdfPaged() && x !in w / 3f..w * 2 / 3f) return goTo(position() + if (x > w / 2f) 1 else -1)
        setBarsVisible(!binding.topBar.isVisible)
    }

    private fun setBarsVisible(visible: Boolean) {
        binding.topBar.isVisible = visible
        binding.bottomBar.isVisible = visible
        val c = WindowInsetsControllerCompat(window, binding.root)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun count() = if (format == DocFormat.PDF) pageSizes.size else chapterTitles().size

    /** Chapter names of the text being read (EPUB book or online novel). */
    private fun chapterTitles(): List<String> = if (novel != null) novelChapters.map { it.name } else book?.chapters?.map { it.title }.orEmpty()

    private fun position(): Int = when {
        format == DocFormat.EPUB -> chapterIndex
        pdfPaged() -> binding.pager.currentItem
        else -> (binding.list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
    }

    private fun goTo(target: Int) {
        if (target !in 0 until count()) return
        if (format == DocFormat.EPUB) { saveProgress(); pendingOffset = 0f; loadChapter(target); return }
        if (pdfPaged()) binding.pager.currentItem = target
        else (binding.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, 0)
        onPositionShown(target)
    }

    private fun onPositionShown(pos: Int) {
        val n = count()
        if (n == 0) return
        binding.pageIndicator.text = if (format == DocFormat.EPUB) getString(R.string.chapter_n_of, pos + 1, n) else getString(R.string.page_n, pos + 1, n)
        binding.toolbar.subtitle = if (format == DocFormat.EPUB) chapterTitles().getOrNull(pos) else null
        binding.slider.valueTo = (n - 1).coerceAtLeast(1).toFloat()
        binding.slider.isEnabled = n > 1
        binding.slider.value = pos.coerceIn(0, binding.slider.valueTo.toInt()).toFloat()
    }

    // ---------------------------------------------------------------- PDF

    private fun pdfPaged() = prefs.getString(KEY_PDF_MODE, PDF_SCROLL) == PDF_PAGE

    private suspend fun openPdf(startPage: Int) {
        withContext(renderDispatcher) {
            val fd = requireNotNull(contentResolver.openFileDescriptor(Uri.parse(manga.url), "r")) { getString(R.string.err_file_open) }
            pfd = fd
            val renderer = PdfRenderer(fd)
            pdf = renderer
            // Page sizes up front so the scrolling list can reserve each page's height (no jumping while rendering).
            pageSizes = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width to it.height } }
        }
        binding.loading.isVisible = false
        applyPdfMode(startPage.coerceIn(0, (pageSizes.size - 1).coerceAtLeast(0)))
    }

    private fun applyPdfMode(page: Int) {
        val paged = pdfPaged()
        binding.list.isVisible = !paged
        binding.pager.isVisible = paged
        val adapter = PdfAdapter(paged)
        if (paged) {
            binding.list.adapter = null
            binding.pager.adapter = adapter
            binding.pager.setCurrentItem(page, false)
        } else {
            binding.pager.adapter = null
            if (binding.list.layoutManager == null) {
                binding.list.layoutManager = LinearLayoutManager(this)
                // Thin gap so page boundaries stay visible on white pages.
                val gap = (8 * resources.displayMetrics.density).toInt()
                binding.list.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) { out.bottom = gap }
                })
                binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = onPositionShown(position())
                })
            }
            binding.list.adapter = adapter
            (binding.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(page, 0)
        }
        onPositionShown(page)
    }

    /** Renders [index] to fit [width] px (white paper, like a printed page). Cached; one page at a time. */
    private suspend fun render(index: Int, width: Int): Bitmap? {
        bitmaps.get(index)?.takeIf { it.width >= width }?.let { return it }
        return withContext(renderDispatcher) {
            val renderer = pdf ?: return@withContext null
            renderer.openPage(index).use { page ->
                val w = width.coerceIn(1, 2400)
                val bmp = Bitmap.createBitmap(w, (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmaps.put(index, bmp)
                bmp
            }
        }
    }

    private inner class PdfAdapter(private val paged: Boolean) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pageSizes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: ImageView = if (paged) ZoomImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } else ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val iv = holder.itemView as ImageView
            (iv.tag as? Job)?.cancel()
            iv.setImageBitmap(null)
            iv.contentDescription = getString(R.string.page_n, position + 1, pageSizes.size)
            val screenW = resources.displayMetrics.widthPixels
            if (!paged) {
                val (w, h) = pageSizes[position]
                iv.layoutParams.height = (screenW.toFloat() * h / w).toInt() // reserve the page's height
            }
            // Paged mode renders sharper so zooming in stays readable.
            iv.tag = lifecycleScope.launch { render(position, if (paged) (screenW * 1.6f).toInt() else screenW)?.let { iv.setImageBitmap(it) } }
        }
    }

    private fun askPage() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "1–${count()}" }
        val pad = (20 * resources.displayMetrics.density).toInt()
        AlertDialog.Builder(this).setTitle(R.string.go_to_page)
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setPositiveButton(android.R.string.ok) { _, _ -> input.text.toString().toIntOrNull()?.let { goTo(it - 1) } }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---------------------------------------------------------------- EPUB

    private suspend fun openEpub(startChapter: Int) {
        val dir = LocalDocuments.bookDir(this, manga.url)
        book = withContext(Dispatchers.IO) {
            if (!File(dir, ".complete").exists()) {
                requireNotNull(contentResolver.openInputStream(Uri.parse(manga.url))) { getString(R.string.err_file_open) }.use { EpubBook.extract(it, dir) }
            }
            EpubBook.open(dir)
        }
        setupWebView()
        binding.web.isVisible = true
        loadChapter(startChapter.coerceIn(0, count() - 1))
    }

    /** Online novel: chapters from the source (oldest first for reading), resuming [startUrl] where it was left. */
    private suspend fun openNovel(startUrl: String?, saved: HistoryEntity?) {
        val stored = app.repository.chapters(manga.id)
        novelChapters = stored.ifEmpty { app.repository.refresh(manga).chapters.orEmpty() }.asReversed()
        if (novelChapters.isEmpty()) throw java.io.IOException(getString(R.string.no_chapters))
        val start = novelChapters.indexOfFirst { it.url == startUrl }.takeIf { it >= 0 } ?: 0
        // The saved scroll offset belongs to the saved chapter only.
        pendingOffset = if (saved != null && novelChapters.getOrNull(start)?.url == saved.chapterUrl) saved.offset else 0f
        setupWebView()
        binding.web.isVisible = true
        loadChapter(start)
    }

    /** Builds the chapter page from sanitised paragraphs: a paid-chapter notice and a link to the next chapter. */
    private fun novelHtml(t: ChapterText, next: MangaChapter?): String {
        fun esc(s: String) = android.text.Html.escapeHtml(s)
        val note = if (t.locked) "<div class=\"folium-note\">${esc(getString(R.string.paid_chapter_note))}" +
            "<br><a href=\"https://$NOVEL_HOST/web\">${esc(getString(R.string.read_on_site))}</a></div>" else ""
        val body = t.paragraphs.joinToString("") { "<p>$it</p>" }
        val footer = next?.let { "<p class=\"folium-next\"><a href=\"https://$NOVEL_HOST/next\">${esc(getString(R.string.next_chapter_is, it.name))}</a></p>" }.orEmpty()
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "</head><body><h1>${esc(t.title)}</h1>$note$body${if (t.locked) note else ""}$footer</body></html>"
    }

    @SuppressLint("SetJavaScriptEnabled") // needed to apply reader CSS and save/restore the scroll position
    private fun setupWebView() {
        val web = binding.web
        web.setBackgroundColor(ReaderAppearance.backgroundColor(this, ReaderProfile.TEXT))
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            builtInZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                serve(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == NOVEL_HOST) {
                    when (url.path) {
                        "/next" -> goTo(position() + 1)
                        "/web" -> novelText?.let { startActivity(BrowserActivity.intent(this@DocumentActivity, it.webUrl)) }
                    }
                    return true
                }
                if (url.host == BOOK_HOST) {
                    // Link to another chapter of the book.
                    val href = EpubBook.normalize(url.path.orEmpty().trimStart('/'))
                    book?.chapters?.indexOfFirst { it.href == href }?.takeIf { it >= 0 }?.let { saveProgress(); pendingOffset = 0f; loadChapter(it) }
                }
                return true // external links are not followed from inside a book
            }

            override fun onPageFinished(view: WebView, url: String) {
                applyTextStyle()
                val offset = pendingOffset
                pendingOffset = 0f
                if (offset > 0f) view.postDelayed({
                    view.evaluateJavascript("window.scrollTo(0,(document.documentElement.scrollHeight-window.innerHeight)*$offset)", null)
                }, 150)
                binding.loading.isVisible = false
            }
        }
    }

    /** Serves files of the extracted book only; anything else (network, other paths) gets an empty 404. */
    private fun serve(url: Uri): WebResourceResponse {
        if (url.host == NOVEL_HOST && url.path == "/chapter") {
            return WebResourceResponse("text/html", "utf-8", novelPage.byteInputStream())
        }
        val dir = book?.dir?.canonicalFile
        if (url.host == BOOK_HOST && dir != null) {
            val file = File(dir, EpubBook.normalize(url.path.orEmpty().trimStart('/'))).canonicalFile
            if (file.path.startsWith(dir.path + File.separator) && file.isFile) {
                return WebResourceResponse(mimeOf(file.name), "utf-8", FileInputStream(file))
            }
        }
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "xhtml", "xht" -> "application/xhtml+xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun loadChapter(index: Int) {
        val source = novel
        if (source != null) return loadNovelChapter(source, index)
        val ch = book?.chapters?.getOrNull(index) ?: return
        chapterIndex = index
        binding.loading.isVisible = true
        binding.web.loadUrl("https://$BOOK_HOST/" + ch.href.split('/').joinToString("/") { Uri.encode(it) })
        onPositionShown(index)
    }

    private fun loadNovelChapter(source: TextSource, index: Int) {
        val ch = novelChapters.getOrNull(index) ?: return
        chapterIndex = index
        onPositionShown(index)
        binding.loading.isVisible = true
        novelJob?.cancel()
        novelJob = lifecycleScope.launch {
            try {
                // Downloaded chapters are read from disk (works offline), else fetched.
                val text = withContext(Dispatchers.IO) { app.storage.localText(manga.id, ch.url) } ?: source.getChapterText(ch)
                novelText = text
                // Self-contained page on a private origin, served by serve(); nothing else is loaded.
                novelPage = novelHtml(text, novelChapters.getOrNull(index + 1))
                binding.web.loadUrl("https://$NOVEL_HOST/chapter?i=$index")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.loading.isVisible = false
                showError(e, "https://www.webnovel.com" + ch.url) { loadNovelChapter(source, index) }
            }
        }
    }

    /** Reader typography (spec §5.2): font, size, line height, paragraph spacing, margins, page colours. */
    private fun applyTextStyle() {
        val bg = String.format("#%06X", 0xFFFFFF and ReaderAppearance.backgroundColor(this, ReaderProfile.TEXT))
        val fg = String.format("#%06X", 0xFFFFFF and ReaderAppearance.textColor(this, ReaderProfile.TEXT))
        val link = String.format("#%06X", 0xFFFFFF and getColor(if (ReaderAppearance.isLight(this, ReaderProfile.TEXT)) R.color.forest_green else R.color.sage))
        val font = if (prefs.getString(KEY_FONT, FONT_SERIF) == FONT_SANS) "sans-serif" else "serif" // serif = Noto Serif on Android
        val size = prefs.getInt(KEY_TEXT_SIZE, 110)
        val line = prefs.getInt(KEY_LINE, 16) / 10f
        val margin = prefs.getInt(KEY_MARGIN, 20)
        val css = "html,body{background:$bg!important;color:$fg!important;font-family:$font!important;font-size:$size%!important;" +
            "line-height:$line!important;margin:0!important;padding:0 ${margin}px!important;word-wrap:break-word}" +
            "body{padding-top:24px!important;padding-bottom:64px!important}" +
            "p,li,div,span{line-height:inherit!important;color:inherit!important;font-family:inherit!important}" +
            "p{margin:0 0 0.9em 0!important}a{color:$link!important}" +
            "img,svg,video{max-width:100%!important;height:auto!important}" +
            ".folium-note{border:1px solid $link;border-radius:12px;padding:12px 16px;margin:0 0 1.2em 0;font-family:sans-serif}" +
            ".folium-next{margin-top:2em!important;font-family:sans-serif;font-weight:600}"
        binding.web.setBackgroundColor(ReaderAppearance.backgroundColor(this, ReaderProfile.TEXT))
        binding.web.evaluateJavascript(
            "(function(){var s=document.getElementById('folium-style');if(!s){s=document.createElement('style');s.id='folium-style';" +
                "(document.head||document.documentElement).appendChild(s);}s.textContent=${JSONObject.quote(css)};})()", null,
        )
    }

    private fun showToc() {
        val titles = chapterTitles().takeIf { it.isNotEmpty() } ?: return
        AlertDialog.Builder(this).setTitle(R.string.table_of_contents)
            .setSingleChoiceItems(titles.toTypedArray(), chapterIndex) { d, i -> d.dismiss(); goTo(i) }
            .show()
    }

    // ---------------------------------------------------------------- settings, info, progress

    private fun showSettings() {
        val sheet = BottomSheetDialog(this)
        val b = SheetDocumentSettingsBinding.inflate(layoutInflater)
        sheet.setContentView(b.root)
        b.pdfSection.isVisible = format == DocFormat.PDF
        b.textSection.isVisible = format == DocFormat.EPUB
        if (format == DocFormat.PDF) {
            b.pdfMode.check(if (pdfPaged()) R.id.pdf_page else R.id.pdf_scroll)
            b.pdfMode.addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                val page = position()
                prefs.edit().putString(KEY_PDF_MODE, if (id == R.id.pdf_page) PDF_PAGE else PDF_SCROLL).apply()
                if (pageSizes.isNotEmpty()) applyPdfMode(page)
            }
        } else {
            b.font.check(if (prefs.getString(KEY_FONT, FONT_SERIF) == FONT_SANS) R.id.font_sans else R.id.font_serif)
            b.font.addOnButtonCheckedListener { _, id, checked ->
                if (checked) { prefs.edit().putString(KEY_FONT, if (id == R.id.font_sans) FONT_SANS else FONT_SERIF).apply(); applyTextStyle() }
            }
            b.textSize.value = prefs.getInt(KEY_TEXT_SIZE, 110).toFloat().coerceIn(80f, 200f)
            b.textSize.addOnChangeListener { _, v, user -> if (user) { prefs.edit().putInt(KEY_TEXT_SIZE, v.toInt()).apply(); applyTextStyle() } }
            b.lineSpacing.value = (prefs.getInt(KEY_LINE, 16) / 10f).coerceIn(1.2f, 2.2f)
            b.lineSpacing.addOnChangeListener { _, v, user -> if (user) { prefs.edit().putInt(KEY_LINE, Math.round(v * 10)).apply(); applyTextStyle() } }
            b.margins.value = prefs.getInt(KEY_MARGIN, 20).let { it - it % 4 }.toFloat().coerceIn(0f, 48f)
            b.margins.addOnChangeListener { _, v, user -> if (user) { prefs.edit().putInt(KEY_MARGIN, v.toInt()).apply(); applyTextStyle() } }
        }
        ReaderAppearance.bind(this, binding.root, b.appearance, ReaderProfile.TEXT) { if (format == DocFormat.EPUB) applyTextStyle() }
        sheet.show()
    }

    /** Local metadata editing (spec §4.4): imported files often have poor titles. */
    private fun editInfo() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val title = EditText(this).apply { hint = getString(R.string.title_label); setText(manga.title); setSingleLine() }.also { form.addView(it) }
        val author = EditText(this).apply { hint = getString(R.string.author_label); setText(manga.author); setSingleLine() }.also { form.addView(it) }
        AlertDialog.Builder(this).setTitle(R.string.edit_info).setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = title.text.toString().trim().ifEmpty { manga.title }
                manga = manga.copy(title = t, author = author.text.toString().trim().ifEmpty { null })
                binding.toolbar.title = manga.title
                lifecycleScope.launch { app.repository.saveManga(manga) }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Writes the position to history: PDF page, or EPUB chapter + scroll offset inside it. */
    private fun saveProgress() {
        val n = count()
        if (n == 0) return
        val pos = position()
        val m = manga
        fun write(offset: Float) {
            novelChapters.getOrNull(pos)?.takeIf { novel != null }?.let { ch ->
                app.appScope.launch { app.repository.saveHistory(m, ch, 0, offset) }
                return
            }
            val percent = dev.mangareader.document.documentProgress(format, pos, offset, n)
            val name = if (format == DocFormat.EPUB) chapterTitles().getOrNull(pos).orEmpty() else getString(R.string.page_n, pos + 1, n)
            app.appScope.launch { app.repository.saveDocumentHistory(m, HistoryEntity(m.id, m.url, name, pos, percent, offset = offset)) }
        }
        if (format == DocFormat.EPUB && binding.web.isVisible) {
            binding.web.evaluateJavascript("(function(){var h=document.documentElement.scrollHeight-window.innerHeight;return h>0?window.scrollY/h:0})()") {
                write(it?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f)
            }
        } else write(0f)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POSITION, position())
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.web.destroy()
        val renderer = pdf
        val fd = pfd
        app.appScope.launch(renderDispatcher) { // on the render thread, after any page still rendering
            renderer?.close(); fd?.close()
        }
        bitmaps.evictAll()
    }

    companion object {
        private const val EXTRA_MANGA = "manga"
        private const val KEY_POSITION = "position"
        private const val BOOK_HOST = "book.local"
        private const val NOVEL_HOST = "novel.local"
        private const val EXTRA_CHAPTER = "chapter"
        private const val KEY_PDF_MODE = "pdf_mode"
        private const val PDF_SCROLL = "SCROLL"
        private const val PDF_PAGE = "PAGE"
        private const val KEY_FONT = "epub_font"
        private const val FONT_SERIF = "SERIF"
        private const val FONT_SANS = "SANS"
        private const val KEY_TEXT_SIZE = "epub_text_size"
        private const val KEY_LINE = "epub_line_spacing" // tenths (16 = 1.6), shared with Settings
        private const val KEY_MARGIN = "epub_margin"

        /** [chapterUrl]: online novels open this chapter (else where the reader left off). */
        fun intent(ctx: Context, m: Manga, chapterUrl: String? = null) =
            Intent(ctx, DocumentActivity::class.java).putExtra(EXTRA_MANGA, m.copy(chapters = null)).putExtra(EXTRA_CHAPTER, chapterUrl)
    }
}
