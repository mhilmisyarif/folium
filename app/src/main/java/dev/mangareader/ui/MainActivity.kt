package dev.mangareader.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/** Folium shell (spec §4): Home · Library · Explore · Settings. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.nav.setOnItemSelectedListener { show(it.itemId); true }
        if (savedInstanceState == null) {
            binding.nav.selectedItemId = R.id.nav_home
            openFeedIfRequested(intent)
        }
        lifecycleScope.launch {
            app.db.dao().observeUnreadFeed().collect { n ->
                // New chapters live in Home's "Baru diperbarui" rail.
                if (n > 0) binding.nav.getOrCreateBadge(R.id.nav_home).number = n else binding.nav.removeBadge(R.id.nav_home)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !app.settings.getBoolean("asked_notifications", false)) {
            app.settings.edit().putBoolean("asked_notifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFeedIfRequested(intent)
    }

    /** New-chapter notifications land on Home with the full update list on top. */
    private fun openFeedIfRequested(intent: Intent) {
        if (intent.getStringExtra(EXTRA_TAB) != TAB_FEED) return
        select(R.id.nav_home)
        startActivity(Intent(this, FeedActivity::class.java))
    }

    fun select(itemId: Int) {
        binding.nav.selectedItemId = itemId
    }

    private fun show(itemId: Int) {
        val tag = "tab$itemId"
        if (supportFragmentManager.findFragmentByTag(tag)?.isVisible == true) return
        val fragment: Fragment = supportFragmentManager.findFragmentByTag(tag) ?: when (itemId) {
            R.id.nav_library -> LibraryFragment()
            R.id.nav_explore -> ExploreFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        // New tab fades/scales in (Material fade-through), skipped when the user disabled animations.
        // No exit transition: it snapshots the old tab into a software bitmap, which crashes on Coil's hardware bitmaps.
        if (animationsEnabled()) fragment.enterTransition = com.google.android.material.transition.MaterialFadeThrough()
        supportFragmentManager.beginTransaction().setReorderingAllowed(true).replace(R.id.container, fragment, tag).commit()
        updateTitle(itemId)
    }

    // Recreated (theme change, rotation): the fragment and tab come back, the title does not. The nav's selection
    // is restored only here, after onCreate.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        updateTitle(binding.nav.selectedItemId)
    }

    private fun updateTitle(itemId: Int) {
        binding.toolbar.title = getString(
            when (itemId) {
                R.id.nav_library -> R.string.library
                R.id.nav_explore -> R.string.explore
                R.id.nav_settings -> R.string.settings
                else -> R.string.app_name
            },
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> { startActivity(Intent(this, SearchActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_FEED = "feed"
    }
}
