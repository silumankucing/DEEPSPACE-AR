package com.example.deepspace

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

open class BaseFullscreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved locale
        LocaleUtils.applyLocale(this)
        // Apply dark mode preference before any UI is created
        applyDarkModePreference()
        super.onCreate(savedInstanceState)
        // Setelah theme diterapkan AppCompat, samakan konfigurasi resources dengan toggle
        // Setting supaya drawable berkualifikasi `values-night` (mis. latar popup) tidak
        // tertinggal mengikuti mode gelap perangkat.
        DeepspaceTheme.syncResourcesNightMode(this)
        applyFullscreen()
    }

    override fun onResume() {
        super.onResume()
        applyFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullscreen()
        }
    }

    /**
     * Mode terang/gelap mengikuti toggle "Dark Mode" di halaman Setting
     * (lihat [DeepspaceTheme]), bukan mode gelap perangkat.
     */
    private fun applyDarkModePreference() {
        DeepspaceTheme.applySavedMode(this)
    }

    private fun applyFullscreen() {
        val prefs = getSharedPreferences("display_settings", MODE_PRIVATE)
        if (prefs.getBoolean("fullscreen", true)) {
            DeepspaceApp.hideBars(this)
        } else {
            DeepspaceApp.showBars(this)
        }
    }

    /**
     * Halaman turunan dapat meng-override fungsi ini, mis. halaman yang selalu memakai
     * light mode (default) tanpa mengikuti pengaturan dark mode aplikasi.
     */
    protected open fun isDarkThemeEnabled(): Boolean = DeepspaceTheme.isDarkMode(this)

    protected fun themeSurfaceColor(): Int = DeepspaceTheme.surfaceColor(this)

    protected fun themeSurfaceVariantColor(): Int = DeepspaceTheme.surfaceVariantColor(this)

    protected fun themeTextPrimaryColor(): Int = DeepspaceTheme.textPrimaryColor(this)

    protected fun themeTextSecondaryColor(): Int = DeepspaceTheme.textSecondaryColor(this)

    /**
     * Ikon menu Deepspace adalah line-art gelap, jadi pada dark mode ikon di-tint putih
     * supaya tetap terbaca di atas permukaan abu-abu gelap. Filter selalu di-reset pada
     * light mode karena view popup di-recycle lewat convertView.
     */
    protected fun applyMenuIconTint(icon: android.widget.ImageView?) {
        if (icon == null) return
        if (isDarkThemeEnabled()) {
            icon.setColorFilter(ContextCompat.getColor(this, R.color.dark_on_surface))
        } else {
            icon.clearColorFilter()
        }
    }

    /**
     * Warna teks untuk baris outliner/feature tree. Depth makin dalam makin redup,
     * dan arah gradasinya dibalik pada dark mode supaya tetap kontras terhadap latar gelap.
     */
    protected fun themeTreeTextColor(depth: Int): Int {
        return if (isDarkThemeEnabled()) {
            when (depth) {
                1 -> Color.parseColor("#EDEDED")
                2 -> Color.parseColor("#D0D0D0")
                else -> Color.parseColor("#A0A0A0")
            }
        } else {
            when (depth) {
                1 -> Color.parseColor("#555555")
                2 -> Color.parseColor("#666666")
                else -> Color.parseColor("#888888")
            }
        }
    }

    protected fun themeMutedSurfaceColor(): Int {
        return if (isDarkThemeEnabled()) {
            ContextCompat.getColor(this, R.color.dark_surface_variant)
        } else {
            Color.LTGRAY
        }
    }

}
