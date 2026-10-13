package com.example.deepspace

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Binary format handler for *.dsar files (AR Scene).
 *
 * Format structure:
 * [4 bytes]  magic: "DSAR"
 * [2 bytes]  version (LE) = 2
 * [2 bytes]  projectNameLength (LE)
 * [N bytes]  projectName (UTF-8)
 * ─── AR Tracking Config ───
 * [1 byte]   useImageMarker (0 = plane detection, 1 = image marker)
 * [2 bytes]  markerPathLength (LE) — 0 if no marker image
 * [N bytes]  markerPath (UTF-8)
 * [4 bytes]  markerWidth   (float LE, physical width in meters)
 * ─── Object count ───
 * [4 bytes]  objectCount (LE)
 * For each object:
 *   [2 bytes]  nameLength (LE)
 *   [N bytes]  name (UTF-8)
 *   [1 byte]   sourceType: 0 = *.dsform internal, 1 = GLB external
 *   [2 bytes]  sourcePathLength (LE)
 *   [N bytes]  sourcePath (UTF-8)
 *   [16*4 bytes] modelMatrix (16 floats LE) — anchor transform in AR world
 *   [12 bytes] color (3 floats LE: r, g, b)
 *   [1 byte]   visible
 *   [1 byte]   receiveShadow
 *   [4 bytes]  scale (float LE) — AR object scale
 *   [4 bytes]  embeddedPayloadLength (int LE)
 *   [N bytes]  embeddedPayload — original GLB/DSFORM/DSPART/DSASM bytes
 * ─── Camera ───
 * [16*4 bytes] camera model matrix
 * ─── Lighting ───
 * [4 bytes] lighting count
 * For each light: direction (3 floats), color (3 floats), intensity (float), castShadows (byte)
 */
object DsArFormat {
    private const val TAG = "DsArFormat"
    private const val MAGIC = "DSAR"
    private const val VERSION: Short = 2

    const val SOURCE_DSFORM = 0
    const val SOURCE_GLB = 1
    const val SOURCE_DSPART = 2
    const val SOURCE_DSASM = 3

    data class ArObject(
        val name: String,
        val sourceType: Int,
        val sourcePath: String,
        val modelMatrix: FloatArray,
        val color: FloatArray,
        var visible: Boolean,
        var receiveShadow: Boolean,
        var scale: Float,
        val embeddedPayload: ByteArray? = null
    )

    data class ArLight(
        val direction: FloatArray,
        val color: FloatArray,
        val intensity: Float,
        val castShadows: Boolean
    )

    data class ArScene(
        var projectName: String,
        var useImageMarker: Boolean,
        var markerPath: String,
        var markerWidth: Float,
        var objects: List<ArObject>,
        var cameraMatrix: FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f },
        var lights: List<ArLight> = emptyList()
    )

    fun save(file: File, scene: ArScene) {
        val bos = FileOutputStream(file)
        try {
            bos.write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
            writeShort(bos, VERSION)

            val pnBytes = scene.projectName.toByteArray(StandardCharsets.UTF_8)
            if (pnBytes.size > 65535) throw IllegalArgumentException("Project name too long")
            writeShort(bos, pnBytes.size.toShort())
            bos.write(pnBytes)

            // AR config
            bos.write(if (scene.useImageMarker) 1 else 0)
            val mkBytes = scene.markerPath.toByteArray(StandardCharsets.UTF_8)
            writeShort(bos, mkBytes.size.toShort())
            if (mkBytes.isNotEmpty()) bos.write(mkBytes)
            writeFloat(bos, scene.markerWidth)

            // Objects
            writeInt(bos, scene.objects.size)
            for (obj in scene.objects) {
                val nameBytes = obj.name.toByteArray(StandardCharsets.UTF_8)
                if (nameBytes.size > 65535) throw IllegalArgumentException("Name too long")
                writeShort(bos, nameBytes.size.toShort())
                bos.write(nameBytes)

                bos.write(obj.sourceType)

                val spBytes = obj.sourcePath.toByteArray(StandardCharsets.UTF_8)
                if (spBytes.size > 65535) throw IllegalArgumentException("Source path too long")
                writeShort(bos, spBytes.size.toShort())
                bos.write(spBytes)

                writeFloats(bos, obj.modelMatrix, 16)
                writeFloats(bos, obj.color, 3)

                bos.write(if (obj.visible) 1 else 0)
                bos.write(if (obj.receiveShadow) 1 else 0)
                writeFloat(bos, obj.scale)
                val payload = obj.embeddedPayload ?: ByteArray(0)
                writeInt(bos, payload.size)
                if (payload.isNotEmpty()) bos.write(payload)
            }

            writeFloats(bos, scene.cameraMatrix, 16)
            writeInt(bos, scene.lights.size)
            for (light in scene.lights) {
                writeFloats(bos, light.direction, 3)
                writeFloats(bos, light.color, 3)
                writeFloat(bos, light.intensity)
                bos.write(if (light.castShadows) 1 else 0)
            }

            bos.flush()
            Log.d(TAG, "Saved AR scene to ${file.absolutePath}")
        } finally {
            try { bos.close() } catch (_: Exception) {}
        }
    }

    fun load(file: File): ArScene? {
        try {
            val bis = FileInputStream(file)
            val bytes = bis.readBytes()
            bis.close()

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(4)
            buf.get(magic)
            if (String(magic, StandardCharsets.US_ASCII) != MAGIC) {
                Log.e(TAG, "Invalid magic: ${String(magic, StandardCharsets.US_ASCII)}")
                return null
            }

            val version = buf.getShort()
            if (version < 1 || version > VERSION) { Log.e(TAG, "Unsupported version: $version"); return null }

            val pnLen = buf.getShort().toInt() and 0xFFFF
            val pnBytes = ByteArray(pnLen)
            buf.get(pnBytes)
            val projectName = String(pnBytes, StandardCharsets.UTF_8)

            val useImageMarker = buf.get() != 0.toByte()
            val mkLen = buf.getShort().toInt() and 0xFFFF
            val markerPath = if (mkLen > 0) {
                val mkBytes = ByteArray(mkLen)
                buf.get(mkBytes)
                String(mkBytes, StandardCharsets.UTF_8)
            } else ""
            val markerWidth = buf.getFloat()

            val objectCount = buf.getInt()
            val objects = mutableListOf<ArObject>()

            for (i in 0 until objectCount) {
                val nameLen = buf.getShort().toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val name = String(nameBytes, StandardCharsets.UTF_8)

                val sourceType = buf.get().toInt() and 0xFF

                val spLen = buf.getShort().toInt() and 0xFFFF
                val spBytes = ByteArray(spLen)
                buf.get(spBytes)
                val sourcePath = String(spBytes, StandardCharsets.UTF_8)

                val modelMatrix = FloatArray(16) { buf.getFloat() }
                val color = FloatArray(3) { buf.getFloat() }

                val visible = buf.get() != 0.toByte()
                val receiveShadow = buf.get() != 0.toByte()
                val scale = buf.getFloat()
                val payload = if (version >= 2) {
                    val payloadLength = buf.getInt()
                    if (payloadLength < 0 || payloadLength > buf.remaining()) return null
                    ByteArray(payloadLength).also { buf.get(it) }
                } else null

                objects.add(ArObject(name, sourceType, sourcePath, modelMatrix, color, visible, receiveShadow, scale, payload))
            }

            val cameraMatrix = if (version >= 2 && buf.remaining() >= 64) {
                FloatArray(16) { buf.getFloat() }
            } else FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
            val lights = mutableListOf<ArLight>()
            if (version >= 2 && buf.remaining() >= 4) {
                val lightCount = buf.getInt()
                if (lightCount < 0 || lightCount > 128) return null
                repeat(lightCount) {
                    if (buf.remaining() < 29) return null
                    lights.add(ArLight(
                        FloatArray(3) { buf.getFloat() },
                        FloatArray(3) { buf.getFloat() },
                        buf.getFloat(),
                        buf.get() != 0.toByte()
                    ))
                }
            }
            return ArScene(projectName, useImageMarker, markerPath, markerWidth, objects, cameraMatrix, lights)
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}", e)
            return null
        }
    }

    fun getProjectName(file: File): String? {
        return try {
            val bis = FileInputStream(file)
            val bytes = bis.readBytes()
            bis.close()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            if (String(magic, StandardCharsets.US_ASCII) != MAGIC) return null
            if (buf.getShort() < 1) return null
            val pnLen = buf.getShort().toInt() and 0xFFFF
            val pnBytes = ByteArray(pnLen)
            buf.get(pnBytes)
            String(pnBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "getProjectName failed: ${e.message}")
            null
        }
    }

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
        for (i in 0 until count) buf.putFloat(arr[i])
        bos.write(buf.array())
    }
}
