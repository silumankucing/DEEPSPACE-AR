package com.example.deepspace

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * MeshImporter — parser untuk file mesh eksternal yang diimpor ke halaman Polygon.
 *
 * Menghasilkan triangle-soup flat (x,y,z per vertex, 3 vertex per segitiga) yang
 * langsung bisa dipakai sebagai [PolygonEngine.SceneObject.vertices].
 */
object MeshImporter {

    val supportedExtensions = setOf("ply", "obj", "stl", "glb", "dspart", "dsasm")

    /** Parse file mesh berdasarkan ekstensi menjadi triangle-soup, atau null bila gagal. */
    fun parse(bytes: ByteArray, ext: String): FloatArray? {
        return when (ext.lowercase()) {
            "ply" -> parsePly(bytes)
            "obj" -> parseObj(bytes)
            "stl" -> parseStl(bytes)
            "glb" -> glbToTriangles(bytes)
            "dspart", "dsasm" -> {
                val glb = DeepspaceBinaryParser.toGlb(bytes, ext)
                if (glb != null) glbToTriangles(glb) else null
            }
            else -> null
        }
    }

    /** Konversi GLB (glTF 2.0 biner) menjadi triangle-soup. */
    fun glbToTriangles(glb: ByteArray): FloatArray? {
        // Pakai pembaca datar: bentuk keluarannya sudah triangle-soup, dan tidak
        // mengalokasikan Tri/V3 per segitiga (aman untuk mesh CAD besar).
        val flat = GlbReader.readTriangleFloats(glb)
        return if (flat.isEmpty()) null else flat
    }

    private data class PlyProperty(
        val name: String,
        val type: String,
        val isList: Boolean = false,
        val countType: String = "",
    )

    // ============================================================================================
    // PLY
    // ============================================================================================

    private fun parsePly(bytes: ByteArray): FloatArray? {
        val headerEnd = findHeaderEnd(bytes) ?: return null
        val header = String(bytes, 0, headerEnd, StandardCharsets.US_ASCII)
        val lines = header.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || lines[0] != "ply") return null

        var binary = false
        var bigEndian = false
        var vertexCount = -1
        var faceCount = -1
        val vertexProps = mutableListOf<PlyProperty>()
        val faceProps = mutableListOf<PlyProperty>()
        var currentElement: String? = null

        for (ln in lines) {
            val parts = ln.split(Regex("\\s+"))
            when (parts[0]) {
                "format" -> when (parts.getOrNull(1)) {
                    "ascii" -> binary = false
                    "binary_little_endian" -> { binary = true; bigEndian = false }
                    "binary_big_endian" -> { binary = true; bigEndian = true }
                }
                "element" -> {
                    currentElement = parts.getOrNull(1)
                    val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    if (currentElement == "vertex") vertexCount = count
                    else if (currentElement == "face") faceCount = count
                }
                "property" -> {
                    if (currentElement == "vertex") {
                        if (parts.getOrNull(1) == "list") {
                            vertexProps.add(PlyProperty(parts.getOrNull(4) ?: "", parts.getOrNull(3) ?: "", true, parts.getOrNull(2) ?: ""))
                        } else {
                            vertexProps.add(PlyProperty(parts.getOrNull(2) ?: "", parts.getOrNull(1) ?: "", false))
                        }
                    } else if (currentElement == "face") {
                        if (parts.getOrNull(1) == "list") {
                            faceProps.add(PlyProperty(parts.getOrNull(4) ?: "", parts.getOrNull(3) ?: "", true, parts.getOrNull(2) ?: ""))
                        } else {
                            faceProps.add(PlyProperty(parts.getOrNull(2) ?: "", parts.getOrNull(1) ?: "", false))
                        }
                    }
                }
            }
        }

        if (vertexCount <= 0) return null
        var bodyStart = headerEnd
        while (bodyStart < bytes.size && (bytes[bodyStart] == '\n'.code.toByte() || bytes[bodyStart] == '\r'.code.toByte())) bodyStart++
        val body = bytes.copyOfRange(bodyStart, bytes.size)

        return if (!binary) {
            parsePlyAscii(body, vertexCount, faceCount, vertexProps, faceProps)
        } else {
            parsePlyBinary(body, vertexCount, faceCount, vertexProps, faceProps, bigEndian)
        }
    }

    private fun parsePlyAscii(
        body: ByteArray,
        vertexCount: Int,
        faceCount: Int,
        vertexProps: List<PlyProperty>,
        faceProps: List<PlyProperty>,
    ): FloatArray? {
        val scalarVertexProps = vertexProps.filter { !it.isList }
        if (scalarVertexProps.size < 3) return null

        val tokens = String(body, StandardCharsets.US_ASCII).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var ti = 0
        val positions = ArrayList<Float>(vertexCount * 3)
        val perRecord = scalarVertexProps.size

        for (v in 0 until vertexCount) {
            if (ti + perRecord > tokens.size) break
            var x = Float.NaN; var y = Float.NaN; var z = Float.NaN
            for ((k, p) in scalarVertexProps.withIndex()) {
                val value = tokens[ti + k].toFloatOrNull() ?: 0f
                when (p.name) {
                    "x", "X" -> x = value
                    "y", "Y" -> y = value
                    "z", "Z" -> z = value
                }
            }
            if (x.isNaN() || y.isNaN() || z.isNaN()) {
                x = tokens[ti].toFloatOrNull() ?: 0f
                y = tokens[ti + 1].toFloatOrNull() ?: 0f
                z = tokens[ti + 2].toFloatOrNull() ?: 0f
            }
            positions.add(x); positions.add(y); positions.add(z)
            ti += perRecord
        }

        val indices = ArrayList<Int>()
        if (faceCount > 0) {
            val faceList = faceProps.firstOrNull { it.isList }
            if (faceList != null) {
                for (f in 0 until faceCount) {
                    if (ti >= tokens.size) break
                    val count = tokens[ti].toIntOrNull() ?: 0
                    ti++
                    if (count < 3) { ti += count; continue }
                    var first = -1; var prev = -1
                    for (k in 0 until count) {
                        if (ti >= tokens.size) break
                        val idx = tokens[ti].toIntOrNull() ?: -1
                        ti++
                        if (idx < 0 || idx >= positions.size / 3) continue
                        if (k == 0) { first = idx; prev = idx }
                        else if (k >= 2) { indices.add(first); indices.add(prev); indices.add(idx) }
                        prev = idx
                    }
                }
            }
        }
        return buildSoup(positions, indices)
    }

    private fun parsePlyBinary(
        body: ByteArray,
        vertexCount: Int,
        faceCount: Int,
        vertexProps: List<PlyProperty>,
        faceProps: List<PlyProperty>,
        bigEndian: Boolean,
    ): FloatArray? {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val bb = ByteBuffer.wrap(body).order(order)

        var vertexRecordSize = 0
        var xOff = -1; var yOff = -1; var zOff = -1
        var off = 0
        for (p in vertexProps) {
            if (p.isList) return null
            val size = plyScalarSize(p.type)
            if (size <= 0) return null
            when (p.name) {
                "x", "X" -> xOff = off
                "y", "Y" -> yOff = off
                "z", "Z" -> zOff = off
            }
            off += size
        }
        vertexRecordSize = off
        if (xOff < 0 || yOff < 0 || zOff < 0 || vertexRecordSize <= 0) return null

        val positions = ArrayList<Float>(vertexCount * 3)
        for (v in 0 until vertexCount) {
            val base = v * vertexRecordSize
            if (base + vertexRecordSize > body.size) break
            positions.add(bb.getFloat(base + xOff))
            positions.add(bb.getFloat(base + yOff))
            positions.add(bb.getFloat(base + zOff))
        }

        val indices = ArrayList<Int>()
        if (faceCount > 0) {
            val faceList = faceProps.firstOrNull { it.isList }
            if (faceList != null) {
                val countSize = plyScalarSize(faceList.countType)
                val itemSize = plyScalarSize(faceList.type)
                if (countSize <= 0 || itemSize <= 0) return null
                var p = vertexCount * vertexRecordSize
                for (f in 0 until faceCount) {
                    if (p + countSize > body.size) break
                    val count = readPlyInt(bb, p, countSize)
                    p += countSize
                    if (count < 3) { p += count * itemSize; continue }
                    var first = -1; var prev = -1
                    for (k in 0 until count) {
                        if (p + itemSize > body.size) break
                        val idx = readPlyInt(bb, p, itemSize)
                        p += itemSize
                        if (idx < 0 || idx >= positions.size / 3) continue
                        if (k == 0) { first = idx; prev = idx }
                        else if (k >= 2) { indices.add(first); indices.add(prev); indices.add(idx) }
                        prev = idx
                    }
                }
            }
        }
        return buildSoup(positions, indices)
    }

    // ============================================================================================
    // OBJ
    // ============================================================================================

    private fun parseObj(bytes: ByteArray): FloatArray? {
        val text = String(bytes, StandardCharsets.UTF_8)
        val positions = ArrayList<Float>()
        val indices = ArrayList<Int>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "v" -> {
                    if (parts.size >= 4) {
                        positions.add(parts[1].toFloatOrNull() ?: 0f)
                        positions.add(parts[2].toFloatOrNull() ?: 0f)
                        positions.add(parts[3].toFloatOrNull() ?: 0f)
                    }
                }
                "f" -> {
                    val faceIdx = ArrayList<Int>()
                    for (i in 1 until parts.size) {
                        val token = parts[i]
                        if (token.isEmpty()) continue
                        val rawIdx = token.split("/")[0].toIntOrNull() ?: continue
                        val idx = if (rawIdx < 0) (positions.size / 3) + rawIdx else (rawIdx - 1)
                        if (idx in 0 until (positions.size / 3)) faceIdx.add(idx)
                    }
                    for (k in 1 until faceIdx.size - 1) {
                        indices.add(faceIdx[0]); indices.add(faceIdx[k]); indices.add(faceIdx[k + 1])
                    }
                }
            }
        }
        return buildSoup(positions, indices)
    }

    // ============================================================================================
    // STL
    // ============================================================================================

    private fun parseStl(bytes: ByteArray): FloatArray? {
        if (bytes.size >= 84) {
            val count = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(80)
            if (count > 0 && 84L + count.toLong() * 50L == bytes.size.toLong()) {
                return parseStlBinary(bytes, count)
            }
        }
        return parseStlAscii(bytes)
    }

    private fun parseStlBinary(bytes: ByteArray, count: Int): FloatArray {
        val out = FloatArray(count * 9)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var o = 0
        for (i in 0 until count) {
            val base = 84 + i * 50
            bb.position(base + 12) // lewati normal (12 byte)
            for (j in 0 until 9) out[o++] = bb.float
        }
        return out
    }

    private fun parseStlAscii(bytes: ByteArray): FloatArray? {
        val text = String(bytes, StandardCharsets.US_ASCII)
        val regex = Regex("vertex\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)")
        val positions = ArrayList<Float>()
        for (m in regex.findAll(text)) {
            positions.add(m.groupValues[1].toFloatOrNull() ?: 0f)
            positions.add(m.groupValues[2].toFloatOrNull() ?: 0f)
            positions.add(m.groupValues[3].toFloatOrNull() ?: 0f)
        }
        if (positions.isEmpty() || positions.size % 9 != 0) return null
        return positions.toFloatArray()
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private fun findHeaderEnd(bytes: ByteArray): Int {
        val marker = "end_header".toByteArray(StandardCharsets.US_ASCII)
        val searchEnd = minOf(bytes.size - marker.size, 1 shl 20)
        if (searchEnd < 0) return -1
        for (i in 0..searchEnd) {
            var ok = true
            for (j in marker.indices) {
                if (bytes[i + j] != marker[j]) { ok = false; break }
            }
            if (ok) return i + marker.size
        }
        return -1
    }

    private fun plyScalarSize(type: String): Int = when (type.lowercase()) {
        "char", "int8", "uchar", "uint8" -> 1
        "short", "int16", "ushort", "uint16" -> 2
        "int", "int32", "uint", "uint32", "float", "float32" -> 4
        "double", "float64" -> 8
        else -> 0
    }

    private fun readPlyInt(bb: ByteBuffer, pos: Int, size: Int): Int = when (size) {
        1 -> bb.get(pos).toInt() and 0xFF
        2 -> bb.getShort(pos).toInt() and 0xFFFF
        else -> bb.getInt(pos)
    }

    private fun buildSoup(positions: List<Float>, indices: List<Int>): FloatArray? {
        if (positions.isEmpty() || indices.isEmpty()) return null
        val out = FloatArray(indices.size * 3)
        var o = 0
        for (idx in indices) {
            val b = idx * 3
            if (idx < 0 || b + 2 >= positions.size) continue
            out[o++] = positions[b]
            out[o++] = positions[b + 1]
            out[o++] = positions[b + 2]
        }
        return if (o == 0) null else out.copyOf(o)
    }
}
