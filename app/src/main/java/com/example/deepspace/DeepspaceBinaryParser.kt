package com.example.deepspace

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Binary parser lintas halaman untuk membuka file proyek Deepspace.
 *
 * - .dspart / .dsasm : ZIP container; preview model disimpan di "data/preview.glb".
 * - .dsform          : binary mesh (magic "DSFM"); direkonstruksi menjadi GLB.
 * - .glb / lainnya   : GLB langsung.
 *
 * Dipakai oleh halaman AR untuk membuka berkas .dspart/.dsasm/.dsform/.dsrender.
 */
object DeepspaceBinaryParser {

    private const val TAG = "DeepspaceBinaryParser"

    /**
     * Ubah bytes file (berdasar ekstensinya) menjadi bytes GLB yang siap dirender.
     * Mengembalikan null bila file tidak berisi model 3D yang valid.
     */
    fun toGlb(bytes: ByteArray, ext: String): ByteArray? {
        return when (ext.lowercase()) {
            "dspart", "dsasm" -> extractPreviewGlbFromZip(bytes)
            "dsform" -> dsFormToGlb(bytes)
            else -> bytes
        }
    }

    /**
     * Ubah file proyek (berdasar ekstensi + path) menjadi GLB siap render.
     * Berbeda dengan [toGlb] berbasis bytes, versi ini juga menangani .dsrender
     * yang perlu me-resolve referensi model (sourcePath relatif folder file).
     */
    fun toGlb(file: File): ByteArray? {
        val ext = file.extension.lowercase()
        if (ext == "dsrender") return dsRenderToGlb(file)
        return toGlb(file.readBytes(), ext)
    }

    /**
     * Parse .dsrender (DSRN) menjadi satu GLB. Tiap objek mereferensikan model
     * (dsform/glb) via sourcePath relatif folder file .dsrender, dengan modelMatrix.
     */
    private fun dsRenderToGlb(file: File): ByteArray? {
        return try {
            val scene = DsRenderFormat.load(file) ?: return null
            // Geometry self-contained (v2) dipakai langsung bila tersimpan.
            scene.embeddedGlb?.takeIf { it.isNotEmpty() }?.let { return it }
            val baseDir = file.parentFile ?: return null
            // Satu Part per object: warna (color/albedo) dan alpha yang tersimpan di
            // .dsrender ditulis sebagai baseColorFactor + alphaMode BLEND per part,
            // sehingga halaman AR masih bisa menampilkan warna & transparansi.
            val parts = ArrayList<GlbBuilder.Part>()
            for (obj in scene.objects) {
                if (!obj.visible) continue
                val srcFile = File(baseDir, obj.sourcePath)
                val srcGlb = if (srcFile.exists()) toGlb(srcFile.readBytes(), srcFile.extension.lowercase()) else null
                if (srcGlb == null) continue
                val tris = GlbReader.readTriangles(srcGlb)
                if (tris.isEmpty()) continue
                val m = obj.modelMatrix
                val posArr = FloatArray(tris.size * 9)
                var p = 0
                for (t in tris) {
                    posArr[p++] = (m[0] * t.a.x + m[4] * t.a.y + m[8] * t.a.z + m[12]).toFloat()
                    posArr[p++] = (m[1] * t.a.x + m[5] * t.a.y + m[9] * t.a.z + m[13]).toFloat()
                    posArr[p++] = (m[2] * t.a.x + m[6] * t.a.y + m[10] * t.a.z + m[14]).toFloat()
                    posArr[p++] = (m[0] * t.b.x + m[4] * t.b.y + m[8] * t.b.z + m[12]).toFloat()
                    posArr[p++] = (m[1] * t.b.x + m[5] * t.b.y + m[9] * t.b.z + m[13]).toFloat()
                    posArr[p++] = (m[2] * t.b.x + m[6] * t.b.y + m[10] * t.b.z + m[14]).toFloat()
                    posArr[p++] = (m[0] * t.c.x + m[4] * t.c.y + m[8] * t.c.z + m[12]).toFloat()
                    posArr[p++] = (m[1] * t.c.x + m[5] * t.c.y + m[9] * t.c.z + m[13]).toFloat()
                    posArr[p++] = (m[2] * t.c.x + m[6] * t.c.y + m[10] * t.c.z + m[14]).toFloat()
                }
                val col = obj.color
                val colArr = if (col.size >= 3) floatArrayOf(col[0], col[1], col[2]) else floatArrayOf(0.62f, 0.63f, 0.65f)
                parts.add(GlbBuilder.Part(
                    name = obj.name,
                    positions = posArr,
                    indices = IntArray(tris.size * 3) { it },
                    color = colArr,
                    alpha = obj.alpha,
                ))
            }
            if (parts.isEmpty()) return null
            GlbBuilder.buildParts(parts)
        } catch (e: Exception) {
            Log.e(TAG, "dsRenderToGlb error", e)
            null
        }
    }

    /** Ekstrak "data/preview.glb" dari ZIP container (.dspart / .dsasm). */
    private fun extractPreviewGlbFromZip(zipBytes: ByteArray): ByteArray? {
        return try {
            val zip = ZipInputStream(ByteArrayInputStream(zipBytes))
            try {
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == "data/preview.glb") {
                        return zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
                null
            } finally {
                try { zip.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractPreviewGlbFromZip error", e)
            null
        }
    }

    /** Parse binary .dsform (DSFM) menjadi GLB siap render. */
    private fun dsFormToGlb(dsformBytes: ByteArray): ByteArray? {
        return try {
            val objects = DsFormFormat.load(dsformBytes)?.second
            if (objects.isNullOrEmpty()) return null
            buildGlbFromSceneObjects(objects)
        } catch (e: Exception) {
            Log.e(TAG, "dsFormToGlb error", e)
            null
        }
    }

        /**
     * Bangun GLB per-object dari scene .dsform, setiap object sebagai Part terpisah
     * dengan warna dan alpha-nya masing-masing (menggunakan buildParts).
     * Dengan demikian warna appearance yang tersimpan di .dsform akan ditampilkan
     * dengan benar di halaman AR.
     */
    private fun buildGlbFromSceneObjects(objects: List<PolygonEngine.SceneObject>): ByteArray? {
        val parts = ArrayList<GlbBuilder.Part>()

        fun addRec(obj: PolygonEngine.SceneObject) {
            val vCount = obj.vertexCount
            if (vCount > 0) {
                val m = obj.modelMatrix
                // Terapkan transformasi modelMatrix per vertex (column-major, OpenGL)
                val posArr = FloatArray(vCount * 3)
                var p = 0
                for (i in 0 until vCount) {
                    val x = obj.vertices[i * 3]
                    val y = obj.vertices[i * 3 + 1]
                    val z = obj.vertices[i * 3 + 2]
                    posArr[p++] = (m[0] * x + m[4] * y + m[8] * z + m[12])
                    posArr[p++] = (m[1] * x + m[5] * y + m[9] * z + m[13])
                    posArr[p++] = (m[2] * x + m[6] * y + m[10] * z + m[14])
                }
                val triCount = vCount / 3
                val idxArr = IntArray(triCount * 3) { it }
                val col = obj.color
                val r = if (col.size > 0) col[0].toDouble() else 0.62
                val g = if (col.size > 1) col[1].toDouble() else 0.63
                val b = if (col.size > 2) col[2].toDouble() else 0.65
                val a = if (col.size > 3) col[3].toDouble() else 1.0
                parts.add(GlbBuilder.Part(
                    name = obj.name,
                    positions = posArr,
                    indices = idxArr,
                    color = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat()),
                    alpha = a.toFloat(),
                ))
            }
            // Children group tetap ikut dirender/diexport agar tidak hilang.
            obj.children?.forEach { addRec(it) }
        }

        for (obj in objects) addRec(obj)

        if (parts.isEmpty()) return null
        return GlbBuilder.buildParts(parts)
    }
}
