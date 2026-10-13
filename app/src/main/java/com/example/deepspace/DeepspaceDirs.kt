package com.example.deepspace

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Centralized directory structure for DEEPSPACE app.
 *
 * Folder structure on device storage:
 * DEEPSPACE/
 * ├── AR/
 * ├── General/
 * │   ├── Sticker/
 * │   └── Markers/
 * ├── Explore/
 * ├── Export/
 * ├── Import/
 * └── Capture/
 */
object DeepspaceDirs {

    private const val ROOT_NAME = "DEEPSPACE"

        // ─── Root directory ───
        fun getRootDir(ctx: Context): File {
            val dir = File(ctx.getExternalFilesDir(null), ROOT_NAME)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    // ─── Export ───
    fun getExportDir(ctx: Context): File {
        val dir = File(getRootDir(ctx), "Export")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─── Import ───
    /**
     * Folder pustaka Import: SATU-SATUNYA folder yang menyimpan file hasil import.
     * Halaman proyek tidak menyalin ulang model ke sini; folder ini diisi lewat tab
     * Import di halaman Project.
     */
    fun getImportDir(ctx: Context): File {
        val dir = File(getRootDir(ctx), "Import")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Cache sementara untuk file yang hanya perlu wujud fisik sesaat, mis. bytes yang
     * perlu punya path nyata agar bisa dibuka parser/mesh importer. Ditulis di cache aplikasi — BUKAN di
     * folder Import — supaya import tidak meninggalkan salinan ganda di library Import.
     *
     * Isi yang lebih dari satu jam (sisa sesi sebelumnya) dibersihkan otomatis agar
     * cache tidak menumpuk.
     */
    fun getImportCacheDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, "import_pick")
        if (!dir.exists()) dir.mkdirs()
        val expiry = System.currentTimeMillis() - 60L * 60L * 1000L
        dir.listFiles()?.forEach { old ->
            if (old.lastModified() < expiry) {
                try { old.delete() } catch (_: Exception) { }
            }
        }
        return dir
    }

    // ─── Capture ───
    fun getCaptureDir(ctx: Context): File {
        val dir = File(getRootDir(ctx), "Capture")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─── AR ───
    fun getArDir(ctx: Context): File {
        val dir = File(getRootDir(ctx), "AR")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─── General ───
    /**
     * Folder umum lintas halaman. Semua aset yang tidak terikat satu halaman saja
     * (sticker decal, marker AR) disimpan di sini supaya root DEEPSPACE tetap rapi.
     */
    fun getGeneralDir(ctx: Context): File {
        val dir = File(getRootDir(ctx), "General")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─── Explore ───
    /** Folder pustaka Explore (katalog model). */
    fun getExploreDir(ctx: Context): File {
        val dir = File(getRootDir(ctx), "Explore")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─── Sticker (decal) ───
    /**
     * Gambar sticker disalin ke sini saat diimpor. Sengaja tidak menyimpan URI dari
     * file picker Android: izin bacanya bisa kedaluwarsa setelah proses aplikasi mati.
     *
     * Berlokasi di `DEEPSPACE/General/Sticker`.
     */
    fun getStickerDir(ctx: Context): File {
        val dir = File(getGeneralDir(ctx), "Sticker")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─── Markers ───
    /** Marker AR. Berlokasi di `DEEPSPACE/General/Markers`. */
    fun getMarkerDir(ctx: Context): File {
        val dir = File(getGeneralDir(ctx), "Markers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Pindahkan isi folder lama di root DEEPSPACE (mis. "Sticker" / "Markers" yang dulu
     * berada langsung di bawah root) ke lokasi barunya di dalam [target]. Dipanggil sekali
     * saat startup supaya aset yang sudah tersimpan di perangkat lama tidak hilang setelah
     * struktur folder berubah.
     *
     * File yang gagal dipindah dibiarkan di tempat lama (tidak ada data yang dihapus),
     * dan folder lama hanya dihapus bila sudah benar-benar kosong.
     */
    fun migrateLegacyInto(ctx: Context, legacyNames: List<String>, target: File) {
        for (name in legacyNames) {
            val legacy = File(getRootDir(ctx), name)
            if (!legacy.isDirectory) continue
            // Folder target berada di dalam root, jadi jangan pernah memindah ke dirinya sendiri.
            if (legacy.absolutePath == target.absolutePath) continue
            val children = legacy.listFiles() ?: continue
            for (child in children) {
                // Sub-folder dilewati: struktur lama tidak punya sub-folder, dan memindah
                // direktori berisiko setengah jalan bila gagal.
                if (child.isDirectory) continue
                val dest = uniqueFile(target, child.name)
                val moved = try {
                    child.renameTo(dest)
                } catch (_: Exception) {
                    false
                }
                if (!moved) {
                    try {
                        dest.writeBytes(child.readBytes())
                        child.delete()
                    } catch (_: Exception) {
                        // Gagal pindah: biarkan file lama apa adanya.
                    }
                }
            }
            if (legacy.listFiles()?.isEmpty() == true) legacy.delete()
        }
    }

    /**
     * Pastikan nama file unik dalam [dir]. Bila file dengan [fileName] sudah ada,
     * tambahkan "_2", "_3", dst. di akhir nama dasar (sebelum ekstensi).
     * Contoh: "Part.dspart" -> "Part_2.dspart", "Part_3.dspart", dst.
     */
    fun uniqueFile(dir: File, fileName: String): File {
        val candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 2
        while (true) {
            val next = File(dir, "${base}_$i$ext")
            if (!next.exists()) return next
            i++
        }
    }
}

