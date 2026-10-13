package com.example.deepspace

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat

/**
 * ImportBrowserActivity — file manager layar penuh (BUKAN popup) yang otomatis
 * terbuka di folder root DEEPSPACE. Tampilan grid dengan ikon folder.
 *
 * Layout ditentukan lewat intent extra [EXTRA_LAYOUT]:
 *  - [LAYOUT_PORTRAIT]  (default): orientation portrait, grid 2 kolom.
 *    Dipergunakan dari CAD Part, CAD Assembly, Polygon dan AR.
 *  - [LAYOUT_LANDSCAPE]: orientation landscape, grid 4 kolom.
 *    Dipakai oleh [ImportBrowserActivityLandscape].
 *
 * Tombol Close sudah dihapus; penutupan memakai tombol back sistem. Di kanan bawah ada
 * tombol "+" bulat biru yang membuka file explorer bawaan Android (DocumentsUI) dengan
 * filter [ALLOWED_EXTENSIONS] — jalur alternatif bila file berada di luar folder DEEPSPACE.
 */
open class ImportBrowserActivity : BaseFullscreenActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "import_file_path"
        const val EXTRA_LAYOUT = "import_browser_layout"
        /** Ekstensi tambahan (array string) yang boleh tampil di browser, dikirim halaman pemanggil. */
        const val EXTRA_EXTENSIONS = "import_extra_extensions"
        const val LAYOUT_PORTRAIT = "portrait"
        const val LAYOUT_LANDSCAPE = "landscape"

        /**
         * Filter ekstensi yang boleh diimpor — dipakai bersama oleh browser folder internal
         * (grid) dan file explorer bawaan Android (tombol "+"): hanya file dengan ekstensi
         * ini yang tampil/diterima, file lain tidak ditampilkan.
         */
        val ALLOWED_EXTENSIONS = setOf(
            "dsform", "dspart", "dsasm", "dsrender",   // proyek Deepspace
            "glb", "ply", "stl", "obj",                // mesh
            "igs", "iges", "step", "stp"               // CAD interchange (IGES/STEP)
        )

        /**
         * MIME type untuk ACTION_OPEN_DOCUMENT (jadi DocumentsUI menyaring sesuai filter).
         * Format yang tidak punya MIME resmi (ply, igs, stp, dsform, dst.) dipetakan ke
         * "application/octet-stream"; validasi ekstensi tetap diulang setelah file dipilih.
         */
        val SYSTEM_PICKER_MIME_TYPES = arrayOf(
            "model/gltf-binary", "model/stl", "model/obj", "model/iges",
            "application/octet-stream", "application/x-step", "application/sla"
        )
        const val ICON_DIR = "📁"
        const val ICON_FILE = "📄"
    }

    private lateinit var pathText: android.widget.TextView
    private lateinit var grid: android.widget.GridLayout
    private lateinit var root: java.io.File
    private var currentDir: java.io.File? = null
    protected var columns: Int = 2

    /** Ekstensi tambahan dari halaman pemanggil (intent extra [EXTRA_EXTENSIONS]). */
    private var extraExtensions: Set<String> = emptySet()

    /** Font Jaturat — dipakai judul "FILE EXPLORER", nama folder, dan nama file. */
    private val popupTypeface by lazy { ResourcesCompat.getFont(this, R.font.jaturat) }

    /**
     * File explorer bawaan Android (DocumentsUI) — dibuka oleh tombol "+" di kanan bawah.
     * Memakai `OpenDocument` supaya langsung masuk ke berkas dokumen (bukan Google Photos),
     * dengan [SYSTEM_PICKER_MIME_TYPES] sebagai filter tampilan.
     */
    private val systemFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onSystemFilePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Gere paraapkan orientation baku super.onCreate() yang jalur layout awal,
        // supaya layout landscape langsung tampil landscape tanpa blink portrait.
        val layout = intent?.getStringExtra(EXTRA_LAYOUT)
        val landscape = layout == LAYOUT_LANDSCAPE
        requestedOrientation = if (landscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        columns = if (landscape) 4 else 2
        super.onCreate(savedInstanceState)
        extraExtensions = intent?.getStringArrayExtra(EXTRA_EXTENSIONS)
            ?.map { it.lowercase() }?.toSet() ?: emptySet()
        buildUi()
        root = DeepspaceDirs.getRootDir(this)
        if (!root.exists()) root.mkdirs()
        currentDir = root
        render()
    }

    private fun buildUi() {
        val bg = android.widget.FrameLayout(this)
        bg.setBackgroundColor(themeSurfaceColor())

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER_HORIZONTAL
        container.setPadding(dpToPx(14), dpToPx(38), dpToPx(14), dpToPx(96))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER_HORIZONTAL

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = android.widget.TextView(this).apply {
            text = "FILE EXPLORER"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(themeTextPrimaryColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
            popupTypeface?.let { setTypeface(it, android.graphics.Typeface.BOLD) }
        }
        titleBlock.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val titleLine = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))
        }
        titleBlock.addView(titleLine, LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 2f / 3f).toInt(), dpToPx(2)
        ).also { it.topMargin = dpToPx(6) })
        header.addView(titleBlock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pathText = android.widget.TextView(this)
        pathText.textSize = 13f
        pathText.setTextColor(themeTextSecondaryColor())
        pathText.setTypeface(null, android.graphics.Typeface.BOLD)
        popupTypeface?.let { pathText.setTypeface(it, android.graphics.Typeface.BOLD) }
        pathText.setPadding(0, dpToPx(10), 0, dpToPx(6))
        container.addView(pathText)

        grid = android.widget.GridLayout(this).apply {
            // Portrait (CAD/Assembly/Polygon/AR) → 2 kolom; landscape → 4 kolom.
            columnCount = columns
            setPadding(0, dpToPx(6), 0, 0)
        }
        val scroll = android.widget.ScrollView(this)
        scroll.addView(grid)
        container.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bg.addView(container, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // Tombol "+" bulat biru di kanan bawah: membuka file explorer bawaan Android
        // (DocumentsUI) dengan filter MIME [SYSTEM_PICKER_MIME_TYPES]. File yang dipilih
        // disalin ke folder Import lalu path-nya dikembalikan ke halaman pemanggil.
        val btnAdd = android.widget.ImageButton(this)
        btnAdd.setImageResource(R.drawable.icon_add)
        btnAdd.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        btnAdd.setBackgroundResource(R.drawable.circle_button_bg)
        btnAdd.setColorFilter(android.graphics.Color.WHITE)
        btnAdd.contentDescription = "Import dari file explorer"
        btnAdd.setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
        btnAdd.setOnClickListener { systemFilePicker.launch(SYSTEM_PICKER_MIME_TYPES) }
        bg.addView(btnAdd, android.widget.FrameLayout.LayoutParams(
            dpToPx(56), dpToPx(56), Gravity.BOTTOM or Gravity.END
        ).also {
            it.bottomMargin = dpToPx(24)
            it.marginEnd = dpToPx(20)
        })
        setContentView(bg)
        DeepspaceApp.hideBars(this)
    }

    private fun makeCell(label: String, icon: String, onClick: () -> Unit): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10))
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
            }
            layoutParams = lp
        }
        val iconView = android.widget.TextView(this).apply {
            text = icon
            textSize = if (icon == ICON_DIR) 96f else 32f
            gravity = Gravity.CENTER
        }
        cell.addView(iconView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val labelView = android.widget.TextView(this).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(themeTextPrimaryColor()); maxLines = 2; setPadding(0, dpToPx(4), 0, 0)
            // Nama folder/file memakai font Jaturat.
            popupTypeface?.let { typeface = it }
        }
        cell.addView(labelView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cell.setOnClickListener { onClick() }
        return cell
    }

    private fun render() {
        val dir = currentDir ?: root
        grid.removeAllViews()
        val dirAbs = dir.absolutePath.trimEnd('/', '\\')
        val rootAbs = root.absolutePath.trimEnd('/', '\\')
        pathText.text = if (dirAbs == rootAbs) "" else dir.name
        if (dirAbs != rootAbs) {
            dir.parentFile?.let { up -> grid.addView(makeCell("..", ICON_DIR) { currentDir = up; render() }) }
        }
        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        for (f in entries) {
            when {
                f.isDirectory -> grid.addView(makeCell(f.name, ICON_DIR) { currentDir = f; render() })
                allowedExtensions().contains(f.extension.lowercase()) ->
                    grid.addView(makeCell(f.name, ICON_FILE) { finishWith(f) })
            }
        }
        if (grid.childCount == 0) {
            val empty = android.widget.TextView(this).apply {
                text = "Tidak ada file yang bisa diimpor di folder ini\n" +
                    "Format: ${ALLOWED_EXTENSIONS.sorted().joinToString(", ")}\n" +
                    "Tekan tombol + untuk memilih file dari penyimpanan perangkat"
                setTextColor(themeTextSecondaryColor()); textSize = 14f; gravity = Gravity.CENTER
                setPadding(dpToPx(10), dpToPx(12), dpToPx(10), dpToPx(12))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(0, 2)
                }
            }
            grid.addView(empty)
        }
    }

    private fun finishWith(file: java.io.File) {
        val intent = Intent().putExtra(EXTRA_FILE_PATH, file.absolutePath)
        setResult(android.app.Activity.RESULT_OK, intent)
        finish()
    }

    /** Ekstensi yang tampil di grid: filter tetap + ekstensi tambahan dari halaman pemanggil. */
    private fun allowedExtensions(): Set<String> = ALLOWED_EXTENSIONS + extraExtensions

    /**
     * File dari file explorer bawaan Android (tombol "+") dipakai APA ADANYA — tidak disalin
     * ulang ke folder Import, jadi folder itu tidak terisi duplikat file yang sudah ada di
     * perangkat.
     *
     * Resolusi URI menjadi [java.io.File] (path nyata di penyimpanan, atau salinan di cache
     * aplikasi untuk penyedia tanpa file fisik) dan verifikasi ekstensi dikerjakan oleh
     * [ImportFileUtils] — jalur yang sama dengan tombol "+" di halaman AR, termasuk
     * pesan Toast saat formatnya tidak didukung.
     */
    private fun onSystemFilePicked(uri: android.net.Uri) {
        val file = ImportFileUtils.resolvePickedFile(this, uri, allowedExtensions()) ?: return
        finishWith(file)
    }

    private fun dpToPx(valueDp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, valueDp.toFloat(), resources.displayMetrics).toInt()
}

/**
 * ImportBrowserActivityLandscape — varian landscape dari browser folder internal.
 * Dideklarasikan dengan android:screenOrientation="landscape" pada AndroidManifest.xml,
 * supaya frame pertama langsung landscape (tanpa flash portrait → landscape) dan grid 4 kolom.
 *
 * Kelas ini tidak lagi dipakai oleh halaman mana pun sejak tombol "+" halaman AR langsung
 * membuka file manager bawaan Android (DocumentsUI); disisakan sebagai varian landscape bila
 * suatu saat browser folder internal dibutuhkan kembali.
 */
class ImportBrowserActivityLandscape : ImportBrowserActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        columns = 4
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        super.onCreate(savedInstanceState)
    }
}