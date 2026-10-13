package com.example.deepspace

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Membangun byte GLB minimal (glTF 2.0 biner) dari mesh segitiga:
 * POSITION + NORMAL + indices, satu mesh, material default.
 * Dipakai halaman AR dan Project untuk mengekspor/menggabungkan mesh menjadi GLB.
 */
object GlbBuilder {

    /**
     * Bangun GLB dari positions (x,y,z flat) + indices (segitiga). Normal dihitung per-vertex.
     * Warna default model: abu-abu netral.
     */
    fun build(positions: FloatArray, indices: IntArray): ByteArray {
        return buildWithColor(positions, indices, 0.62, 0.63, 0.65, 1.0)
    }

    /** Bangun GLB dengan warna kustom (r,g,b,a 0..1). */
    fun buildWithColor(positions: FloatArray, indices: IntArray, r: Double, g: Double, b: Double, a: Double): ByteArray {
        val vertCount = positions.size / 3
        val normals = computeNormals(positions, indices)

        // ── Binary buffer: positions | normals | indices (uint32) ──
        val posBytes = vertCount * 3 * 4
        val normBytes = vertCount * 3 * 4
        val idxBytes = indices.size * 4
        val bin = ByteBuffer.allocate(posBytes + normBytes + idxBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (v in positions) bin.putFloat(v)
        for (v in normals) bin.putFloat(v)
        for (i in indices) bin.putInt(i)
        val binArray = bin.array()

        // min/max posisi untuk accessor POSITION
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var k = 0
        while (k < positions.size) {
            for (c in 0..2) {
                val v = positions[k + c]
                if (v < min[c]) min[c] = v
                if (v > max[c]) max[c] = v
            }
            k += 3
        }

        val gltf = JSONObject()
        gltf.put("asset", JSONObject().put("version", "2.0").put("generator", "DEEPSPACE-OCCT"))
        gltf.put("scene", 0)
        gltf.put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
        gltf.put("nodes", JSONArray().put(JSONObject().put("mesh", 0)))

        val prim = JSONObject()
        prim.put("attributes", JSONObject().put("POSITION", 0).put("NORMAL", 1))
        prim.put("indices", 2)
        prim.put("material", 0)
        prim.put("mode", 4) // triangles
        gltf.put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(prim))))

        val mat = JSONObject()
        val pbr = JSONObject()
        pbr.put("baseColorFactor", JSONArray().put(r).put(g).put(b).put(a))
        pbr.put("metallicFactor", 0.1)
        pbr.put("roughnessFactor", 0.6)
        mat.put("pbrMetallicRoughness", pbr)
        mat.put("doubleSided", true)
        if (a < 1.0) {
            mat.put("alphaMode", "BLEND")
        }
        gltf.put("materials", JSONArray().put(mat))

        gltf.put("buffers", JSONArray().put(JSONObject().put("byteLength", binArray.size)))

        val bufferViews = JSONArray()
        bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", 0).put("byteLength", posBytes).put("target", 34962))
        bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", posBytes).put("byteLength", normBytes).put("target", 34962))
        bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", posBytes + normBytes).put("byteLength", idxBytes).put("target", 34963))
        gltf.put("bufferViews", bufferViews)

        val accessors = JSONArray()
        accessors.put(JSONObject().put("bufferView", 0).put("componentType", 5126).put("count", vertCount)
            .put("type", "VEC3").put("min", JSONArray().put(min[0]).put(min[1]).put(min[2]))
            .put("max", JSONArray().put(max[0]).put(max[1]).put(max[2])))
        accessors.put(JSONObject().put("bufferView", 1).put("componentType", 5126).put("count", vertCount).put("type", "VEC3"))
        accessors.put(JSONObject().put("bufferView", 2).put("componentType", 5125).put("count", indices.size).put("type", "SCALAR"))
        gltf.put("accessors", accessors)

        // ── Rakit GLB ──
        var jsonBytes = gltf.toString().toByteArray(Charsets.UTF_8)
        jsonBytes = padTo4(jsonBytes, 0x20) // pad spasi
        val binPadded = padTo4(binArray, 0x00)

        val total = 12 + 8 + jsonBytes.size + 8 + binPadded.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // "glTF"
        out.putInt(2)
        out.putInt(total)
        out.putInt(jsonBytes.size)
        out.putInt(0x4E4F534A) // "JSON"
        out.put(jsonBytes)
        out.putInt(binPadded.size)
        out.putInt(0x004E4942) // "BIN\0"
        out.put(binPadded)
        return out.array()
    }

    /**
     * Satu part (mesh) di dalam GLB multi-node hasil [buildParts].
     *
     * [positions] sudah berada di ruang akhir (baked), [indices] segitiga terpisah,
     * [color]/[alpha] menjadi material PBR tersendiri per part sehingga warna, material,
     * dan transparansi bisa diatur per part oleh pemanggil.
     */
    class Part(
        val name: String,
        val positions: FloatArray,
        val indices: IntArray,
        val color: FloatArray = floatArrayOf(0.62f, 0.63f, 0.65f),
        val alpha: Float = 1.0f,
    )

    /**
     * Bangun GLB yang berisi **satu node + mesh + primitive + material per part**.
     *
     * Berbeda dengan [build] yang menggabungkan semua geometri ke satu mesh, versi ini
     * menghasilkan renderable terpisah per part di gltfio/Filament: setiap part punya
     * entity sendiri sehingga bisa diseleksi, di-highlight, diberi material, dibuat
     * transparan, disembunyikan, dan di-transform secara individual.
     *
     * Nama node = [Part.name], urutan node = urutan [parts] (sejajar dengan
     * `renderableEntities` dan [GlbReader.readPartTriangleFloats]).
     *
     * @return null bila tidak ada part yang punya geometri valid.
     */
    fun buildParts(parts: List<Part>): ByteArray? {
        val valid = parts.filter { it.positions.size >= 9 && it.indices.size >= 3 }
        if (valid.isEmpty()) return null

        // ── Binary buffer: per part [positions | normals | indices (uint32)] ──
        var binSize = 0
        val layouts = ArrayList<IntArray>(valid.size) // [posOff, normOff, idxOff, idxCount] per part
        for (p in valid) {
            val vertCount = p.positions.size / 3
            layouts.add(intArrayOf(binSize, binSize + vertCount * 12, binSize + vertCount * 24, p.indices.size))
            binSize += vertCount * 24 + p.indices.size * 4
        }
        val bin = ByteBuffer.allocate(binSize).order(ByteOrder.LITTLE_ENDIAN)
        for (p in valid) {
            val normals = computeNormals(p.positions, p.indices)
            for (v in p.positions) bin.putFloat(v)
            for (v in normals) bin.putFloat(v)
            for (i in p.indices) bin.putInt(i)
        }
        val binArray = bin.array()

        val gltf = JSONObject()
        gltf.put("asset", JSONObject().put("version", "2.0").put("generator", "DEEPSPACE-OCCT-parts"))
        gltf.put("scene", 0)
        val sceneNodes = JSONArray()
        val nodes = JSONArray()
        val meshes = JSONArray()
        val materials = JSONArray()
        val bufferViews = JSONArray()
        val accessors = JSONArray()

        for ((i, p) in valid.withIndex()) {
            appendPart(sceneNodes, nodes, meshes, materials, bufferViews, accessors, i, p, layouts[i])
        }

        gltf.put("nodes", nodes)
        gltf.put("meshes", meshes)
        gltf.put("materials", materials)
        gltf.put("scenes", JSONArray().put(JSONObject().put("nodes", sceneNodes)))
        gltf.put("buffers", JSONArray().put(JSONObject().put("byteLength", binArray.size)))
        gltf.put("bufferViews", bufferViews)
        gltf.put("accessors", accessors)

        return assembleGlb(gltf, binArray)
    }

    /** Tulis JSON satu part (node, mesh, material, bufferViews, accessors). */
    private fun appendPart(
        sceneNodes: JSONArray,
        nodes: JSONArray,
        meshes: JSONArray,
        materials: JSONArray,
        bufferViews: JSONArray,
        accessors: JSONArray,
        i: Int,
        p: Part,
        layout: IntArray,
    ) {
        val vertCount = p.positions.size / 3
        val posOff = layout[0]; val normOff = layout[1]; val idxOff = layout[2]; val idxCount = layout[3]

        // min/max posisi untuk accessor POSITION
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var k = 0
        while (k < p.positions.size) {
            for (c in 0..2) {
                val v = p.positions[k + c]
                if (v < min[c]) min[c] = v
                if (v > max[c]) max[c] = v
            }
            k += 3
        }

        val prim = JSONObject()
        prim.put("attributes", JSONObject().put("POSITION", 3 * i).put("NORMAL", 3 * i + 1))
        prim.put("indices", 3 * i + 2)
        prim.put("material", i)
        prim.put("mode", 4) // triangles
        meshes.put(JSONObject().put("name", p.name).put("primitives", JSONArray().put(prim)))

        nodes.put(JSONObject().put("name", p.name).put("mesh", i))
        sceneNodes.put(i)

        val r = p.color.getOrElse(0) { 0.62f }.toDouble()
        val g = p.color.getOrElse(1) { 0.63f }.toDouble()
        val b = p.color.getOrElse(2) { 0.65f }.toDouble()
        val a = p.alpha.toDouble().coerceIn(0.0, 1.0)
        val mat = JSONObject()
        val pbr = JSONObject()
        pbr.put("baseColorFactor", JSONArray().put(r).put(g).put(b).put(a))
        pbr.put("metallicFactor", 0.1)
        pbr.put("roughnessFactor", 0.6)
        mat.put("pbrMetallicRoughness", pbr)
        mat.put("doubleSided", true)
        if (a < 1.0) {
            mat.put("alphaMode", "BLEND")
        }
        materials.put(mat)

        bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", posOff).put("byteLength", vertCount * 12).put("target", 34962))
        bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", normOff).put("byteLength", vertCount * 12).put("target", 34962))
        bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", idxOff).put("byteLength", idxCount * 4).put("target", 34963))

        accessors.put(JSONObject().put("bufferView", 3 * i).put("componentType", 5126).put("count", vertCount)
            .put("type", "VEC3").put("min", JSONArray().put(min[0]).put(min[1]).put(min[2]))
            .put("max", JSONArray().put(max[0]).put(max[1]).put(max[2])))
        accessors.put(JSONObject().put("bufferView", 3 * i + 1).put("componentType", 5126).put("count", vertCount).put("type", "VEC3"))
        accessors.put(JSONObject().put("bufferView", 3 * i + 2).put("componentType", 5125).put("count", idxCount).put("type", "SCALAR"))
    }

    /** Rakit GLB utuh dari JSON gltf + binary chunk (dipakai [build] dan [buildParts]). */
    private fun assembleGlb(gltf: JSONObject, binArray: ByteArray): ByteArray {
        var jsonBytes = gltf.toString().toByteArray(Charsets.UTF_8)
        jsonBytes = padTo4(jsonBytes, 0x20) // pad spasi
        val binPadded = padTo4(binArray, 0x00)

        val total = 12 + 8 + jsonBytes.size + 8 + binPadded.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // "glTF"
        out.putInt(2)
        out.putInt(total)
        out.putInt(jsonBytes.size)
        out.putInt(0x4E4F534A) // "JSON"
        out.put(jsonBytes)
        out.putInt(binPadded.size)
        out.putInt(0x004E4942) // "BIN\0"
        out.put(binPadded)
        return out.array()
    }

    private fun computeNormals(positions: FloatArray, indices: IntArray): FloatArray {
        val normals = FloatArray(positions.size)
        var t = 0
        while (t < indices.size) {
            val a = indices[t] * 3; val b = indices[t + 1] * 3; val c = indices[t + 2] * 3
            val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (idx in intArrayOf(a, b, c)) {
                normals[idx] += nx; normals[idx + 1] += ny; normals[idx + 2] += nz
            }
            t += 3
        }
        var i = 0
        while (i < normals.size) {
            val len = Math.sqrt((normals[i] * normals[i] + normals[i + 1] * normals[i + 1] + normals[i + 2] * normals[i + 2]).toDouble()).toFloat()
            if (len > 1e-6f) { normals[i] /= len; normals[i + 1] /= len; normals[i + 2] /= len }
            else { normals[i] = 0f; normals[i + 1] = 0f; normals[i + 2] = 1f }
            i += 3
        }
        return normals
    }

    private fun padTo4(data: ByteArray, pad: Int): ByteArray {
        val rem = data.size % 4
        if (rem == 0) return data
        val padded = data.copyOf(data.size + (4 - rem))
        for (i in data.size until padded.size) padded[i] = pad.toByte()
        return padded
    }
}
