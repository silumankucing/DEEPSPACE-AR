package com.example.deepspace

import com.google.android.filament.Texture
import java.io.File

/**
 * Sticker — satu decal beserta kotak proyeksinya di ruang dunia.
 *
 * Kotaknya adalah kubus satuan `[-0.5, 0.5]³` yang dipetakan ke dunia lewat
 * `center + x·width·right + y·height·up + z·depth·forward`. [forward] adalah arah proyeksi
 * (menembus ke dalam permukaan), sehingga permukaan yang menghadap proyektor punya
 * `dot(normal, forward) < 0` — itulah uji yang dipakai shader untuk menolak sisi belakang
 * (implementasinya memakai ambang -0.05 dan normal geometris turunan-layar; lihat
 * [DecalMaterial]).
 */
class Sticker(
    val id: Long,
    /** Nama berkas lengkap dengan ekstensi, untuk ditampilkan di ListView. */
    val displayName: String,
    /** Salinan gambar di dalam folder proyek (bukan URI, yang izinnya bisa kedaluwarsa). */
    val file: File,
    val texture: Texture,
    /** tinggi / lebar gambar. */
    val aspect: Float,
) {
    val center = floatArrayOf(0f, 0f, 0f)
    val right = floatArrayOf(1f, 0f, 0f)
    val up = floatArrayOf(0f, 1f, 0f)
    val forward = floatArrayOf(0f, 0f, -1f)

    /** Lebar decal dalam satuan dunia; tingginya mengikuti [aspect]. */
    var width = 0.5f

    /**
     * Ketebalan kotak proyeksi. Harus cukup besar agar menembus permukaan yang melengkung,
     * tapi tidak sampai menjangkau sisi seberang part.
     */
    var depth = 0.5f

    var opacity = 1f

    /** Rotasi pada sumbu decal (radian); diubah saat gizmo Rotate. Nilai ini sudah
     *  terwakili oleh [right]/[up] sehingga proyeksi tetap world-locked. */
    var roll = 0f

    val height: Float get() = width * aspect

    /**
     * Tempatkan decal di [point] dengan permukaan bernormal [normal] (ternormalisasi,
     * menghadap kamera). [geometricNormal] = normal **sesuai urutan vertex** dari raycast:
     * bila ia berlawanan dengan [normal], winding mesh terbalik dan sumbu proyeksi ikut
     * dibalik agar uji hadap di shader menstamp sisi depan yang benar, bukan sisi belakang.
     */
    fun placeOn(point: FloatArray, normal: FloatArray, geometricNormal: FloatArray? = null) {
        center[0] = point[0]; center[1] = point[1]; center[2] = point[2]
        // Proyeksi menembus ke dalam permukaan, jadi berlawanan dengan normal.
        // Untuk mesh winding-terbalik (dot(geo, normal) < 0), shader melihat normal turunan-
        // layar yang mengarah keluar pada sisi BELAKANG mesh; membalik forward di sini
        // membuat uji hadap `dot(gn, forward) < 0` kembali menstamp sisi yang terlihat.
        // Tanpa [geometricNormal] (fallback tanpa raycast) perilaku lama dipertahankan.
        val dotGeo = if (geometricNormal == null) 1f
        else geometricNormal[0] * normal[0] + geometricNormal[1] * normal[1] + geometricNormal[2] * normal[2]
        val flip = if (dotGeo > 0f) 1f else -1f
        forward[0] = -normal[0] * flip; forward[1] = -normal[1] * flip; forward[2] = -normal[2] * flip
        buildTangentBasis()
    }

    /** Susun [right]/[up] yang tegak lurus [forward], memakai up dunia sebagai acuan. */
    fun buildTangentBasis() {
        normalize(forward)
        // Bila forward hampir sejajar Y dunia, pakai Z sebagai acuan agar tidak degenerate.
        val refX: Float; val refY: Float; val refZ: Float
        if (kotlin.math.abs(forward[1]) > 0.99f) {
            refX = 0f; refY = 0f; refZ = 1f
        } else {
            refX = 0f; refY = 1f; refZ = 0f
        }
        // right = normalize(cross(ref, forward))
        right[0] = refY * forward[2] - refZ * forward[1]
        right[1] = refZ * forward[0] - refX * forward[2]
        right[2] = refX * forward[1] - refY * forward[0]
        normalize(right)
        // up = cross(forward, right)
        up[0] = forward[1] * right[2] - forward[2] * right[1]
        up[1] = forward[2] * right[0] - forward[0] * right[2]
        up[2] = forward[0] * right[1] - forward[1] * right[0]
        normalize(up)
    }

    /** Geser pusat decal sebesar delta dunia. */
    fun translate(dx: Float, dy: Float, dz: Float) {
        center[0] += dx; center[1] += dy; center[2] += dz
    }

    /** Putar decal pada sumbunya sendiri: 0 = roll (sekitar forward), 1 = right, 2 = up. */
    fun rotate(axis: Int, radians: Float) {
        // Roll = putaran pada bidang decal (sekitar forward). Shader memakai basis right/up,
        // jadi yang benar-benar mengubah gambar adalah rotasi basis di bawah; [roll] hanya
        // disimpan sebagai nilai baca-saja untuk tooltip/gizmo.
        if (axis == 0) roll += radians
        val a = when (axis) {
            1 -> right
            2 -> up
            else -> forward
        }
        rotateAbout(right, a, radians)
        rotateAbout(up, a, radians)
        if (axis != 0) rotateAbout(forward, a, radians)
        normalize(right); normalize(up); normalize(forward)
        // Jaga ortogonalitas agar tidak menumpuk galat setelah banyak drag.
        up[0] = forward[1] * right[2] - forward[2] * right[1]
        up[1] = forward[2] * right[0] - forward[0] * right[2]
        up[2] = forward[0] * right[1] - forward[1] * right[0]
        normalize(up)
    }

    /** Kalikan ukuran decal, dibatasi agar tidak hilang atau meledak. */
    fun scaleBy(factor: Float) {
        width = (width * factor).coerceIn(0.01f, 100f)
    }

    /**
     * Kemas sticker menjadi slot material. Basis (right/up/forward) beserta ukuran dikirim
     * apa adanya: shader yang memproyeksikan offset dunia ke bidang decal, sehingga sticker
     * tetap menempel di permukaan.
     */
    fun toSlot(): DecalMaterial.Slot = DecalMaterial.Slot(
        texture = texture,
        center = center.copyOf(),
        right = right.copyOf(),
        up = up.copyOf(),
        forward = forward.copyOf(),
        width = width,
        height = height,
        depth = depth,
        opacity = opacity,
    )

    private fun normalize(v: FloatArray) {
        val len = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        if (len < 1e-9f) return
        v[0] /= len; v[1] /= len; v[2] /= len
    }

    /** Rotasi [v] terhadap sumbu ternormalisasi [axis] (Rodrigues), in-place. */
    private fun rotateAbout(v: FloatArray, axis: FloatArray, radians: Float) {
        val c = kotlin.math.cos(radians.toDouble()).toFloat()
        val s = kotlin.math.sin(radians.toDouble()).toFloat()
        val dot = v[0] * axis[0] + v[1] * axis[1] + v[2] * axis[2]
        val cx = axis[1] * v[2] - axis[2] * v[1]
        val cy = axis[2] * v[0] - axis[0] * v[2]
        val cz = axis[0] * v[1] - axis[1] * v[0]
        val x = v[0] * c + cx * s + axis[0] * dot * (1f - c)
        val y = v[1] * c + cy * s + axis[1] * dot * (1f - c)
        val z = v[2] * c + cz * s + axis[2] * dot * (1f - c)
        v[0] = x; v[1] = y; v[2] = z
    }
}
