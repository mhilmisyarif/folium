package dev.mangareader.ui

import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.data.BookmarkEntity
import dev.mangareader.data.StatsEntity
import dev.mangareader.databinding.ActivityReaderBinding
import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Kotatsu-style reader: webtoon or paged (LTR/RTL), tap zones, page slider, chapter switching, bookmarks.
 * Folium additions (spec §5.1): pinch/double-tap zoom in paged mode, reader settings sheet (mode, page background,
 * brightness, auto next chapter, keep screen on) kept apart from the app theme, and an end-of-chapter footer.
 */
class ReaderActivity : AppCompatActivity() {

    /** Kotatsu reader modes: Standar (LTR), Kanan-ke-kiri, Vertikal, Webtoon. */
    enum class Mode { WEBTOON, PAGED_LTR, PAGED_RTL, PAGED_VERTICAL }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var manga: Manga
    private lateinit var chapter: MangaChapter
    private var chapters: List<MangaChapter> = emptyList()
    private var pages: List<String> = emptyList()
    private var mode = Mode.WEBTOON
    private var loadJob: Job? = null
    private var bookmarksJob: Job? = null
    private var bookmarkedPages: Set<Int> = emptySet()
    private val parser by lazy { app.source(manga.source) }

    // reading-time stats for the current session (Kotatsu: stats table)
    private var sessionStart = 0L
    private val viewedPages = HashSet<String>()
    private var autoNextJob: Job? = null
    private var lastPreloaded = -1
    private var incognito = false
    private val prefs get() = app.settings

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(e.x); return true }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manga = requireNotNull(IntentCompat.getSerializableExtra(intent, EXTRA_MANGA, Manga::class.java))
        // loadChapter() writes the current chapter back into the intent, so recreation resumes it.
        chapter = requireNotNull(IntentCompat.getSerializableExtra(intent, EXTRA_CHAPTER, MangaChapter::class.java))
        val startPage = savedInstanceState?.getInt(EXTRA_PAGE) ?: intent.getIntExtra(EXTRA_PAGE, 0)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyAppearance()
        mode = resolveMode()
        requestedOrientation = when (prefs.getString(KEY_ORIENTATION, "SYSTEM")) {
            "AUTO" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            "PORTRAIT" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "LANDSCAPE" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = manga.title
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_bookmark -> toggleBookmark()
                R.id.action_settings -> showSettings()
            }
            true
        }
        binding.webtoon.layoutManager = LinearLayoutManager(this)
        binding.webtoon.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                // The footer fully on screen = the reader scrolled to the very end of the chapter.
                onEndVisible(lm.findLastCompletelyVisibleItemPosition() == pages.size && pages.isNotEmpty())
                onPageShown(currentPage())
            }
        })
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onEndVisible(position == pages.size && pages.isNotEmpty())
                if (position < pages.size) onPageShown(position)
            }
        })
        binding.slider.addOnChangeListener { _, value, fromUser -> if (fromUser) goToPage(value.toInt()) }
        binding.prev.setOnClickListener { switchChapter(+1) } // list is newest first: older = higher index
        binding.next.setOnClickListener { switchChapter(-1) }
        setBarsVisible(false)

        lifecycleScope.launch {
            incognito = savedInstanceState?.getBoolean(KEY_INCOGNITO) ?: decideIncognito()
            if (incognito) toast(getString(R.string.incognito_on)) else app.markSourceUsed(manga.source)
            chapters = app.repository.chapters(manga.id).ifEmpty {
                runCatching { app.repository.refresh(manga).chapters.orEmpty() }.getOrDefault(emptyList())
            }
            loadChapter(chapter, startPage)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isOnBar(ev)) tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isOnBar(ev: MotionEvent): Boolean = listOf(binding.topBar, binding.bottomBar).any { bar ->
        bar.isVisible && Rect().also { bar.getGlobalVisibleRect(it) }.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    /** Paged mode: outer thirds turn pages (mirrored for RTL); centre toggles the bars. Webtoon: any tap toggles. */
    private fun onTap(x: Float) {
        val w = binding.root.width
        if (mode == Mode.WEBTOON || x in w / 3f..w * 2 / 3f) return setBarsVisible(!binding.topBar.isVisible)
        val forward = (x > w * 2 / 3f) == (mode != Mode.PAGED_RTL || prefs.getBoolean(KEY_TAPS_LTR, false))
        goToPage(currentPage() + if (forward) 1 else -1)
    }

    /**
     * Mode for this title (Kotatsu): the one chosen for it in the reader, else webtoon when "Otomatis deteksi" finds
     * a long-strip title, else the default mode from Settings.
     */
    private fun resolveMode(): Mode {
        perMangaModes().getString(manga.id, null)?.let { saved -> runCatching { return Mode.valueOf(saved) } }
        if (prefs.getBoolean(KEY_MODE_DETECT, true) && looksLikeWebtoon()) return Mode.WEBTOON
        return runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.WEBTOON.name)!!) }.getOrDefault(Mode.WEBTOON)
    }

    /**
     * shortcut: Kotatsu measures page proportions; here the source type and tags decide (manhwa/manhua/webtoon are
     * long strips). Upgrade to measuring the first pages if titles get misdetected.
     */
    private fun looksLikeWebtoon(): Boolean {
        val type = app.sourceOrNull(manga.source)?.contentType
        if (type == "MANHWA" || type == "MANHUA") return true
        val tags = manga.tags.joinToString(" ").lowercase()
        return listOf("webtoon", "manhwa", "manhua", "long strip", "full color").any { it in tags }
    }

    private fun perMangaModes() = getSharedPreferences("reader_modes", MODE_PRIVATE)

    /** Hardware keys (Kotatsu): volume buttons and arrows turn pages; "Kontrol pembaca ergonomis" keeps them LTR. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val volume = prefs.getBoolean(KEY_VOLUME_BUTTONS, false)
        val inverted = prefs.getBoolean(KEY_NAV_INVERTED, false)
        val ltr = prefs.getBoolean(KEY_TAPS_LTR, false)
        val step = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (volume) (if (inverted) -1 else 1) else 0
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (volume) (if (inverted) 1 else -1) else 0
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (mode == Mode.PAGED_RTL && !ltr) -1 else 1
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (mode == Mode.PAGED_RTL && !ltr) 1 else -1
            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_SPACE -> 1
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> -1
            else -> 0
        }
        if (step == 0 || pages.isEmpty()) return super.onKeyDown(keyCode, event)
        if (mode == Mode.WEBTOON) binding.webtoon.smoothScrollBy(0, step * binding.webtoon.height * 9 / 10)
        else goToPage(currentPage() + step)
        return true
    }

    private fun setBarsVisible(visible: Boolean) {
        binding.topBar.isVisible = visible
        binding.bottomBar.isVisible = visible
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // "Mode layar penuh": system bars hide with the reader bars; off = they always stay.
        val fullscreen = prefs.getBoolean(KEY_FULLSCREEN, true)
        if (visible || !fullscreen) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun loadChapter(target: MangaChapter, startPage: Int, announce: Boolean = false) {
        autoNextJob?.cancel()
        if (announce && prefs.getBoolean(KEY_CHAPTER_TOAST, true)) toast(target.name) // "Tampilkan popup perubahan bab"
        saveHistory()
        chapter = target
        intent.putExtra(EXTRA_CHAPTER, target)
        binding.toolbar.subtitle = target.name
        binding.loading.isVisible = true
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                // Downloaded chapters are read from disk (works offline).
                pages = app.storage.localPages(manga.id, target.url) ?: parser.getPages(target)
                applyMode(startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                if (!incognito) app.repository.saveHistory(manga, target, currentPage())
                observeBookmarks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e, parser.publicUrl(manga)) { loadChapter(target, startPage) }
            } finally {
                binding.loading.isVisible = false
            }
        }
    }

    private fun applyMode(page: Int) {
        val webtoon = mode == Mode.WEBTOON
        binding.webtoon.isVisible = webtoon
        binding.pager.isVisible = !webtoon
        val footer = Footer(chapter.name, nextChapter()?.name, lightBackground()) { switchChapter(-1) }
        val scaleMode = runCatching { ZoomImageView.ScaleMode.valueOf(prefs.getString(KEY_ZOOM_MODE, null)!!) }.getOrDefault(ZoomImageView.ScaleMode.FIT_CENTER)
        // "Bilah informasi": page indicator on/off and transparent or on a scrim.
        binding.pageIndicator.isVisible = prefs.getBoolean(KEY_INFO_BAR, true)
        if (prefs.getBoolean(KEY_INFO_BAR_TRANSPARENT, true)) binding.pageIndicator.background = null
        else binding.pageIndicator.setBackgroundResource(R.color.image_scrim_mid)
        if (webtoon) {
            binding.pager.adapter = null
            while (binding.webtoon.itemDecorationCount > 0) binding.webtoon.removeItemDecorationAt(0)
            if (prefs.getBoolean(KEY_WEBTOON_GAPS, false)) {
                val gap = (12 * resources.displayMetrics.density).toInt()
                binding.webtoon.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) { out.bottom = gap }
                })
            }
            binding.webtoon.adapter = PageAdapter(pages, manga.source, fullscreen = false, footer)
            (binding.webtoon.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(page, 0)
        } else {
            binding.webtoon.adapter = null
            binding.pager.orientation = if (mode == Mode.PAGED_VERTICAL) ViewPager2.ORIENTATION_VERTICAL else ViewPager2.ORIENTATION_HORIZONTAL
            binding.pager.layoutDirection = if (mode == Mode.PAGED_RTL) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            binding.pager.setPageTransformer(if (pageAnimation() == ANIM_ADVANCED) DepthTransformer else null)
            binding.pager.adapter = PageAdapter(pages, manga.source, fullscreen = true, footer, scaleMode)
            binding.pager.setCurrentItem(page, false)
        }
        binding.slider.valueTo = (pages.size - 1).coerceAtLeast(1).toFloat()
        binding.slider.isEnabled = pages.size > 1
        onPageShown(page)
    }

    /** Current page index, never the footer (history, bookmarks and the slider only know real pages). */
    private fun currentPage(): Int = (if (mode == Mode.WEBTOON) {
        (binding.webtoon.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
    } else binding.pager.currentItem).coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    private fun nextChapter(): MangaChapter? = chapters.indexOfFirst { it.url == chapter.url }.takeIf { it > 0 }?.let { chapters[it - 1] }

    /**
     * End of chapter shown: with auto-next on, open the next chapter after a short pause (scrolling back cancels it).
     * Without a next chapter, just say so once.
     */
    private fun onEndVisible(visible: Boolean) {
        if (!visible) { autoNextJob?.cancel(); autoNextJob = null; return }
        if (autoNextJob?.isActive == true || !prefs.getBoolean(KEY_AUTO_NEXT, true)) return
        if (nextChapter() == null) return
        autoNextJob = lifecycleScope.launch {
            toast(getString(R.string.opening_next))
            kotlinx.coroutines.delay(AUTO_NEXT_DELAY_MS)
            switchChapter(-1)
        }
    }

    private fun goToPage(page: Int) {
        if (pages.isEmpty()) return
        if (page !in pages.indices) return switchChapter(if (page < 0) +1 else -1)
        if (mode == Mode.WEBTOON) (binding.webtoon.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(page, 0)
        else binding.pager.setCurrentItem(page, pageAnimation() != ANIM_NONE && animationsEnabled()) // "Animasi halaman"
        onPageShown(page)
    }

    private fun pageAnimation() = prefs.getString(KEY_ANIMATION, ANIM_DEFAULT) ?: ANIM_DEFAULT

    /** "Muat halaman lebih awal" (Kotatsu pages_preload): fetch the next pages into the image cache ahead of time. */
    private fun preloadAfter(page: Int) {
        val allowed = when (prefs.getString(KEY_PRELOAD, PRELOAD_ALWAYS)) {
            PRELOAD_NEVER -> false
            PRELOAD_WIFI -> getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
            else -> true
        }
        if (!allowed) return
        val loader = coil3.SingletonImageLoader.get(this)
        pages.drop(page + 1).take(PRELOAD_COUNT).filter { it.startsWith("http") }.forEach { url ->
            loader.enqueue(
                coil3.request.ImageRequest.Builder(this).data(url)
                    .httpHeaders(NetworkHeaders.Builder().set(dev.mangareader.parser.HEADER_SOURCE, manga.source).build())
                    .build(),
            )
        }
    }

    private fun onPageShown(page: Int) {
        if (pages.isEmpty()) return
        binding.pageIndicator.text = getString(R.string.page_n, page + 1, pages.size)
        if (binding.slider.value.toInt() != page) binding.slider.value = page.coerceIn(0, binding.slider.valueTo.toInt()).toFloat()
        viewedPages += "${chapter.url}#$page"
        updateBookmarkIcon()
        if (page != lastPreloaded) { lastPreloaded = page; preloadAfter(page) }
    }

    /** [delta] -1 = next (newer) chapter, +1 = previous (older); the list is newest first. */
    private fun switchChapter(delta: Int) {
        val i = chapters.indexOfFirst { it.url == chapter.url }
        val target = chapters.getOrNull(i + delta)
        if (i < 0 || target == null) return toast(getString(if (delta < 0) R.string.no_next_chapter else R.string.no_prev_chapter))
        loadChapter(target, 0, announce = true)
    }

    // ---------------------------------------------------------------- reader appearance & settings

    private fun lightBackground() = ReaderAppearance.isLight(this, ReaderProfile.COMIC)
    private fun applyAppearance() = ReaderAppearance.apply(this, binding.root, ReaderProfile.COMIC)

    private fun showSettings() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val b = dev.mangareader.databinding.SheetReaderSettingsBinding.inflate(layoutInflater)
        sheet.setContentView(b.root)
        b.mode.check(when (mode) {
            Mode.WEBTOON -> R.id.mode_webtoon; Mode.PAGED_LTR -> R.id.mode_ltr; Mode.PAGED_RTL -> R.id.mode_rtl; Mode.PAGED_VERTICAL -> R.id.mode_vertical
        })
        b.mode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val page = currentPage()
            mode = when (id) { R.id.mode_ltr -> Mode.PAGED_LTR; R.id.mode_rtl -> Mode.PAGED_RTL; R.id.mode_vertical -> Mode.PAGED_VERTICAL; else -> Mode.WEBTOON }
            // Kotatsu remembers the mode per title; the default stays in Settings → Pembaca komik.
            perMangaModes().edit().putString(manga.id, mode.name).apply()
            if (pages.isNotEmpty()) applyMode(page)
        }
        ReaderAppearance.bind(this, binding.root, b.appearance, ReaderProfile.COMIC) {
            if (pages.isNotEmpty()) applyMode(currentPage()) // footer text colour follows the background
        }
        b.autoNext.isChecked = prefs.getBoolean(KEY_AUTO_NEXT, true)
        b.autoNext.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(KEY_AUTO_NEXT, on).apply(); if (!on) autoNextJob?.cancel() }
        sheet.show()
    }

    // ---------------------------------------------------------------- bookmarks

    private fun observeBookmarks() {
        bookmarksJob?.cancel()
        bookmarksJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.db.dao().observeChapterBookmarks(manga.id, chapter.url).collect { list ->
                    bookmarkedPages = list.mapTo(HashSet()) { it.page }
                    updateBookmarkIcon()
                }
            }
        }
    }

    private fun updateBookmarkIcon() {
        binding.toolbar.menu.findItem(R.id.action_bookmark)?.icon?.alpha = if (currentPage() in bookmarkedPages) 255 else 110
    }

    private fun toggleBookmark() {
        val page = currentPage()
        val image = pages.getOrNull(page) ?: return
        val dao = app.db.dao()
        lifecycleScope.launch {
            if (page in bookmarkedPages) {
                dao.deleteBookmark(manga.id, chapter.url, page)
                toast(getString(R.string.bookmark_removed))
            } else {
                app.repository.saveManga(manga)
                dao.upsertBookmark(BookmarkEntity(manga.id, chapter.url, chapter.name, page, image))
                toast(getString(R.string.bookmark_added))
            }
        }
    }

    // ---------------------------------------------------------------- history & stats

    /**
     * Kotatsu "Mode penyamaran untuk manga NSFW": reading an NSFW source leaves no history or stats —
     * always, never, or after asking.
     */
    private suspend fun decideIncognito(): Boolean {
        if (!parser.isNsfw) return false
        return when (prefs.getString(KEY_INCOGNITO_NSFW, INCOGNITO_ASK)) {
            INCOGNITO_ENABLE -> true
            INCOGNITO_DISABLE -> false
            else -> kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.incognito_ask_title)
                    .setMessage(R.string.incognito_ask_message)
                    .setPositiveButton(R.string.incognito_yes) { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(true)) }
                    .setNegativeButton(R.string.incognito_no) { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(false)) }
                    .setOnCancelListener { if (cont.isActive) cont.resumeWith(Result.success(false)) }
                    .show()
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    private fun saveHistory() {
        if (pages.isEmpty() || incognito) return
        val m = manga
        val c = chapter
        val page = currentPage()
        app.appScope.launch { app.repository.saveHistory(m, c, page) }
    }

    override fun onResume() {
        super.onResume()
        sessionStart = System.currentTimeMillis()
        viewedPages.clear()
    }

    override fun onPause() {
        super.onPause()
        saveHistory()
        val duration = System.currentTimeMillis() - sessionStart
        val pagesRead = viewedPages.size
        if (duration > 5_000 && pagesRead > 0 && !incognito) {
            val entry = StatsEntity(manga.id, sessionStart, duration, pagesRead)
            app.appScope.launch {
                app.repository.saveManga(manga)
                app.db.dao().insertStats(entry)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_PAGE, currentPage())
        outState.putBoolean(KEY_INCOGNITO, incognito)
    }

    /** End-of-chapter card: [next] null = this is the latest chapter. */
    private class Footer(val chapterName: String, val next: String?, val lightBackground: Boolean, val onNext: () -> Unit)

    /** "Lanjutan" page animation: the leaving page shrinks and fades. */
    private object DepthTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val p = position.coerceIn(-1f, 1f)
            val s = 1f - 0.12f * kotlin.math.abs(p)
            page.scaleX = s
            page.scaleY = s
            page.alpha = 1f - 0.5f * kotlin.math.abs(p)
        }
    }

    private class PageAdapter(
        private val pages: List<String>,
        private val source: String,
        private val fullscreen: Boolean,
        private val footer: Footer,
        private val scaleMode: ZoomImageView.ScaleMode = ZoomImageView.ScaleMode.FIT_CENTER,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pages.size + 1
        override fun getItemViewType(position: Int) = if (position < pages.size) TYPE_PAGE else TYPE_FOOTER

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_FOOTER) {
                val b = dev.mangareader.databinding.ItemChapterEndBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
                b.root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
                return object : RecyclerView.ViewHolder(b.root) {}
            }
            val iv = if (fullscreen) {
                ZoomImageView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleMode = this@PageAdapter.scaleMode
                }
            } else {
                ImageView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    adjustViewBounds = true
                    minimumHeight = (400 * resources.displayMetrics.density).toInt() // placeholder height while loading
                }
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position >= pages.size) return bindFooter(holder.itemView)
            (holder.itemView as ImageView).apply {
                contentDescription = "Page ${position + 1}"
                (this as? ZoomImageView)?.reset()
                loadImage(pages[position], source)
            }
        }

        private fun bindFooter(view: View) {
            val b = dev.mangareader.databinding.ItemChapterEndBinding.bind(view)
            val ctx = view.context
            val primary = ctx.getColor(if (footer.lightBackground) R.color.ink_black else R.color.on_image)
            val secondary = ctx.getColor(if (footer.lightBackground) R.color.wood_brown else R.color.on_image_variant)
            b.endTitle.setTextColor(primary)
            b.endNext.setTextColor(secondary)
            b.endTitle.text = ctx.getString(R.string.chapter_end, footer.chapterName)
            b.endNext.text = footer.next?.let { ctx.getString(R.string.next_chapter_is, it) } ?: ctx.getString(R.string.last_chapter_reached)
            b.endButton.isVisible = footer.next != null
            b.endButton.setOnClickListener { footer.onNext() }
        }

        private companion object {
            const val TYPE_PAGE = 0
            const val TYPE_FOOTER = 1
        }
    }

    companion object {
        private const val EXTRA_MANGA = "manga"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_PAGE = "page"
        // Keys as in Kotatsu's pref_reader.xml (Settings → Pembaca komik).
        private const val KEY_MODE = "reader_mode"
        private const val KEY_MODE_DETECT = "reader_mode_detect"
        private const val KEY_ZOOM_MODE = "zoom_mode"
        private const val KEY_WEBTOON_GAPS = "webtoon_gaps"
        private const val KEY_TAPS_LTR = "reader_taps_ltr"
        private const val KEY_VOLUME_BUTTONS = "reader_volume_buttons"
        private const val KEY_NAV_INVERTED = "reader_navigation_inverted"
        private const val KEY_ANIMATION = "reader_animation2"
        private const val KEY_FULLSCREEN = "reader_fullscreen"
        private const val KEY_ORIENTATION = "reader_orientation"
        private const val KEY_INFO_BAR = "reader_bar"
        private const val KEY_INFO_BAR_TRANSPARENT = "reader_bar_transparent"
        private const val KEY_CHAPTER_TOAST = "reader_chapter_toast"
        private const val KEY_PRELOAD = "pages_preload"
        private const val ANIM_NONE = "NONE"
        private const val ANIM_DEFAULT = "DEFAULT"
        private const val ANIM_ADVANCED = "ADVANCED"
        private const val PRELOAD_ALWAYS = "ALWAYS"
        private const val PRELOAD_WIFI = "WIFI"
        private const val PRELOAD_NEVER = "NEVER"
        private const val PRELOAD_COUNT = 3
        private const val KEY_INCOGNITO = "incognito"
        private const val KEY_INCOGNITO_NSFW = "incognito_nsfw"
        private const val INCOGNITO_ENABLE = "ENABLE"
        private const val INCOGNITO_ASK = "ASK"
        private const val INCOGNITO_DISABLE = "DISABLE"
        private const val KEY_AUTO_NEXT = "reader_auto_next"
        private const val AUTO_NEXT_DELAY_MS = 1_500L

        fun intent(ctx: Context, m: Manga, ch: MangaChapter, page: Int): Intent =
            if (m.source == dev.mangareader.document.LOCAL_SOURCE) DocumentActivity.intent(ctx, m) // resumes from history
            else if (ctx.app.sourceOrNull(m.source) is dev.mangareader.parser.TextSource) DocumentActivity.intent(ctx, m, ch.url) // novel
            else Intent(ctx, ReaderActivity::class.java)
                .putExtra(EXTRA_MANGA, m.copy(chapters = null)).putExtra(EXTRA_CHAPTER, ch).putExtra(EXTRA_PAGE, page)
    }
}
