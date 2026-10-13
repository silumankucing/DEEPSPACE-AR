package com.example.deepspace

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Binary format handler for *.dsrender files.
 *
 * Menyimpan scene .dsrender: camera, tree object 3D (referensi model),
 * material, warna, dan render settings.
 *
 * Format structure:
 * [4 bytes]  magic: "DSRN"
 * [2 bytes]  version (LE) = 1
 * [2 bytes]  projectNameLength (LE)
 * [N bytes]  projectName (UTF-8)
 * ─── Camera ───
 * [12 bytes] cameraPosition (3 floats LE: x, y, z)
 * [12 bytes] cameraTarget   (3 floats LE: tx, ty, tz)
 * [4 bytes]  cameraFov      (float LE)
 * ─── Object count ───
 * [4 bytes]  objectCount (LE)
 * For each object:
 *   [2 bytes]  nameLength (LE)
 *   [N bytes]  name (UTF-8)
 *   [1 byte]   sourceType: 0 = *.dsform internal, 1 = GLB external
 *   [2 bytes]  sourcePathLength (LE) — relative path from project dir
 *   [N bytes]  sourcePath (UTF-8)
 *   [4 bytes]  modelMatrix (16 floats LE)
 *   [12 bytes] color (3 floats LE: r, g, b)
 *   [1 byte]   visible
 *   [1 byte]   selected
 *   [1 byte]   castsShadow
 *   ─── Material ───
 *   [4 bytes]  materialNameLength (LE) — 0 if no material
 *   [N bytes]  materialName (UTF-8)
 *   [12 bytes] albedo       (3 floats LE: r, g, b) — fallback jika material name = ""
 *   [4 bytes]  metallic     (float LE)
 *   [4 bytes]  roughness    (float LE)
 *   [4 bytes]  emission     (float LE, intensity)
 *   [12 bytes] emissionColor (3 floats LE)
 *   [4 bytes]  alpha        (float LE, v2) — opacity 0..1, 1 = opaque
 * ─── Render Settings ───
 * [4 bytes]  engineType    (int LE: 0 = path tracer, 1 = rasterizer, 2 = hybrid)
 * [4 bytes]  sampleCount   (int LE)
 * [4 bytes]  maxBounces    (int LE)
 * [4 bytes]  resolutionX   (int LE)
 * [4 bytes]  resolutionY   (int LE)
 * [1 byte]   denoiserEnabled
 * [4 bytes]  toneMapOp     (int LE: 0 = ACES, 1 = Reinhard, 2 = Filmic, 3 = None)
 * [4 bytes]  exposure      (float LE)
 * [4 bytes]  gamma         (float LE)
 * ─── Environment ───
 * [2 bytes]  envMapPathLength (LE) — 0 = no env map
 * [N bytes]  envMapPath (UTF-8)
 * [4 bytes]  envRotation   (float LE)
 * ─── Embedded geometry (v2) ───
 * [4 bytes]  glbLength (LE) — 0 = none
 * [N bytes]  glb (GLB kombinasi scene, membuat file self-contained)
 */
object DsRenderFormat {
    private const val TAG = "DsRenderFormat"
    private const val MAGIC = "DSRN"
    private const val VERSION: Short = 2

    // Source types
    const val SOURCE_DSFORM = 0
    const val SOURCE_GLB = 1

    // Engine types
    const val ENGINE_PATH_TRACER = 0
    const val ENGINE_RASTERIZER = 1
    const val ENGINE_HYBRID = 2

    // Tone mapping
    const val TONEMAP_ACES = 0
    const val TONEMAP_REINHARD = 1
    const val TONEMAP_FILMIC = 2
    const val TONEMAP_NONE = 3

    /** Data class untuk satu object di rendering scene */
    data class RenderObject(
        val name: String,
        val sourceType: Int,            // SOURCE_DSFORM or SOURCE_GLB
        val sourcePath: String,         // relative path
        val modelMatrix: FloatArray,    // 16 floats
        val color: FloatArray,          // 3 floats r,g,b
        var visible: Boolean,
        var selected: Boolean,
        var castsShadow: Boolean,
        val materialName: String,
        val albedo: FloatArray,         // 3 floats r,g,b — fallback color
        val metallic: Float,
        val roughness: Float,
        val emission: Float,
        val emissionColor: FloatArray,  // 3 floats
        /** Opacity 0..1 (1 = opaque). Ditulis mulai v2; dibaca sebagai 1.0 pada file v1. */
        var alpha: Float = 1.0f,
    )

    /** Data class untuk render settings */
    data class RenderSettings(
        var engineType: Int,
        var sampleCount: Int,
        var maxBounces: Int,
        var resolutionX: Int,
        var resolutionY: Int,
        var denoiserEnabled: Boolean,
        var toneMapOp: Int,
        var exposure: Float,
        var gamma: Float,
        var envMapPath: String,
        var envRotation: Float
    )

    /** Data class untuk camera */
    data class CameraData(
        var position: FloatArray,   // 3 floats
        var target: FloatArray,     // 3 floats
        var fov: Float
    )

    /**
     * Complete scene data.
     *
     * [embeddedGlb] berisi GLB gabungan dari seluruh object (geometry self-contained).
     * Bila bukan null, pemuat tidak perlu me-resolve [RenderObject.sourcePath] lagi —
     * file .dsrender berdiri sendiri walau sumber model aslinya (glb/dsform/dspart/dsasm)
     * sudah tidak ada.
     */
    data class RenderScene(
        var projectName: String,
        var camera: CameraData,
        var objects: List<RenderObject>,
        var settings: RenderSettings,
        var embeddedGlb: ByteArray? = null,
    )

    fun save(file: File, scene: RenderScene) {
        val bos = FileOutputStream(file)
        try {
            // ── Header ──
            bos.write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
            val versionBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            versionBuf.putShort(VERSION)
            bos.write(versionBuf.array())

            // ── Project name ──
            val pnBytes = scene.projectName.toByteArray(StandardCharsets.UTF_8)
            if (pnBytes.size > 65535) throw IllegalArgumentException("Project name too long")
            bos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(pnBytes.size.toShort()).array())
            bos.write(pnBytes)

            // ── Camera ──
            writeFloats(bos, scene.camera.position, 3)
            writeFloats(bos, scene.camera.target, 3)
            writeFloat(bos, scene.camera.fov)

            // ── Object count ──
            writeInt(bos, scene.objects.size)

            // ── Objects ──
            for (obj in scene.objects) {
                // name
                val nameBytes = obj.name.toByteArray(StandardCharsets.UTF_8)
                if (nameBytes.size > 65535) throw IllegalArgumentException("Name too long: ${obj.name}")
                writeShort(bos, nameBytes.size.toShort())
                bos.write(nameBytes)

                // sourceType
                bos.write(obj.sourceType)

                // sourcePath
                val spBytes = obj.sourcePath.toByteArray(StandardCharsets.UTF_8)
                if (spBytes.size > 65535) throw IllegalArgumentException("Source path too long")
                writeShort(bos, spBytes.size.toShort())
                bos.write(spBytes)

                // modelMatrix (16 floats)
                writeFloats(bos, obj.modelMatrix, 16)

                // color (3 floats)
                writeFloats(bos, obj.color, 3)

                // visible, selected, castsShadow
                bos.write(if (obj.visible) 1 else 0)
                bos.write(if (obj.selected) 1 else 0)
                bos.write(if (obj.castsShadow) 1 else 0)

                // materialName
                val matBytes = obj.materialName.toByteArray(StandardCharsets.UTF_8)
                writeInt(bos, matBytes.size)
                if (matBytes.isNotEmpty()) bos.write(matBytes)

                // albedo (3 floats), metallic, roughness, emission, emissionColor (3 floats), alpha
                writeFloats(bos, obj.albedo, 3)
                writeFloat(bos, obj.metallic)
                writeFloat(bos, obj.roughness)
                writeFloat(bos, obj.emission)
                writeFloats(bos, obj.emissionColor, 3)
                writeFloat(bos, obj.alpha)
            }

            // ── Render Settings ──
            val s = scene.settings
            writeInt(bos, s.engineType)
            writeInt(bos, s.sampleCount)
            writeInt(bos, s.maxBounces)
            writeInt(bos, s.resolutionX)
            writeInt(bos, s.resolutionY)
            bos.write(if (s.denoiserEnabled) 1 else 0)
            writeInt(bos, s.toneMapOp)
            writeFloat(bos, s.exposure)
            writeFloat(bos, s.gamma)

            // ── Environment ──
            val envBytes = s.envMapPath.toByteArray(StandardCharsets.UTF_8)
            writeShort(bos, envBytes.size.toShort())
            if (envBytes.isNotEmpty()) bos.write(envBytes)
            writeFloat(bos, s.envRotation)

            // ── Embedded geometry (v2): GLB kombinasi scene, self-contained ──
            val glb = scene.embeddedGlb
            if (glb != null && glb.isNotEmpty()) {
                writeInt(bos, glb.size)
                bos.write(glb)
            } else {
                writeInt(bos, 0)
            }

            bos.flush()
            Log.d(TAG, "Saved rendering scene to ${file.absolutePath}")
        } finally {
            try { bos.close() } catch (_: Exception) {}
        }
    }

    fun load(file: File): RenderScene? {
        try {
            val bis = FileInputStream(file)
            val bytes = bis.readBytes()
            bis.close()

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // ── Magic ──
            val magic = ByteArray(4)
            buf.get(magic)
            if (String(magic, StandardCharsets.US_ASCII) != MAGIC) {
                Log.e(TAG, "Invalid magic: ${String(magic, StandardCharsets.US_ASCII)}")
                return null
            }

            // ── Version ──
            val version = buf.getShort()
            if (version < 1) {
                Log.e(TAG, "Unsupported version: $version")
                return null
            }

            // ── Project name ──
            val pnLen = buf.getShort().toInt() and 0xFFFF
            val pnBytes = ByteArray(pnLen)
            buf.get(pnBytes)
            val projectName = String(pnBytes, StandardCharsets.UTF_8)

            // ── Camera ──
            val camPos = FloatArray(3) { buf.getFloat() }
            val camTarget = FloatArray(3) { buf.getFloat() }
            val camFov = buf.getFloat()
            val camera = CameraData(camPos, camTarget, camFov)

            // ── Object count ──
            val objectCount = buf.getInt()
            val objects = mutableListOf<RenderObject>()

            for (i in 0 until objectCount) {
                // name
                val nameLen = buf.getShort().toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val name = String(nameBytes, StandardCharsets.UTF_8)

                // sourceType
                val sourceType = buf.get().toInt() and 0xFF

                // sourcePath
                val spLen = buf.getShort().toInt() and 0xFFFF
                val spBytes = ByteArray(spLen)
                buf.get(spBytes)
                val sourcePath = String(spBytes, StandardCharsets.UTF_8)

                // modelMatrix (16 floats)
                val modelMatrix = FloatArray(16) { buf.getFloat() }

                // color (3 floats)
                val color = FloatArray(3) { buf.getFloat() }

                // visible, selected, castsShadow
                val visible = buf.get() != 0.toByte()
                val selected = buf.get() != 0.toByte()
                val castsShadow = buf.get() != 0.toByte()

                // materialName
                val matLen = buf.getInt()
                val matName = if (matLen > 0) {
                    val matBytes = ByteArray(matLen)
                    buf.get(matBytes)
                    String(matBytes, StandardCharsets.UTF_8)
                } else ""

                // albedo (3 floats), metallic, roughness, emission, emissionColor (3 floats)
                val albedo = FloatArray(3) { buf.getFloat() }
                val metallic = buf.getFloat()
                val roughness = buf.getFloat()
                val emission = buf.getFloat()
                val emissionColor = FloatArray(3) { buf.getFloat() }
                // alpha ditambahkan mulai v2; file v1 dibaca sebagai opaque.
                val alpha = if (version >= 2) buf.getFloat() else 1.0f

                val obj = RenderObject(
                    name = name,
                    sourceType = sourceType,
                    sourcePath = sourcePath,
                    modelMatrix = modelMatrix,
                    color = color,
                    visible = visible,
                    selected = selected,
                    castsShadow = castsShadow,
                    materialName = matName,
                    albedo = albedo,
                    metallic = metallic,
                    roughness = roughness,
                    emission = emission,
                    emissionColor = emissionColor,
                    alpha = alpha
                )
                objects.add(obj)
            }

            // ── Render Settings ──
            val engineType = buf.getInt()
            val sampleCount = buf.getInt()
            val maxBounces = buf.getInt()
            val resolutionX = buf.getInt()
            val resolutionY = buf.getInt()
            val denoiserEnabled = buf.get() != 0.toByte()
            val toneMapOp = buf.getInt()
            val exposure = buf.getFloat()
            val gamma = buf.getFloat()

            // ── Environment ──
            val envLen = buf.getShort().toInt() and 0xFFFF
            val envPath = if (envLen > 0) {
                val envBytes = ByteArray(envLen)
                buf.get(envBytes)
                String(envBytes, StandardCharsets.UTF_8)
            } else ""
            val envRotation = buf.getFloat()

            // ── Embedded geometry (v2): hanya bila masih ada sisa bytes ──
            val embeddedGlb: ByteArray? = if (version >= 2 && buf.remaining() >= 4) {
                val glbLen = buf.getInt()
                if (glbLen > 0 && buf.remaining() >= glbLen) {
                    val glb = ByteArray(glbLen)
                    buf.get(glb)
                    glb
                } else null
            } else null

            val settings = RenderSettings(
                engineType = engineType,
                sampleCount = sampleCount,
                maxBounces = maxBounces,
                resolutionX = resolutionX,
                resolutionY = resolutionY,
                denoiserEnabled = denoiserEnabled,
                toneMapOp = toneMapOp,
                exposure = exposure,
                gamma = gamma,
                envMapPath = envPath,
                envRotation = envRotation
            )

            Log.d(TAG, "Loaded ${objects.size} objects from ${file.absolutePath}")
            return RenderScene(projectName, camera, objects, settings, embeddedGlb)
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}", e)
            return null
        }
    }

    /** Read only project name from .dsrender (fast, no full load) */
    fun getProjectName(file: File): String? {
        return try {
            val bis = FileInputStream(file)
            val bytes = bis.readBytes()
            bis.close()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(4)
            buf.get(magic)
            if (String(magic, StandardCharsets.US_ASCII) != MAGIC) return null

            val version = buf.getShort()
            if (version < 1) return null

            val pnLen = buf.getShort().toInt() and 0xFFFF
            val pnBytes = ByteArray(pnLen)
            buf.get(pnBytes)
            String(pnBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "getProjectName failed: ${e.message}")
            null
        }
    }

    fun setProjectName(file: File, newName: String) {
        val scene = load(file) ?: return
        val updated = scene.copy(projectName = newName)
        save(file, updated)
    }

    // ── Binary write helpers ──

    private fun writeShort(bos: FileOutputStream, v: Short) {
        bos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array())
    }

    private fun writeInt(bos: FileOutputStream, v: Int) {
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }

    private fun writeFloat(bos: FileOutputStream, v: Float) {
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
    }

    private fun writeFloats(bos: FileOutputStream, arr: FloatArray, count: Int) {
        val buf = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            buf.putFloat(arr[i])
        }
        bos.write(buf.array())
    }
}
