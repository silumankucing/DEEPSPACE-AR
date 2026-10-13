package com.example.deepspace

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View

class Home_Activity : BaseFullscreenActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mode terang/gelap mengikuti toggle "Dark Mode" di halaman Setting.
        // Diterapkan sebelum super.onCreate() supaya theme activity langsung benar.
        DeepspaceTheme.applySavedMode(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Deteksi dark mode dari toggle Setting (bukan mode gelap perangkat)
        val isDarkModeUi = DeepspaceTheme.isDarkMode(this)

        // Dark mode: background scroll digelapkan, header jadi hitam
        if (isDarkModeUi) {
            val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollViewHome)
            scrollView?.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))

            // Header: background tetap biru, text, logo & hamburger hitam
            findViewById<android.widget.TextView>(R.id.txtAppName)?.setTextColor(android.graphics.Color.BLACK)
            findViewById<android.widget.ImageView>(R.id.imgLogo)?.setColorFilter(android.graphics.Color.BLACK)
            findViewById<android.widget.ImageView>(R.id.btnHamburgerMenu)?.setColorFilter(android.graphics.Color.BLACK)
        }

        // Tombol tetap biru, tapi text & icon hitam di dark mode
        val textColor = if (isDarkModeUi) android.graphics.Color.BLACK else android.graphics.Color.WHITE

        // Daftar tombol utama dengan effect animasi klik
        val btns = listOf(
            R.id.btnAR,
            R.id.btnLibrary,
            R.id.btnProject,
        )
        btns.forEach { id ->
            findViewById<View>(id).apply {
                // Tombol tetap biru (dari XML), text & icon hitam di dark mode
                val iconId = when (id) {
                    R.id.btnAR -> R.id.iconAR
                    R.id.btnLibrary -> R.id.iconLibrary
                    R.id.btnProject -> R.id.iconProject
                    else -> null
                }
                if (iconId != null) {
                    findViewById<android.widget.ImageView>(iconId)?.setColorFilter(textColor)
                }
                val textViews = mutableListOf<android.widget.TextView>()
                getTextViews(this, textViews)
                textViews.forEach { it.setTextColor(textColor) }
                addClickEffect()
                setOnClickListener {
                    startActivity(when (id) {
                        R.id.btnAR -> Intent(this@Home_Activity, AR_Activity::class.java)
                        R.id.btnLibrary -> Intent(this@Home_Activity, Explore_Activity::class.java)
                        R.id.btnProject -> Intent(this@Home_Activity, Project_Activity::class.java)
                        else -> return@setOnClickListener
                    })
                }
            }
        }

        // Tombol hamburger menu (Setting) dengan effect animasi
        findViewById<android.widget.ImageView>(R.id.btnHamburgerMenu).apply {
            addClickEffect()
            setOnClickListener {
                startActivity(Intent(this@Home_Activity, Settings_Activity::class.java))
            }
        }
    }

    // Helper untuk mengumpulkan semua TextView dalam ViewGroup
    private fun getTextViews(view: android.view.View, result: MutableList<android.widget.TextView>) {
        if (view is android.widget.TextView) {
            result.add(view)
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                getTextViews(view.getChildAt(i), result)
            }
        }
    }

    private fun View.addClickEffect() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    false
                }
                else -> false
            }
        }
    }
}

