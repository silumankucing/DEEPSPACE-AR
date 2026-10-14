package com.example.deepspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class Settings_Activity : BaseFullscreenActivity() {

        override fun onCreate(savedInstanceState: Bundle?) {
        LocaleUtils.applyLocale(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Item "General Settings" dihapus: isinya (Dark Mode, Language,
        // Orientation Mode) kini tampil langsung di daftar Setting di atas Learning.
        setupGeneralItems()

        val menuLearning = findViewById<LinearLayout>(R.id.menuLearning)
        val menuShareApps = findViewById<LinearLayout>(R.id.menuShareApps)
        val menuFeedback = findViewById<LinearLayout>(R.id.menuFeedback)
        val menuReportBug = findViewById<LinearLayout>(R.id.menuReportBug)
        val menuPrivacyPolicy = findViewById<LinearLayout>(R.id.menuPrivacyPolicy)
        val menuAbout = findViewById<LinearLayout>(R.id.menuAbout)

        val allMenus = listOf(menuLearning,
            menuShareApps, menuFeedback, menuReportBug, menuPrivacyPolicy, menuAbout)
        allMenus.forEach { addClickEffect(it) }

        menuLearning.setOnClickListener { openLearning() }
        menuShareApps.setOnClickListener { showShareAppsDialog() }
        menuFeedback.setOnClickListener { sendFeedbackEmail() }
        menuReportBug.setOnClickListener { sendBugReportEmail() }
        menuAbout.setOnClickListener { showAboutDialog() }
        menuPrivacyPolicy.setOnClickListener { openPrivacyPolicy() }
    }

    // ─── NAVIGASI ───

    // Halaman About di dalam activity (contentContainer/contentLayout + showContent/
    // showMenu) sudah dihapus: item About kini membuka popup sudut tajam
    // showAboutDialog(). Back dari halaman Setting langsung kembali ke halaman AR.

    // ─── ITEM GENERAL (DARK MODE, LANGUAGE, ORIENTATION) ───

    /**
     * Item pindahan dari halaman "General Settings" yang kini tampil langsung di daftar
     * Setting (di atas Learning): Dark Mode, Language, Orientation Mode.
     */
    private fun setupGeneralItems() {
        val darkModeToggle = findViewById<SwitchMaterial>(R.id.darkModeToggle)
        val languageSpinner = findViewById<Spinner>(R.id.languageSpinner)
        val orientationSpinner = findViewById<Spinner>(R.id.orientationSpinner)

        val prefs = getSharedPreferences("display_settings", MODE_PRIVATE)
        darkModeToggle.isChecked = prefs.getBoolean("dark_mode", false)

        // Set language spinner position
        val currentLang = LocaleUtils.getCurrentLanguageCode(this)
        languageSpinner.setSelection(if (currentLang == "in") 1 else 0)

        // Set orientation spinner position: halaman AR default Landscape (index 1).
        val currentArOrientation = prefs.getString(AR_Activity.PREF_AR_ORIENTATION,
            AR_Activity.ORIENTATION_LANDSCAPE)
        orientationSpinner.setSelection(
            if (currentArOrientation == AR_Activity.ORIENTATION_PORTRAIT) 0 else 1)

        darkModeToggle.setOnCheckedChangeListener { _, isChecked ->
            // Satu sumber kebenaran: DeepspaceTheme (prefs "display_settings"/"dark_mode").
            DeepspaceTheme.setDarkMode(this, isChecked)
            DeepspaceTheme.setNightMode(isChecked)
            recreate()
        }

        // Item "Fullscreen Mode" dihapus: aplikasi selalu edge-to-edge. Pref `fullscreen`
        // (dibaca DeepspaceApp + BaseFullscreenActivity) dibiarkan default `true` tanpa UI.

        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val langCode = if (pos == 1) "in" else "en"
                val current = LocaleUtils.getCurrentLanguageCode(this@Settings_Activity)
                if (langCode != current) {
                    LocaleUtils.setLocale(this@Settings_Activity, langCode)
                    recreate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Item Orientation Mode (AR): index 0 = Portrait, 1 = Landscape.
        // Pref dibaca AR_Activity saat halaman AR dibuka.
        orientationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.edit().putString(AR_Activity.PREF_AR_ORIENTATION,
                    if (pos == 0) AR_Activity.ORIENTATION_PORTRAIT
                    else AR_Activity.ORIENTATION_LANDSCAPE).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ─── POPUP ABOUT ───

    /**
     * Item **About** membuka popup (bukan halaman terpisah lagi) dengan sudut **tajam**
     * ([R.drawable.bg_dialog_sharp]) berisi dua baris:
     *  1. [R.string.about_app_version] → "DEEPSPACE AR v0.8.0"
     *  2. [R.string.about_early_access] → "(Early Access)"
     * Lebar popup dipatok 300dp (halaman Setting dikunci landscape); popup ditutup
     * dengan tombol OK (lebarnya proporsional terhadap lebar popup) atau dengan
     * menekan area di luar popup.
     */
    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings_about, null, false)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(view)
            .create()

        // Tombol OK menutup popup; efek tekan disamakan dengan item daftar Setting.
        view.findViewById<android.widget.TextView>(R.id.btnAboutOk).apply {
            setOnClickListener { dialog.dismiss() }
            addClickEffect(this)
        }

        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(R.drawable.bg_dialog_sharp)
            window.setLayout(dpToPx(300), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    // SHARE DIALOG

    private fun showShareAppsDialog() {
        val shareApps = listOf(
            ShareApp("WhatsApp", "com.whatsapp", R.drawable.whatsapp),
            ShareApp("Facebook", "com.facebook.katana", R.drawable.facebook),
            ShareApp("Instagram", "com.instagram.android", R.drawable.instagram),
            ShareApp("LinkedIn", "com.linkedin.android", R.drawable.linkedin),
            ShareApp("Twitter", "com.twitter.android", R.drawable.twitter),
            ShareApp("Email", "email", R.drawable.email),
        )

        val appUrl = "https://deepspace.id"
        val shareText = "Check out DeepSpace app! $appUrl"

        val gridView = android.widget.GridView(this)
        gridView.numColumns = 3
        gridView.horizontalSpacing = dpToPx(8)
        gridView.verticalSpacing = dpToPx(8)
        gridView.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        gridView.stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
        gridView.columnWidth = dpToPx(80)
        gridView.setBackgroundColor(themeSurfaceColor())

        val gridAdapter = object : android.widget.BaseAdapter() {
            override fun getCount() = shareApps.size
            override fun getItem(pos: Int) = shareApps[pos]
            override fun getItemId(pos: Int) = pos.toLong()

            override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val item = shareApps[pos]
                val v = convertView ?: run {
                    val ll = android.widget.LinearLayout(this@Settings_Activity)
                    ll.orientation = android.widget.LinearLayout.VERTICAL
                    ll.gravity = android.view.Gravity.CENTER
                    ll.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                    ll.setBackgroundResource(android.R.drawable.list_selector_background)
                    val icon = android.widget.ImageView(this@Settings_Activity).apply {
                        id = android.R.id.icon
                        layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                    val text = android.widget.TextView(this@Settings_Activity).apply {
                        id = android.R.id.text1
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                        gravity = android.view.Gravity.CENTER
                                                textSize = 11f
                        setTextColor(themeTextPrimaryColor())
                        maxLines = 1
                    }
                    ll.addView(icon); ll.addView(text); ll
                }
                v.findViewById<android.widget.ImageView>(android.R.id.icon)?.setImageDrawable(
                    androidx.core.content.ContextCompat.getDrawable(this@Settings_Activity, item.iconRes))
                v.findViewById<android.widget.TextView>(android.R.id.text1)?.text = item.name
                return v
            }
        }

        gridView.adapter = gridAdapter

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.share_to_friends)).setView(gridView).create()

        gridView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
            val selected = shareApps[position]
            if (selected.packageName == "email") {
                shareViaEmail(shareText)
            } else {
                shareToApp(selected.packageName, shareText, selected.name)
            }
            dialog.dismiss()
        }

                dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded_white)
            window.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    // ─── EMAIL ───

        private fun sendFeedbackEmail() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:feedback@deepspace3d.com")
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.feedback_subject))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.feedback_body))
            }, getString(R.string.feedback)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendBugReportEmail() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:bug@deepspace3d.com")
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.bug_subject))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.bug_body))
            }, getString(R.string.report_bug)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    // ─── SHARE HELPERS ───

        private fun shareToApp(packageName: String, text: String, appName: String) {
        try {
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); setPackage(packageName)
            })
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.app_not_installed, appName), Toast.LENGTH_SHORT).show()
        }
    }

        private fun shareViaEmail(text: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_SUBJECT, "DeepSpace App")
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Share via Email"))
        } catch (e: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

        // ─── BROWSER ───

    private fun openPrivacyPolicy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.deepspace3d.com/privacy-policy")))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLearning() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.deepspace3d.com/learning")))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    // ─── UTILITY ───

    private fun addClickEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(100).start(); false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start(); false
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private data class ShareApp(val name: String, val packageName: String, val iconRes: Int)
}

