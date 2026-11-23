package dev.mangareader.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.webkit.CookieManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.parser.KotatsuSource
import dev.mangareader.parser.MangaParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey

/** Per-source settings (Kotatsu: SourceSettingsFragment + pref_source.xml / pref_source_parser.xml). */
class SourceSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = app.sourceOrNull(intent.getStringExtra(EXTRA_SOURCE).orEmpty()) ?: return finish()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            title = source.name
            setSubtitle(R.string.source_settings)
        }
        val container = android.widget.FrameLayout(this).apply { id = R.id.container }
        root.addView(toolbar)
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SourceSettingsFragment().apply { arguments = Bundle().apply { putString(EXTRA_SOURCE, source.id) } })
                .commit()
        }
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        fun intent(ctx: Context, sourceId: String) = Intent(ctx, SourceSettingsActivity::class.java).putExtra(EXTRA_SOURCE, sourceId)
    }
}

class SourceSettingsFragment : PreferenceFragmentCompat() {
    private val source: MangaParser by lazy { requireContext().app.source(requireArguments().getString(SourceSettingsActivity.EXTRA_SOURCE)!!) }
    private val kotatsu get() = source as? KotatsuSource
    private val auth get() = kotatsu?.parser as? MangaParserAuthProvider

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val app = requireContext().app
        // Parser options live in one file per source, as in Kotatsu; the domain stays in "domains" (part of backups).
        preferenceManager.sharedPreferencesName = app.sourcePrefsName(source.id)
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        val ctx = requireContext()

        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = "enable"
            isPersistent = false
            setTitle(R.string.enable_source)
            isChecked = source.id in app.enabledSourceIds
            setOnPreferenceChangeListener { _, v ->
                app.enabledSourceIds = if (v == true) app.enabledSourceIds + source.id else app.enabledSourceIds - source.id
                true
            }
        })
        screen.addPreference(Preference(ctx).apply {
            key = "domain"
            isPersistent = false
            setTitle(R.string.domain)
            summary = source.domain
            setOnPreferenceClickListener {
                requireActivity().showDomainDialog(source) { summary = source.domain }
                true
            }
        })
        kotatsu?.configKeys.orEmpty().forEach { key ->
            val p: Preference = when (key) {
                is ConfigKey.Domain -> return@forEach // above, shared with the catalogue
                is ConfigKey.UserAgent -> EditTextPreference(ctx).apply {
                    setTitle(R.string.user_agent)
                    setDialogTitle(R.string.user_agent)
                    dialogMessage = getString(R.string.user_agent_hint)
                    setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT; it.hint = key.defaultValue }
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { it.text?.takeIf(String::isNotBlank) ?: key.defaultValue }
                    // Blank = the source default; a header value cannot span lines.
                    setOnPreferenceChangeListener { _, v -> (v as String).let { it.isBlank() || it.none { c -> c == '\n' || c == '\r' } } }
                }
                is ConfigKey.ShowSuspiciousContent -> SwitchPreferenceCompat(ctx).apply {
                    setDefaultValue(key.defaultValue)
                    setTitle(R.string.show_suspicious_content)
                }
                is ConfigKey.SplitByTranslations -> SwitchPreferenceCompat(ctx).apply {
                    setDefaultValue(key.defaultValue)
                    setTitle(R.string.split_by_translations)
                    setSummary(R.string.split_by_translations_summary)
                }
                is ConfigKey.PreferredImageServer -> ListPreference(ctx).apply {
                    entries = key.presetValues.values.map { it ?: getString(R.string.automatic) }.toTypedArray()
                    entryValues = key.presetValues.keys.map { it.orEmpty() }.toTypedArray()
                    setDefaultValue(key.defaultValue.orEmpty())
                    setTitle(R.string.image_server)
                    setDialogTitle(R.string.image_server)
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }
            }
            p.key = key.key
            screen.addPreference(p)
        }
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = KEY_SLOWDOWN
            setDefaultValue(false)
            setTitle(R.string.download_slowdown)
            setSummary(R.string.download_slowdown_summary)
        })
        if (auth != null) screen.addPreference(Preference(ctx).apply {
            key = "auth"
            isPersistent = false
            setTitle(R.string.sign_in)
            setOnPreferenceClickListener { startActivity(BrowserActivity.intent(ctx, auth!!.authUrl)); true }
        })
        screen.addPreference(Preference(ctx).apply {
            key = "cookies_clear"
            isPersistent = false
            setTitle(R.string.clear_cookies)
            setSummary(R.string.clear_source_cookies_summary)
            setOnPreferenceClickListener { clearCookies(); true }
        })
        screen.addPreference(Preference(ctx).apply {
            key = "open_browser"
            isPersistent = false
            setIcon(R.drawable.ic_web)
            setTitle(R.string.open_in_browser)
            setOnPreferenceClickListener { startActivity(BrowserActivity.intent(ctx, siteUrl())); true }
        })
        noIconIndent(screen)
    }

    override fun onResume() {
        super.onResume()
        findPreference<Preference>("open_browser")?.summary = siteUrl()
        refreshAuth()
    }

    private fun siteUrl() = "https://${source.domain}/"

    /** Kotatsu: "Masuk" stays enabled until the site reports a session, then shows the account name. */
    private fun refreshAuth() {
        val provider = auth ?: return
        val pref = findPreference<Preference>("auth") ?: return
        lifecycleScope.launch {
            pref.isEnabled = false
            try {
                if (provider.isAuthorized()) {
                    pref.summary = getString(R.string.logged_in_as, runCatching { provider.getUsername() }.getOrDefault("—"))
                } else {
                    pref.summary = null
                    pref.isEnabled = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pref.summary = requireContext().errorMessage(e)
                pref.isEnabled = true
            }
        }
    }

    /** Expires every cookie the site set (WebView store, shared with OkHttp), e.g. to drop a stale Cloudflare pass or log out. */
    private fun clearCookies() {
        val cm = CookieManager.getInstance()
        val url = siteUrl()
        val host = source.domain
        cm.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
            cm.setCookie(url, "$name=; Max-Age=0")
            cm.setCookie(url, "$name=; Max-Age=0; Domain=$host; Path=/")
            cm.setCookie(url, "$name=; Max-Age=0; Domain=.$host; Path=/")
        }
        cm.flush()
        requireContext().toast(getString(R.string.cookies_cleared))
        refreshAuth()
    }

    companion object {
        /** Kotatsu SourceSettings.KEY_SLOWDOWN: pause between downloaded pages for sites that ban fast clients. */
        const val KEY_SLOWDOWN = "slowdown"
    }
}
