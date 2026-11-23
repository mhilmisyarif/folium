package dev.mangareader

import android.app.Application
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.google.android.material.color.DynamicColors
import dev.mangareader.data.AppDatabase
import dev.mangareader.data.LocalStorage
import dev.mangareader.data.MangaRepository
import dev.mangareader.parser.Manga
import dev.mangareader.parser.MangaChapter
import dev.mangareader.parser.MangaParser
import dev.mangareader.parser.CloudflareInterceptor
import dev.mangareader.parser.KotatsuContext
import dev.mangareader.parser.KotatsuSource
import dev.mangareader.parser.SourceHeadersInterceptor
import dev.mangareader.parser.WebViewFetcher
import dev.mangareader.scrobbling.AniListScrobbler
import dev.mangareader.scrobbling.KitsuScrobbler
import dev.mangareader.scrobbling.MalScrobbler
import dev.mangareader.scrobbling.Scrobbler
import dev.mangareader.scrobbling.ShikimoriScrobbler
import dev.mangareader.scrobbling.chapterNumber
import dev.mangareader.ui.FaviconFetcher
import dev.mangareader.work.TrackerWorker
import dev.mangareader.work.createNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.io.File

class App : Application(), SingletonImageLoader.Factory {

    /** Work that must outlive a screen (scrobbling, history writes). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db by lazy { AppDatabase.create(this) }
    val settings by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    val domainPrefs by lazy { getSharedPreferences("domains", MODE_PRIVATE) }

    /** Per-source options (Settings of a source: user agent, image server, slowdown …), one file per source like Kotatsu. */
    fun sourcePrefsName(sourceId: String) = "source_" + sourceId.replace(java.io.File.separatorChar, '$')
    fun sourcePrefs(sourceId: String): android.content.SharedPreferences = getSharedPreferences(sourcePrefsName(sourceId), MODE_PRIVATE)
    val storage by lazy { LocalStorage(File(filesDir, "downloads")) }

    /** Same UA as the WebView: Cloudflare binds cf_clearance to it (Kotatsu: CommonHeadersInterceptor). */
    private val userAgent by lazy {
        runCatching { WebSettings.getDefaultUserAgent(this) }.getOrDefault(System.getProperty("http.agent").orEmpty())
    }

    private val web by lazy { WebViewFetcher(this) }
    private val cookieJar = WebViewCookieJar()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // hard cap: DNS/TLS stalls otherwise have no overall limit
            .cookieJar(cookieJar)
            .addInterceptor(SourceHeadersInterceptor { id -> sourceById[id] as? KotatsuSource })
            .addInterceptor(CloudflareInterceptor(web))
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(if (req.header("User-Agent") != null) req else req.newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
    }

    /** Every Kotatsu source except text novels, which this reader cannot show; broken ones stay listed but marked, like Kotatsu. */
    val sources: List<MangaParser> by lazy {
        val ctx = KotatsuContext(client, cookieJar, userAgent, domainPrefs, ::sourcePrefs, web)
        // kotatsu-parsers has no novel sources (0/1256); WebNovel is Folium's own text source.
        MangaParserSource.entries.filter { it.contentType != ContentType.NOVEL }.map { KotatsuSource(it, ctx, mirrorSwitcher) } +
            dev.mangareader.parser.novel.WebnovelSource(client, domainPrefs)
    }
    private val sourceById by lazy { sources.associateBy { it.id } }

    fun source(id: String): MangaParser = sourceById[id] ?: error("Unknown source: $id")
    fun sourceOrNull(id: String): MangaParser? = sourceById[id]

    /** "Tangani tautan": the source that owns [url] (by its known domains) and the title behind it. */
    suspend fun resolveLink(url: String): dev.mangareader.parser.Manga? {
        val link = url.toHttpUrlOrNull() ?: return null
        val host = link.host.removePrefix("www.")
        val source = sources.filterIsInstance<KotatsuSource>().firstOrNull { s ->
            (s.domainPresets + s.domain).any { d -> host == d.removePrefix("www.") || host.endsWith(".$d") }
        } ?: return null
        return source.resolveLink(link)
    }

    /** Sources shown in Explore and global search; chosen in the source catalogue (Kotatsu: enabled sources). */
    var enabledSourceIds: Set<String>
        get() = settings.getStringSet(KEY_ENABLED_SOURCES, null) ?: DEFAULT_SOURCES
        set(value) = settings.edit().putStringSet(KEY_ENABLED_SOURCES, value).apply()

    /**
     * Sources in Explore and global search (Settings → Sumber manga, Kotatsu pref_sources): the added ones, or all with
     * "Aktifkan semua sumber manga"; NSFW ones left out with "Nonaktifkan NSFW"; in the chosen order.
     */
    val enabledSources: List<MangaParser>
        get() {
            val ids = enabledSourceIds
            val all = settings.getBoolean(KEY_ENABLE_ALL, false)
            val noNsfw = settings.getBoolean(KEY_NO_NSFW, false)
            return sortSources(sources.filter { (all || it.id in ids) && !(noNsfw && it.isNsfw) })
        }

    fun sortSources(list: List<MangaParser>): List<MangaParser> = when (settings.getString(KEY_SORT, SORT_MANUAL)) {
        SORT_ALPHABETIC -> list.sortedBy { it.name.lowercase() }
        SORT_LAST_USED -> list.sortedWith(compareByDescending<MangaParser> { sourceUsage.getLong(it.id, 0L) }.thenBy { it.name.lowercase() })
        else -> {
            val index = sourceOrder.withIndex().associate { it.value to it.index }
            list.sortedWith(compareBy<MangaParser> { index[it.id] ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
        }
    }

    /** Manual order from "Kelola sumber"; sources not in it follow by name. */
    var sourceOrder: List<String>
        get() = settings.getString(KEY_ORDER, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        set(value) = settings.edit().putString(KEY_ORDER, value.joinToString(",")).apply()

    private val sourceUsage by lazy { getSharedPreferences("source_usage", MODE_PRIVATE) }

    /** For "Terakhir digunakan" sorting: browsing a source or reading from it. */
    fun markSourceUsed(id: String) = sourceUsage.edit().putLong(id, System.currentTimeMillis()).apply()

    val mirrorSwitcher by lazy { dev.mangareader.parser.MirrorSwitcher(settings, domainPrefs) }

    val scrobblers: List<Scrobbler> by lazy {
        val prefs = getSharedPreferences("scrobblers", MODE_PRIVATE)
        listOf(AniListScrobbler(client, prefs), MalScrobbler(client, prefs), KitsuScrobbler(client, prefs), ShikimoriScrobbler(client, prefs))
    }

    val repository by lazy { MangaRepository(db.dao(), ::source, ::scrobble) { getString(R.string.default_collection) } }

    override fun onCreate() {
        super.onCreate()
        // The designed stone/amber palette by default; wallpaper colours (Android 12+) only when the user opts in.
        if (settings.getBoolean("dynamic_color", false)) DynamicColors.applyToActivitiesIfAvailable(this)
        applyTheme(settings.getString("theme", "system"))
        createNotificationChannels(this)
        TrackerWorker.schedule(this, settings.getBoolean("tracker_enabled", true), settings.getString("tracker_interval", "6")!!.toLong())
    }

    /**
     * English is the default language: on first start (or after updating from a version without a language setting) the
     * app switches to English unless a language was already chosen, in the app or in Android's per-app settings.
     * Called from the first activity: on Android 13+ AppCompat can only reach the system LocaleManager through one.
     */
    fun applyDefaultLanguage() {
        if (settings.getBoolean(KEY_LANGUAGE_INIT, false)) return
        if (AppCompatDelegate.getApplicationLocales().isEmpty) setLanguage(LANGUAGE_DEFAULT)
        settings.edit().putBoolean(KEY_LANGUAGE_INIT, true).apply()
    }

    /** Settings → Language: "en", "id", or "system" (follow the device). */
    fun setLanguage(tag: String) = AppCompatDelegate.setApplicationLocales(
        if (tag == LANGUAGE_SYSTEM) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        else androidx.core.os.LocaleListCompat.forLanguageTags(tag),
    )

    /** Current choice for the Settings list ("in" is Java's old code for Indonesian). */
    fun currentLanguage(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return LANGUAGE_SYSTEM
        return when (locales[0]?.language) { "in", "id" -> "id"; else -> "en" }
    }

    fun applyTheme(value: String?) = AppCompatDelegate.setDefaultNightMode(
        when (value) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        },
    )

    /** Pushes reading progress to linked services when a new chapter is opened (Kotatsu: Scrobbler.scrobble). */
    private fun scrobble(manga: Manga, chapter: MangaChapter) {
        val number = chapterNumber(chapter.name) ?: return
        appScope.launch {
            for (link in db.dao().scrobblings(manga.id)) {
                val scrobbler = scrobblers.firstOrNull { it.id == link.service && it.isAuthorized } ?: continue
                if (number <= link.lastChapter) continue
                runCatching { scrobbler.updateProgress(link.targetId, number) }
                    .onSuccess { db.dao().upsertScrobbling(link.copy(lastChapter = number)) }
                    .onFailure { Log.w("Scrobble", "${scrobbler.name} failed", it) }
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            add(FaviconFetcher.Factory({ sourceById[it] as? KotatsuSource }, client, File(cacheDir, "favicons")))
            add(FaviconFetcher.IconKeyer)
        }.build()
}

val Context.app get() = applicationContext as App

private const val KEY_LANGUAGE_INIT = "language_initialized"
const val LANGUAGE_DEFAULT = "en"
const val LANGUAGE_SYSTEM = "system"

// Settings → Sumber manga (Kotatsu keys)
const val KEY_SORT = "sources_sort_order"
const val KEY_ORDER = "sources_order"
const val KEY_ENABLE_ALL = "sources_enabled_all"
const val KEY_NO_NSFW = "no_nsfw"
const val SORT_MANUAL = "MANUAL"
const val SORT_ALPHABETIC = "ALPHABETIC"
const val SORT_LAST_USED = "LAST_USED"
private const val KEY_ENABLED_SOURCES = "sources_enabled"
private val DEFAULT_SOURCES = setOf("KOMIKSTATION", "MANGAREAD", "MANGADEX")

/** Cookies live in the WebView's CookieManager so a challenge solved in the browser applies to OkHttp (Kotatsu: AndroidCookieJar). */
private class WebViewCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        CookieManager.getInstance().getCookie(url.toString())
            ?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }
            .orEmpty()
}
