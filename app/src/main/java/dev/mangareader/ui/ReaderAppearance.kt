package dev.mangareader.ui

import android.app.Activity
import android.view.View
import android.view.WindowManager
import dev.mangareader.R
import dev.mangareader.app
import dev.mangareader.databinding.PartReaderAppearanceBinding

/**
 * Comics and text (novels, EPUB, PDF) keep separate reader appearance settings (Settings → Pembaca komik /
 * Pembaca novel & buku). Comic keys are the original ones, so existing choices are kept.
 */
enum class ReaderProfile(val keyBackground: String, val keyBrightness: String, val keyKeepOn: String, val defaultBackground: String) {
    COMIC("reader_background", "reader_brightness", "keep_screen_on", ReaderAppearance.BG_BLACK),
    TEXT("text_reader_background", "text_reader_brightness", "text_keep_screen_on", ReaderAppearance.BG_PAPER),
}

/**
 * Reader appearance (spec §3.1: independent of the app theme): page background, brightness and keep-screen-on.
 */
object ReaderAppearance {
    const val BG_BLACK = "BLACK"
    const val BG_GRAY = "GRAY"
    const val BG_PAPER = "PAPER"
    const val BG_WHITE = "WHITE"

    fun background(activity: Activity, p: ReaderProfile): String =
        activity.app.settings.getString(p.keyBackground, p.defaultBackground) ?: p.defaultBackground

    fun isLight(activity: Activity, p: ReaderProfile) = background(activity, p).let { it == BG_PAPER || it == BG_WHITE }

    fun backgroundColor(activity: Activity, p: ReaderProfile): Int = activity.getColor(when (background(activity, p)) {
        BG_GRAY -> R.color.reader_bg_gray
        BG_PAPER -> R.color.warm_cream
        BG_WHITE -> R.color.reader_bg_white
        else -> R.color.reader_bg_black
    })

    /** Text colour for reflowable text on the current background (≥ 7:1 on all four). */
    fun textColor(activity: Activity, p: ReaderProfile): Int =
        activity.getColor(if (isLight(activity, p)) R.color.ink_black else R.color.warm_cream)

    fun apply(activity: Activity, root: View, p: ReaderProfile) {
        val prefs = activity.app.settings
        root.setBackgroundColor(backgroundColor(activity, p))
        val brightness = prefs.getFloat(p.keyBrightness, -1f)
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
        }
        if (prefs.getBoolean(p.keyKeepOn, true)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Wires the shared in-reader controls; [onBackgroundChanged] lets a reader recolour what it draws itself. */
    fun bind(activity: Activity, root: View, b: PartReaderAppearanceBinding, p: ReaderProfile, onBackgroundChanged: () -> Unit) {
        val prefs = activity.app.settings
        val bgIds = mapOf(BG_BLACK to R.id.bg_black, BG_GRAY to R.id.bg_gray, BG_PAPER to R.id.bg_paper, BG_WHITE to R.id.bg_white)
        b.background.check(bgIds.getValue(background(activity, p)))
        b.background.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.edit().putString(p.keyBackground, bgIds.entries.first { it.value == id }.key).apply()
            apply(activity, root, p)
            onBackgroundChanged()
        }
        val brightness = prefs.getFloat(p.keyBrightness, -1f)
        b.brightnessAuto.isChecked = brightness < 0
        b.brightness.isEnabled = brightness >= 0
        b.brightness.value = if (brightness < 0) 60f else (brightness * 100).coerceIn(5f, 100f)
        b.brightnessAuto.setOnCheckedChangeListener { _, auto ->
            b.brightness.isEnabled = !auto
            prefs.edit().putFloat(p.keyBrightness, if (auto) -1f else b.brightness.value / 100f).apply()
            apply(activity, root, p)
        }
        b.brightness.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.edit().putFloat(p.keyBrightness, value / 100f).apply()
            apply(activity, root, p)
        }
        b.keepOn.isChecked = prefs.getBoolean(p.keyKeepOn, true)
        b.keepOn.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(p.keyKeepOn, on).apply(); apply(activity, root, p) }
    }
}
