package com.example.deepspace

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.features2d.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import android.os.Environment

class AR_Activity : BaseFullscreenActivity() {

    companion object {
        private const val TAG = "AR_Activity"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val DETECTION_INTERVAL_MS = 500L
        private const val MARKER_SIZE = 300

        /**
         * Orientasi halaman AR (item *Orientation Mode (AR)* di halaman Setting),
         * disimpan pada prefs `display_settings` dengan nilai [ORIENTATION_PORTRAIT]
         * atau [ORIENTATION_LANDSCAPE]. Default landscape.
         */
        const val PREF_AR_ORIENTATION = "ar_orientation"
        const val ORIENTATION_PORTRAIT = "portrait"
        const val ORIENTATION_LANDSCAPE = "landscape"

        /**
         * Sumbu yang diatur oleh slider Panel Lighting ([setupLightingPanel]): tiga pertama
         * adalah komponen arah (pergeseran) cahaya dan yang terakhir tingkat brightness.
         * Nilainya juga dipakai sebagai indeks [lightDirection] untuk x/y/z.
         */
        private const val LIGHT_AXIS_X = 0
        private const val LIGHT_AXIS_Y = 1
        private const val LIGHT_AXIS_Z = 2
        private const val LIGHT_AXIS_BRIGHTNESS = 3
    }

    private lateinit var filamentSurface: android.view.SurfaceView
    private var arRenderer: ArFilamentRenderer? = null

    // Preview kamera memakai CameraX: ukuran buffer, rotasi (portrait/landscape)
    // dan crop-aspek preview diatur CameraX sendiri sesuai orientasi layar.
    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null

    private var currentModelBytes: ByteArray? = null
    private var currentModelExtension: String = "glb"
    private var currentModelSourcePath: String = "model.glb"
    private var captureProgressView: CaptureProgressView? = null
    private var captureProgressAnimator: ValueAnimator? = null

    /**
     * Tombol Browse pada popup Capture ([showCapturePopup]). Labelnya diganti menjadi path
     * folder begitu pengguna memilih folder tujuan lewat [captureFolderPickerLauncher]; null
     * selama popup belum tampil (atau setelah popup ditutup).
     */
    private var captureFolderButton: Button? = null

    /**
     * Folder tujuan capture terakhir yang dipilih pengguna (tree URI Storage Access Framework).
     * Diingat selama halaman AR hidup supaya membuka popup Capture lagi tidak perlu memilih
     * folder dari awal. Null = belum pernah dipilih → hasil capture masuk folder default
     * `DEEPSPACE/Capture` ([DeepspaceDirs.getCaptureDir]).
     */
    private var captureFolderUri: Uri? = null

    private val popupTypeface by lazy { ResourcesCompat.getFont(this, R.font.jaturat) }

    // ─── OpenCV (feature-based marker detection) ───
    private var openCVReady = false
    private var orbDetector: ORB? = null
    private var descriptorMatcher: DescriptorMatcher? = null
    private var referenceDescriptors: Mat? = null

    // Marker scan state
    private var isMarkerScanning = false
    private var scanHandler: Handler? = null
    private var scanThread: HandlerThread? = null

    // Overlay untuk feedback visual scan
    private var markerOverlayView: ImageView? = null
    private var lastDetectedInfo: String = ""

        // ─── Tree / Project Name ───
    private var projectName: String = "AR Scene"
    private var hasManuallySaved = false
    private var autoSaveJob: Pair<android.os.Handler, Runnable>? = null
    private lateinit var treePanel: FrameLayout
    private lateinit var treeContainer: LinearLayout
    private var treeNodeNames: List<Pair<String, Int>> = emptyList()
    private var selectedTreeIndex: Int = -1
    private val lineColor = android.graphics.Color.parseColor("#3D93C8")
    private val highlightColor = android.graphics.Color.parseColor("#E65100")
    private val highlightAlphaColor = android.graphics.Color.argb(60, 230, 81, 0)

    // ─── Panel Lighting (pengganti popup Lighting) ───
    private lateinit var lightingPanel: LinearLayout
    private lateinit var lightingSlider: SeekBar
    private lateinit var lightingValueLabel: TextView
    private var lightingAxisButtons: List<Button> = emptyList()
    /** Sumbu yang sedang diatur slider (salah satu konstanta `LIGHT_AXIS_*`). */
    private var lightingAxis = LIGHT_AXIS_X
    /** Arah cahaya mentah (belum dinormalisasi) dari slider x/y/z; default = arah utama. */
    private val lightDirection = floatArrayOf(0.5f, -1f, -0.8f)
    /** Skala intensitas (1f = default) dari slider b. */
    private var lightIntensityScale = 1f
    private val lightingButtonColor = android.graphics.Color.parseColor("#3D93C8")
    private val lightingButtonActiveColor = android.graphics.Color.parseColor("#FF6B35")

    // Stabilize state
    private var isStabilizeActive = false

    /**
     * Item **Local** pada popup tombol "+" (btnDocument) membuka **file manager bawaan Android
     * (DocumentsUI) secara langsung** dengan filter
     * [ImportBrowserActivity.SYSTEM_PICKER_MIME_TYPES] — bukan browser folder internal. Jadi
     * file bisa dipilih dari mana pun (Download, Drive, kartu SD) tanpa lebih dulu menyusuri
     * folder DEEPSPACE.
     *
     * URI yang dipilih diubah jadi file yang bisa dibaca oleh [ImportFileUtils] (path nyata dipakai
     * apa adanya, atau isinya disalin ke cache bila penyedianya tidak punya file fisik) lalu
     * diumpankan ke [loadModelBytes], sehingga **seluruh jalur parser yang sudah ada tidak
     * berubah**: container Deepspace (.dspart/.dsasm/.dsform/.dsrender) dan GLB lewat
     * [DeepspaceBinaryParser], lalu ditambahkan ke scene AR + tree.
     */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importPickedFile(uri)
    }

    /**
     * Pemilih folder tujuan capture lewat file manager bawaan Android (Storage Access Framework).
     * Tree URI hasil pilihan dipakai sebagai parent dokumen saat menyimpan PNG, jadi penyimpanan
     * **tidak memerlukan izin penyimpanan sama sekali** (minSdk 26 aman) — pola yang sama dengan
     * pemilih folder pada popup Download di halaman Explore.
     *
     * Popup Capture dibiarkan tetap terbuka selama file manager tampil; begitu folder dipilih,
     * label tombol Browse langsung berubah menjadi path folder tersebut.
     */
    private val captureFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        // Pengguna menutup file manager tanpa memilih folder → folder sebelumnya tetap dipakai.
        if (treeUri == null) return@registerForActivityResult

        captureFolderUri = treeUri
        captureFolderButton?.text = treeFolderPathText(treeUri)
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Izin kamera diperlukan untuk AR", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Orientasi halaman AR mengikuti item *Orientation Mode (AR)* di halaman Setting
        // (default landscape). Diterapkan sebelum super.onCreate() supaya frame pertama
        // sudah memakai orientasi yang benar.
        requestedOrientation = orientationFlag(savedArOrientation())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        initOpenCV()

        previewView = findViewById(R.id.previewView)
        // COMPATIBLE (TextureView internal) wajib: jalur capture dan deteksi marker
        // OpenCV membaca snapshot PreviewView.bitmap, sedangkan mode PERFORMANCE
        // (SurfaceView) tidak menyediakannya.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // Preview penuh layar tanpa distorsi (center-crop) pada kedua orientasi.
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        val btnDocument = findViewById<ImageButton>(R.id.btnDocument)

        btnMenu.setOnClickListener { showArMenuPopup() }
        btnDocument.setOnClickListener { showDocumentMenuPopup() }

        setupFilament()
        setupLightingPanel()
        autoSaveJob = AutoSaveManager.schedule(this) { saveArProject(false) }

        // Buka file proyek yang diteruskan dari halaman lain (mis. CAD Part -> AR)
        val projectPath = intent?.getStringExtra("EXTRA_PROJECT_FILE")
        if (projectPath != null) {
            hasManuallySaved = true
            val projectFile = File(projectPath)
            if (projectFile.exists()) {
                val ext = projectFile.extension.lowercase()
                loadModelBytes(projectFile.readBytes(), ext)
            }
        }

        // Kamera di-bind ke lifecycle activity (lihat startCamera) sehingga otomatis
        // berhenti saat halaman tidak aktif dan di-bind ulang saat aktif kembali,
        // termasuk setelah activity dibuat ulang karena pergantian orientasi layar.
        requestCamera()
    }

    // ─── ORIENTASI HALAMAN AR ───

    /**
     * Simpan + terapkan orientasi halaman AR. Dipakai tombol *Orientation* di halaman AR,
     * dan nilainya dibaca lagi oleh dropdown *Orientation Mode (AR)* di halaman Setting.
     */
    private fun setArOrientation(value: String) {
        getSharedPreferences("display_settings", MODE_PRIVATE)
            .edit().putString(PREF_AR_ORIENTATION, value).apply()
        requestedOrientation = orientationFlag(value)
        Toast.makeText(this, getString(
            if (value == ORIENTATION_PORTRAIT) R.string.orientation_portrait
            else R.string.orientation_landscape), Toast.LENGTH_SHORT).show()
    }

    /** Orientasi tersimpan halaman AR (prefs `display_settings`); default landscape. */
    private fun savedArOrientation(): String =
        getSharedPreferences("display_settings", MODE_PRIVATE)
            .getString(PREF_AR_ORIENTATION, ORIENTATION_LANDSCAPE) ?: ORIENTATION_LANDSCAPE

    /** Flag ActivityInfo untuk nilai pref orientasi [value]. */
    private fun orientationFlag(value: String): Int =
        if (value == ORIENTATION_PORTRAIT) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

    override fun onResume() {
        super.onResume()
        arRenderer?.resume()
        // Sinkronkan orientasi bila dropdown *Orientation Mode (AR)* di Setting diubah
        // saat halaman AR sedang tidak aktif.
        val savedFlag = orientationFlag(savedArOrientation())
        if (requestedOrientation != savedFlag) requestedOrientation = savedFlag
    }

    override fun onPause() {
        super.onPause()
        arRenderer?.pause()
        stopMarkerScanning()
    }

    override fun onDestroy() {
        super.onDestroy()
        AutoSaveManager.cancel(autoSaveJob)
        stopMarkerScanning()
        referenceDescriptors?.release()
        // Lepas use case kamera milik activity ini dari provider (singleton per
        // proses) supaya kamera tidak dianggap masih terpakai saat activity
        // dibuat ulang, mis. karena pergantian orientasi.
        cameraProvider?.unbindAll()
        cameraProvider = null
        arRenderer?.destroy()
        arRenderer = null
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(CAMERA_PERMISSION)
        }
    }

    /**
     * Mengikat preview kamera ke [previewView] lewat CameraX.
     *
     * CameraX memilih ukuran buffer, menghitung rotasi (portrait/landscape) dan
     * crop aspek preview sendiri, jadi frame kamera selalu tegak dan memenuhi
     * layar. Inilah yang membuat preview ikut landscape ketika halaman AR
     * dipindah ke landscape (sebelumnya Camera2 mentah memakai buffer 1280x720
     * tetap tanpa koreksi rotasi/aspek).
     *
     * Pengikatan memakai lifecycle activity, jadi kamera otomatis dilepas saat
     * halaman berhenti dan diikat ulang saat aktif kembali (mis. selesai import
     * model atau setelah activity dibuat ulang karena orientasi layar).
     */
    private fun startCamera() {
        // Sama seperti Camera2 dulu: izin CAMERA harus dicek di method yang sama
        // (Lint MissingPermission), sekaligus mencegah SecurityException bila izin
        // dicabut saat aplikasi berjalan.
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestCamera()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(Runnable {
            // Activity bisa sudah ditutup saat provider selesai diinisialisasi;
            // bind ke lifecycle yang sudah mati akan melempar exception.
            if (isDestroyed) return@Runnable
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // Lepas use case lama (bila ada) sebelum bind ulang, mis. saat
                // activity dibuat ulang karena pergantian orientasi.
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal membuka kamera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Snapshot isi preview untuk OpenCV (referensi & deteksi marker, capture layar).
     *
     * [PreviewView.bitmap] hanya boleh dibaca dari main thread (CameraX
     * memverifikasinya), sedangkan loop scan marker berjalan di thread sendiri —
     * karena itu pembacaan dari thread lain di-hop ke main thread lalu ditunggu.
     */
    private fun readPreviewBitmap(): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) return previewView.bitmap

        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<Bitmap>(1)
        runOnUiThread {
            try {
                holder[0] = previewView.bitmap
            } catch (_: Exception) {
                // Preview belum siap atau activity sudah ditutup → biarkan null.
            } finally {
                latch.countDown()
            }
        }
        return try {
            if (latch.await(1, java.util.concurrent.TimeUnit.SECONDS)) holder[0] else null
        } catch (_: InterruptedException) {
            null
        }
    }

    private fun setupFilament() {
        filamentSurface = findViewById(R.id.filamentSurface)
        filamentSurface.setZOrderOnTop(true)
        filamentSurface.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        arRenderer = ArFilamentRenderer(filamentSurface)

        // Inisialisasi tree
        treePanel = findViewById(R.id.treePanel)
        treeContainer = findViewById(R.id.treeContainer)
        val txtProjectName = findViewById<TextView>(R.id.txtProjectName)
        txtProjectName.text = projectName
        txtProjectName.setOnClickListener { toggleTree() }
        txtProjectName.setOnLongClickListener {
            showRenameProjectDialog()
            true
        }
        updateTreeView(treeNodeNames)

        // Touch handler untuk zoom (pinch) dan rotate (single-finger drag)
        setupTouchGestures()
    }

        private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isRotating = false
    private var isPanning = false

    // Stabilize smoothing variables
    private var smoothRotateX = 0f
    private var smoothRotateY = 0f
    private var smoothPanX = 0f
    private var smoothPanY = 0f
    private val smoothFactor = 0.15f

    private fun setupTouchGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                arRenderer?.scaleBy(factor)
                return true
            }
        })

        filamentSurface.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isRotating = true
                    isPanning = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 2 jari → mode pan
                    isRotating = false
                    isPanning = true
                    // Hitung midpoint
                    lastTouchX = (event.getX(0) + event.getX(1)) / 2f
                    lastTouchY = (event.getY(0) + event.getY(1)) / 2f
                    true
                }
                                MotionEvent.ACTION_MOVE -> {
                    if (isRotating && event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (isStabilizeActive) {
                            // Smoothing: lerp toward target
                            smoothRotateX += (dy * 0.3f - smoothRotateX) * smoothFactor
                            smoothRotateY += (dx * 0.3f - smoothRotateY) * smoothFactor
                            arRenderer?.rotateBy(smoothRotateX, smoothRotateY)
                        } else {
                            arRenderer?.rotateBy(dy * 0.3f, dx * 0.3f)
                        }
                        lastTouchX = event.x
                        lastTouchY = event.y
                    } else if (isPanning && event.pointerCount >= 2) {
                        val midX = (event.getX(0) + event.getX(1)) / 2f
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        val dx = midX - lastTouchX
                        val dy = midY - lastTouchY
                        if (isStabilizeActive) {
                            smoothPanX += (dx - smoothPanX) * smoothFactor
                            smoothPanY += (dy - smoothPanY) * smoothFactor
                            arRenderer?.panBy(smoothPanX, smoothPanY)
                        } else {
                            arRenderer?.panBy(dx, dy)
                        }
                        lastTouchX = midX
                        lastTouchY = midY
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isRotating = false
                    isPanning = false
                    // Reset smoothing accumulators on release when stabilize active
                    if (isStabilizeActive) {
                        smoothRotateX = 0f
                        smoothRotateY = 0f
                        smoothPanX = 0f
                        smoothPanY = 0f
                    }
                    true
                }
                else -> false
            }
        }
        filamentSurface.isFocusable = true
        filamentSurface.isFocusableInTouchMode = true
        filamentSurface.requestFocus()
    }

    // ===================== OPENCV =====================

    private fun initOpenCV() {
        try {
            if (!OpenCVLoader.initDebug()) {
                android.util.Log.e(TAG, "OpenCV initDebug failed")
                Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show()
                return
            }
            openCVReady = true
            android.util.Log.d(TAG, "OpenCV initialized successfully")

            orbDetector = ORB.create(
                500, 1.2f, 8, 31, 0, 2,
                ORB.HARRIS_SCORE, 31, 20
            )
            descriptorMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            android.util.Log.d(TAG, "ORB detector & matcher ready")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "OpenCV init error: ${e.message}", e)
        }
    }

    // ===================== MARKER PRINT =====================

    private fun generateAndSaveMarker() {
        if (!openCVReady) {
            Toast.makeText(this, "OpenCV belum siap", Toast.LENGTH_SHORT).show()
            return
        }
        showMarkerIdInputDialog()
    }

        private fun showMarkerIdInputDialog() {
                val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Marker ID (0-999)"
        input.setText("1")
        input.setTextColor(themeTextPrimaryColor())
        input.setHintTextColor(themeTextSecondaryColor())
        input.gravity = android.view.Gravity.CENTER
        input.textSize = 18f

        // Layout container
                val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(8))

                val label = TextView(this)
        label.text = "ID Marker :"
        label.textSize = 22f
        label.gravity = android.view.Gravity.CENTER
        label.setTextColor(themeTextPrimaryColor())
        popupTypeface?.let { label.typeface = it }
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layout.addView(label)

        layout.addView(input)

                // Tombol layout
                val btnLayout = LinearLayout(this)
                btnLayout.orientation = LinearLayout.HORIZONTAL
                btnLayout.gravity = android.view.Gravity.CENTER
                btnLayout.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                val btnCancel = com.google.android.material.button.MaterialButton(this)
                                btnCancel.layoutParams = LinearLayout.LayoutParams(
                    0, dpToPx(48)
                ).also {
                    it.weight = 1f
                    it.setMargins(0, dpToPx(16), dpToPx(8), dpToPx(12))
                }
                btnCancel.text = "Cancel"
                btnCancel.isAllCaps = false
                btnCancel.textSize = 14f
                btnCancel.setTypeface(null, android.graphics.Typeface.BOLD)
                popupTypeface?.let { btnCancel.typeface = it }
                                btnCancel.cornerRadius = dpToPx(12)
                btnCancel.setTextColor(android.graphics.Color.WHITE)
                btnCancel.setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))

                                val btnGenerate = com.google.android.material.button.MaterialButton(this)
                btnGenerate.layoutParams = LinearLayout.LayoutParams(
                    0, dpToPx(48)
                ).also {
                    it.weight = 1f
                    it.setMargins(0, dpToPx(16), 0, dpToPx(12))
                }
                btnGenerate.text = "Generate"
                btnGenerate.isAllCaps = false
                btnGenerate.textSize = 14f
                btnGenerate.setTypeface(null, android.graphics.Typeface.BOLD)
                popupTypeface?.let { btnGenerate.typeface = it }
                                btnGenerate.cornerRadius = dpToPx(12)
                btnGenerate.setTextColor(android.graphics.Color.WHITE)
                btnGenerate.setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))

                btnLayout.addView(btnCancel)
                btnLayout.addView(btnGenerate)
                layout.addView(btnLayout)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(layout)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnGenerate.setOnClickListener {
            val idStr = input.text.toString()
            val markerId = idStr.toIntOrNull()
            if (markerId == null || markerId < 0 || markerId > 999) {
                Toast.makeText(this, "ID harus antara 0-999", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            doGenerateMarker(markerId)
        }

                dialog.show()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
            val popupWidth = (resources.displayMetrics.widthPixels * 0.75f).toInt()
            w.setLayout(popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun doGenerateMarker(markerId: Int) {
        try {
            // Generate marker gambar dengan pola ArUco-style (binary 6x6 grid)
            val markerMat = Mat(MARKER_SIZE, MARKER_SIZE, CvType.CV_8UC1, Scalar(255.0))

            // Border hitam tebal
            val border = 25
            Imgproc.rectangle(markerMat,
                Point(0.0, 0.0),
                Point(MARKER_SIZE.toDouble(), MARKER_SIZE.toDouble()),
                Scalar(0.0), -1)

            // Inner white area
            Imgproc.rectangle(markerMat,
                Point(border.toDouble(), border.toDouble()),
                Point((MARKER_SIZE - border).toDouble(), (MARKER_SIZE - border).toDouble()),
                Scalar(255.0), -1)

            // Grid 6x6 dengan pola unik berdasarkan ID marker
            val cellSize = (MARKER_SIZE - 2 * border) / 6
            val bits = generateMarkerBits(markerId)
            for (row in 0 until 6) {
                for (col in 0 until 6) {
                    if (bits[row][col] == 0) {
                        val x = border + col * cellSize
                        val y = border + row * cellSize
                        Imgproc.rectangle(markerMat,
                            Point(x.toDouble(), y.toDouble()),
                            Point((x + cellSize).toDouble(), (y + cellSize).toDouble()),
                            Scalar(0.0), -1)
                    }
                }
            }

            // Konversi Mat ke Bitmap
            val bmp = Bitmap.createBitmap(MARKER_SIZE, MARKER_SIZE, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(markerMat, bmp)
            markerMat.release()

            // Simpan ke folder Download publik
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val file = File(downloadDir, "Marker_$markerId.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Toast.makeText(this, "Marker #$markerId tersimpan di Downloads", Toast.LENGTH_LONG).show()
            showMarkerPreview(bmp, markerId)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Generate marker error: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Generate pola 6x6 bit unik untuk setiap marker ID */
    private fun generateMarkerBits(id: Int): Array<IntArray> {
        val bits = Array(6) { IntArray(6) }
        var seed = id * 2654435761L  // golden ratio hash
        for (row in 0 until 6) {
            for (col in 0 until 6) {
                seed = seed * 31 + 17
                bits[row][col] = (seed ushr (row * 6 + col) % 16).toInt() and 1
            }
        }
        return bits
    }

    private fun showMarkerPreview(bmp: Bitmap, markerId: Int) {
        // Layout utama: vertikal (image + baris tombol OK/Share)
        val mainLayout = LinearLayout(this)
        mainLayout.orientation = LinearLayout.VERTICAL
        mainLayout.setPadding(16, 16, 16, 8)

        val imageView = ImageView(this)
        imageView.setImageBitmap(bmp)
        imageView.adjustViewBounds = true
        imageView.setBackgroundColor(android.graphics.Color.WHITE)
        imageView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(240)
        )
        mainLayout.addView(imageView)

        // Baris tombol OK dan Share (side-by-side, biru dengan teks putih)
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = android.view.Gravity.CENTER
        btnRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dpToPx(10) }

        val btnOk = com.google.android.material.button.MaterialButton(this)
        btnOk.text = "OK"
        btnOk.isAllCaps = false
        btnOk.textSize = 14f
        btnOk.setTypeface(null, android.graphics.Typeface.BOLD)
        btnOk.cornerRadius = dpToPx(12)
        btnOk.setTextColor(android.graphics.Color.WHITE)
        btnOk.setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))
        btnOk.layoutParams = LinearLayout.LayoutParams(0, dpToPx(44)).also {
            it.weight = 1f
            it.setMargins(0, 0, dpToPx(6), 0)
        }

        val btnShare = com.google.android.material.button.MaterialButton(this)
        btnShare.text = "Share"
        btnShare.isAllCaps = false
        btnShare.textSize = 14f
        btnShare.setTypeface(null, android.graphics.Typeface.BOLD)
        btnShare.cornerRadius = dpToPx(12)
        btnShare.setTextColor(android.graphics.Color.WHITE)
        btnShare.setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))
        btnShare.layoutParams = LinearLayout.LayoutParams(0, dpToPx(44)).also {
            it.weight = 1f
            it.setMargins(dpToPx(6), 0, 0, 0)
        }
        btnShare.setOnClickListener { showSharePopup(markerId) }

        btnRow.addView(btnOk)
        btnRow.addView(btnShare)
        mainLayout.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(mainLayout)
            .create()
        btnOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.let { w ->
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            // Tinggi mengikuti konten (image + tombol) agar tidak ada space kosong di bawah.
            w.setLayout((screenWidth * 0.7f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showSharePopup(markerId: Int) {
        val items = listOf(
            Pair("WhatsApp", R.drawable.whatsapp),
            Pair("Instagram", R.drawable.instagram),
        )

        val gridView = GridView(this)
        gridView.numColumns = 2
        gridView.horizontalSpacing = dpToPx(8)
        gridView.verticalSpacing = dpToPx(8)
        gridView.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        gridView.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        gridView.columnWidth = dpToPx(80)
        gridView.setBackgroundColor(themeSurfaceColor())

        val gridAdapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(pos: Int) = items[pos]
            override fun getItemId(pos: Int) = pos.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val (title, iconRes) = items[pos]
                val v = convertView ?: run {
                    val ll = LinearLayout(this@AR_Activity)
                    ll.orientation = LinearLayout.VERTICAL
                    ll.gravity = android.view.Gravity.CENTER
                    ll.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                    ll.setBackgroundResource(android.R.drawable.list_selector_background)

                    val icon = ImageView(this@AR_Activity)
                    icon.layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER

                    val text = TextView(this@AR_Activity)
                    text.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    text.gravity = android.view.Gravity.CENTER
                    text.textSize = 12f
                    text.setTextColor(themeTextPrimaryColor())

                    ll.addView(icon)
                    ll.addView(text)
                    ll
                }

                v.findViewById<ImageView>(android.R.id.icon)?.setImageDrawable(
                    ContextCompat.getDrawable(this@AR_Activity, iconRes)
                )
                v.findViewById<TextView>(android.R.id.text1)?.text = title
                return v
            }
        }

        gridView.adapter = gridAdapter

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Share via")
            .setView(gridView)
            .create()

        gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val (title, _) = items[position]
            dialog.dismiss()
            shareMarkerImage(markerId, title)
        }

        dialog.show()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
            val popupWidth = (resources.displayMetrics.widthPixels * 0.6f).toInt()
            w.setLayout(popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun shareMarkerImage(markerId: Int, appName: String) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, "Marker_$markerId.png")
            if (!file.exists()) {
                Toast.makeText(this, "File marker tidak ditemukan", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val packageName = when (appName) {
                "WhatsApp" -> "com.whatsapp"
                "Instagram" -> "com.instagram.android"
                else -> null
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                packageName?.let { setPackage(it) }
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Fallback: share via chooser
                startActivity(Intent.createChooser(intent, "Share Marker"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===================== MARKER SCAN =====================

    /**
     * Set gambar referensi untuk dideteksi - mengambil frame kamera saat ini
     */
    private fun captureReferenceFromCamera() {
        val bmp = readPreviewBitmap() ?: return
        if (!openCVReady || orbDetector == null) return
        try {
            val refMat = Mat()
            Utils.bitmapToMat(bmp, refMat)
            val grayMat = Mat()
            Imgproc.cvtColor(refMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            val keypoints = MatOfKeyPoint()
            referenceDescriptors = Mat()
            orbDetector!!.detectAndCompute(grayMat, Mat(), keypoints, referenceDescriptors!!)

            android.util.Log.d(TAG, "Reference captured: ${keypoints.rows()} keypoints")

            refMat.release()
            grayMat.release()
            keypoints.release()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error capturing reference: ${e.message}")
        }
    }

    private fun startMarkerScanning() {
        if (!openCVReady || orbDetector == null || isMarkerScanning) return

        // Capture frame saat ini sebagai referensi
        captureReferenceFromCamera()
        if (referenceDescriptors == null) {
            Toast.makeText(this, "Gagal menangkap referensi", Toast.LENGTH_SHORT).show()
            return
        }

        isMarkerScanning = true
        scanThread = HandlerThread("Scan").apply { start() }
        scanHandler = Handler(scanThread!!.looper)

        scanHandler!!.post(object : Runnable {
            override fun run() {
                if (!isMarkerScanning) return
                try {
                    val bmp = readPreviewBitmap()
                    if (bmp != null) {
                        detectMarker(bmp)
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Scan loop: ${e.message}")
                }
                scanHandler?.postDelayed(this, DETECTION_INTERVAL_MS)
            }
        })

        showScanOverlay(true)
        Toast.makeText(this, "Marker Scan aktif - Arahkan kamera", Toast.LENGTH_SHORT).show()
    }

    private fun stopMarkerScanning() {
        isMarkerScanning = false
        scanHandler?.removeCallbacksAndMessages(null)
        scanThread?.quitSafely()
        scanHandler = null
        scanThread = null
        showScanOverlay(false)
        referenceDescriptors?.release()
        referenceDescriptors = null
        lastDetectedInfo = ""
    }

    /**
     * Deteksi marker dari frame kamera menggunakan feature matching
     */
    private fun detectMarker(frameBitmap: Bitmap) {
        val orb = orbDetector ?: return
        val refDesc = referenceDescriptors ?: return
        val matcher = descriptorMatcher ?: return

        try {
            val frameMat = Mat()
            Utils.bitmapToMat(frameBitmap, frameMat)
            val grayMat = Mat()
            Imgproc.cvtColor(frameMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            val frameKp = MatOfKeyPoint()
            val frameDesc = Mat()
            orb.detectAndCompute(grayMat, Mat(), frameKp, frameDesc)

            if (frameDesc.rows() < 4 || refDesc.rows() < 4) {
                frameMat.release(); grayMat.release(); frameKp.release(); frameDesc.release()
                return
            }

            val matches = MatOfDMatch()
            matcher.match(refDesc, frameDesc, matches)
            val matchList = matches.toList()

            if (matchList.isEmpty()) {
                frameMat.release(); grayMat.release(); frameKp.release(); frameDesc.release(); matches.release()
                return
            }

            val minDist = matchList.minOf { it.distance }
            val goodMatches = matchList.filter { it.distance <= maxOf(minDist * 3, 30f) }

            if (goodMatches.size >= 6) {
                val frameKpList = frameKp.toList()
                var sumX = 0f; var sumY = 0f
                var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
                var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE

                for (m in goodMatches) {
                    val idx = m.trainIdx
                    if (idx < frameKpList.size) {
                        val kp = frameKpList[idx]
                        val px = kp.pt.x.toFloat()
                        val py = kp.pt.y.toFloat()
                        sumX += px; sumY += py
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (py < minY) minY = py
                        if (py > maxY) maxY = py
                    }
                }

                val w = frameBitmap.width.toFloat()
                val h = frameBitmap.height.toFloat()
                val n = goodMatches.size.toFloat()
                val confidence = goodMatches.size.toFloat() / matchList.size.toFloat()

                val centerX = (sumX / n) / w
                val centerY = (sumY / n) / h
                val objWidth = (maxX - minX) / w

                runOnUiThread {
                    arRenderer?.setModelAnchor(centerX, centerY, objWidth.coerceAtLeast(0.05f))
                    arRenderer?.showAnchor()

                    val info = "Match: ${goodMatches.size}/${matchList.size} (${(confidence * 100).toInt()}%)"
                    if (info != lastDetectedInfo) {
                        lastDetectedInfo = info
                        Toast.makeText(this@AR_Activity, "Marker terdeteksi! $info", Toast.LENGTH_SHORT).show()
                    }
                }

                // Overlay visual
                try {
                    val overlayMat = frameMat.clone()
                    for (m in goodMatches) {
                        val idx = m.trainIdx
                        if (idx < frameKpList.size) {
                            val pt = frameKpList[idx].pt
                            Imgproc.circle(overlayMat, Point(pt.x, pt.y), 3, Scalar(0.0, 255.0, 0.0), -1)
                        }
                    }
                    // Gambar bounding box
                    Imgproc.rectangle(overlayMat,
                        Point(minX.toDouble(), minY.toDouble()),
                        Point(maxX.toDouble(), maxY.toDouble()),
                        Scalar(0.0, 255.0, 0.0), 2)
                    val overlayBmp = Bitmap.createBitmap(frameMat.cols(), frameMat.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(overlayMat, overlayBmp)
                    runOnUiThread { updateScanOverlay(overlayBmp) }
                    overlayMat.release()
                } catch (_: Exception) {}
            }

            frameMat.release(); grayMat.release(); frameKp.release(); frameDesc.release(); matches.release()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Detect error: ${e.message}")
        }
    }

    // ─── Scan Overlay ───

    private fun showScanOverlay(show: Boolean) {
        if (show) {
            if (markerOverlayView == null) {
                markerOverlayView = ImageView(this).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = 0.5f
                }
                (findViewById<ViewGroup>(android.R.id.content)).addView(markerOverlayView)
            }
        } else {
            markerOverlayView?.let {
                (findViewById<ViewGroup>(android.R.id.content)).removeView(it)
            }
            markerOverlayView = null
        }
    }

    private fun updateScanOverlay(bmp: Bitmap) {
        markerOverlayView?.setImageBitmap(bmp)
    }

    // ===================== TREE / PROJECT NAME =====================

    /**
     * Update tree dengan daftar node. Format: (name, depth).
     * Depth 1 = root, 2 = child, dst.
     * Setiap node memiliki garis vertikal biru di kiri sejajar dengan ProjectName.
     */
    private fun updateTreeView(nodeNames: List<Pair<String, Int>>) {
        treeNodeNames = nodeNames
        treeContainer.removeAllViews()

        if (nodeNames.isEmpty()) {
            treePanel.visibility = View.VISIBLE
            val emptyText = TextView(this)
            emptyText.text = "no objects"
            emptyText.setTextColor(themeTextSecondaryColor())
            emptyText.textSize = 15f
            emptyText.setPadding(dpToPx(16), dpToPx(6), dpToPx(8), dpToPx(6))
            emptyText.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC)
            emptyText.alpha = 0.7f
            treeContainer.addView(emptyText)
            return
        }

        val indentPx = dpToPx(16) // indentasi garis vertikal dari kiri

        for ((index, pair) in nodeNames.withIndex()) {
            val (name, depth) = pair
            if (depth == 0) continue

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Padding kiri = marginStart ProjectName (16dp) agar garis sejajar
            row.setPadding(dpToPx(16), 0, 0, 0)

            // Garis vertikal biru di kiri (sejajar dengan sisi kiri ProjectName)
            val lineWidth = dpToPx(3)
            val verticalLine = View(this)
            verticalLine.setBackgroundColor(lineColor)
            val lineLp = LinearLayout.LayoutParams(lineWidth, dpToPx(20))
            lineLp.bottomMargin = dpToPx(8)
            verticalLine.layoutParams = lineLp

            // Wrapper untuk konten setelah garis (highlight dimulai dari sini)
            val contentWrapper = LinearLayout(this)
            contentWrapper.orientation = LinearLayout.HORIZONTAL
            contentWrapper.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Background highlight jika terpilih
            if (selectedTreeIndex == index) {
                contentWrapper.setBackgroundColor(highlightAlphaColor)
            }

            // Spasi setelah garis (jarak dari garis ke text)
            val spaceAfterLine = dpToPx(8)
            val gap = View(this)
            val gapLp = LinearLayout.LayoutParams(spaceAfterLine, dpToPx(20))
            gapLp.bottomMargin = dpToPx(8)
            gap.layoutParams = gapLp

            // Indentasi tambahan berdasarkan depth (dari garis)
            val depthIndent = dpToPx((depth - 1) * 8)
            val depthSpacer = View(this)
            val spacerLp = LinearLayout.LayoutParams(depthIndent, dpToPx(20))
            spacerLp.bottomMargin = dpToPx(8)
            depthSpacer.layoutParams = spacerLp

            // Text label
            val item = TextView(this)
            item.text = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            item.textSize = 15f
            item.setPadding(dpToPx(2), dpToPx(1), dpToPx(8), dpToPx(1))
            item.ellipsize = android.text.TextUtils.TruncateAt.END
            item.maxLines = 1
            item.typeface = popupTypeface
            item.setTypeface(null, android.graphics.Typeface.BOLD)
            val defaultColor = themeTreeTextColor(depth)
            item.setTextColor(defaultColor)

                        // Jika selected, highlight warna teks orange
            if (selectedTreeIndex == index) {
                item.setTextColor(android.graphics.Color.WHITE)
            }

                        contentWrapper.addView(gap)
            contentWrapper.addView(depthSpacer)
            contentWrapper.addView(item)

            row.addView(verticalLine)
            row.addView(contentWrapper)

            val itemIndex = index
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { selectTreeItem(itemIndex) }

            treeContainer.addView(row)
        }

        treePanel.visibility = View.VISIBLE
    }

    private fun selectTreeItem(index: Int) {
        selectedTreeIndex = if (selectedTreeIndex == index) -1 else index
        updateTreeView(treeNodeNames)
        if (selectedTreeIndex >= 0 && selectedTreeIndex < treeNodeNames.size) {
            val (name, _) = treeNodeNames[selectedTreeIndex]
            Toast.makeText(this, "Selected: $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleTree() {
        treePanel.visibility = if (treePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (treePanel.visibility == View.VISIBLE)         updateTreeView(treeNodeNames)
    }

        private fun showRenameProjectDialog() {
        val input = EditText(this)
        input.setText(projectName)
        input.setSelection(projectName.length)
        input.textSize = 16f
        popupTypeface?.let { input.typeface = it }
        input.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        input.hint = "Enter project name"
        input.setTextColor(themeTextPrimaryColor())
        input.setHintTextColor(themeTextSecondaryColor())
        input.gravity = android.view.Gravity.CENTER

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        layout.setBackgroundColor(themeSurfaceColor())

        layout.addView(input)

        val btnLayout = LinearLayout(this)
        btnLayout.orientation = LinearLayout.HORIZONTAL
        btnLayout.gravity = android.view.Gravity.CENTER
        btnLayout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val btnCancel = com.google.android.material.button.MaterialButton(this)
        btnCancel.layoutParams = LinearLayout.LayoutParams(
            0, dpToPx(48)
        ).also { 
            it.weight = 1f
            it.setMargins(0, dpToPx(12), dpToPx(4), 0)
        }
        btnCancel.text = "Cancel"
        btnCancel.isAllCaps = false
        btnCancel.textSize = 16f
        btnCancel.setTypeface(null, android.graphics.Typeface.BOLD)
        popupTypeface?.let { btnCancel.typeface = it }
        btnCancel.cornerRadius = dpToPx(12)
        btnCancel.setTextColor(android.graphics.Color.WHITE)
        btnCancel.setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))

        val btnRename = com.google.android.material.button.MaterialButton(this)
        btnRename.layoutParams = LinearLayout.LayoutParams(
            0, dpToPx(48)
        ).also { 
            it.weight = 1f
            it.setMargins(0, dpToPx(12), 0, 0)
        }
        btnRename.text = "Rename"
        btnRename.isAllCaps = false
        btnRename.textSize = 16f
        btnRename.setTypeface(null, android.graphics.Typeface.BOLD)
        popupTypeface?.let { btnRename.typeface = it }
        btnRename.cornerRadius = dpToPx(12)
        btnRename.setTextColor(android.graphics.Color.WHITE)
        btnRename.setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))

        btnLayout.addView(btnCancel)
        btnLayout.addView(btnRename)
        layout.addView(btnLayout)

                val dialog = android.app.AlertDialog.Builder(this)
            .setView(layout)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnRename.setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty()) {
                projectName = newName
                findViewById<TextView>(R.id.txtProjectName)?.text = newName
                dialog.dismiss()
            }
        }

        dialog.show()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
            val popupWidth = (resources.displayMetrics.widthPixels * 0.80f).toInt()
            w.setLayout(popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        }

    // ===================== SAVE / NEW =====================

        private fun showSaveDialog() {
        saveArProject(isManual = true)
    }

    private fun saveArProject(isManual: Boolean = false) {
        // Save current AR scene as .dsar file
        try {
            if (isManual) hasManuallySaved = true
            val arDir = DeepspaceDirs.getArDir(this)
            val rawName = if (projectName.isBlank()) "AR_Scene_${System.currentTimeMillis()}" else projectName
            val fileName = (if (hasManuallySaved) rawName else "temp_$rawName") + ".dsar"
            val file = if (isManual) {
                DeepspaceDirs.uniqueFile(arDir, fileName)
            } else {
                File(arDir, fileName)
            }
            if (isManual) File(arDir, "temp_$rawName.dsar").delete()

            val objectName = treeNodeNames.firstOrNull()?.first
                ?: projectName.ifBlank { "AR Object" }
            val sourceType = when (currentModelExtension.lowercase()) {
                "dsform" -> DsArFormat.SOURCE_DSFORM
                "dspart" -> DsArFormat.SOURCE_DSPART
                "dsasm" -> DsArFormat.SOURCE_DSASM
                else -> DsArFormat.SOURCE_GLB
            }
            val objects = if (currentModelBytes != null) listOf(
                DsArFormat.ArObject(
                    name = objectName,
                    sourceType = sourceType,
                    sourcePath = currentModelSourcePath,
                    modelMatrix = arRenderer?.getSavedCameraMatrix() ?: FloatArray(16).also {
                        android.opengl.Matrix.setIdentityM(it, 0)
                    },
                    color = floatArrayOf(0.8f, 0.8f, 0.8f),
                    visible = true,
                    receiveShadow = true,
                    scale = 1.0f,
                    embeddedPayload = currentModelBytes?.copyOf()
                )
            ) else treeNodeNames.map { (name, _) ->
                DsArFormat.ArObject(
                    name = name,
                    sourceType = DsArFormat.SOURCE_GLB,
                    sourcePath = name,
                    modelMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) },
                    color = floatArrayOf(0.8f, 0.8f, 0.8f),
                    visible = true,
                    receiveShadow = true,
                    scale = 1.0f
                )
            }

            val cameraMatrix = arRenderer?.getSavedCameraMatrix()
                ?: FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
            val lights = arRenderer?.getSavedLights()?.map { light ->
                DsArFormat.ArLight(light.direction, light.color, light.intensity, light.castShadows)
            } ?: emptyList()

            val scene = DsArFormat.ArScene(
                projectName = projectName,
                useImageMarker = false,
                markerPath = "",
                markerWidth = 0.2f,
                objects = objects,
                cameraMatrix = cameraMatrix,
                lights = lights
            )

            DsArFormat.save(file, scene)
            if (isManual) Toast.makeText(this, "Saved: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (isManual) Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNewProjectDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("New Project")
            .setMessage("Start a new AR project? Current data will be cleared.")
            .setPositiveButton("Yes") { _, _ ->
                projectName = "New Project"
                arRenderer?.clearObjects()
                Toast.makeText(this, "New project", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===================== END OPENCV / TREE =====================

    /**
     * Item **Local** pada popup tombol "+" (btnDocument) membuka file manager bawaan Android
     * (DocumentsUI) langsung, memakai filter tampilan
     * [ImportBrowserActivity.SYSTEM_PICKER_MIME_TYPES].
     *
     * DocumentsUI hanya menyaring berbasis MIME (format seperti .dsform/.dspart/.dsrender/.ply
     * terbaca sebagai "application/octet-stream"), jadi ekstensinya diverifikasi ulang oleh
     * [ImportFileUtils] setelah file dipilih.
     */
    private fun launchFilePicker() {
        importLauncher.launch(ImportBrowserActivity.SYSTEM_PICKER_MIME_TYPES)
    }

    /**
     * File yang dipilih dari file manager Android disiapkan oleh [ImportFileUtils] — bila format
     * tidak didukung atau file tak terbaca, util-nya yang menampilkan Toast — lalu diumpankan ke
     * [loadModelBytes]. Jadi **jalur parser tidak berubah sama sekali**; yang berubah hanya cara
     * memilih file (file manager bawaan Android, bukan browser folder internal).
     */
    private fun importPickedFile(uri: Uri) {
        val file = ImportFileUtils.resolvePickedFile(this, uri) ?: return
        try {
            loadModelBytes(file.readBytes(), file.extension.lowercase())
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memuat ${file.name}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

                                                                private fun loadGLBModel(uri: Uri) {
            try {
                Toast.makeText(this, "Memuat model 3D...", Toast.LENGTH_SHORT).show()
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    val ext = uri.path?.substringAfterLast('.')?.lowercase() ?: "glb"
                    loadModelBytes(bytes, ext)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal memuat model: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Binary parser + muat model: .dspart / .dsasm / .dsform / .glb -> GLB
         * lalu tambahkan ke scene AR dan tree.
         */
        private fun loadModelBytes(bytes: ByteArray, ext: String) {
            try {
                val glb = DeepspaceBinaryParser.toGlb(bytes, ext)
                if (glb == null) {
                    Toast.makeText(this, ".$ext tidak berisi model 3D", Toast.LENGTH_LONG).show()
                    return
                }
                currentModelBytes = glb
                currentModelExtension = ext.lowercase().ifBlank { "glb" }
                currentModelSourcePath = "${projectName.ifBlank { "model" }}.$currentModelExtension"
                arRenderer?.addGlb(glb)

                // Parse node names dari GLB
                val nodeNames = parseGlbNodeNames(glb)
                // Cari root assembly name: node pertama depth=0 yang bukan kamera
                val rootName = nodeNames.firstOrNull { (name, depth) ->
                    depth == 0 && !name.contains("camera", true) && !name.contains("Camera", false)
                }?.first ?: nodeNames.firstOrNull { (_, depth) -> depth == 0 }?.first ?: "AR Scene"
                // .dsform: pakai parser dsform yang sudah ada supaya model & tree sama seperti halaman Polygon.
                val dsFormLoaded = if (ext.equals("dsform", true)) DsFormFormat.load(bytes) else null
                if (dsFormLoaded != null && dsFormLoaded.second.isNotEmpty()) {
                    val (dsProjectName, dsObjects) = dsFormLoaded
                    if (dsProjectName.isNotBlank()) {
                        projectName = dsProjectName
                        findViewById<TextView>(R.id.txtProjectName)?.text = projectName
                    }
                    val dsFormParts = mutableListOf<Pair<String, Int>>()
                    for (obj in dsObjects) {
                        dsFormParts.add(Pair(obj.name, 1))
                        obj.children?.forEach { child ->
                            dsFormParts.add(Pair("  ${child.name}", 2))
                        }
                    }
                    // Tambah semua part dari Polygon (jangan replace)
                    treeNodeNames = treeNodeNames + dsFormParts
                    updateTreeView(treeNodeNames)
                    treePanel.visibility = View.GONE
                    Toast.makeText(this, "Model 3D siap! $projectName", Toast.LENGTH_SHORT).show()
                    return
                }

                // Tambah komponen tree baru (jangan replace)
                treeNodeNames = treeNodeNames + listOf(rootName to 1)
                updateTreeView(treeNodeNames)
                treePanel.visibility = View.GONE

                Toast.makeText(this, "Model 3D siap! $rootName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal memuat model: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

                /**
                 * Parse glTF 2.0 binary (GLB) to extract node names hierarchy.
                 * Returns list of (name, depth) pairs representing the scene graph.
                 */
                private fun parseGlbNodeNames(glbBytes: ByteArray): List<Pair<String, Int>> {
                    try {
                        val buffer = java.nio.ByteBuffer.wrap(glbBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val magic = buffer.getInt()
                        if (magic != 0x46546C67) return emptyList()
                        val version = buffer.getInt()
                        if (version != 2) return emptyList()
                        val totalLen = buffer.getInt()

                        var jsonBytes: ByteArray? = null
                        while (buffer.remaining() >= 8) {
                            val chunkLen = buffer.getInt()
                            val chunkType = buffer.getInt()
                            val chunkData = ByteArray(chunkLen)
                            buffer.get(chunkData)
                            if (chunkType == 0x4E4F534A) {
                                var actualLen = chunkLen
                                for (i in chunkData.indices) {
                                    if (chunkData[i] == 0.toByte()) {
                                        actualLen = i
                                        break
                                    }
                                }
                                jsonBytes = chunkData.copyOf(actualLen)
                                break
                            }
                        }

                        if (jsonBytes == null) return emptyList()

                        val jsonStr = String(jsonBytes, Charsets.UTF_8)
                        val root = org.json.JSONObject(jsonStr)

                        val nodes = root.optJSONArray("nodes") ?: return emptyList()
                        val scene = root.optInt("scene", 0)
                        val scenes = root.optJSONArray("scenes")
                        val sceneNodes = mutableListOf<Int>()
                        if (scenes != null && scene < scenes.length()) {
                            val sceneObj = scenes.getJSONObject(scene)
                            val nodeIds = sceneObj.optJSONArray("nodes")
                            if (nodeIds != null) {
                                for (i in 0 until nodeIds.length()) {
                                    sceneNodes.add(nodeIds.getInt(i))
                                }
                            }
                        }

                        if (sceneNodes.isEmpty()) {
                            for (i in 0 until nodes.length()) sceneNodes.add(i)
                        }

                        val result = mutableListOf<Pair<String, Int>>()
                        val queue = ArrayDeque<Pair<Int, Int>>()
                        for (rootIdx in sceneNodes) {
                            queue.addLast(Pair(rootIdx, 0))
                        }

                        while (queue.isNotEmpty()) {
                            val (nodeIdx, depth) = queue.removeFirst()
                            val nodeObj = nodes.getJSONObject(nodeIdx)
                            val name = nodeObj.optString("name", "").ifEmpty { "Node_$nodeIdx" }
                            result.add(Pair(name, depth))
                            val children = nodeObj.optJSONArray("children")
                            if (children != null) {
                                for (i in 0 until children.length()) {
                                    queue.addLast(Pair(children.getInt(i), depth + 1))
                                }
                            }
                        }
                        return result
                    } catch (e: Exception) {
                        return emptyList()
                    }
                }

    private data class ArMenuItem(val id: Int, val title: String, val iconRes: Int)

        private fun showArMenuPopup() {
                    val items = run {
              // Label & icon Hide/Show bergantian sesuai status model yang dipilih di Tree.
              val selectedHidden = selectedTreeIndex in treeNodeNames.indices &&
                  (arRenderer?.isModelHidden(selectedTreeIndex) ?: false)
              listOf(
                  ArMenuItem(4, if (selectedHidden) "Show" else "Hide", if (selectedHidden) R.drawable.icon_show else R.drawable.icon_hide),
                  ArMenuItem(1, "Lighting", R.drawable.icon_lighting),
                  ArMenuItem(2, "Delete", R.drawable.icon_delete),
                  ArMenuItem(3, "Marker Create", R.drawable.icon_marker_create),
                  ArMenuItem(5, "Marker Scan", R.drawable.icon_marker),
                  ArMenuItem(6, "Capture", R.drawable.icon_capture),
              )
          }
  
          val textColor = themeTextPrimaryColor()
  
                    val gridView = GridView(this)
                    gridView.numColumns = 3
          gridView.horizontalSpacing = dpToPx(10)
          gridView.verticalSpacing = dpToPx(15)
          gridView.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(15))
          gridView.stretchMode = GridView.STRETCH_COLUMN_WIDTH
                    gridView.columnWidth = dpToPx(80)
          gridView.setBackgroundColor(themeSurfaceColor())
  
          val gridAdapter = object : BaseAdapter() {
              override fun getCount() = items.size
              override fun getItem(pos: Int) = items[pos]
              override fun getItemId(pos: Int) = pos.toLong()
  
              override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                  val item = items[pos]
                  val v = convertView ?: run {
                      val ll = LinearLayout(this@AR_Activity)
                      ll.orientation = LinearLayout.VERTICAL
                      ll.gravity = android.view.Gravity.CENTER
                      ll.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                      ll.setBackgroundResource(android.R.drawable.list_selector_background)
  
                      val icon = ImageView(this@AR_Activity)
                      icon.id = android.R.id.icon
                      icon.layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
                      icon.scaleType = ImageView.ScaleType.FIT_CENTER
  
                      val text = TextView(this@AR_Activity)
                      text.id = android.R.id.text1
                      text.layoutParams = LinearLayout.LayoutParams(
                          LinearLayout.LayoutParams.WRAP_CONTENT,
                          LinearLayout.LayoutParams.WRAP_CONTENT
                      )
                                            text.gravity = android.view.Gravity.CENTER
                                            text.textSize = 11f
                                            text.setTextColor(textColor)
                                            popupTypeface?.let { text.typeface = it }
                                            text.maxLines = 1
                                            text.ellipsize = null
                                            text.isSingleLine = true

                                            ll.addView(icon)
                                            ll.addView(text)
                                            ll
                  }

                  v.findViewById<ImageView>(android.R.id.icon)?.let { iv ->
                      iv.setImageDrawable(ContextCompat.getDrawable(this@AR_Activity, item.iconRes))
                      applyMenuIconTint(iv)
                  }
                  v.findViewById<TextView>(android.R.id.text1)?.text = item.title

                  // Highlight Hide/Show item (id 4) jika ada model yang disembunyikan — tidak perlu highlight khusus.
                  return v
              }
          }

          gridView.adapter = gridAdapter

          val dialog = android.app.AlertDialog.Builder(this)
              .setTitle("")
              .setView(gridView)
              .create()

          gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
              val selected = items[position]
              dialog.dismiss()
              when (selected.id) {
                  1 -> toggleLightingPanel()
                  2 -> {
                      if (selectedTreeIndex >= 0 && selectedTreeIndex < treeNodeNames.size) {
                          val (name, _) = treeNodeNames[selectedTreeIndex]
                          arRenderer?.removeModelAt(selectedTreeIndex)
                          treeNodeNames = treeNodeNames.toMutableList().apply { removeAt(selectedTreeIndex) }
                          selectedTreeIndex = -1
                          updateTreeView(treeNodeNames)
                          Toast.makeText(this, "$name dihapus", Toast.LENGTH_SHORT).show()
                      } else {
                          Toast.makeText(this, "Pilih komponen di Tree dulu", Toast.LENGTH_SHORT).show()
                      }
                  }
                3 -> generateAndSaveMarker()
                4 -> {
                    // Hide/Show: toggle visibilitas part/object yang dipilih di Tree.
                    if (selectedTreeIndex >= 0 && selectedTreeIndex < treeNodeNames.size) {
                        val (name, _) = treeNodeNames[selectedTreeIndex]
                        val hidden = arRenderer?.toggleVisibilityAt(selectedTreeIndex) ?: false
                        val msg = if (hidden) "$name disembunyikan" else "$name ditampilkan"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Pilih komponen di Tree dulu", Toast.LENGTH_SHORT).show()
                    }
                }
                5 -> {
                    if (isMarkerScanning) {
                        stopMarkerScanning()
                        Toast.makeText(this, "Marker Scan dimatikan", Toast.LENGTH_SHORT).show()
                    } else {
                        startMarkerScanning()
                    }
                }
                6 -> showCapturePopup()
            }
        }

                dialog.show()
        dialog.window?.let { window ->
                        window.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
                        val popupWidth = dpToPx(280)
                        window.setLayout(popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

        /**
         * Popup tombol "+" (btnDocument) halaman AR — layout **dua baris dua kolom (2×2)**, urut
         * baris pertama **Local, Explore** lalu baris kedua **Orientation, Setting**:
         *  - **Local** ([R.drawable.icon_local], id 302): membuka **file manager bawaan Android
         *    (DocumentsUI)** lewat [launchFilePicker] — bukan browser folder internal. URI
         *    terpilih diubah jadi file oleh [ImportFileUtils],
         *    lalu diparsing lewat [importPickedFile] → [loadModelBytes], jadi seluruh jalur parser
         *    yang sudah ada tetap dipakai.
         *  - **Explore** ([R.drawable.icon_explore], id 304): pindah ke halaman pustaka Explore.
         *  - **Orientation** ([R.drawable.icon_display_orientation], id 303): memutar halaman AR
         *    portrait/landscape + menyimpan pref agar sinkron dengan dropdown di Setting.
         *  - **Setting** ([R.drawable.icon_setting], id 305): pindah ke halaman Settings
         *    ([Settings_Activity]). Halaman AR otomatis
         *    di-pause (kamera di-bind ke lifecycle) dan aktif lagi saat kembali.
         *
         * Label Local/Explore/Setting memakai string resource supaya ikut bahasa aplikasi
         * (en: "Local / Explore / Setting", id: "Lokal / Jelajahi / Pengaturan").
         */
        private fun showDocumentMenuPopup() {
            val items = listOf(
                // Baris 1: Local, Explore
                ArMenuItem(302, getString(R.string.local), R.drawable.icon_local),
                ArMenuItem(304, getString(R.string.explore), R.drawable.icon_explore),
                // Baris 2: Orientation, Setting
                ArMenuItem(303, "Orientation", R.drawable.icon_display_orientation),
                ArMenuItem(305, getString(R.string.drawer_setting), R.drawable.icon_setting),
            )

            val textColor = themeTextPrimaryColor()

            val gridView = GridView(this)
            // Dua kolom: dua baris berisi Local | Explore (baris 1)
            // dan Orientation | Setting (baris 2).
            gridView.numColumns = 2
            gridView.horizontalSpacing = 10 // jarak antar item 10px
            gridView.verticalSpacing = dpToPx(6)
            gridView.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(8))
            gridView.stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gridView.columnWidth = dpToPx(80)
            gridView.setBackgroundColor(themeSurfaceColor())

            val gridAdapter = object : BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(pos: Int) = items[pos]
                override fun getItemId(pos: Int) = pos.toLong()

                override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                    val item = items[pos]
                    val v = convertView ?: run {
                        val ll = LinearLayout(this@AR_Activity)
                        ll.orientation = LinearLayout.VERTICAL
                        ll.gravity = android.view.Gravity.CENTER
                        ll.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                        ll.setBackgroundResource(android.R.drawable.list_selector_background)

                        val icon = ImageView(this@AR_Activity)
                        icon.id = android.R.id.icon
                        icon.layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
                        icon.scaleType = ImageView.ScaleType.FIT_CENTER

                        val text = TextView(this@AR_Activity)
                        text.id = android.R.id.text1
                        text.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        text.gravity = android.view.Gravity.CENTER
                        text.textSize = 11f
                        text.setTextColor(textColor)
                        popupTypeface?.let { text.typeface = it }
                        text.maxLines = 1

                        ll.addView(icon)
                        ll.addView(text)
                        ll
                    }

                    v.findViewById<ImageView>(android.R.id.icon)?.let { iv ->
                        iv.setImageDrawable(ContextCompat.getDrawable(this@AR_Activity, item.iconRes))
                        applyMenuIconTint(iv)
                    }
                    v.findViewById<TextView>(android.R.id.text1)?.text = item.title
                    return v
                }
            }

            gridView.adapter = gridAdapter

            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("")
                .setView(gridView)
                .create()

            gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val selected = items[position]
                dialog.dismiss()
                when (selected.id) {
                    // Local: file manager bawaan Android (DocumentsUI) langsung, lalu
                    // parser lama (loadModelBytes) memproses file terpilih.
                    302 -> launchFilePicker()
                    // Explore: pindah ke halaman pustaka Explore. Halaman AR otomatis
                    // di-pause (kamera di-bind ke lifecycle) dan aktif lagi saat kembali.
                    304 -> startActivity(Intent(this@AR_Activity, Explore_Activity::class.java))
                    303 -> {
                        // Rotasi halaman AR + simpan pref supaya dropdown
                        // *Orientation Mode (AR)* di Setting tetap sinkron.
                        val isLandscape = resources.configuration.orientation ==
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        setArOrientation(
                            if (isLandscape) ORIENTATION_PORTRAIT else ORIENTATION_LANDSCAPE)
                    }
                    // Setting: pindah ke halaman Settings. Halaman AR otomatis di-pause
                    // (kamera di-bind ke lifecycle) dan aktif lagi saat kembali.
                    305 -> startActivity(Intent(this@AR_Activity, Settings_Activity::class.java))
                }
            }

            dialog.show()
            dialog.window?.let { window ->
                window.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
                val popupWidth = dpToPx(280)
                window.setLayout(popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

    /**
     * Panel **Lighting** halaman AR (item Lighting pada menu AR) — pengganti popup Lighting
     * lama yang memakai empat slider. Susunannya sekarang:
     *  - **1 slider** ([lightingSlider]) yang mengatur parameter tombol sumbu yang aktif,
     *  - tombol lingkaran biru bertulisan putih **x / y / z** untuk pergeseran arah cahaya, dan
     *  - tombol **b** untuk tingkat **brightness**.
     *
     * Tombol aktif ditandai warna orange dan nilai sumbu aktif ditampilkan pada
     * [lightingValueLabel], jadi satu slider cukup untuk keempat parameter.
     */
    private fun setupLightingPanel() {
        lightingPanel = findViewById(R.id.lightingPanel)
        lightingSlider = findViewById(R.id.lightingSlider)
        lightingValueLabel = findViewById(R.id.txtLightingValue)
        lightingAxisButtons = listOf(
            R.id.btnLightAxisX,
            R.id.btnLightAxisY,
            R.id.btnLightAxisZ,
            R.id.btnLightAxisB
        ).map { findViewById<Button>(it) }

        // Warna slider memakai aksen biru (sama dengan tombol sumbu), bukan hitam, supaya
        // tetap terbaca pada panel yang latarnya mengikuti dark/light mode.
        lightingSlider.thumbTintList = android.content.res.ColorStateList.valueOf(lightingButtonColor)
        lightingSlider.progressTintList = android.content.res.ColorStateList.valueOf(lightingButtonColor)
        lightingSlider.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.argb(77, 61, 147, 200))

        lightingAxisButtons.forEachIndexed { axis, button ->
            button.setOnClickListener { selectLightingAxis(axis) }
        }
        lightingSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyLightingProgress(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        selectLightingAxis(LIGHT_AXIS_X)
    }

    /**
     * Buka/tutup Panel Lighting. Saat dibuka, slider disegarkan ke sumbu yang terakhir dipakai
     * supaya nilai pada panel selalu sesuai kondisi cahaya sekarang.
     */
    private fun toggleLightingPanel() {
        if (lightingPanel.visibility == View.VISIBLE) {
            lightingPanel.visibility = View.GONE
            return
        }
        selectLightingAxis(lightingAxis)
        lightingPanel.visibility = View.VISIBLE
    }

    /** Pilih parameter yang diatur slider: x / y / z (arah cahaya) atau b (brightness). */
    private fun selectLightingAxis(axis: Int) {
        lightingAxis = axis
        lightingAxisButtons.forEachIndexed { index, button ->
            val active = index == axis
            button.isSelected = active
            button.setTextColor(android.graphics.Color.WHITE)
            // backgroundTintList (bukan setBackgroundColor) supaya bentuk MaterialShapeDrawable
            // tetap bulat sesuai app:cornerRadius pada layout.
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (active) lightingButtonActiveColor else lightingButtonColor)
        }
        lightingSlider.progress = lightingProgressFor(axis)
        updateLightingValueLabel()
    }

    /** Nilai slider (0..100) yang mewakili state sumbu [axis] sekarang. */
    private fun lightingProgressFor(axis: Int): Int =
        if (axis == LIGHT_AXIS_BRIGHTNESS) Math.round(lightIntensityScale * 100f)
        // Arah cahaya: -1..1 dipetakan ke 0..100.
        else Math.round((lightDirection[axis] + 1f) / 2f * 100f)

    /** Terapkan nilai slider (0..100) ke sumbu aktif lalu perbarui cahaya di renderer. */
    private fun applyLightingProgress(progress: Int) {
        if (lightingAxis == LIGHT_AXIS_BRIGHTNESS) {
            // Brightness: skala intensitas (0 = padam, 100 = default seperti cahaya awal).
            lightIntensityScale = progress / 100f
            arRenderer?.setLightIntensityScale(lightIntensityScale)
        } else {
            lightDirection[lightingAxis] = (progress / 100f) * 2f - 1f
            arRenderer?.setLightDirection(
                lightDirection[LIGHT_AXIS_X],
                lightDirection[LIGHT_AXIS_Y],
                lightDirection[LIGHT_AXIS_Z]
            )
        }
        updateLightingValueLabel()
    }

    /** Nilai sumbu aktif pada header panel, mis. `x +0.50` atau `b 100%`. */
    private fun updateLightingValueLabel() {
        val name = lightingAxisButtons.getOrNull(lightingAxis)?.text?.toString() ?: ""
        val value = if (lightingAxis == LIGHT_AXIS_BRIGHTNESS)
            "${Math.round(lightIntensityScale * 100f)}%"
        else String.format(java.util.Locale.US, "%+.2f", lightDirection[lightingAxis])
        lightingValueLabel.text = "$name $value"
    }

    /**
     * Popup **Capture** halaman AR (item Capture pada popup menu tombol kiri atas):
     *  - **Browse**: membuka file manager bawaan Android (SAF) untuk memilih folder tujuan.
     *    Setelah folder dipilih, label tombol ini **diganti menjadi path folder** tersebut,
     *    mis. `/storage/emulated/0/Download` (lihat [captureFolderPickerLauncher]).
     *  - **Cancel**: menutup popup tanpa mengambil gambar.
     *  - **Capture**: mengambil snapshot layar AR ([captureCurrentView]) lalu menyimpannya ke
     *    folder tujuan; bila pengguna belum memilih folder, hasilnya tetap masuk folder default
     *    `DEEPSPACE/Capture` ([DeepspaceDirs.getCaptureDir]) seperti perilaku sebelumnya.
     *
     * Menulis ke folder pilihan memakai DocumentsContract (SAF), jadi **tidak butuh izin
     * penyimpanan** — pola yang sama dengan popup Download di halaman Explore.
     */
    private fun showCapturePopup() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(8))
        }

        layout.addView(TextView(this).apply {
            text = getString(R.string.capture_title)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(themeTextPrimaryColor())
            popupTypeface?.let { typeface = it }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(16) }
        })

        // Tombol Browse: labelnya "Browse" selama belum ada folder tujuan, lalu berganti jadi
        // path folder begitu pengguna memilihnya.
        val btnBrowse = com.google.android.material.button.MaterialButton(this).apply {
            text = treeFolderPathText(captureFolderUri)
            isAllCaps = false
            textSize = 13f
            // Path folder bisa panjang: izinkan dua baris, sisa yang masih kelebihan dipotong di
            // tengah supaya nama folder di ujungnya tetap terbaca.
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTypeface(null, android.graphics.Typeface.BOLD)
            popupTypeface?.let { typeface = it }
            cornerRadius = dpToPx(12)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            )
            setOnClickListener { captureFolderPickerLauncher.launch(null) }
        }
        layout.addView(btnBrowse)
        captureFolderButton = btnBrowse

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun actionButton(label: String) =
            com.google.android.material.button.MaterialButton(this).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                popupTypeface?.let { typeface = it }
                cornerRadius = dpToPx(12)
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#3D93C8"))
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(48)).also {
                    it.weight = 1f
                    it.setMargins(dpToPx(6), dpToPx(16), dpToPx(6), dpToPx(12))
                }
            }

        val btnCancel = actionButton(getString(R.string.cancel))
        val btnCapture = actionButton(getString(R.string.capture_confirm))
        btnRow.addView(btnCancel)
        btnRow.addView(btnCapture)
        layout.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(layout)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnCapture.setOnClickListener {
            dialog.dismiss()
            // Folder tujuan dikunci saat tombol Capture ditekan (null = folder default
            // DEEPSPACE/Capture) supaya hasil tidak berpindah bila pilihan folder diubah
            // selagi proses simpan berjalan.
            captureCurrentView(captureFolderUri)
        }
        // Tombol Browse tidak lagi dipegang setelah popup tertutup.
        dialog.setOnDismissListener { captureFolderButton = null }

        dialog.show()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(R.drawable.bg_render_menu_popup)
            // Sedikit lebih lebar dari popup menu supaya path folder panjang tetap terbaca.
            w.setLayout(
                (resources.displayMetrics.widthPixels * 0.6f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * Ambil snapshot layar AR (preview kamera + surface Filament) lalu simpan sebagai PNG
     * ber-watermark.
     *
     * @param targetFolder folder tujuan pilihan pengguna (tree URI SAF dari popup Capture),
     *        atau null untuk memakai folder default `DEEPSPACE/Capture`.
     */
    private fun captureCurrentView(targetFolder: Uri?) {
        showCaptureProgress()
        try {
            val w = previewView.width
            val h = previewView.height
            if (w <= 0 || h <= 0) {
                hideCaptureProgress()
                Toast.makeText(this, "Capture failed: view belum siap", Toast.LENGTH_SHORT).show()
                return
            }

            val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(composite)
            readPreviewBitmap()?.let { canvas.drawBitmap(it, null, android.graphics.Rect(0, 0, w, h), null) }

            val surfaceBmp = Bitmap.createBitmap(
                filamentSurface.width.coerceAtLeast(1),
                filamentSurface.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            android.view.PixelCopy.request(filamentSurface, surfaceBmp, { result ->
                updateCaptureProgress(75)
                if (result == android.view.PixelCopy.SUCCESS) {
                    canvas.drawBitmap(surfaceBmp, null, android.graphics.Rect(0, 0, w, h), null)
                }
                saveCaptureBitmap(composite, targetFolder)
            }, Handler(mainLooper))
        } catch (e: Exception) {
            hideCaptureProgress()
            Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

        /**
         * Tambahkan watermark DEEPSPACE lalu tulis hasil capture sebagai PNG ke folder tujuan:
         * folder pilihan pengguna lewat SAF ([saveBitmapToTreeFolder]) bila [targetFolder] ada,
         * atau folder default `DEEPSPACE/Capture` ([DeepspaceDirs.getCaptureDir]).
         */
        private fun saveCaptureBitmap(bitmap: Bitmap, targetFolder: Uri?) {
        updateCaptureProgress(85)
        Thread {
            try {
            // Add watermark
            val w = bitmap.width
            val h = bitmap.height
            val watermarked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(watermarked)
            canvas.drawBitmap(bitmap, 0f, 0f, null)

            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 36f
                isAntiAlias = true
                setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }

            val watermarkText = "Made by DEEPSPACE"
            val x = w / 2f
            val y = h - 40f
            canvas.drawText(watermarkText, x, y, paint)

            val fileName = "AR_Capture_${System.currentTimeMillis()}.png"
            // Folder tujuan: folder pilihan pengguna lewat popup Capture (SAF) bila ada,
            // kalau tidak folder default DEEPSPACE/Capture.
            val savedName = if (targetFolder != null) {
                saveBitmapToTreeFolder(targetFolder, fileName, watermarked)
            } else {
                val capturesDir = DeepspaceDirs.getCaptureDir(this)
                val resultFile = File(capturesDir, fileName)
                FileOutputStream(resultFile).use { out ->
                    watermarked.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                resultFile.name
            }
            watermarked.recycle()
            runOnUiThread {
                updateCaptureProgress(100)
                window.decorView.postDelayed({ hideCaptureProgress() }, 350)
                if (savedName != null) {
                    Toast.makeText(
                        this,
                        getString(R.string.capture_saved_file, savedName),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.capture_folder_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                hideCaptureProgress()
                Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        }.start()
    }

    /**
     * Teks tombol Browse pada popup Capture: label [R.string.capture_browse] ("Browse") bila
     * folder belum dipilih, atau **path folder** hasil pilihan pengguna
     * (mis. `/storage/emulated/0/Download/Captures`).
     *
     * Tree URI SAF tidak menyimpan path absolut, jadi volume `primary` dipetakan ke folder
     * penyimpanan utama ([Environment.getExternalStorageDirectory]) dan volume lain ke
     * `/storage/<volume>`.
     */
    private fun treeFolderPathText(treeUri: Uri?): String {
        if (treeUri == null) return getString(R.string.capture_browse)

        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            null
        }
        if (docId.isNullOrBlank()) return treeUri.toString()

        // docId berbentuk "<volume>:<path relatif>", mis. "primary:Download/Captures".
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "").trim('/')
        val path = when (volume) {
            "primary" -> "${Environment.getExternalStorageDirectory().absolutePath}/$relative"
            "" -> relative
            else -> "/storage/$volume/$relative"
        }
        // Akar penyimpanan ("primary:") menghasilkan path tanpa garis miring di ujung.
        return path.trimEnd('/').ifBlank { treeUri.toString() }
    }

    /**
     * Tulis [bitmap] sebagai berkas PNG [fileName] di dalam folder [treeUri] hasil pemilih folder
     * popup Capture. Memakai DocumentsContract (SAF), jadi tidak ada izin penyimpanan yang
     * dibutuhkan.
     *
     * @return nama berkas yang benar-benar dipakai (bisa berbeda bila nama sudah terpakai),
     *         atau null bila berkas gagal dibuat/ditulis.
     */
    private fun saveBitmapToTreeFolder(treeUri: Uri, fileName: String, bitmap: Bitmap): String? {
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )

            val name = uniqueTreeChildName(treeUri, fileName)
            val firstTry = createTreeDocument(parent, name)
            val document: Uri
            val savedName: String
            if (firstTry != null) {
                document = firstTry
                savedName = name
            } else {
                // Nama asli ditolak provider → pakai nama ber-cap waktu agar tetap tersimpan.
                savedName = timestampedFileName(fileName)
                document = createTreeDocument(parent, savedName) ?: return null
            }

            contentResolver.openOutputStream(document)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            } ?: return null
            savedName
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Capture gagal disimpan ke folder tujuan", e)
            null
        }
    }

    /** Buat dokumen PNG baru di folder [parent]; null bila provider menolak (mis. nama terpakai). */
    private fun createTreeDocument(parent: Uri, name: String): Uri? = try {
        DocumentsContract.createDocument(contentResolver, parent, "image/png", name)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "createDocument gagal: $name", e)
        null
    }

    /**
     * Nama berkas unik di dalam folder [treeUri]: bila sudah dipakai, tambahkan " (2)", " (3)",
     * dst. sebelum ekstensi (pola sama dengan [DeepspaceDirs.uniqueFile] untuk folder biasa).
     */
    private fun uniqueTreeChildName(treeUri: Uri, fileName: String): String {
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
            android.util.Log.w(TAG, "Tidak bisa membaca isi folder tujuan capture", e)
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
    private fun timestampedFileName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    private fun showCaptureProgress() {
        if (captureProgressView == null) {
            val overlay = CaptureProgressView(this)
            overlay.setBackgroundColor(android.graphics.Color.argb(100, 0, 0, 0))
            overlay.isClickable = true
            overlay.elevation = 1000f
            overlay.translationZ = 1000f
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            root.addView(overlay, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            overlay.bringToFront()
            captureProgressView = overlay
        }
        captureProgressView?.visibility = View.VISIBLE
        captureProgressView?.bringToFront()
        updateCaptureProgress(10)
        captureProgressAnimator?.cancel()
        captureProgressAnimator = ValueAnimator.ofInt(10, 65).apply {
            duration = 900
            addUpdateListener { updateCaptureProgress(it.animatedValue as Int) }
            start()
        }
    }

    private fun updateCaptureProgress(value: Int) {
        captureProgressView?.progress = value.coerceIn(0, 100)
    }

    private fun hideCaptureProgress() {
        captureProgressAnimator?.cancel()
        captureProgressAnimator = null
        captureProgressView?.visibility = View.GONE
    }

    private class CaptureProgressView(context: android.content.Context) : View(context) {
        var progress: Int = 0
            set(value) { field = value; invalidate() }
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 10f
            color = android.graphics.Color.argb(100, 255, 255, 255)
        }
        private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = android.graphics.Color.parseColor("#3D93C8")
        }
        private val whiteBackground = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.WHITE
            setShadowLayer(18f, 0f, 4f, android.graphics.Color.argb(160, 0, 0, 0))
        }
        private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 38f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val radius = (width.coerceAtMost(height) * 0.12f).coerceAtLeast(48f)
            val cx = width / 2f
            val cy = height / 2f
            val bounds = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawCircle(cx, cy, radius + 18f, whiteBackground)
            canvas.drawCircle(cx, cy, radius, track)
            canvas.drawArc(bounds, -90f, progress * 3.6f, false, arc)
            canvas.drawText("$progress%", cx, cy - (label.ascent() + label.descent()) / 2f, label)
        }
    }

    private fun dpToPx(valueDp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            valueDp.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }
}
