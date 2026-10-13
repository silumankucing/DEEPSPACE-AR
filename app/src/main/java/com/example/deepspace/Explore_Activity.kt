package com.example.deepspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Satu entri katalog Explore yang dibaca dari `metadata.json`.
 *
 * `thumbnailFile` dan `objectFile` adalah path RELATIF terhadap root situs katalog
 * (contoh "assets/2cadb3d5-….png"), bukan URL absolut — lihat [Explore_Activity.resolveUrl].
 */
private data class ExploreAsset(
    val uniqueId: Long,
    val title: String,
    val objectFile: String,
    val thumbnailFile: String,
    val createdAt: String
)

/** Galat HTTP (status di luar 2xx) dari katalog Explore; dipisah agar pesan popup spesifik. */
private class HttpException(val code: Int, message: String) : IOException(message)

class Explore_Activity : AppCompatActivity() {

    /**
     * Root situs katalog. `metadata.json` berada di root situs, dan path aset di dalamnya
     * ("assets/<uuid>.png" / "assets/<uuid>.glb") juga relatif terhadap root ini.
     */
    private companion object {
        const val CATALOG_BASE_URL = "https://dsproject-test-2025.web.app/"
        /** https://dsproject-test-2025.web.app/metadata.json */
        const val METADATA_URL = CATALOG_BASE_URL + "metadata.json"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
    }

    private lateinit var searchInput: EditText
    private lateinit var gridAdapter: ExploreGridAdapter
    private var loadingIndicator: ProgressBar? = null

    /** Seluruh aset dari metadata.json (belum difilter). */
    private val allAssets = mutableListOf<ExploreAsset>()

    /** Aset yang sedang ditampilkan di grid (hasil filter pencarian). */
    private val assets = mutableListOf<ExploreAsset>()

    private var errorDialog: android.app.AlertDialog? = null

    /** Popup galat thumbnail hanya ditampilkan sekali per pemuatan katalog (anti-menumpuk). */
    private var thumbnailErrorReported = false

    /** Aset yang unduhannya menunggu folder tujuan dipilih lewat file manager. */
    private var pendingObjectAsset: ExploreAsset? = null

    /** Folder tujuan terakhir yang dipilih pengguna (tree URI SAF); null = belum dipilih. */
    private var pendingFolderUri: Uri? = null

    /** Popup "Download" yang sedang tampil dan label folder di dalamnya (diperbarui setelah Browse). */
    private var downloadDialog: android.app.AlertDialog? = null
    private var downloadFolderLabel: TextView? = null

    /**
     * Pemilih folder tujuan lewat file manager bawaan Android (Storage Access Framework).
     * Tree URI hasil pilihan dipakai sebagai parent dokumen saat menyimpan file 3D, jadi
     * penyimpanan tidak memerlukan izin penyimpanan sama sekali (minSdk 26 aman).
     */
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) {
            // Pengguna menutup file manager tanpa memilih folder → batalkan unduhan tertunda.
            pendingObjectAsset = null
            return@registerForActivityResult
        }

        pendingFolderUri = treeUri
        downloadFolderLabel?.text = folderLabelText(treeUri)

        // Bila tombol Download sudah ditekan sebelum folder dipilih, lanjutkan unduhan
        // yang tertunda sekarang juga (popup Download ditutup karena folder sudah pasti).
        val asset = pendingObjectAsset
        pendingObjectAsset = null
        if (asset != null) {
            downloadDialog?.dismiss()
            startObjectDownload(asset, treeUri)
        }
    }

    /** Cache thumbnail di memori, dibatasi 8 MB (sizeOf dihitung dari byteCount bitmap). */
    private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Pool thread kecil untuk unduhan thumbnail agar tidak memblokir UI. */
    private val thumbnailExecutor = Executors.newFixedThreadPool(4)

    /**
     * Pool thread terpisah untuk unduhan file 3D (satu berkas sekaligus), supaya unduhan
     * model yang besar tidak menahan pemuatan thumbnail di grid.
     */
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mode terang/gelap mengikuti toggle "Dark Mode" di halaman Setting, bukan
        // mode gelap perangkat. Halaman ini tidak turunan BaseFullscreenActivity,
        // jadi preferensinya diterapkan sendiri sebelum super.onCreate().
        DeepspaceTheme.applySavedMode(this)

        super.onCreate(savedInstanceState)
        DeepspaceTheme.syncResourcesNightMode(this)

        // Explore adalah satu-satunya halaman yang status bar/nav bar-nya tetap tampil (bukan
        // BaseFullscreenActivity). Window dibuat edge-to-edge supaya grid dan search bar (yang
        // kini berada di bagian bawah) dapat menggambar sampai tepi layar, lalu system bar
        // dikompensasi lewat listener WindowInsets (lihat search bar & grid di bawah).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Warna teks/ikon mengikuti mode yang aktif supaya kontras di kedua tema.
        val primaryTextColor = DeepspaceTheme.textPrimaryColor(this)

        // Root layout vertikal
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ===== SEARCH BAR (di bagian bawah halaman, tanpa latar biru) =====
        // Halaman ini terkunci landscape (lihat AndroidManifest), jadi search bar
        // ditambahkan sebagai anak TERAKHIR rootLayout: selalu di bagian bawah,
        // tidak ikut ter-scroll bersama grid.
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Latar biru dihapus; bar menyatu dengan warna halaman
            // (light: putih, dark: dark_background).
            setBackgroundColor(DeepspaceTheme.backgroundColor(this@Explore_Activity))
            // Padding awal; jarak bawah sebenarnya dihitung dari tinggi system bar
            // (WindowInsets) lewat listener di bawah.
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
        }

        // Search bar menempel ke tepi bawah: hanya menyisakan tinggi system bar (nav bar/
        // gesture) + 6dp di bawah input, tapi tetap tidak tertutup system bar. Window sudah
        // edge-to-edge, jadi bar tetap menggambar sampai tepi bawah layar.
        ViewCompat.setOnApplyWindowInsetsListener(searchBar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                dpToPx(16) + bars.left,
                dpToPx(10),
                dpToPx(16) + bars.right,
                bars.bottom + dpToPx(6)
            )
            insets
        }

        // Search Input — lebarnya dikurangi (≈45% lebar layar, maksimum 420dp) dan
        // dipusatkan supaya tidak membentang penuh di layar landscape.
        val searchWidth = (resources.displayMetrics.widthPixels * 0.45f).toInt()
            .coerceIn(dpToPx(240), dpToPx(420))
        searchInput = EditText(this).apply {
            hint = getString(R.string.explore_search_hint)
            setTextColor(primaryTextColor)
            setHintTextColor(DeepspaceTheme.textSecondaryColor(this@Explore_Activity))
            // Style outline: tanpa isian, hanya garis tepi biru (bg_input_field_outline).
            setBackgroundResource(R.drawable.bg_input_field_outline)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                searchWidth,
                dpToPx(44)
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            // Teks yang diketik DAN hint rata tengah (vertikal + horizontal).
            gravity = Gravity.CENTER
            // Menyaring aset katalog berdasarkan judul (title) secara real-time.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    applyFilter(s?.toString().orEmpty())
                }
            })
        }
        searchBar.addView(searchInput)

        // ===== GRID VIEW — landscape: 4 kolom =====
        gridAdapter = ExploreGridAdapter()
        val gridView = GridView(this).apply {
            // Halaman landscape → 4 kolom. Kolom direntangkan (STRETCH_COLUMN_WIDTH)
            // supaya keempat kolom mengisi lebar layar tanpa celah kanan.
            numColumns = 4
            horizontalSpacing = dpToPx(12)
            verticalSpacing = dpToPx(12)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            // Lebar dasar kolom; GridView melebarkannya otomatis
            // (sisa lebar dibagi 4 kolom) sehingga tetap 4 kolom penuh.
            columnWidth = dpToPx(160)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Latar grid mengikuti toggle Setting (light: putih, dark: dark_background)
            setBackgroundColor(DeepspaceTheme.backgroundColor(this@Explore_Activity))
            adapter = gridAdapter
        }
        // Window kini edge-to-edge, jadi grid diberi inset agar sisi kanan/kiri pada layar
        // dengan cutout tidak tertutup system bars. Inset BAWAH tidak ditambahkan di sini:
        // search bar di bawah grid sudah mengompensasi nav bar/gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(gridView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                dpToPx(16) + bars.left,
                dpToPx(16),
                dpToPx(16) + bars.right,
                dpToPx(16)
            )
            insets
        }

        // Progress indicator (indeterminate) selama metadata.json diunduh.
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        loadingIndicator = progress

        // Grid dibungkus FrameLayout supaya progress tampil di tengah tanpa menggeser grid.
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        contentFrame.addView(
            gridView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            progress,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        // Content (grid) ditambahkan lebih dulu dengan bobot 1, baru search bar:
        // search bar jadi elemen terakhir/terbawah dan selalu terlihat di bawah grid.
        rootLayout.addView(contentFrame)
        rootLayout.addView(searchBar)

        setContentView(rootLayout)
        // Paksa pengiriman WindowInsets ke listener search bar & grid di atas (window sudah
        // edge-to-edge) supaya padding system bars langsung diterapkan pada layout pertama.
        ViewCompat.requestApplyInsets(rootLayout)

        // Muat katalog Explore dari server (metadata.json).
        loadCatalog()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    /**
     * Unduh dan tampilkan katalog Explore.
     *
     * Seluruh proses (jaringan + parsing) berjalan di thread latar; hasilnya dipublikasikan
     * ke UI thread. SEMUA jenis kegagalan diteruskan ke [showErrorPopup] (popup + tombol OK):
     * koneksi/jaringan, status HTTP non-2xx, JSON tidak valid, dan katalog kosong.
     */
    private fun loadCatalog() {
        setLoading(true)
        thumbnailErrorReported = false
        Thread({
            var list: List<ExploreAsset>? = null
            var error: Throwable? = null
            try {
                list = parseMetadata(httpGetString(METADATA_URL))
            } catch (t: Throwable) {
                error = t
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setLoading(false)
                val failure = error
                val data = list
                when {
                    failure != null -> showErrorPopup(errorMessageFor(failure))
                    data.isNullOrEmpty() -> showErrorPopup(getString(R.string.explore_error_empty))
                    else -> {
                        allAssets.clear()
                        allAssets.addAll(data)
                        // Terapkan ulang teks pencarian yang mungkin sudah diketik.
                        applyFilter(searchInput.text?.toString().orEmpty())
                    }
                }
            }
        }, "ExploreCatalog").start()
    }

    private fun setLoading(loading: Boolean) {
        loadingIndicator?.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /** Saring [allAssets] berdasarkan judul lalu tampilkan hasilnya di grid. */
    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allAssets
        } else {
            allAssets.filter { it.title.lowercase().contains(q) }
        }
        assets.clear()
        assets.addAll(filtered)
        gridAdapter.notifyDataSetChanged()
    }

    /** Unduh isi URL sebagai teks; melempar [HttpException] untuk status non-2xx. */
    private fun httpGetString(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw HttpException(code, "HTTP $code ${conn.responseMessage ?: ""}".trim())
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Unduh gambar lalu decode; null bila konten bukan gambar valid. */
    private fun downloadBitmap(url: String): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code, "HTTP $code")
            return conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** Baca array JSON katalog menjadi daftar [ExploreAsset]. */
    private fun parseMetadata(json: String): List<ExploreAsset> {
        val root = org.json.JSONArray(json)
        val list = ArrayList<ExploreAsset>(root.length())
        for (i in 0 until root.length()) {
            val obj = root.optJSONObject(i) ?: continue
            val thumbnail = obj.optString("thumbnail_file").trim()
            // Entri tanpa thumbnail tidak bisa ditampilkan di grid → dilewati.
            if (thumbnail.isEmpty()) continue
            list.add(
                ExploreAsset(
                    uniqueId = obj.optLong("unique_id", -1L),
                    title = obj.optString("title").trim().ifBlank { "Untitled" },
                    objectFile = obj.optString("object_file").trim(),
                    thumbnailFile = thumbnail,
                    createdAt = obj.optString("create_at").trim()
                )
            )
        }
        return list
    }

    /** Bentuk URL absolut dari path relatif metadata.json (URL absolut dibiarkan apa adanya). */
    private fun resolveUrl(relative: String): String =
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            relative
        } else {
            CATALOG_BASE_URL + relative.trimStart('/')
        }

    /** Terjemahkan jenis kegagalan menjadi pesan popup yang sesuai. */
    private fun errorMessageFor(t: Throwable): String = when (t) {
        is HttpException -> getString(R.string.explore_error_server, t.code.toString())
        is org.json.JSONException -> getString(R.string.explore_error_parse)
        // IOException lain (UnknownHost, timeout, koneksi ditolak) → galat jaringan.
        else -> getString(R.string.explore_error_network)
    }

    /**
     * Popup galat tunggal dengan satu tombol OK. Dipakai untuk SEMUA kegagalan katalog
     * (jaringan, HTTP, JSON, katalog kosong, thumbnail, unduh). Dialog sebelumnya ditutup
     * lebih dulu agar tidak menumpuk saat beberapa kegagalan terjadi berurutan.
     */
    private fun showErrorPopup(message: String) {
        if (isFinishing || isDestroyed) return
        errorDialog?.dismiss()
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.error_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .setCancelable(true)
            .create()
        dialog.setOnShowListener {
            // Latar popup mengikuti toggle Setting (light/dark).
            dialog.window?.setBackgroundDrawable(DeepspaceTheme.popupBackground(this, 12f))
        }
        errorDialog = dialog
        dialog.show()
    }

    /**
     * Adapter grid: tiap sel menampilkan thumbnail (diunduh dari server) + judul aset.
     * Thumbnail di-cache di memori dan unduhannya berjalan di [thumbnailExecutor].
     */
    private inner class ExploreGridAdapter : BaseAdapter() {
        override fun getCount(): Int = assets.size

        override fun getItem(p: Int): Any = assets[p]

        override fun getItemId(p: Int): Long = assets[p].uniqueId

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: run {
                val ll = LinearLayout(this@Explore_Activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // Tile mengisi lebar kolom (4 kolom landscape) dengan tinggi tetap 160dp;
                // CENTER_CROP menjaga gambar tetap memenuhi tile.
                val iv = ImageView(this@Explore_Activity).apply {
                    id = android.R.id.icon
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(ContextCompat.getColor(this@Explore_Activity, R.color.grey))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(160)
                    )
                }
                ll.addView(iv)

                // Judul aset di bawah thumbnail.
                val tv = TextView(this@Explore_Activity).apply {
                    id = android.R.id.text1
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(2))
                }
                ll.addView(tv)
                ll
            }

            val asset = assets[pos]
            val iv = v.findViewById<ImageView>(android.R.id.icon)
            val tv = v.findViewById<TextView>(android.R.id.text1)
            tv.text = asset.title
            tv.setTextColor(DeepspaceTheme.textPrimaryColor(this@Explore_Activity))
            loadThumbnail(asset, iv)
            v.setOnClickListener { showAssetPopup(asset) }
            return v
        }
    }

    /** Muat thumbnail [asset] ke [imageView] dari cache, atau unduh di latar belakang. */
    private fun loadThumbnail(asset: ExploreAsset, imageView: ImageView) {
        val url = resolveUrl(asset.thumbnailFile)
        // tag dipakai untuk mendeteksi view yang sudah dipakai ulang (recycled) supaya
        // hasil unduhan lama tidak menimpa posisi baru.
        imageView.tag = url

        val cached = thumbnailCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        // Placeholder selama unduh: kosongkan gambar supaya view daur-ulang tidak sempat
        // menampilkan thumbnail lama; latar abu-abu tile tetap terlihat.
        imageView.setImageDrawable(null)
        thumbnailExecutor.execute {
            val bitmap = try {
                downloadBitmap(url)
            } catch (t: Throwable) {
                Log.w("Explore_Activity", "Thumbnail gagal dimuat: $url", t)
                null
            }
            if (bitmap != null) {
                thumbnailCache.put(url, bitmap)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && imageView.tag == url) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } else {
                // Gagal unduh → placeholder tetap tampil, dan pengguna diberi tahu lewat popup.
                runOnUiThread { reportThumbnailError() }
            }
        }
    }

    /**
     * Laporkan galat thumbnail lewat popup galat. Hanya sekali per pemuatan katalog agar
     * beberapa thumbnail yang gagal sekaligus tidak memunculkan dialog bertumpuk.
     */
    private fun reportThumbnailError() {
        if (isFinishing || isDestroyed || thumbnailErrorReported) return
        thumbnailErrorReported = true
        showErrorPopup(getString(R.string.explore_error_thumbnail))
    }

    /**
     * Popup item: thumbnail yang diklik + tombol Download berbentuk lingkaran biru
     * (icon_download) di pojok kanan bawah. Tanpa tombol Close — popup ditutup dengan
     * tap di luar area popup.
     */
    private fun showAssetPopup(asset: ExploreAsset) {
        val url = resolveUrl(asset.thumbnailFile)

        val content = FrameLayout(this).apply {
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(ContextCompat.getColor(this@Explore_Activity, R.color.grey))
            val cached = thumbnailCache.get(url)
            // Placeholder sementara: latar abu-abu; bitmap diisi setelah unduhan selesai.
            if (cached != null) setImageBitmap(cached) else setImageDrawable(null)
        }
        content.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(320)
            )
        )

        // Bila thumbnail belum ada di cache, muat dulu lalu perbarui preview.
        if (thumbnailCache.get(url) == null) {
            thumbnailExecutor.execute {
                val bmp = try {
                    downloadBitmap(url)
                } catch (t: Throwable) {
                    null
                }
                if (bmp != null) {
                    thumbnailCache.put(url, bmp)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) preview.setImageBitmap(bmp)
                    }
                }
            }
        }

        val downloadButton = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@Explore_Activity, R.drawable.icon_download))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            background = ContextCompat.getDrawable(this@Explore_Activity, R.drawable.circle_button_bg)
            layoutParams = FrameLayout.LayoutParams(dpToPx(40), dpToPx(40)).also {
                it.gravity = Gravity.BOTTOM or Gravity.END
            }
        }
        downloadButton.setOnClickListener { showDownloadPopup(asset) }
        content.addView(downloadButton)

        val dialog = android.app.Dialog(this)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.8f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /**
     * Popup "Download": memperlihatkan nama file 3D yang akan diunduh (`object_file`,
     * mis. "1.glb") beserta folder tujuannya. Tombol Browse membuka file manager bawaan
     * Android untuk memilih folder, lalu tombol Download menyimpan FILE 3D tersebut —
     * bukan thumbnail (nama file 3D sama dengan thumbnail, hanya beda ekstensi).
     */
    private fun showDownloadPopup(asset: ExploreAsset) {
        val fileName = fileNameOf(asset.objectFile)

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(8), dpToPx(24), 0)
        }
        labels.addView(
            TextView(this).apply {
                text = getString(R.string.explore_download_file_label, fileName)
                setTextColor(DeepspaceTheme.textPrimaryColor(this@Explore_Activity))
            }
        )
        val folderLabel = TextView(this).apply {
            // Folder terakhir yang dipilih tetap diingat supaya mengunduh model lain tidak
            // perlu memilih folder lagi.
            text = folderLabelText(pendingFolderUri)
            setPadding(0, dpToPx(8), 0, 0)
            setTextColor(DeepspaceTheme.textPrimaryColor(this@Explore_Activity))
        }
        labels.addView(folderLabel)
        downloadFolderLabel = folderLabel

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.explore_download_title)
            .setView(labels)
            .setNeutralButton(R.string.explore_download_browse, null)
            .setPositiveButton(R.string.explore_download_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // Listener tombol dipasang manual: listener bawaan AlertDialog selalu menutup dialog,
        // sedangkan Browse harus membiarkan popup tetap terbuka selama file manager dibuka.
        dialog.setOnShowListener { d ->
            val alert = d as android.app.AlertDialog
            // Latar popup mengikuti toggle Setting (light/dark), sama seperti popup galat.
            alert.window?.setBackgroundDrawable(DeepspaceTheme.popupBackground(this, 12f))
            alert.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                pendingObjectAsset = null
                folderPickerLauncher.launch(null)
            }
            alert.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val folder = pendingFolderUri
                if (folder == null) {
                    // Folder belum dipilih → buka file manager dulu; unduhan dilanjutkan
                    // otomatis begitu folder tujuan dipilih (lihat folderPickerLauncher).
                    android.widget.Toast.makeText(
                        this@Explore_Activity,
                        getString(R.string.explore_download_pick_folder),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    pendingObjectAsset = asset
                    folderPickerLauncher.launch(null)
                } else {
                    alert.dismiss()
                    startObjectDownload(asset, folder)
                }
            }
        }

        downloadDialog = dialog
        dialog.show()
    }

    /**
     * Unduh file 3D ([ExploreAsset.objectFile]) dari server lalu tulis ke folder tujuan yang
     * dipilih pengguna. Penyimpanan memakai SAF sehingga tidak butuh izin runtime apa pun.
     * Kegagalan unduh/penulisan dilaporkan lewat popup galat dengan tombol OK.
     */
    private fun startObjectDownload(asset: ExploreAsset, treeUri: Uri) {
        if (asset.objectFile.isBlank()) {
            showErrorPopup(getString(R.string.explore_error_no_object))
            return
        }

        val url = resolveUrl(asset.objectFile)
        val fileName = fileNameOf(asset.objectFile)

        setLoading(true)
        downloadExecutor.execute {
            val bytes = try {
                httpGetBytes(url)
            } catch (t: Throwable) {
                // Unduhan gagal / server menolak (HttpException) → pesan popup khusus.
                Log.e("Explore_Activity", "Unduhan file 3D gagal: $url", t)
                null
            }
            val savedName = if (bytes != null) saveBytesToFolder(treeUri, fileName, bytes) else null

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setLoading(false)
                when {
                    savedName != null -> android.widget.Toast.makeText(
                        this@Explore_Activity,
                        getString(R.string.explore_download_saved_file, savedName),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()

                    bytes == null -> showErrorPopup(getString(R.string.explore_error_download))
                    else -> showErrorPopup(getString(R.string.explore_error_folder))
                }
            }
        }
    }

    /** Unduh isi URL sebagai byte (berkas 3D); melempar [HttpException] untuk status non-2xx. */
    private fun httpGetBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code, "HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Tulis [bytes] sebagai berkas [fileName] di dalam folder [treeUri] hasil pemilih folder.
     * Memakai DocumentsContract (SAF), jadi tidak ada izin penyimpanan yang dibutuhkan.
     *
     * @return nama berkas yang benar-benar dipakai (bisa berbeda bila nama sudah ada),
     *         atau null bila berkas gagal dibuat/ditulis.
     */
    private fun saveBytesToFolder(treeUri: Uri, fileName: String, bytes: ByteArray): String? {
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )

            val name = uniqueChildName(treeUri, fileName)
            val firstTry = createDocumentOrNull(parent, name)
            val document: Uri
            val savedName: String
            if (firstTry != null) {
                document = firstTry
                savedName = name
            } else {
                // Nama asli ditolak provider → pakai nama ber-cap waktu agar tetap tersimpan.
                savedName = withTimestamp(fileName)
                document = createDocumentOrNull(parent, savedName) ?: return null
            }

            contentResolver.openOutputStream(document)?.use { os -> os.write(bytes) } ?: return null
            savedName
        } catch (e: Exception) {
            Log.e("Explore_Activity", "saveBytesToFolder failed", e)
            null
        }
    }

    /** Buat dokumen baru di folder [parent]; null bila provider menolak (mis. nama sudah dipakai). */
    private fun createDocumentOrNull(parent: Uri, name: String): Uri? = try {
        DocumentsContract.createDocument(contentResolver, parent, mimeTypeFor(name), name)
    } catch (e: Exception) {
        Log.w("Explore_Activity", "createDocument gagal: $name", e)
        null
    }

    /**
     * Nama berkas unik di dalam folder [treeUri]: bila sudah dipakai, tambahkan " (2)", " (3)",
     * dst. sebelum ekstensi (pola sama dengan [DeepspaceDirs.uniqueFile] untuk folder biasa).
     */
    private fun uniqueChildName(treeUri: Uri, fileName: String): String {
        val used = HashSet<String>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { used.add(it) }
                }
            }
        } catch (e: Exception) {
            // Isi folder tidak terbaca → lewati pemeriksaan; provider tetap menjaga nama unik.
            Log.w("Explore_Activity", "Tidak bisa membaca isi folder tujuan", e)
        }

        if (!used.contains(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 2
        while (used.contains("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    /** Nama berkas dengan cap waktu sebelum ekstensi (cadangan bila nama asli sudah dipakai). */
    private fun withTimestamp(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    /** MIME type berkas 3D — provider memerlukannya saat membuat dokumen di folder tujuan. */
    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "glb" -> "model/gltf-binary"
            "gltf" -> "model/gltf+json"
            else -> "application/octet-stream"
        }

    /** Nama berkas dari path relatif metadata.json, mis. "assets/1.glb" → "1.glb". */
    private fun fileNameOf(path: String): String {
        val name = path.trim().trimEnd('/').substringAfterLast('/')
        return name.ifBlank { "model" }
    }

    /** Nama folder (mudah dibaca) dari tree URI pemilih folder, mis. "primary:Download" → "Download". */
    private fun folderNameOf(treeUri: Uri): String {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            null
        }
        val name = docId?.substringAfter(':')?.trimEnd('/')
        if (!name.isNullOrBlank()) return name
        if (!docId.isNullOrBlank()) return docId
        return treeUri.lastPathSegment ?: treeUri.toString()
    }

    /** Teks label folder pada popup Download: nama folder terpilih, atau "belum dipilih". */
    private fun folderLabelText(treeUri: Uri?): String =
        if (treeUri == null) {
            getString(R.string.explore_download_folder_none)
        } else {
            getString(R.string.explore_download_folder_label, folderNameOf(treeUri))
        }

    override fun onDestroy() {
        // Tutup dialog & hentikan unduhan thumbnail/file 3D yang masih berjalan.
        errorDialog?.dismiss()
        errorDialog = null
        downloadDialog?.dismiss()
        downloadDialog = null
        downloadFolderLabel = null
        thumbnailExecutor.shutdownNow()
        downloadExecutor.shutdownNow()
        super.onDestroy()
    }
}
