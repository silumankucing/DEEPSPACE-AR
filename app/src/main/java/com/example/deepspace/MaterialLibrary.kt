package com.example.deepspace

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Definisi satu material PBR — murni angka, tanpa tekstur atau proyeksi image.
 *
 * Semua warna dalam ruang **linear** (sesuai konvensi glTF `baseColorFactor`), bukan sRGB.
 * Untuk menampilkannya di UI harus dikonversi dulu ke ruang sRGB.
 *
 * Catatan konvensi PBR yang mudah salah:
 * - [metallic] praktis **biner**: 0 untuk semua non-logam, 1 untuk logam. Nilai 0.5 tidak
 *   punya padanan fisik.
 * - Untuk logam, [baseColor] adalah warna **pantulan spekular**, bukan warna diffuse.
 * - Untuk dielektrik ([metallic] = 0), kekuatan pantul diatur [reflectance];
 *   0.5 setara F0 4% yang berlaku untuk hampir semua non-logam.
 * - [roughness] bersifat perseptual: selisih 0.2→0.4 sangat terasa, di atas 0.8 nyaris seragam.
 */
data class MaterialDef(
    val id: String,
    val displayName: String,
    /** Linear RGB 0..1 */
    val baseColor: FloatArray,
    val metallic: Float,
    val roughness: Float,
    val reflectance: Float = 0.5f,
    val clearCoat: Float = 0f,
    val clearCoatRoughness: Float = 0.1f,
    /** < 1 berarti tembus pandang; pemanggil perlu material blending. */
    val alpha: Float = 1f,
) {
    // data class + FloatArray: equals/hashCode tidak dipakai, bandingkan lewat [id].
    override fun equals(other: Any?) = other is MaterialDef && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * MaterialLibrary — katalog material berbasis data.
 *
 * Sumber utamanya `assets/materials/materials.json` supaya nilai bisa disetel tanpa
 * rekompilasi; [BUILT_IN] dipakai sebagai isian awal dan cadangan bila asset hilang atau
 * gagal di-parse. Entri JSON dengan `id` yang sama akan **menimpa** built-in, dan `id` baru
 * akan ditambahkan.
 */
object MaterialLibrary {

    private const val TAG = "MaterialLibrary"
    private const val ASSET_PATH = "materials/materials.json"

    /**
     * Nilai default. Angka logam mengikuti reflektansi terukur (steel ~0.56 netral,
     * aluminium ~0.91, copper/brass berwarna).
     */
    val BUILT_IN: List<MaterialDef> = listOf(
        MaterialDef("steel", "Steel", floatArrayOf(0.560f, 0.570f, 0.580f), 1f, 0.35f),
        MaterialDef("stainless", "Stainless", floatArrayOf(0.560f, 0.570f, 0.580f), 1f, 0.12f),
        MaterialDef("aluminium", "Aluminium", floatArrayOf(0.910f, 0.920f, 0.925f), 1f, 0.30f),
        MaterialDef("copper", "Copper", floatArrayOf(0.955f, 0.638f, 0.538f), 1f, 0.25f),
        MaterialDef("brass", "Brass", floatArrayOf(0.887f, 0.789f, 0.434f), 1f, 0.28f),
        MaterialDef("cast_iron", "Cast Iron", floatArrayOf(0.290f, 0.290f, 0.300f), 1f, 0.62f),
        MaterialDef(
            "plastic_glossy", "Plastic", floatArrayOf(0.180f, 0.380f, 0.620f), 0f, 0.25f,
            clearCoat = 1f, clearCoatRoughness = 0.05f
        ),
        MaterialDef("plastic_matte", "Plastic Matte", floatArrayOf(0.620f, 0.620f, 0.625f), 0f, 0.65f),
        MaterialDef("rubber", "Rubber", floatArrayOf(0.040f, 0.040f, 0.042f), 0f, 0.90f),
        MaterialDef("wood", "Wood", floatArrayOf(0.350f, 0.200f, 0.100f), 0f, 0.55f),
        MaterialDef("concrete", "Concrete", floatArrayOf(0.520f, 0.510f, 0.490f), 0f, 0.85f),
        MaterialDef(
            "glass", "Glass", floatArrayOf(0.900f, 0.930f, 0.950f), 0f, 0.05f,
            reflectance = 0.65f, alpha = 0.25f
        ),
    )

    @Volatile
    private var cache: List<MaterialDef>? = null

    /** Katalog aktif. Dibaca sekali lalu di-cache. */
    fun catalog(context: Context): List<MaterialDef> {
        cache?.let { return it }
        val merged = synchronized(this) {
            cache ?: mergeWithAsset(context).also { cache = it }
        }
        return merged
    }

    fun byId(context: Context, id: String): MaterialDef? =
        catalog(context).firstOrNull { it.id == id }

    /** Buang cache; berguna bila JSON diubah saat pengembangan. */
    fun invalidate() {
        cache = null
    }

    private fun mergeWithAsset(context: Context): List<MaterialDef> {
        val overrides = readAsset(context)
        if (overrides.isEmpty()) return BUILT_IN

        val byId = LinkedHashMap<String, MaterialDef>()
        for (m in BUILT_IN) byId[m.id] = m
        for (m in overrides) byId[m.id] = m
        Log.d(TAG, "katalog: ${byId.size} material (${overrides.size} dari asset)")
        return byId.values.toList()
    }

    private fun readAsset(context: Context): List<MaterialDef> {
        return try {
            val text = context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
            val array = JSONArray(text)
            val out = ArrayList<MaterialDef>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val color = o.optJSONArray("baseColor")
                if (color == null || color.length() < 3) {
                    Log.w(TAG, "material '$id' dilewati: baseColor harus 3 angka")
                    continue
                }
                out.add(
                    MaterialDef(
                        id = id,
                        displayName = o.optString("displayName", id),
                        baseColor = floatArrayOf(
                            color.optDouble(0, 0.5).toFloat(),
                            color.optDouble(1, 0.5).toFloat(),
                            color.optDouble(2, 0.5).toFloat(),
                        ),
                        metallic = o.optDouble("metallic", 0.0).toFloat(),
                        roughness = o.optDouble("roughness", 0.5).toFloat(),
                        reflectance = o.optDouble("reflectance", 0.5).toFloat(),
                        clearCoat = o.optDouble("clearCoat", 0.0).toFloat(),
                        clearCoatRoughness = o.optDouble("clearCoatRoughness", 0.1).toFloat(),
                        alpha = o.optDouble("alpha", 1.0).toFloat(),
                    )
                )
            }
            out
        } catch (e: java.io.FileNotFoundException) {
            emptyList()   // asset opsional
        } catch (e: Exception) {
            Log.e(TAG, "gagal membaca $ASSET_PATH, memakai built-in", e)
            emptyList()
        }
    }
}
