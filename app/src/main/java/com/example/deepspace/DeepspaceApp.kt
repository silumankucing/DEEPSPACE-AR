package com.example.deepspace

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class DeepspaceApp : Application() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        // Mode terang/gelap hanya mengikuti toggle di halaman Setting, bukan mode sistem.
        // Diterapkan paling awal supaya activity pertama pun memakai theme yang benar.
        DeepspaceTheme.applySavedMode(this)
        // Apply saved locale before anything else
        LocaleUtils.applyLocale(this)

        prefs = getSharedPreferences("display_settings", MODE_PRIVATE)

        // Inisialisasi folder DEEPSPACE
        initDirectories()

        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Apply locale to each activity
                LocaleUtils.applyLocale(activity)
                if (prefs.getBoolean("fullscreen", true)) {
                    DeepspaceApp.hideBars(activity)
                } else {
                    DeepspaceApp.showBars(activity)
                }
                    }

            override fun onActivityStarted(activity: Activity) {
                if (prefs.getBoolean("fullscreen", true)) {
                    DeepspaceApp.hideBars(activity)
                } else {
                    DeepspaceApp.showBars(activity)
                }
                    }

            override fun onActivityResumed(activity: Activity) {
                if (prefs.getBoolean("fullscreen", true)) {
                    DeepspaceApp.hideBars(activity)
                    // Sistem bisa memunculkan kembali bars setelah transisi,
                    // jadwalkan ulang untuk memastikan bars tetap tersembunyi
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!activity.isFinishing) {
                            DeepspaceApp.hideBars(activity)
                        }
                    }, 300)
                } else {
                    DeepspaceApp.showBars(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun initDirectories() {
        try {
            DeepspaceDirs.getArDir(this)
            DeepspaceDirs.getExportDir(this)
            DeepspaceDirs.getImportDir(this)
            DeepspaceDirs.getCaptureDir(this)
            // General menampung aset lintas halaman (sticker & marker) sebagai sub-folder;
            // Explore adalah folder pustaka.
            DeepspaceDirs.getGeneralDir(this)
            DeepspaceDirs.getExploreDir(this)
            DeepspaceDirs.getMarkerDir(this)
            DeepspaceDirs.getStickerDir(this)
            // Struktur lama menaruh Sticker/Markers langsung di root DEEPSPACE; pindahkan
            // isinya ke General supaya aset yang sudah ada tidak hilang.
            DeepspaceDirs.migrateLegacyInto(
                this, listOf("Sticker"), DeepspaceDirs.getStickerDir(this)
            )
            DeepspaceDirs.migrateLegacyInto(
                this, listOf("Markers", "Marker"), DeepspaceDirs.getMarkerDir(this)
            )
        } catch (e: Exception) {
            android.util.Log.e("DeepspaceApp", "initDirectories error: ${e.message}")
        }
    }

    companion object {
        fun hideBars(activity: Activity) {
            try {
                val window = activity.window

                // STEP 1: Extend content ke edges (seamless fullscreen)
                WindowCompat.setDecorFitsSystemWindows(window, false)

                // STEP 1b: Izinkan konten menggambar ke area notch/cutout.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                }

                // STEP 2: Set TRANSPARENT colors
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                // setStatusBarContrastEnforced / setNavigationBarContrastEnforced
                // baru ada sejak API 29 (Q) — guard agar aman di minSdk 26.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }

                // STEP 3: Sembunyikan system bars
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())

            } catch (e: Exception) {
                android.util.Log.e("DeepspaceApp", "hideBars error: ${e.message}", e)
            }
        }

        fun showBars(activity: Activity) {
            try {
                val window = activity.window

                // STEP 1: Biarkan sistem mengelola inset (tidak extend ke system bars)
                WindowCompat.setDecorFitsSystemWindows(window, true)

                // STEP 2: Kembalikan warna default system bars
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                // Lihat catatan di hideBars(): kedua API ini butuh API 29+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = true
                    window.isNavigationBarContrastEnforced = true
                }

                // STEP 3: Tampilkan system bars
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())

            } catch (e: Exception) {
                android.util.Log.e("DeepspaceApp", "showBars error: ${e.message}", e)
            }
        }
    }
}


