package com.example.deepspace

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.filamat.MaterialBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DecalMaterial — material LIT yang memproyeksikan sticker ke permukaan part.
 *
 * Kenapa proyeksi dan bukan pemetaan UV: geometri yang dihasilkan [GlbBuilder] hanya punya
 * POSITION + NORMAL, tanpa `TEXCOORD_0`. Jadi sticker tidak bisa ditempel lewat UV.
 *
 * Proyeksinya **menempel pada permukaan** (world-locked): fragment shader mengubah posisi
 * dunia fragmen ke ruang decal memakai basis sticker itu sendiri — `right`/`up`/`forward`
 * yang disusun [Sticker.placeOn] — lalu menolak fragmen di luar kotak proyeksi DAN fragmen
 * yang tidak menghadap sumber proyeksi (uji hadap). Karena acuannya dunia dan bukan kamera,
 * gambarnya tetap di tempatnya saat kamera berputar: ia hanya mengalami pemendekan
 * perspektif yang wajar seperti decal sungguhan, bukan mengambang atau bergeser-geser di
 * atas permukaan.
 *
 * Nama parameter PBR-nya **sengaja dibuat identik** dengan ubershader gltfio
 * (`baseColorFactor`, `metallicFactor`, …), supaya [MaterialLibrary] dan panel transparansi
 * bisa menulis ke material ini tanpa perubahan kode sama sekali.
 */
class DecalMaterial private constructor(
    private val engine: Engine,
    private val opaque: Material,
    private val fade: Material,
    private val placeholder: Texture,
) {

    /**
     * Satu sticker siap dikirim ke shader. Orientasi dikirim sebagai tiga vektor terpisah —
     * bukan satu matriks — supaya urutan komponennya pasti dan tidak bergantung pada
     * konvensi row/column-major saat nilai-nilai itu di-upload ke uniform Filament.
     */
    class Slot(
        val texture: Texture,
        /** Pusat sticker di ruang dunia; sekaligus penanda slot aktif. */
        val center: FloatArray,
        /** Sumbu decal di ruang dunia, ortonormal ternormalisasi (lihat [Sticker.placeOn]). */
        val right: FloatArray,
        val up: FloatArray,
        val forward: FloatArray,
        /** Lebar gambar sepanjang [right]. */
        val width: Float,
        /** Tinggi gambar sepanjang [up]. */
        val height: Float,
        /** Ketebalan kotak proyeksi sepanjang [forward]; harus menembus permukaan. */
        val depth: Float,
        val opacity: Float,
    )

    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )

    /**
     * Buat material instance baru. [blended] dipakai saat part diberi transparansi;
     * versi opaque tetap menulis depth sehingga tidak ada artefak tumpang-tindih.
     */
    fun newInstance(blended: Boolean): MaterialInstance {
        val instance = (if (blended) fade else opaque).createInstance()
        // Default disesuaikan dengan material bawaan GlbBuilder agar part tidak berubah
        // penampilan hanya karena materialnya ditukar.
        instance.setParameter("baseColorFactor", 0.62f, 0.63f, 0.65f, 1f)
        instance.setParameter("metallicFactor", 0.1f)
        instance.setParameter("roughnessFactor", 0.6f)
        instance.setParameter("reflectance", 0.5f)
        instance.setParameter("clearCoatFactor", 0f)
        instance.setParameter("clearCoatRoughnessFactor", 0.1f)
        // Semua sampler HARUS terikat walau slot-nya kosong: Filament menolak menggambar
        // material dengan sampler yang belum di-set.
        for (i in 0 until MAX_SLOTS) {
            instance.setParameter("decalMap$i", placeholder, sampler)
        }
        clearDecals(instance)
        return instance
    }

    /** Nolkan semua slot decal pada [instance]. */
    fun clearDecals(instance: MaterialInstance) {
        val basis = FloatArray(4 * BASIS_ROWS * MAX_SLOTS)
        val params = FloatArray(4 * MAX_SLOTS)
        instance.setParameter("decalBasis", MaterialInstance.FloatElement.FLOAT4, basis, 0, BASIS_ROWS * MAX_SLOTS)
        instance.setParameter("decalParams", MaterialInstance.FloatElement.FLOAT4, params, 0, MAX_SLOTS)
    }

    /**
     * Kirim daftar sticker ke [instance]. Slot melebihi [MAX_SLOTS] diabaikan — jumlah
     * sampler pada material bersifat tetap saat dikompilasi.
     */
    fun setDecals(instance: MaterialInstance, slots: List<Slot>) {
        val basis = FloatArray(4 * BASIS_ROWS * MAX_SLOTS)
        val params = FloatArray(4 * MAX_SLOTS)
        for (i in 0 until MAX_SLOTS) {
            val slot = slots.getOrNull(i)
            if (slot == null) {
                instance.setParameter("decalMap$i", placeholder, sampler)
                continue
            }
            // decalParams = (center.x, center.y, center.z, opacity). w dipakai sebagai
            // opacity sekaligus penanda slot aktif (0 = kosong).
            params[i * 4] = slot.center[0]
            params[i * 4 + 1] = slot.center[1]
            params[i * 4 + 2] = slot.center[2]
            params[i * 4 + 3] = slot.opacity

            // decalBasis: 3 vec4 per slot — (sumbu.xyz, ukuran) untuk right, up, forward.
            // Konversi world→decal hanya perlu membagi offset dengan ukuran, jadi orientasi
            // dan skala terbaca eksplisit di shader (tidak ada matriks yang bisa tertukar).
            val base = i * BASIS_ROWS * 4
            basis[base] = slot.right[0]
            basis[base + 1] = slot.right[1]
            basis[base + 2] = slot.right[2]
            basis[base + 3] = slot.width
            basis[base + 4] = slot.up[0]
            basis[base + 5] = slot.up[1]
            basis[base + 6] = slot.up[2]
            basis[base + 7] = slot.height
            basis[base + 8] = slot.forward[0]
            basis[base + 9] = slot.forward[1]
            basis[base + 10] = slot.forward[2]
            basis[base + 11] = slot.depth
            instance.setParameter("decalMap$i", slot.texture, sampler)
        }
        instance.setParameter("decalBasis", MaterialInstance.FloatElement.FLOAT4, basis, 0, BASIS_ROWS * MAX_SLOTS)
        instance.setParameter("decalParams", MaterialInstance.FloatElement.FLOAT4, params, 0, MAX_SLOTS)
    }

    fun destroy() {
        try { engine.destroyMaterial(opaque) } catch (_: Exception) {}
        try { engine.destroyMaterial(fade) } catch (_: Exception) {}
        try { engine.destroyTexture(placeholder) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "DecalMaterial"

        /** Jumlah sticker per part. Tetap karena sampler tidak bisa diindeks dinamis di GLES. */
        const val MAX_SLOTS = 4

        /**
         * Jumlah vec4 per slot pada uniform `decalBasis`: right, up, forward —
         * masing-masing `(x, y, z, ukuran)`.
         */
        private const val BASIS_ROWS = 3

        // Catatan: `MaterialBuilder.variantFilter(int)` bisa memangkas varian yang tidak
        // terpakai dan mempercepat kompilasi, tapi binding Java-nya tidak mengekspos nama
        // konstanta bit-nya (nilainya hanya ada di header native). Menuliskan angkanya
        // secara hardcode berarti berasumsi tanpa bisa diverifikasi, dan bila salah
        // varian yang dibutuhkan ikut hilang sehingga part bersticker bisa salah render
        // atau tidak tampak. Karena ANR sudah diatasi dengan mengompilasi di thread
        // background, filter itu tidak dipakai.

        /** Paket material hasil kompilasi filamat; murni CPU, aman dibuat di background. */
        class Compiled(
            val opaque: java.nio.ByteBuffer,
            val fade: java.nio.ByteBuffer,
        )

        /**
         * Kompilasi shader-nya. **Jalankan di thread background**: memanggil ini di main
         * thread memblokir UI beberapa detik dan memicu ANR. Tidak menyentuh [Engine].
         */
        fun compilePackages(): Compiled? {
            return try {
                MaterialBuilder.init()
                val opaque = buildPackage(MaterialBuilder.BlendingMode.OPAQUE)
                val fade = buildPackage(MaterialBuilder.BlendingMode.FADE)
                MaterialBuilder.shutdown()
                if (opaque == null || fade == null) null else Compiled(opaque, fade)
            } catch (e: Throwable) {
                Log.e(TAG, "compilePackages failed", e)
                try { MaterialBuilder.shutdown() } catch (_: Throwable) {}
                null
            }
        }

        /** Buat [Material] dari paket. **Harus di thread yang memiliki [Engine]** (main). */
        fun fromPackages(engine: Engine, compiled: Compiled): DecalMaterial? {
            return try {
                val opaque = Material.Builder()
                    .payload(compiled.opaque, compiled.opaque.remaining()).build(engine)
                val fade = Material.Builder()
                    .payload(compiled.fade, compiled.fade.remaining()).build(engine)
                DecalMaterial(engine, opaque, fade, createPlaceholder(engine))
            } catch (e: Throwable) {
                Log.e(TAG, "fromPackages failed", e)
                null
            }
        }

        private fun buildPackage(blending: MaterialBuilder.BlendingMode): java.nio.ByteBuffer? {
            val builder = MaterialBuilder()
                .name(if (blending == MaterialBuilder.BlendingMode.OPAQUE) "decalOpaque" else "decalFade")
                .shading(MaterialBuilder.Shading.LIT)
                .blending(blending)
                .doubleSided(true)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "baseColorFactor")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "metallicFactor")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "roughnessFactor")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "reflectance")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "clearCoatFactor")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "clearCoatRoughnessFactor")
                .uniformParameterArray(MaterialBuilder.UniformType.FLOAT4, MAX_SLOTS * BASIS_ROWS, "decalBasis")
                .uniformParameterArray(MaterialBuilder.UniformType.FLOAT4, MAX_SLOTS, "decalParams")
                .material(fragmentShader())
                .targetApi(MaterialBuilder.TargetApi.OPENGL)
                .platform(MaterialBuilder.Platform.MOBILE)

            for (i in 0 until MAX_SLOTS) {
                builder.samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "decalMap$i",
                )
            }

            val pkg = builder.build()
            if (!pkg.isValid) {
                Log.e(TAG, "material package invalid ($blending)")
                return null
            }
            return pkg.buffer
        }

        /**
         * Shader-nya dibangun sebagai string karena slot decal harus **di-unroll**:
         * GLSL ES tidak mengizinkan sampler diindeks dengan variabel.
         */
        private fun fragmentShader(): String {
            val sb = StringBuilder()
            sb.append(
                """
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);
                    material.baseColor = materialParams.baseColorFactor;
                    material.metallic = materialParams.metallicFactor;
                    material.roughness = materialParams.roughnessFactor;
                    material.reflectance = materialParams.reflectance;
                    material.clearCoat = materialParams.clearCoatFactor;
                    material.clearCoatRoughness = materialParams.clearCoatRoughnessFactor;

                    highp vec3 wp = getWorldPosition();
                """.trimIndent()
            )
            for (i in 0 until MAX_SLOTS) {
                sb.append(
                    """

                    {
                        vec4 dp = materialParams.decalParams[$i];
                        if (dp.w > 0.001) {
                            // 3 vec4 per slot: (sumbu.xyz, ukuran). Basisnya milik decal
                            // sendiri, bukan kamera — itulah yang membuat sticker menempel
                            // di permukaan dan hanya berubah perspektif secara wajar.
                            vec4 axisR = materialParams.decalBasis[${i * 3}];
                            vec4 axisU = materialParams.decalBasis[${i * 3 + 1}];
                            vec4 axisF = materialParams.decalBasis[${i * 3 + 2}];
                            vec3 rel = wp - dp.xyz;
                            float lx = dot(rel, axisR.xyz) / max(axisR.w, 0.00001);
                            float ly = dot(rel, axisU.xyz) / max(axisU.w, 0.00001);
                            float lz = dot(rel, axisF.xyz) / max(axisF.w, 0.00001);
                            // Kotak proyeksi: x/y = bidang gambar, z = ketebalan yang
                            // menembus permukaan. Fragmen di luar kotak tidak ikut dicap.
                            if (abs(lx) <= 0.5 && abs(ly) <= 0.5 && abs(lz) <= 0.5) {
                                // Uji hadap: hanya permukaan yang MENGHADAP sumber proyeksi
                                // yang dicap. Tanpa uji ini gambar ikut membekas pada sisi
                                // belakang/dinding samping di dalam kotak dan tampak
                                // berpindah-pindah ("fluid") saat kamera diputar. Normal
                                // geometris dari turunan layar = normal sesuai urutan vertex,
                                // tidak terpengaruh normal-map maupun interpolasi. Ambang -0.05
                                // menolak dinding yang tegak lurus proyeksi (dot ≈ 0) yang
                                // sebabnya beriak setengah-stempel di dekat siluet.
                                vec3 gn = normalize(cross(dFdx(wp), dFdy(wp)));
                                if (dot(gn, axisF.xyz) < -0.05) {
                                    float u = lx + 0.5;
                                    // Bitmap Android tersusun dari atas ke bawah, jadi V dibalik.
                                    float v = 0.5 - ly;
                                    vec4 c = texture(materialParams_decalMap$i, vec2(u, v));
                                    // Pelembut tepi (~1/32 lebar decal) agar batas stempel
                                    // tidak bergerigi saat decal miring terhadap kamera.
                                    float ex = clamp((0.5 - abs(lx)) * 32.0, 0.0, 1.0);
                                    float ey = clamp((0.5 - abs(ly)) * 32.0, 0.0, 1.0);
                                    float a = clamp(c.a * dp.w, 0.0, 1.0) * ex * ey;
                                    material.baseColor.rgb = mix(material.baseColor.rgb, c.rgb, a);
                                }
                            }
                        }
                    }
                    """.trimIndent()
                )
            }
            sb.append("\n}\n")
            return sb.toString()
        }

        /** Tekstur 1x1 putih untuk mengisi slot sampler yang belum terpakai. */
        private fun createPlaceholder(engine: Engine): Texture {
            val texture = Texture.Builder()
                .width(1).height(1).levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .build(engine)
            val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            buffer.put(0, 0); buffer.put(1, 0); buffer.put(2, 0); buffer.put(3, 0)
            texture.setImage(
                engine, 0,
                Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE),
            )
            return texture
        }
    }
}
