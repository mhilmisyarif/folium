package dev.mangareader.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import dev.mangareader.KEY_NO_NSFW
import dev.mangareader.KEY_SORT
import dev.mangareader.R
import dev.mangareader.SORT_MANUAL
import dev.mangareader.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Settings → Sumber manga (Kotatsu pref_sources.xml). */
class SourcesSettingsFragment : PreferenceFragmentCompat() {
    private val app get() = requireContext().app

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_sources, rootKey)
        noIconIndent(preferenceScreen)
        click("remote_sources") { startActivity(android.content.Intent(requireContext(), ManageSourcesActivity::class.java)) }
        click("sources_catalog") { startActivity(SourcesActivity.intent(requireContext())) }
        findPreference<SwitchPreferenceCompat>("handle_links")?.setOnPreferenceChangeListener { _, v ->
            setLinksHandled(requireContext(), v == true)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = app.enabledSourceIds
        val noNsfw = app.settings.getBoolean(KEY_NO_NSFW, false)
        findPreference<Preference>("remote_sources")?.summary = getString(R.string.n_items, enabled.size)
        findPreference<Preference>("sources_catalog")?.summary =
            getString(R.string.available_n, app.sources.count { it.id !in enabled && !(noNsfw && it.isNsfw) })
        findPreference<SwitchPreferenceCompat>("handle_links")?.isChecked = linksHandled(requireContext())
    }

    private fun click(key: String, action: () -> Unit) =
        findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }

    companion object {
        private fun linkAlias(ctx: android.content.Context) = ComponentName(ctx, "dev.mangareader.ui.LinkHandler")

        /** "Tangani tautan": the VIEW filter lives on an activity alias that this switch turns on and off. */
        fun linksHandled(ctx: android.content.Context) =
            ctx.packageManager.getComponentEnabledSetting(linkAlias(ctx)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        fun setLinksHandled(ctx: android.content.Context, on: Boolean) = ctx.packageManager.setComponentEnabledSetting(
            linkAlias(ctx),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

/**
 * Kelola sumber (Kotatsu: SourcesManageFragment): the added sources in their Explore order. Drag to reorder (manual
 * sorting), the trailing button removes one, a tap opens its settings.
 */
class ManageSourcesActivity : AppCompatActivity() {
    private val adapter = SourceAdapter(
        onClick = { startActivity(SourceSettingsActivity.intent(this, it.id)) },
        action = R.drawable.ic_off to R.string.disable,
        onAction = { s ->
            app.enabledSourceIds = app.enabledSourceIds - s.id
            render()
        },
    )
    private lateinit var hint: TextView
    private var rows = mutableListOf<SourceRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            setTitle(R.string.manage_sources)
        }
        hint = TextView(this).apply {
            val pad = resources.getDimensionPixelSize(R.dimen.space_4)
            setPadding(pad, 0, pad, pad / 2)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ManageSourcesActivity.adapter
        }
        root.addView(toolbar)
        root.addView(hint)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = manual()
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                rows.add(b, rows.removeAt(a))
                adapter.submitList(rows.toList())
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                app.sourceOrder = rows.map { it.source.id }
            }
        }).attachToRecyclerView(list)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun manual() = app.settings.getString(KEY_SORT, SORT_MANUAL) == SORT_MANUAL

    private fun render() {
        val ids = app.enabledSourceIds
        rows = app.sortSources(app.sources.filter { it.id in ids }).map { SourceRow(it) }.toMutableList()
        adapter.submitList(rows.toList())
        hint.setText(if (manual()) R.string.manage_sources_hint_manual else R.string.manage_sources_hint_sorted)
    }
}

/** Opens a web link to a known source as a title in Folium (Kotatsu: handle links); else falls back to the browser. */
class LinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.dataString ?: return finish()
        val progress = androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.opening_link)
            .setOnCancelListener { finish() }
            .show()
        lifecycleScope.launch {
            val manga = try {
                app.resolveLink(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorMessage(e))
                null
            }
            progress.dismiss()
            if (manga != null) {
                startActivity(DetailsActivity.intent(this@LinkActivity, manga))
            } else {
                toast(getString(R.string.link_not_supported))
                startActivity(BrowserActivity.intent(this@LinkActivity, url))
            }
            finish()
        }
    }
}

/** Kotatsu "Tandai genre berbahaya": tags that may be inappropriate for most readers. */
private val WARNING_TAGS = setOf(
    "hentai", "smut", "adult", "mature", "ecchi", "gore", "guro", "rape", "sexual violence", "incest",
    "loli", "lolicon", "shota", "shotacon", "dewasa", "18+",
)

fun isWarningTag(tag: String) = tag.trim().lowercase() in WARNING_TAGS
