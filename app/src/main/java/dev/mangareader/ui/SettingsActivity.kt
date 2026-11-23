package dev.mangareader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import coil3.SingletonImageLoader
import com.google.android.material.appbar.MaterialToolbar
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.data.exportBackup
import dev.mangareader.data.parseBackup
import dev.mangareader.data.restoreBackup
import dev.mangareader.data.toJson
import dev.mangareader.scrobbling.Scrobbler
import dev.mangareader.work.TrackerWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full list of new chapters ("Lihat semua" on Home, new-chapter notifications). */
class FeedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        val container = android.widget.FrameLayout(this).apply { id = R.id.container }
        root.addView(toolbar)
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        setSupportActionBar(toolbar) // FeedFragment contributes its menu (check now, clear)
        setTitle(R.string.recently_updated)
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.container, FeedFragment()).commit()
    }
}

/** Settings sub-screens: Sumber manga, Pembaca komik, Pembaca novel & buku. */
class SettingsScreenActivity : AppCompatActivity() {
    enum class Screen(val title: Int) { SOURCES(R.string.manga_sources), COMIC(R.string.reader_comic_settings), TEXT(R.string.reader_text_settings) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = runCatching { Screen.valueOf(intent.getStringExtra(EXTRA_SCREEN)!!) }.getOrDefault(Screen.COMIC)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            setTitle(screen.title)
        }
        val container = android.widget.FrameLayout(this).apply { id = R.id.container }
        root.addView(toolbar)
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null) {
            val fragment = when (screen) {
                Screen.SOURCES -> SourcesSettingsFragment()
                Screen.COMIC -> ReaderSettingsFragment.of(comic = true)
                Screen.TEXT -> ReaderSettingsFragment.of(comic = false)
            }
            supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
        }
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"
        fun intent(ctx: android.content.Context, screen: Screen) = Intent(ctx, SettingsScreenActivity::class.java).putExtra(EXTRA_SCREEN, screen.name)
    }
}

class ReaderSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val comic = requireArguments().getBoolean(ARG_COMIC)
        setPreferencesFromResource(if (comic) R.xml.pref_reader_comic else R.xml.pref_reader_text, rootKey)
        noIconIndent(preferenceScreen)
        // Line spacing is stored in tenths; show the multiplier the reader uses.
        findPreference<androidx.preference.SeekBarPreference>("epub_line_spacing")?.let { p ->
            fun show(v: Int) { p.summary = String.format(java.util.Locale.getDefault(), "%.1f×", v / 10f) }
            show(p.value)
            p.setOnPreferenceChangeListener { _, v -> show(v as Int); true }
        }
    }

    companion object {
        private const val ARG_COMIC = "comic"
        fun of(comic: Boolean) = ReaderSettingsFragment().apply { arguments = Bundle().apply { putBoolean(ARG_COMIC, comic) } }
    }
}

/** Flush-left rows: only entries that actually have an icon keep the icon column. */
internal fun noIconIndent(group: androidx.preference.PreferenceGroup) {
    for (i in 0 until group.preferenceCount) {
        val p = group.getPreference(i)
        if (p.icon == null) p.isIconSpaceReserved = false
        if (p is androidx.preference.PreferenceGroup) noIconIndent(p)
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    private val app get() = requireContext().app

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { runIo(R.string.backup_done) { writeBackup(it) } }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runIo(R.string.restore_done) { readBackup(it) } }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        noIconIndent(preferenceScreen)
        findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, v -> app.applyTheme(v as String); true }
        findPreference<SwitchPreferenceCompat>("tracker_enabled")?.setOnPreferenceChangeListener { _, v ->
            TrackerWorker.schedule(requireContext(), v as Boolean, app.settings.getString("tracker_interval", "6")!!.toLong()); true
        }
        findPreference<ListPreference>("tracker_interval")?.setOnPreferenceChangeListener { _, v ->
            TrackerWorker.schedule(requireContext(), app.settings.getBoolean("tracker_enabled", true), (v as String).toLong()); true
        }
        click("reader_comic_settings") { startActivity(SettingsScreenActivity.intent(requireContext(), SettingsScreenActivity.Screen.COMIC)) }
        click("reader_text_settings") { startActivity(SettingsScreenActivity.intent(requireContext(), SettingsScreenActivity.Screen.TEXT)) }
        click("sources") { startActivity(SettingsScreenActivity.intent(requireContext(), SettingsScreenActivity.Screen.SOURCES)) }
        click("tracking") { startActivity(Intent(requireContext(), ScrobblersActivity::class.java)) }
        click("backup") { createBackup.launch("folium-backup.json") }
        click("restore") { openBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
        click("clear_cache") {
            SingletonImageLoader.get(requireContext()).run { memoryCache?.clear(); diskCache?.clear() }
            requireContext().toast(getString(R.string.cleared))
        }
        click("clear_downloads") { confirm { clearDownloads() } }
        click("clear_stats") { confirm { runIo(R.string.cleared) { app.db.dao().clearStats() } } }
        val version = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName.orEmpty()
        findPreference<Preference>("about")?.summary = getString(R.string.about_summary, version)
        click("about") {
            AlertDialog.Builder(requireContext()).setIcon(R.drawable.ic_folium_logo).setTitle(R.string.app_name)
                .setMessage(getString(R.string.tagline) + "\n\n" + getString(R.string.about_text))
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun click(key: String, action: () -> Unit) =
        findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }

    private fun confirm(action: () -> Unit) {
        AlertDialog.Builder(requireContext()).setMessage("Lanjutkan? Tindakan ini tidak bisa dibatalkan.")
            .setPositiveButton(R.string.delete) { _, _ -> action() }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun clearDownloads() = runIo(R.string.cleared) {
        app.db.dao().clearAllDownloads()
        java.io.File(requireContext().filesDir, "downloads").deleteRecursively()
    }

    private fun runIo(doneMsg: Int, block: suspend () -> Unit) = lifecycleScope.launch {
        try {
            withContext(Dispatchers.IO) { block() }
            requireContext().toast(getString(doneMsg))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            requireContext().toast(e.message ?: e.toString())
        }
    }

    private suspend fun writeBackup(uri: Uri) {
        val domains = app.domainPrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        val json = app.db.dao().exportBackup(domains).toJson()
        requireNotNull(requireContext().contentResolver.openOutputStream(uri)).use { it.write(json.toByteArray()) }
    }

    private suspend fun readBackup(uri: Uri) {
        val json = requireNotNull(requireContext().contentResolver.openInputStream(uri)).use { it.readBytes().decodeToString() }
        val data = parseBackup(json)
        app.db.dao().restoreBackup(data)
        val edit = app.domainPrefs.edit()
        data.domains.forEach { (k, v) -> dev.mangareader.parser.normalizeDomain(v)?.let { edit.putString(k, it) } }
        edit.apply()
    }
}

/** Accounts for tracking services (Kotatsu: settings → Tracking). */
class ScrobblersActivity : ListActivity() {
    private val adapter = TextAdapter(onClick = { item -> manage(app.scrobblers.first { it.id == item.id }) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setTitle(R.string.tracking)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.submitList(app.scrobblers.map {
            val status = when {
                it.isAuthorized -> getString(R.string.logged_in)
                !it.isConfigured -> getString(R.string.not_configured)
                else -> getString(R.string.not_logged_in)
            }
            TextItem(it.id, it.name, status, if (it.isAuthorized) R.drawable.ic_check else null)
        })
    }

    private fun manage(s: Scrobbler) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        if (!s.usesPasswordLogin) {
            form.addView(TextView(this).apply { text = getString(R.string.redirect_uri_hint, s.redirectUri); setTextIsSelectable(true) })
        }
        val inputs = s.credentialFields.map { (key, label) ->
            EditText(this).apply { hint = label; setText(s.credential(key)); setSingleLine() }.also { form.addView(it) }
        }
        AlertDialog.Builder(this).setTitle(s.name).setView(form)
            .setPositiveButton(R.string.login) { _, _ ->
                s.credentialFields.forEachIndexed { i, (key, _) -> s.setCredential(key, inputs[i].text.toString()) }
                if (!s.isConfigured) return@setPositiveButton toast(getString(R.string.not_configured))
                if (s.usesPasswordLogin) passwordLogin(s)
                else s.authUrl()?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            }
            .setNeutralButton(R.string.logout) { _, _ -> s.logout(); onResume() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun passwordLogin(s: Scrobbler) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val user = EditText(this).apply { setHint(R.string.username); setSingleLine() }.also { form.addView(it) }
        val pass = EditText(this).apply {
            setHint(R.string.password); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }.also { form.addView(it) }
        AlertDialog.Builder(this).setTitle(s.name).setView(form)
            .setPositiveButton(R.string.login) { _, _ ->
                lifecycleScope.launch {
                    try {
                        s.loginWithPassword(user.text.toString().trim(), pass.text.toString())
                        toast(getString(R.string.logged_in))
                        onResume()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(e.message ?: e.toString())
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}

/** Receives mangareader://oauth/<service>… redirects from the browser. */
class OAuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val scrobbler = app.scrobblers.firstOrNull { it.id == uri?.pathSegments?.firstOrNull() }
        if (uri == null || scrobbler == null) return finish()
        lifecycleScope.launch {
            try {
                scrobbler.handleRedirect(uri)
                toast(getString(R.string.logged_in) + ": " + scrobbler.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: e.toString())
            }
            startActivity(Intent(this@OAuthActivity, ScrobblersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }
}
