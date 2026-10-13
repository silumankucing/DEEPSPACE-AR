package com.example.deepspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/**
 * Penolong tunggal untuk file yang dipilih dari file manager bawaan Android (DocumentsUI).
 *
 * Tombol "+" di halaman AR, browser internal [ImportBrowserActivity], dan jalur
 * import lain sama-sama menerima URI `content://` dari DocumentsUI. URI itu belum tentu punya
 * file fisik (mis. Google Drive), jadi perubahan URI menjadi [File] yang bisa dibaca parser
 * dikumpulkan di satu tempat:
 *  - path asli dipakai apa adanya bila penyedianya menyimpan file nyata (tanpa salinan), atau
 *  - isinya disalin ke cache aplikasi ([DeepspaceDirs.getImportCacheDir]) bila tidak.
 *
 * Tidak ada parser 3D di sini — hanya penyediaan file, supaya semua halaman memakai jalur
 * parsing yang sama ([MeshImporter], [DeepspaceBinaryParser], [GlbReader]).
 */
object ImportFileUtils {

    /**
     * Nama file asli dari sebuah content URI.
     *
     * `uri.lastPathSegment` pada URI document provider hanya berisi ID dokumen
     * (mis. "msf:1000000001"), bukan nama berkas — jadi nama asli dibaca lewat kolom
     * DISPLAY_NAME, dengan fallback ke segmen terakhir URI.
     */
    fun displayNameOf(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback ke segmen terakhir URI di bawah.
        }
        return uri.lastPathSegment?.substringAfterLast('/')
            ?: "imported_${System.currentTimeMillis()}"
    }

    /** Ekstensi nama file yang dipilih (tanpa titik, huruf kecil); "" bila tanpa ekstensi. */
    fun extensionOf(context: Context, uri: Uri): String =
        displayNameOf(context, uri).substringAfterLast('.', "").lowercase()

    /**
     * Path file nyata di balik URI file manager, atau null bila penyedianya tidak punya file
     * fisik (mis. dokumen awan). Dipakai supaya file sumber dipakai langsung tanpa disalin.
     */
    fun realFilePathOf(uri: Uri): File? {
        val candidate: File? = when {
            uri.scheme == "file" -> uri.path?.let { File(it) }
            uri.authority == "com.android.externalstorage.documents" -> {
                // docId berbentuk "primary:Download/model.obj" atau "1A2B-3C4D:Foto/model.obj".
                val docId = try {
                    DocumentsContract.getDocumentId(uri)
                } catch (_: Exception) {
                    null
                }
                val parts = docId?.split(':', limit = 2)
                if (parts != null && parts.size == 2) {
                    val storageRoot = if (parts[0].equals("primary", ignoreCase = true)) {
                        android.os.Environment.getExternalStorageDirectory()
                    } else {
                        File("/storage/${parts[0]}")
                    }
                    File(storageRoot, parts[1])
                } else {
                    null
                }
            }
            else -> null
        }
        return candidate?.takeIf { it.isFile && it.canRead() && it.length() > 0L }
    }

    /**
     * Ubah URI dari file manager Android (DocumentsUI) menjadi [File] yang bisa dibaca parser.
     *
     * Jalur 1: URI menunjuk file nyata di penyimpanan → path aslinya dikembalikan apa adanya,
     * tanpa salinan sama sekali (folder Import tidak terisi duplikat).
     * Jalur 2: penyedia tanpa file fisik (Drive, penyedia awan lain) → isinya disalin ke cache
     * aplikasi, BUKAN folder Import, supaya tidak menumpuk ([DeepspaceDirs.getImportCacheDir]).
     *
     * Ekstensi diverifikasi ulang di sini karena DocumentsUI hanya menyaring berbasis MIME:
     * format Deepspace (.dsform/.dspart/.dsasm/.dsrender) terbaca sebagai
     * "application/octet-stream", jadi file yang tidak dikenal tetap harus ditolak di sini.
     *
     * Bila format tidak didukung atau file tidak bisa dibaca, fungsi ini menampilkan Toast dan
     * mengembalikan null — pemanggil cukup berhenti tanpa pesan tambahan.
     */
    fun resolvePickedFile(
        context: Context,
        uri: Uri,
        allowedExtensions: Set<String> = ImportBrowserActivity.ALLOWED_EXTENSIONS
    ): File? {
        val displayName = displayNameOf(context, uri)
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (!allowedExtensions.contains(ext)) {
            Toast.makeText(
                context,
                "Format .${ext.ifEmpty { "?" }} tidak didukung.\nFormat yang bisa diimpor: " +
                    allowedExtensions.sorted().joinToString(", "),
                Toast.LENGTH_LONG
            ).show()
            return null
        }
        return try {
            // Jalur 1: file nyata di penyimpanan → pakai path aslinya, tanpa salinan.
            realFilePathOf(uri)?.let { return it }
            // Jalur 2: penyedia tanpa path nyata → salin ke cache aplikasi (bukan folder Import).
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(context, "File tidak bisa dibaca", Toast.LENGTH_LONG).show()
                return null
            }
            DeepspaceDirs.uniqueFile(DeepspaceDirs.getImportCacheDir(context), displayName)
                .also { it.writeBytes(bytes) }
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membaca file: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }
}
