package com.example.deepspace

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/**
 * Sumber kebenaran tunggal untuk mode terang/gelap aplikasi.
 *
 * Mode aplikasi HANYA ditentukan oleh toggle "Dark Mode" di halaman Setting
 * (SharedPreferences "display_settings" -> "dark_mode"), BUKAN oleh mode gelap
 * perangkat. Sebelumnya warna popup dibaca dari dua sumber berbeda
 * (uiMode/`values-night` dan `AppCompatDelegate`), sehingga popup bisa tampil
 * gelap saat light mode sedang aktif. Semua helper di sini memakai preferensi
 * tersebut supaya popup/dialog selalu sesuai toggle Setting.
 */
object DeepspaceTheme {

    const val PREFS_NAME = "display_settings"
    const val KEY_DARK_MODE = "dark_mode"

    /** True bila toggle "Dark Mode" di halaman Setting aktif. */
    fun isDarkMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)

    /** Simpan pilihan mode dari halaman Setting. */
    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, enabled)
            .apply()
    }

    /** Terapkan mode tersimpan ke AppCompat (panggil SEBELUM `super.onCreate()`). */
    fun applySavedMode(context: Context) {
        setNightMode(isDarkMode(context))
    }

    fun setNightMode(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun nightModeFlag(dark: Boolean): Int =
        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO

    /**
     * Paksa konfigurasi resources mengikuti toggle Setting.
     *
     * Folder resources berkualifikasi night (mis. `values-night`, `drawable-night`)
     * hanya aktif bila `uiMode` pada konfigurasi resources bernilai night. Bila mode
     * perangkat berbeda dengan toggle, konfigurasi bisa tertinggal (mis. popup memakai
     * drawable night padahal light mode aktif), jadi setelah theme diterapkan kita
     * samakan night bit-nya dengan [dark].
     */
    fun syncResourcesNightMode(context: Context, dark: Boolean = isDarkMode(context)) {
        val resources = context.resources
        val config = Configuration(resources.configuration)
        val target = nightModeFlag(dark)
        if ((config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == target) return
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or target
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    // ===== Warna popup/dialog (selalu mengikuti toggle Setting) =====

    /** Latar permukaan popup/dialog: putih saat light mode, abu-abu gelap saat dark mode. */
    fun surfaceColor(context: Context): Int =
        if (isDarkMode(context)) ContextCompat.getColor(context, R.color.dark_surface)
        else ContextCompat.getColor(context, R.color.white)

    /** Latar halaman/aplikasi: putih saat light mode, gelap saat dark mode. */
    fun backgroundColor(context: Context): Int =
        if (isDarkMode(context)) ContextCompat.getColor(context, R.color.dark_background)
        else ContextCompat.getColor(context, R.color.white)

    fun surfaceVariantColor(context: Context): Int =
        if (isDarkMode(context)) ContextCompat.getColor(context, R.color.dark_surface_variant)
        else ContextCompat.getColor(context, R.color.freeform_background)

    fun textPrimaryColor(context: Context): Int =
        if (isDarkMode(context)) ContextCompat.getColor(context, R.color.dark_on_surface)
        else ContextCompat.getColor(context, R.color.black)

    fun textSecondaryColor(context: Context): Int =
        if (isDarkMode(context)) ContextCompat.getColor(context, R.color.dark_text_secondary)
        else ContextCompat.getColor(context, R.color.grey)

    /** Latar popup membulat yang mengikuti toggle (pengganti drawable berkualifikasi night). */
    fun popupBackground(context: Context, cornerRadiusDp: Float = 8f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * context.resources.displayMetrics.density
            setColor(surfaceColor(context))
        }
}
