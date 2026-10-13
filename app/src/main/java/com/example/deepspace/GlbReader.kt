package com.example.deepspace

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GlbReader — pembaca GLB (glTF 2.0 biner) minimal: mengambil segitiga dunia dari seluruh
 * mesh, lengkap dengan transformasi node (TRS maupun matrix) di sepanjang hirarki scene.
 *
 * Dipakai halaman AR dan Project untuk membaca model GLB yang diimpor maupun yang
 * dihasilkan [GlbBuilder].
 *
 * Batasan yang disengaja: hanya buffer dari chunk BIN (bukan URI eksternal / data URI),
 * primitive mode 4 (triangles), dan accessor non-sparse — sesuai GLB yang dihasilkan
 * [GlbBuilder] dan eksportir CAD umum.
 */
object GlbReader {

    /** Baca semua segitiga dalam koordinat dunia. Mengembalikan list kosong bila gagal. */
    fun readTriangles(glb: ByteArray): List<Tri> {
        return try {
            val out = mutableListOf<Tri>()
            walkScene(glb) { _, gltf, bin, meshIndex, world ->
                readMesh(gltf, bin, meshIndex, world, out)
            }
            out
        } catch (e: Throwable) {
            android.util.Log.e("GlbReader", "parse failed", e)
            emptyList()
        }
    }

    /**
     * Varian hemat memori dari [readTriangles]: segitiga dikembalikan sebagai array datar
     * `[ax,ay,az, bx,by,bz, cx,cy,cz, …]` (9 float per segitiga).
     *
     * [readTriangles] mengalokasikan satu `Tri` + tiga `V3` per segitiga (~150 byte), sehingga
     * mesh CAD berukuran ratusan ribu segitiga bisa memicu `OutOfMemoryError`. Versi ini hanya
     * butuh 36 byte per segitiga dan dipakai jalur render AR yang cuma perlu posisi.
     */
    fun readTriangleFloats(glb: ByteArray): FloatArray {
        return try {
            val sink = FloatSink()
            walkScene(glb) { _, gltf, bin, meshIndex, world ->
                readMeshFlat(gltf, bin, meshIndex, world, sink)
            }
            sink.toFloatArray()
        } catch (e: Throwable) {
            android.util.Log.e("GlbReader", "flat parse failed", e)
            FloatArray(0)
        }
    }

    /**
     * Nama node + segitiga (9 float per segitiga, ruang dunia GLB) untuk **setiap primitive**
     * yang punya geometri, urut seperti traversal scene GLB.
     *
     * gltfio membuat satu renderable entity per primitive, sehingga urutan hasil fungsi ini
     * sejajar dengan `renderableEntities` — dipakai halaman AR untuk pick presisi
     * (part mana yang benar-benar disentuh) dan untuk memetakan baris tree ke entity.
     */
    fun readPartTriangleFloats(glb: ByteArray): List<Pair<String, FloatArray>> {
        return try {
            val out = mutableListOf<Pair<String, FloatArray>>()
            walkScene(glb) { name, gltf, bin, meshIndex, world ->
                val mesh = gltf.optJSONArray("meshes")?.optJSONObject(meshIndex) ?: return@walkScene
                val prims = mesh.optJSONArray("primitives") ?: return@walkScene
                for (p in 0 until prims.length()) {
                    val sink = FloatSink()
                    readPrimFlat(gltf, bin, meshIndex, p, world, sink)
                    if (sink.size > 0) out.add(name to sink.toFloatArray())
                }
            }
            out
        } catch (e: Throwable) {
            android.util.Log.e("GlbReader", "part parse failed", e)
            emptyList()
        }
    }

    /**
     * Sama seperti [readPartTriangleFloats], tetapi juga mengekstrak warna material
     * (baseColorFactor RGBA) untuk setiap primitive/part.
     *
     * Mengembalikan list of (name, triangleFloats, colorRGBA) per part.
     * Warna default (0.62f, 0.63f, 0.65f, 1.0f) bila material tidak ditemukan.
     */
    fun readPartTriangleFloatsWithColor(glb: ByteArray): List<Triple<String, FloatArray, FloatArray>> {
        return try {
            val out = mutableListOf<Triple<String, FloatArray, FloatArray>>()
            walkScene(glb) { name, gltf, bin, meshIndex, world ->
                val mesh = gltf.optJSONArray("meshes")?.optJSONObject(meshIndex) ?: return@walkScene
                val prims = mesh.optJSONArray("primitives") ?: return@walkScene
                for (p in 0 until prims.length()) {
                    val sink = FloatSink()
                    readPrimFlat(gltf, bin, meshIndex, p, world, sink)
                    if (sink.size > 0) {
                        val color = readPrimColor(gltf, meshIndex, p)
                        out.add(Triple(name, sink.toFloatArray(), color))
                    }
                }
            }
            out
        } catch (e: Throwable) {
            android.util.Log.e("GlbReader", "part color parse failed", e)
            emptyList()
        }
    }

    /**
     * Baca baseColorFactor (RGBA) dari material primitive pada mesh [meshIndex]/primitive [primIndex].
     * Default abu-abu netral bila tidak tersedia.
     */
    private fun readPrimColor(gltf: JSONObject, meshIndex: Int, primIndex: Int): FloatArray {
        val prim = gltf.optJSONArray("meshes")?.optJSONObject(meshIndex)
            ?.optJSONArray("primitives")?.optJSONObject(primIndex) ?: return floatArrayOf(0.62f, 0.63f, 0.65f, 1.0f)
        val matIdx = prim.optInt("material", -1)
        if (matIdx < 0) return floatArrayOf(0.62f, 0.63f, 0.65f, 1.0f)
        val mat = gltf.optJSONArray("materials")?.optJSONObject(matIdx) ?: return floatArrayOf(0.62f, 0.63f, 0.65f, 1.0f)
        val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: return floatArrayOf(0.62f, 0.63f, 0.65f, 1.0f)
        val bcf = pbr.optJSONArray("baseColorFactor") ?: return floatArrayOf(0.62f, 0.63f, 0.65f, 1.0f)
        return floatArrayOf(
            bcf.optDouble(0).toFloat(),
            bcf.optDouble(1).toFloat(),
            bcf.optDouble(2).toFloat(),
            bcf.optDouble(3, 1.0).toFloat()
        )
    }

    /**
     * True bila GLB mengandung minimal satu material bertekstur (baseColorTexture /
     * metallicRoughnessTexture / normalTexture / emissiveTexture / occlusionTexture).
     * Dipakai sebagai gate: bila tidak ada tekstur, halaman AR fallback ke jalur vertex-color.
     */
    fun hasTextures(glb: ByteArray): Boolean {
        val (json, _) = split(glb) ?: return false
        val root = JSONObject(json)
        val materials = root.optJSONArray("materials") ?: return false
        for (i in 0 until materials.length()) {
            val mat = materials.optJSONObject(i) ?: continue
            if (hasMaterialTexture(mat)) return true
        }
        return false
    }

    private fun hasMaterialTexture(mat: JSONObject): Boolean {
        val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: return false
        if (pbr.has("baseColorTexture") && !pbr.isNull("baseColorTexture")) return true
        if (pbr.has("metallicRoughnessTexture") && !pbr.isNull("metallicRoughnessTexture")) return true
        if (mat.has("normalTexture") && !mat.isNull("normalTexture")) return true
        if (mat.has("emissiveTexture") && !mat.isNull("emissiveTexture")) return true
        if (mat.has("occlusionTexture") && !mat.isNull("occlusionTexture")) return true
        return false
    }

    // ─────────────────────── Internal ───────────────────────

    /**
     * Telusuri hirarki scene GLB dan panggil [onMesh] untuk setiap node bermesh, lengkap
     * dengan nama node dan matriks dunianya. Dipakai bersama oleh [readTriangles],
     * [readTriangleFloats], dan [readPartTriangleFloats].
     */
    private inline fun walkScene(
        glb: ByteArray,
        onMesh: (name: String, gltf: JSONObject, bin: ByteArray, meshIndex: Int, world: DoubleArray) -> Unit
    ) {
        val (json, bin) = split(glb) ?: return
        val gltf = JSONObject(json)
        val nodes = gltf.optJSONArray("nodes") ?: return
        val scenes = gltf.optJSONArray("scenes")
        val sceneIndex = gltf.optInt("scene", 0)
        val roots = scenes?.optJSONObject(sceneIndex)?.optJSONArray("nodes")
            ?: JSONArray().apply { for (i in 0 until nodes.length()) put(i) }

        val identity = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        )
        val stack = ArrayDeque<Pair<Int, DoubleArray>>()
        for (i in 0 until roots.length()) stack.addLast(roots.getInt(i) to identity)
        val guard = nodes.length() * 4 + 64
        var steps = 0
        while (stack.isNotEmpty() && steps++ < guard) {
            val (ni, parent) = stack.removeLast()
            val node = nodes.optJSONObject(ni) ?: continue
            val world = multiply(parent, localMatrix(node))
            val nodeName = node.optString("name", "")
            if (node.has("mesh")) onMesh(nodeName, gltf, bin, node.getInt("mesh"), world)
            val children = node.optJSONArray("children")
            if (children != null) for (c in 0 until children.length()) {
                stack.addLast(children.getInt(c) to world)
            }
        }
    }

    /** Penampung FloatArray yang tumbuh sendiri — menghindari boxing `ArrayList<Float>`. */
    private class FloatSink {
        private var data = FloatArray(4096)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toFloatArray(): FloatArray = data.copyOf(size)
    }

    /** Seperti [readMesh], tetapi menulis posisi datar ke [sink] tanpa objek per segitiga. */
    private fun readMeshFlat(
        gltf: JSONObject,
        bin: ByteArray,
        meshIndex: Int,
        world: DoubleArray,
        sink: FloatSink
    ) {
        val prims = gltf.optJSONArray("meshes")?.optJSONObject(meshIndex)?.optJSONArray("primitives") ?: return
        for (p in 0 until prims.length()) readPrimFlat(gltf, bin, meshIndex, p, world, sink)
    }

    /** Baca satu primitive mesh (indeks [primIndex]) dan tulis segitiga datar ke [sink]. */
    private fun readPrimFlat(
        gltf: JSONObject,
        bin: ByteArray,
        meshIndex: Int,
        primIndex: Int,
        world: DoubleArray,
        sink: FloatSink
    ) {
        val mesh = gltf.optJSONArray("meshes")?.optJSONObject(meshIndex) ?: return
        val prim = mesh.optJSONArray("primitives")?.optJSONObject(primIndex) ?: return
        if (prim.optInt("mode", 4) != 4) return
        val posAccessor = prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: return
        if (posAccessor < 0) return
        val positions = readVec3Flat(gltf, bin, posAccessor) ?: return
        val vertCount = positions.size / 3
        val indices = if (prim.has("indices")) readIndices(gltf, bin, prim.getInt("indices"))
        else IntArray(vertCount) { it }
        var i = 0
        while (i + 2 < indices.size) {
            val ia = indices[i]; val ib = indices[i + 1]; val ic = indices[i + 2]
            i += 3
            if (ia < 0 || ib < 0 || ic < 0) continue
            if (ia >= vertCount || ib >= vertCount || ic >= vertCount) continue
            val ax = tx(world, positions, ia); val ay = ty(world, positions, ia); val az = tz(world, positions, ia)
            val bx = tx(world, positions, ib); val by = ty(world, positions, ib); val bz = tz(world, positions, ib)
            val cx = tx(world, positions, ic); val cy = ty(world, positions, ic); val cz = tz(world, positions, ic)
            // Buang segitiga degenerate memakai panjang cross product (sama seperti Tri.area2).
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            if (nx * nx + ny * ny + nz * nz <= 1e-32) continue
            sink.add(ax.toFloat()); sink.add(ay.toFloat()); sink.add(az.toFloat())
            sink.add(bx.toFloat()); sink.add(by.toFloat()); sink.add(bz.toFloat())
            sink.add(cx.toFloat()); sink.add(cy.toFloat()); sink.add(cz.toFloat())
        }
    }

    private fun tx(m: DoubleArray, p: FloatArray, i: Int) =
        m[0] * p[i * 3] + m[4] * p[i * 3 + 1] + m[8] * p[i * 3 + 2] + m[12]

    private fun ty(m: DoubleArray, p: FloatArray, i: Int) =
        m[1] * p[i * 3] + m[5] * p[i * 3 + 1] + m[9] * p[i * 3 + 2] + m[13]

    private fun tz(m: DoubleArray, p: FloatArray, i: Int) =
        m[2] * p[i * 3] + m[6] * p[i * 3 + 1] + m[10] * p[i * 3 + 2] + m[14]

    /** Versi datar dari [readVec3]: FloatArray `[x,y,z, …]`, atau null bila accessor tak didukung. */
    private fun readVec3Flat(gltf: JSONObject, bin: ByteArray, accessorIndex: Int): FloatArray? {
        val acc = gltf.optJSONArray("accessors")?.optJSONObject(accessorIndex) ?: return null
        if (acc.optString("type") != "VEC3" || acc.optInt("componentType") != 5126) return null
        if (acc.has("sparse")) return null
        val count = acc.optInt("count")
        if (count <= 0) return null
        val view = gltf.optJSONArray("bufferViews")?.optJSONObject(acc.optInt("bufferView", -1)) ?: return null
        val base = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val stride = view.optInt("byteStride", 12).let { if (it == 0) 12 else it }
        val buf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(count * 3)
        var n = 0
        for (i in 0 until count) {
            val off = base + i * stride
            if (off < 0 || off + 12 > bin.size) break
            out[n++] = buf.getFloat(off)
            out[n++] = buf.getFloat(off + 4)
            out[n++] = buf.getFloat(off + 8)
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    /** Pisahkan chunk JSON dan BIN dari container GLB. */
    fun split(glb: ByteArray): Pair<String, ByteArray>? {
        if (glb.size < 20) return null
        val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != 0x46546C67) return null // "glTF"
        buf.int // versi
        buf.int // panjang total
        var json: String? = null
        var bin = ByteArray(0)
        while (buf.remaining() >= 8) {
            val len = buf.int
            val type = buf.int
            if (len < 0 || len > buf.remaining()) break
            val chunk = ByteArray(len)
            buf.get(chunk)
            when (type) {
                0x4E4F534A -> json = String(chunk, Charsets.UTF_8).trim { it <= ' ' }
                0x004E4942 -> bin = chunk
            }
        }
        val j = json ?: return null
        return j to bin
    }

    // ─────────────────────── Penggabungan multi-GLB ───────────────────────

    /**
     * Gabung beberapa GLB menjadi satu scene multi-object (dipakai saat beberapa model
     * diimpor bersamaan): setiap model dibungkus satu node group tanpa mesh (nama = [modelName])
     * sebagai root terpisah, sehingga tree object bisa menampilkan
     * `Object/Model → part` sementara geometry aslinya utuh.
     *
     * Mesh, material, accessor, dan bufferView tiap sumber di-remap; binary tiap sumber
     * digabung dengan offset yang disesuaikan. Referensi tekstur/skin/camera tidak dibawa —
     * GLB internal Deepspace tidak memakainya dan material katalog diterapkan runtime.
     *
     * Satu model dikembalikan apa adanya; tidak ada model → null.
     */
    fun mergeGlbs(models: List<Pair<String, ByteArray>>): ByteArray? {
        if (models.isEmpty()) return null
        if (models.size == 1) return models[0].second
        return try {
            val outNodes = JSONArray()
            val outMeshes = JSONArray()
            val outMaterials = JSONArray()
            val outViews = JSONArray()
            val outAccessors = JSONArray()
            val binOut = java.io.ByteArrayOutputStream()
            val sceneRoots = JSONArray()

            for ((modelName, glb) in models) {
                val (json, bin) = split(glb) ?: continue
                val src = JSONObject(json)
                val srcViews = src.optJSONArray("bufferViews")
                val srcAccs = src.optJSONArray("accessors")
                val srcMeshes = src.optJSONArray("meshes")
                val srcMats = src.optJSONArray("materials")
                val srcNodes = src.optJSONArray("nodes") ?: continue

                // BIN sumber disambung setelah alignment 4-byte; offset bufferView
                // sumber digeser sejauh base ini.
                while (binOut.size() % 4 != 0) binOut.write(0)
                val binBase = binOut.size()
                binOut.write(bin)

                fun copyView(vi: Int): Int {
                    val v = srcViews?.optJSONObject(vi) ?: return -1
                    val copy = JSONObject(v.toString())
                    copy.put("byteOffset", binBase + v.optInt("byteOffset", 0))
                    outViews.put(copy)
                    return outViews.length() - 1
                }

                fun copyAccessor(ai: Int): Int {
                    val a = srcAccs?.optJSONObject(ai) ?: return -1
                    val newVi = copyView(a.optInt("bufferView", -1))
                    if (newVi < 0) return -1
                    val copy = JSONObject(a.toString())
                    copy.put("bufferView", newVi)
                    outAccessors.put(copy)
                    return outAccessors.length() - 1
                }

                fun copyMaterial(mi: Int): Int {
                    if (mi < 0) return -1
                    val m = srcMats?.optJSONObject(mi) ?: return -1
                    val copy = JSONObject(m.toString())
                    // Referensi tekstur dibuang (images/samplers tidak ikut digabung).
                    copy.remove("occlusionTexture"); copy.remove("normalTexture"); copy.remove("emissiveTexture")
                    copy.optJSONObject("pbrMetallicRoughness")?.let { pbr ->
                        pbr.remove("baseColorTexture"); pbr.remove("metallicRoughnessTexture")
                    }
                    outMaterials.put(copy)
                    return outMaterials.length() - 1
                }

                fun copyMesh(mi: Int): Int {
                    val mesh = srcMeshes?.optJSONObject(mi) ?: return -1
                    val copy = JSONObject(mesh.toString())
                    val prims = copy.optJSONArray("primitives")
                    if (prims != null) for (p in 0 until prims.length()) {
                        val prim = prims.optJSONObject(p) ?: continue
                        prim.optJSONObject("attributes")?.let { attrs ->
                            val remapped = JSONObject()
                            val keys = attrs.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                remapped.put(k, copyAccessor(attrs.getInt(k)))
                            }
                            prim.put("attributes", remapped)
                        }
                        if (prim.has("indices")) prim.put("indices", copyAccessor(prim.getInt("indices")))
                        prim.put("material", copyMaterial(prim.optInt("material", -1)))
                    }
                    outMeshes.put(copy)
                    return outMeshes.length() - 1
                }

                // Salin node yang reachable dari scene roots. -2 = sedang diproses (anti-siklus).
                val nodeMap = HashMap<Int, Int>()

                fun copyNode(ni: Int): Int {
                    nodeMap[ni]?.let { if (it != -2) return it else return -1 }
                    val node = srcNodes.optJSONObject(ni) ?: return -1
                    nodeMap[ni] = -2
                    val copy = JSONObject(node.toString())
                    copy.remove("skin"); copy.remove("camera")
                    val remappedChildren = JSONArray()
                    node.optJSONArray("children")?.let { ch ->
                        for (i in 0 until ch.length()) {
                            val c = copyNode(ch.getInt(i))
                            if (c >= 0) remappedChildren.put(c)
                        }
                    }
                    if (remappedChildren.length() > 0) copy.put("children", remappedChildren)
                    else copy.remove("children")
                    if (copy.has("mesh")) {
                        val m = copyMesh(copy.getInt("mesh"))
                        if (m >= 0) copy.put("mesh", m) else copy.remove("mesh")
                    }
                    outNodes.put(copy)
                    val outIdx = outNodes.length() - 1
                    nodeMap[ni] = outIdx
                    return outIdx
                }

                // Kumpulkan root node sumber (dari scenes[scene], atau semua node bila
                // scenes tidak ada), lalu bungkus dalam satu node group per model.
                val roots = JSONArray()
                val sceneIdx = src.optInt("scene", 0)
                val srcRoots = src.optJSONArray("scenes")?.optJSONObject(sceneIdx)?.optJSONArray("nodes")
                if (srcRoots != null) {
                    for (i in 0 until srcRoots.length()) {
                        val c = copyNode(srcRoots.getInt(i))
                        if (c >= 0) roots.put(c)
                    }
                } else {
                    for (i in 0 until srcNodes.length()) {
                        val c = copyNode(i)
                        if (c >= 0) roots.put(c)
                    }
                }
                outNodes.put(JSONObject().put("name", modelName).put("children", roots))
                sceneRoots.put(outNodes.length() - 1)
            }

            if (sceneRoots.length() == 0) return null
            val gltf = JSONObject()
            gltf.put("asset", JSONObject().put("version", "2.0").put("generator", "DEEPSPACE-merge"))
            gltf.put("scene", 0)
            gltf.put("scenes", JSONArray().put(JSONObject().put("nodes", sceneRoots)))
            gltf.put("nodes", outNodes)
            if (outMeshes.length() > 0) gltf.put("meshes", outMeshes)
            if (outMaterials.length() > 0) gltf.put("materials", outMaterials)
            gltf.put("bufferViews", outViews)
            gltf.put("accessors", outAccessors)
            gltf.put("buffers", JSONArray().put(JSONObject().put("byteLength", binOut.size())))
            assembleGlb(gltf, binOut.toByteArray())
        } catch (e: Throwable) {
            android.util.Log.e("GlbReader", "mergeGlbs failed", e)
            null
        }
    }

    /** Susun ulang container GLB (header + chunk JSON + chunk BIN) dari JSON dan BIN. */
    private fun assembleGlb(json: JSONObject, bin: ByteArray): ByteArray {
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val binPad = (4 - bin.size % 4) % 4
        val total = 12 + 8 + jsonBytes.size + jsonPad + 8 + bin.size + binPad
        val out = java.io.ByteArrayOutputStream(total)
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x46546C67) // "glTF"
        header.putInt(2)
        header.putInt(total)
        out.write(header.array())
        header.clear()
        header.putInt(jsonBytes.size + jsonPad)
        header.putInt(0x4E4F534A) // "JSON"
        out.write(header.array())
        out.write(jsonBytes)
        repeat(jsonPad) { out.write(0x20) } // padding spasi agar panjang chunk kelipatan 4
        header.clear()
        header.putInt(bin.size + binPad)
        header.putInt(0x004E4942) // "BIN\0"
        out.write(header.array())
        out.write(bin)
        repeat(binPad) { out.write(0) }
        return out.toByteArray()
    }

    private fun readMesh(
        gltf: JSONObject,
        bin: ByteArray,
        meshIndex: Int,
        world: DoubleArray,
        out: MutableList<Tri>
    ) {
        val mesh = gltf.optJSONArray("meshes")?.optJSONObject(meshIndex) ?: return
        val prims = mesh.optJSONArray("primitives") ?: return
        for (p in 0 until prims.length()) {
            val prim = prims.optJSONObject(p) ?: continue
            if (prim.optInt("mode", 4) != 4) continue
            val posAccessor = prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: continue
            if (posAccessor < 0) continue
            val positions = readVec3(gltf, bin, posAccessor) ?: continue
            val indices = if (prim.has("indices")) readIndices(gltf, bin, prim.getInt("indices"))
            else IntArray(positions.size) { it }
            var i = 0
            while (i + 2 < indices.size) {
                val a = positions.getOrNull(indices[i])
                val b = positions.getOrNull(indices[i + 1])
                val c = positions.getOrNull(indices[i + 2])
                i += 3
                if (a == null || b == null || c == null) continue
                val t = Tri(transform(world, a), transform(world, b), transform(world, c))
                if (t.area2() > 1e-16) out.add(t)
            }
        }
    }

    private fun readVec3(gltf: JSONObject, bin: ByteArray, accessorIndex: Int): List<V3>? {
        val acc = gltf.optJSONArray("accessors")?.optJSONObject(accessorIndex) ?: return null
        if (acc.optString("type") != "VEC3" || acc.optInt("componentType") != 5126) return null
        if (acc.has("sparse")) return null
        val count = acc.optInt("count")
        val view = gltf.optJSONArray("bufferViews")?.optJSONObject(acc.optInt("bufferView", -1)) ?: return null
        val base = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val stride = view.optInt("byteStride", 12).let { if (it == 0) 12 else it }
        val buf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<V3>(count)
        for (i in 0 until count) {
            val off = base + i * stride
            if (off + 12 > bin.size) break
            out.add(V3(buf.getFloat(off).toDouble(), buf.getFloat(off + 4).toDouble(), buf.getFloat(off + 8).toDouble()))
        }
        return out
    }

    private fun readIndices(gltf: JSONObject, bin: ByteArray, accessorIndex: Int): IntArray {
        val acc = gltf.optJSONArray("accessors")?.optJSONObject(accessorIndex) ?: return IntArray(0)
        val count = acc.optInt("count")
        val componentType = acc.optInt("componentType")
        val view = gltf.optJSONArray("bufferViews")?.optJSONObject(acc.optInt("bufferView", -1))
            ?: return IntArray(0)
        val base = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val size = when (componentType) {
            5121 -> 1 // unsigned byte
            5123 -> 2 // unsigned short
            5125 -> 4 // unsigned int
            else -> return IntArray(0)
        }
        val stride = view.optInt("byteStride", size).let { if (it == 0) size else it }
        val buf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val out = IntArray(count)
        for (i in 0 until count) {
            val off = base + i * stride
            if (off + size > bin.size) return out.copyOf(i)
            out[i] = when (size) {
                1 -> bin[off].toInt() and 0xFF
                2 -> buf.getShort(off).toInt() and 0xFFFF
                else -> buf.getInt(off)
            }
        }
        return out
    }

    /** Matriks lokal node: `matrix` bila ada, selain itu komposisi T·R·S. */
    private fun localMatrix(node: JSONObject): DoubleArray {
        node.optJSONArray("matrix")?.let { m ->
            if (m.length() == 16) return DoubleArray(16) { m.getDouble(it) }
        }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val tx = t?.optDouble(0, 0.0) ?: 0.0
        val ty = t?.optDouble(1, 0.0) ?: 0.0
        val tz = t?.optDouble(2, 0.0) ?: 0.0
        val sx = s?.optDouble(0, 1.0) ?: 1.0
        val sy = s?.optDouble(1, 1.0) ?: 1.0
        val sz = s?.optDouble(2, 1.0) ?: 1.0
        val qx = r?.optDouble(0, 0.0) ?: 0.0
        val qy = r?.optDouble(1, 0.0) ?: 0.0
        val qz = r?.optDouble(2, 0.0) ?: 0.0
        val qw = r?.optDouble(3, 1.0) ?: 1.0

        // Rotasi kuaternion → matriks 3x3, lalu diskalakan per kolom.
        val m00 = 1 - 2 * (qy * qy + qz * qz)
        val m01 = 2 * (qx * qy - qz * qw)
        val m02 = 2 * (qx * qz + qy * qw)
        val m10 = 2 * (qx * qy + qz * qw)
        val m11 = 1 - 2 * (qx * qx + qz * qz)
        val m12 = 2 * (qy * qz - qx * qw)
        val m20 = 2 * (qx * qz - qy * qw)
        val m21 = 2 * (qy * qz + qx * qw)
        val m22 = 1 - 2 * (qx * qx + qy * qy)

        // Column-major, sesuai konvensi glTF.
        return doubleArrayOf(
            m00 * sx, m10 * sx, m20 * sx, 0.0,
            m01 * sy, m11 * sy, m21 * sy, 0.0,
            m02 * sz, m12 * sz, m22 * sz, 0.0,
            tx, ty, tz, 1.0
        )
    }

    /** Perkalian matriks 4x4 column-major: hasil = a · b (b diterapkan lebih dulu). */
    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(16)
        for (col in 0..3) for (row in 0..3) {
            var sum = 0.0
            for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = sum
        }
        return out
    }

    private fun transform(m: DoubleArray, p: V3): V3 = V3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
    )
}
