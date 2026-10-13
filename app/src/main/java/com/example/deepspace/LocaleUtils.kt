package com.example.deepspace

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

object LocaleUtils {
    private const val PREFS_NAME = "display_settings"
    private const val KEY_LANGUAGE = "selected_language"

    fun setLocale(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()

        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        applyLanguageToResources(context, locale)
    }

    fun getCurrentLanguageCode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, null)
        if (saved != null) return saved
        // Default: follow device language; if Indonesian, use "in"; else "en"
        val deviceLang = context.resources.configuration.locales.get(0).language
        return if (deviceLang == "in" || deviceLang == "id") "in" else "en"
    }

    fun applyLocale(context: Context) {
        val langCode = getCurrentLanguageCode(context)
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        applyLanguageToResources(context, locale)
    }

    /**
     * Terapkan bahasa ke resources tanpa mengacak mode terang/gelap.
     *
     * Konfigurasi disalin dari resources pemanggil dan bisa membawa `uiMode` milik
     * perangkat (mis. night). Bila nilai itu dibiarkan, `values-night` bisa aktif
     * kembali lewat `updateConfiguration()` sehingga popup tampil gelap walau toggle
     * "Dark Mode" di Setting sedang nonaktif (light mode). Karena itu night bit
     * dipaksa mengikuti toggle Setting ([DeepspaceTheme]).
     */
    @Suppress("DEPRECATION")
    private fun applyLanguageToResources(context: Context, locale: Locale) {
        val resources = context.resources
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            DeepspaceTheme.nightModeFlag(DeepspaceTheme.isDarkMode(context))
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}

