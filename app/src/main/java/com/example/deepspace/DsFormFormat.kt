package com.example.deepspace

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Binary format handler for *.dsform files.
 *
 * Format structure (v3 — group-aware + mesh topology):
 * [4 bytes]  magic: "DSFM"
 * [2 bytes]  version (little-endian); v1 = tanpa children, v2 = dengan children,
 *            v3 = + topologi mesh (posisi welded, face berindex, faceColors)
 * [2 bytes]  projectNameLength (LE)
 * [N bytes]  projectName (UTF-8)
 * [4 bytes]  objectCount (little-endian)
 * For each object:
 *   [2 bytes]  nameLength (LE)
 *   [N bytes]  name (UTF-8)
 *   [2 bytes]  typeLength (LE)
 *   [N bytes]  type (UTF-8)
 *   [4 bytes]  vertexCount (LE) — number of vertices (not floats)
 *   [4 bytes]  vertexFloats = vertexCount * 3
 *   [vertexFloats * 4 bytes] vertices (float32 LE)
 *   [16 * 4 bytes] modelMatrix (float32 LE)
 *   [3 * 4 bytes] color (r, g, b float32 LE)
 *   [1 byte]   visible
 *   [1 byte]   selected
 *   (v3 saja) [1 byte]   hasMesh
 *   (v3 saja) bila hasMesh:
 *             [4 bytes] meshVertexCount (LE)
 *             [meshVertexCount * 3 * 4 bytes] posisi vertex welded (float32 LE)
 *             [4 bytes] meshFaceCount (LE)
 *             per face: [4 bytes] indexCount + indexCount * [4 bytes] index (LE)
 *             [4 bytes] faceColorCount (LE)
 *             per entry: [4 bytes] faceIndex + 3 * [4 bytes] rgb (float32 LE)
 *             [1 byte]   mergeCoplanarSelection
 *   (v2 saja) [4 bytes] childCount (LE)
 *   (v2 saja) childCount x object record rekursif (format yang sama)
 */
object DsFormFormat {
    private const val TAG = "DsFormFormat"
    private const val MAGIC = "DSFM"
    private const val VERSION: Short = 3

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    fun save(file: File, objects: List<PolygonEngine.SceneObject>, projectName: String) {
        val bos = FileOutputStream(file)
        try {
            // Header: magic + version
            bos.write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
            val versionBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            versionBuf.putShort(VERSION)
            bos.write(versionBuf.array())

            // Project name
            val pnBytes = projectName.toByteArray(StandardCharsets.UTF_8)
            if (pnBytes.size > 65535) throw IllegalArgumentException("Project name too long")
            bos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(pnBytes.size.toShort()).array())
            bos.write(pnBytes)

            // Object count
            bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(objects.size).array())

            for (obj in objects) {
                writeObject(bos, obj)
            }

            bos.flush()
            Log.d(TAG, "Saved ${objects.size} objects to ${file.absolutePath}")
        } finally {
            try { bos.close() } catch (_: Exception) {}
        }
    }

    private fun writeObject(bos: FileOutputStream, obj: PolygonEngine.SceneObject) {
                // name
                val nameBytes = obj.name.toByteArray(StandardCharsets.UTF_8)
                if (nameBytes.size > 65535) throw IllegalArgumentException("Name too long: ${obj.name}")
                bos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(nameBytes.size.toShort()).array())
                bos.write(nameBytes)

                // type
                val typeBytes = obj.type.toByteArray(StandardCharsets.UTF_8)
                if (typeBytes.size > 65535) throw IllegalArgumentException("Type too long: ${obj.type}")
                bos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(typeBytes.size.toShort()).array())
                bos.write(typeBytes)

                // vertexCount
                bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(obj.vertexCount).array())

                // vertices (vertexCount * 3 floats)
                val floatCount = obj.vertexCount * 3
                val vertBuf = ByteBuffer.allocate(floatCount * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until floatCount) {
                    vertBuf.putFloat(obj.vertices[i])
                }
                bos.write(vertBuf.array())

                // modelMatrix (16 floats)
                val matBuf = ByteBuffer.allocate(16 * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until 16) {
                    matBuf.putFloat(obj.modelMatrix[i])
                }
                bos.write(matBuf.array())

                // color (3 floats)
                val colBuf = ByteBuffer.allocate(3 * 4).order(ByteOrder.LITTLE_ENDIAN)
                colBuf.putFloat(obj.color[0])
                colBuf.putFloat(obj.color[1])
                colBuf.putFloat(obj.color[2])
                bos.write(colBuf.array())

                // visible, selected
                bos.write(if (obj.visible) 1 else 0)
                bos.write(if (obj.selected) 1 else 0)

                // Topologi mesh (v3): posisi welded + face berindex + faceColors.
                // Ini sumber kebenaran bentuk setelah operasi modeling (extrude,
                // subdivide, merge surface, insert face) — tanpa ini, mesh hasil
                // load jadi bentuk dasar Primitives.create(type) lagi.
                val mesh = obj.mesh
                bos.write(if (mesh != null) 1 else 0)
                if (mesh != null) {
                    val mc = mesh.vertexCount
                    bos.write(leInt(mc))
                    val posBuf = ByteBuffer.allocate(mc * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until mc) {
                        posBuf.putFloat(mesh.vx(i))
                        posBuf.putFloat(mesh.vy(i))
                        posBuf.putFloat(mesh.vz(i))
                    }
                    bos.write(posBuf.array())

                    val fc = mesh.faceCount
                    bos.write(leInt(fc))
                    for (f in mesh.faces) {
                        bos.write(leInt(f.size))
                        val fb = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        for (idx in f) fb.putInt(idx)
                        bos.write(fb.array())
                    }

                    val colors = obj.faceColors
                    bos.write(leInt(colors?.size ?: 0))
                    colors?.forEach { (fi, rgb) ->
                        val cb = ByteBuffer.allocate(4 + 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
                        cb.putInt(fi)
                        cb.putFloat(rgb[0]); cb.putFloat(rgb[1]); cb.putFloat(rgb[2])
                        bos.write(cb.array())
                    }

                    bos.write(if (mesh.mergeCoplanarSelection) 1 else 0)
                }

                // children (v2): tulis jumlah anak lalu record anak rekursif.
                val kids = obj.children ?: emptyList()
                bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(kids.size).array())
                for (c in kids) writeObject(bos, c)
    }

    fun load(file: File): Pair<String, List<PolygonEngine.SceneObject>>? {
        return try {
            val bis = FileInputStream(file)
            val bytes = bis.readBytes()
            bis.close()
            load(bytes)?.also { (_, objects) ->
                Log.d(TAG, "Loaded ${objects.size} objects from ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}", e)
            null
        }
    }

    /** Parse .dsform dari raw bytes (untuk binary parser lintas halaman). */
    fun load(bytes: ByteArray): Pair<String, List<PolygonEngine.SceneObject>>? {
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // Magic
            val magic = ByteArray(4)
            buf.get(magic)
            if (String(magic, StandardCharsets.US_ASCII) != MAGIC) {
                Log.e(TAG, "Invalid magic: ${String(magic, StandardCharsets.US_ASCII)}")
                return null
            }

            // Version
            val version = buf.getShort()
            if (version < 1) {
                Log.e(TAG, "Unsupported version: $version")
                return null
            }

            // Project name
            val pnLen = buf.getShort().toInt() and 0xFFFF
            val pnBytes = ByteArray(pnLen)
            buf.get(pnBytes)
            val projectName = String(pnBytes, StandardCharsets.UTF_8)

            // Object count
            val objectCount = buf.getInt()
            val objects = mutableListOf<PolygonEngine.SceneObject>()

            fun readObject(): PolygonEngine.SceneObject {
                // name
                val nameLen = buf.getShort().toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val name = String(nameBytes, StandardCharsets.UTF_8)

                // type
                val typeLen = buf.getShort().toInt() and 0xFFFF
                val typeBytes = ByteArray(typeLen)
                buf.get(typeBytes)
                val type = String(typeBytes, StandardCharsets.UTF_8)

                // vertexCount
                val vertexCount = buf.getInt()
                val floatCount = vertexCount * 3

                // vertices
                val vertices = FloatArray(floatCount)
                for (j in 0 until floatCount) {
                    vertices[j] = buf.getFloat()
                }

                // modelMatrix
                val modelMatrix = FloatArray(16)
                for (j in 0 until 16) {
                    modelMatrix[j] = buf.getFloat()
                }

                // color
                val r = buf.getFloat()
                val g = buf.getFloat()
                val b = buf.getFloat()

                // visible, selected
                val visible = buf.get() != 0.toByte()
                val selected = buf.get() != 0.toByte()

                // Topologi mesh (v3): pulihkan mesh hasil editing, bukan bentuk dasar.
                var restoredMesh: PolyMesh? = null
                var restoredFaceColors: MutableMap<Int, FloatArray>? = null
                if (version >= 3) {
                    val hasMesh = buf.get() != 0.toByte()
                    if (hasMesh) {
                        val posCount = buf.getInt()
                        val posArr = FloatArray(posCount * 3) { buf.getFloat() }
                        val faceCount = buf.getInt()
                        val faceList = ArrayList<IntArray>(faceCount.coerceAtLeast(0))
                        for (fi in 0 until faceCount) {
                            val n = buf.getInt()
                            faceList.add(IntArray(n) { buf.getInt() })
                        }
                        val colorCount = buf.getInt()
                        if (colorCount > 0) restoredFaceColors = mutableMapOf()
                        for (ci in 0 until colorCount) {
                            val fi = buf.getInt()
                            val r = buf.getFloat(); val g = buf.getFloat(); val b = buf.getFloat()
                            restoredFaceColors?.put(fi, floatArrayOf(r, g, b))
                        }
                        val mergeCoplanar = buf.get() != 0.toByte()
                        val pm = PolyMesh()
                        pm.mergeCoplanarSelection = mergeCoplanar
                        pm.restoreTopology(posArr, faceList)
                        restoredMesh = pm
                    }
                }

                // children (hanya v2; v1 tidak punya field ini)
                val kids: List<PolygonEngine.SceneObject>? = if (version >= 2) {
                    val childCount = buf.getInt()
                    List(childCount) { readObject() }.ifEmpty { null }
                } else null


                val obj = PolygonEngine.SceneObject(
                    name = name,
                    type = type,
                    vertices = vertices,
                    vertexCount = vertexCount,
                    modelMatrix = modelMatrix,
                    selected = selected,
                    visible = visible,
                    color = floatArrayOf(r, g, b),
                    children = kids?.takeIf { it.isNotEmpty() },
                    faceColors = restoredFaceColors,
                    // Prioritas: topologi tersimpan (v3). Fallback v1/v2: bentuk dasar.
                    mesh = restoredMesh ?: Primitives.create(type)
                )
                // Samakan triangle soup dengan topologi yang dipulihkan agar edge,
                // face, dan picking konsisten (bukan memakai soup lama dari file).
                if (obj.mesh != null) obj.syncFromMesh()
                return obj
            }

            for (i in 0 until objectCount) {
                objects.add(readObject())
            }

            Pair(projectName, objects)
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}", e)
            null
        }
    }

    /** Read only the project name from a .dsform file (fast, no object data). */
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

    /**
     * Set project name in an existing .dsform file.
     * Rewrites the file with updated project name.
     */
    fun setProjectName(file: File, newName: String) {
        val result = load(file) ?: return
        val (_, objects) = result
        save(file, objects, newName)
    }
}
