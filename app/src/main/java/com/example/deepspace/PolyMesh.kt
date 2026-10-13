package com.example.deepspace

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Indexed polygon mesh untuk halaman Polygon.
 *
 * Aturan face (keputusan desain):
 *  - Face disimpan sebagai 3 atau 4 index saja. 4 index = quad/rectangle (bentuk utama),
 *    3 index = triangle (cap cylinder, apex cone, kutub UV sphere, hasil pecahan n-gon).
 *  - Poligon >4 sisi yang memang berbentuk n-gon (mis. pentagon dodecahedron) dipecah
 *    jadi triangle fan oleh [addFace].
 *  - Pengecualian: face yang hanya kelebihan titik di tengah sisinya — akibat face
 *    sebelahnya di-divide — tetap disimpan utuh sebagai satu face berindex >4.
 *    Bentuknya masih rectangle, hanya salah satu sisinya terbagi dua, jadi memecahnya
 *    jadi triangle justru merusak tampilan. Face seperti ini dibuat lewat penyisipan
 *    langsung di [divideFace], bukan lewat [addFace].
 *
 * Vertex di-share antar face (welded), sehingga extrude/divide menjaga mesh tetap
 * tersambung dan operasi topologi (edge -> face) bisa dihitung.
 *
 * Buffer render (triangle soup + peta triangle->face) adalah data turunan yang
 * di-rebuild saat [dirty], bukan sumber kebenaran.
 */
class PolyMesh {

    /** Group coplanar triangles into one selectable surface for combined objects. */
    var mergeCoplanarSelection: Boolean = false

    /** Posisi vertex unik, flat x,y,z. */
    private val pos = ArrayList<Float>()

    /** Face, masing-masing 3 atau 4 index ke [pos]. */
    val faces = ArrayList<IntArray>()

    /**
     * Kunci welding: koordinat yang sudah dikuantisasi, dibandingkan **persis**.
     * Jangan diganti jadi hash gabungan (mis. XOR) — nilai ±simetris pada primitive
     * simetris akan bertabrakan dan menyatukan vertex yang tak berhubungan.
     */
    private data class WeldKey(val x: Long, val y: Long, val z: Long)

    /** Peta posisi terkuantisasi -> index vertex, untuk welding saat konstruksi. */
    private val weldMap = HashMap<WeldKey, Int>()

    private var dirty = true

    // ── data turunan ──
    private var _soup: FloatArray = FloatArray(0)
    private var _triFace: IntArray = IntArray(0)
    private var _faceNormals: FloatArray = FloatArray(0)
    private var _faceGroups: IntArray = IntArray(0)

    val vertexCount: Int get() = pos.size / 3
    val faceCount: Int get() = faces.size

    /** Triangle soup (x,y,z per vertex, 3 vertex per triangle) untuk di-upload ke VBO. */
    val soup: FloatArray get() { rebuild(); return _soup }

    /** Untuk tiap triangle di [soup], index face asalnya. Dipakai picking. */
    val triFace: IntArray get() { rebuild(); return _triFace }

    /** Normal per face (flat), 3 float per face. */
    val faceNormals: FloatArray get() { rebuild(); return _faceNormals }

    fun faceGroupMembers(faceIndex: Int): IntArray {
        rebuild()
        if (faceIndex !in _faceGroups.indices) return IntArray(0)
        val group = _faceGroups[faceIndex]
        return _faceGroups.indices.filter { _faceGroups[it] == group }.toIntArray()
    }

    // ═══════════════════════ konstruksi ═══════════════════════

    private fun key(x: Float, y: Float, z: Float): WeldKey {
        // Kuantisasi 1e-4 supaya vertex yang "sama" menyatu tanpa perbandingan O(n).
        return WeldKey(
            Math.round(x * 10000.0),
            Math.round(y * 10000.0),
            Math.round(z * 10000.0)
        )
    }

    /** Tambah vertex, mengembalikan index yang sudah ada bila posisinya identik. */
    fun addVertex(x: Float, y: Float, z: Float): Int {
        val k = key(x, y, z)
        weldMap[k]?.let { return it }
        val idx = pos.size / 3
        pos.add(x); pos.add(y); pos.add(z)
        weldMap[k] = idx
        dirty = true
        return idx
    }

    fun vx(i: Int) = pos[i * 3]
    fun vy(i: Int) = pos[i * 3 + 1]
    fun vz(i: Int) = pos[i * 3 + 2]

    fun vertex(i: Int) = floatArrayOf(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])

    fun setVertex(i: Int, x: Float, y: Float, z: Float) {
        pos[i * 3] = x; pos[i * 3 + 1] = y; pos[i * 3 + 2] = z
        dirty = true
    }

    /**
     * Tambah face. Index yang berulang dibuang lebih dulu; poligon >4 sisi
     * dipecah jadi triangle fan sesuai aturan desain.
     */
    fun addFace(vararg idx: Int) = addFace(idx.toList())

    fun addFace(idx: List<Int>) {
        val clean = ArrayList<Int>(idx.size)
        for (i in idx) if (clean.isEmpty() || clean.last() != i) clean.add(i)
        if (clean.size > 1 && clean.first() == clean.last()) clean.removeAt(clean.size - 1)
        when {
            clean.size < 3 -> return
            clean.size <= 4 -> faces.add(clean.toIntArray())
            else -> for (i in 1 until clean.size - 1) {
                faces.add(intArrayOf(clean[0], clean[i], clean[i + 1]))
            }
        }
        dirty = true
    }

    fun markDirty() { dirty = true }

    // ═══════════════════════ data turunan ═══════════════════════

    private fun rebuild() {
        if (!dirty) return
        val soupList = ArrayList<Float>(faces.size * 18)
        val mapList = ArrayList<Int>(faces.size * 2)
        val normArr = FloatArray(faces.size * 3)
        val groups = IntArray(faces.size) { it }

        if (mergeCoplanarSelection) {
            fun root(index: Int): Int {
                var current = index
                while (groups[current] != current) {
                    groups[current] = groups[groups[current]]
                    current = groups[current]
                }
                return current
            }
            fun join(a: Int, b: Int) {
                val ra = root(a); val rb = root(b)
                if (ra != rb) groups[maxOf(ra, rb)] = minOf(ra, rb)
            }
            val edgeFaces = buildEdgeMap()
            for (adjacent in edgeFaces.values) if (adjacent.size == 2) {
                val a = adjacent[0]; val b = adjacent[1]
                val na = faceNormalOf(faces[a]); val nb = faceNormalOf(faces[b])
                val dot = na[0] * nb[0] + na[1] * nb[1] + na[2] * nb[2]
                if (dot > 0.999f) join(a, b)
            }
            for (i in groups.indices) groups[i] = root(i)
        }

        for ((fi, f) in faces.withIndex()) {
            val n = faceNormalOf(f)
            normArr[fi * 3] = n[0]; normArr[fi * 3 + 1] = n[1]; normArr[fi * 3 + 2] = n[2]
            // Fan dari vertex 0. Untuk face yang punya titik tengah di sisinya, sebagian
            // potongan fan bisa berluas nol (tiga titik segaris) — dibuang supaya tidak
            // ada triangle hantu yang mengacaukan picking.
            for (k in 1 until f.size - 1) {
                val a = f[0]; val b = f[k]; val c = f[k + 1]
                if (triangleArea2(a, b, c) < 1e-10f) continue
                soupList.add(pos[a*3]); soupList.add(pos[a*3+1]); soupList.add(pos[a*3+2])
                soupList.add(pos[b*3]); soupList.add(pos[b*3+1]); soupList.add(pos[b*3+2])
                soupList.add(pos[c*3]); soupList.add(pos[c*3+1]); soupList.add(pos[c*3+2])
                mapList.add(groups[fi])
            }
        }
        _soup = soupList.toFloatArray()
        _triFace = mapList.toIntArray()
        _faceNormals = normArr
        _faceGroups = groups
        dirty = false
    }

    /** Kuadrat luas triangle (dikali 4) — cukup untuk mendeteksi potongan degenerate. */
    private fun triangleArea2(a: Int, b: Int, c: Int): Float {
        val ux = pos[b*3] - pos[a*3]; val uy = pos[b*3+1] - pos[a*3+1]; val uz = pos[b*3+2] - pos[a*3+2]
        val vx = pos[c*3] - pos[a*3]; val vy = pos[c*3+1] - pos[a*3+1]; val vz = pos[c*3+2] - pos[a*3+2]
        val cx = uy*vz - uz*vy; val cy = uz*vx - ux*vz; val cz = ux*vy - uy*vx
        return cx*cx + cy*cy + cz*cz
    }

    /** Normal face via Newell — benar juga untuk quad yang sedikit non-planar. */
    fun faceNormalOf(f: IntArray): FloatArray {
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in f.indices) {
            val a = f[i]; val b = f[(i + 1) % f.size]
            val ax = pos[a*3]; val ay = pos[a*3+1]; val az = pos[a*3+2]
            val bx = pos[b*3]; val by = pos[b*3+1]; val bz = pos[b*3+2]
            nx += (ay - by) * (az + bz)
            ny += (az - bz) * (ax + bx)
            nz += (ax - bx) * (ay + by)
        }
        val l = sqrt(nx*nx + ny*ny + nz*nz)
        return if (l > 1e-8f) floatArrayOf(nx/l, ny/l, nz/l) else floatArrayOf(0f, 1f, 0f)
    }

    fun faceNormal(fi: Int): FloatArray = faceNormalOf(faces[fi])

    fun faceCenter(fi: Int): FloatArray {
        val f = faces[fi]
        var x = 0f; var y = 0f; var z = 0f
        for (i in f) { x += pos[i*3]; y += pos[i*3+1]; z += pos[i*3+2] }
        return floatArrayOf(x / f.size, y / f.size, z / f.size)
    }

    /** Panjang rata-rata sisi sebuah face — dipakai sebagai jarak extrude default. */
    fun faceEdgeAverage(fi: Int): Float {
        val f = faces[fi]
        var sum = 0f
        for (i in f.indices) {
            val a = f[i]; val b = f[(i + 1) % f.size]
            val dx = pos[a*3]-pos[b*3]; val dy = pos[a*3+1]-pos[b*3+1]; val dz = pos[a*3+2]-pos[b*3+2]
            sum += sqrt(dx*dx + dy*dy + dz*dz)
        }
        return sum / f.size
    }

    /** Edge unik sebagai pasangan (lo,hi) -> daftar face yang memakainya. */
    fun buildEdgeMap(): LinkedHashMap<Long, MutableList<Int>> {
        val map = LinkedHashMap<Long, MutableList<Int>>()
        for ((fi, f) in faces.withIndex()) {
            for (i in f.indices) {
                val a = f[i]; val b = f[(i + 1) % f.size]
                map.getOrPut(edgeKey(a, b)) { mutableListOf() }.add(fi)
            }
        }
        return map
    }

    /** Daftar edge unik sebagai pasangan index vertex, urutan stabil. */
    fun uniqueEdges(): List<IntArray> = buildEdgeMap().keys.map { intArrayOf(edgeLo(it), edgeHi(it)) }

    /**
     * Edge yang terlihat pada mesh gabungan. Edge di antara dua face coplanar
     * disembunyikan agar sambungan object yang digabung tidak terlihat sebagai seam.
     */
    fun uniqueEdgesWithoutCoplanarSeams(): List<IntArray> {
        val edgeMap = buildEdgeMap()
        return edgeMap.mapNotNull { (key, adjacentFaces) ->
            if (adjacentFaces.size != 2) {
                intArrayOf(edgeLo(key), edgeHi(key))
            } else {
                val a = faceNormal(adjacentFaces[0])
                val b = faceNormal(adjacentFaces[1])
                val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
                if (kotlin.math.abs(dot) < 0.999f) intArrayOf(edgeLo(key), edgeHi(key)) else null
            }
        }
    }

    /** Remove coincident faces created where combined objects share a boundary. */
    fun removeDuplicateFaces() {
        val firstByVertexSet = HashMap<String, Int>()
        val remove = BooleanArray(faces.size)
        for (fi in faces.indices) {
            val key = faces[fi].sortedArray().joinToString(",")
            val previous = firstByVertexSet.putIfAbsent(key, fi)
            if (previous != null) {
                remove[previous] = true
                remove[fi] = true
            }
        }
        if (remove.any { it }) {
            val kept = faces.filterIndexed { index, _ -> !remove[index] }
            faces.clear()
            faces.addAll(kept)
            dirty = true
        }
    }

    /** Merge adjacent coplanar faces back into polygon faces after a Boolean Union. */
    fun mergeCoplanarTriangles() {
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in faces.indices) {
                val first = faces[i]
                for (j in i + 1 until faces.size) {
                    val second = faces[j]
                    val sharedEdges = first.indices.mapNotNull { edgeIndex ->
                        val a = first[edgeIndex]
                        val b = first[(edgeIndex + 1) % first.size]
                        if (second.indices.any { k ->
                                val c = second[k]; val d = second[(k + 1) % second.size]
                                (a == c && b == d) || (a == d && b == c)
                            }) a to b else null
                    }
                    if (sharedEdges.size != 1) continue

                    val n = faceNormalOf(first)
                    val n2 = faceNormalOf(second)
                    val dot = n[0] * n2[0] + n[1] * n2[1] + n[2] * n2[2]
                    if (abs(dot) < 0.999f) continue

                    val edges = mutableListOf<Pair<Int, Int>>()
                    fun addOrCancel(a: Int, b: Int) {
                        val reverse = edges.indexOfFirst { it.first == b && it.second == a }
                        if (reverse >= 0) edges.removeAt(reverse) else edges.add(a to b)
                    }
                    for (face in listOf(first, second)) {
                        for (k in face.indices) addOrCancel(face[k], face[(k + 1) % face.size])
                    }
                    if (edges.size < 3) continue

                    val ordered = ArrayList<Int>(edges.size)
                    var edge = edges.removeAt(0)
                    ordered.add(edge.first)
                    ordered.add(edge.second)
                    while (ordered.last() != ordered.first() && edges.isNotEmpty()) {
                        val next = edges.indexOfFirst { it.first == ordered.last() }
                        if (next < 0) break
                        edge = edges.removeAt(next)
                        ordered.add(edge.second)
                    }
                    if (ordered.last() == ordered.first()) ordered.removeAt(ordered.lastIndex)
                    if (ordered.size < 3 || edges.isNotEmpty()) continue

                    var cx = 0f; var cy = 0f; var cz = 0f
                    for (v in ordered) { cx += vx(v); cy += vy(v); cz += vz(v) }
                    cx /= ordered.size; cy /= ordered.size; cz /= ordered.size

                    val reference = if (abs(n[0]) < 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
                    var ux = n[1] * reference[2] - n[2] * reference[1]
                    var uy = n[2] * reference[0] - n[0] * reference[2]
                    var uz = n[0] * reference[1] - n[1] * reference[0]
                    val ul = sqrt(ux * ux + uy * uy + uz * uz)
                    ux /= ul; uy /= ul; uz /= ul
                    val vxAxis = n[1] * uz - n[2] * uy
                    val vyAxis = n[2] * ux - n[0] * uz
                    val vzAxis = n[0] * uy - n[1] * ux
                    val sorted = ordered.distinct().sortedBy { v ->
                        val dx = vx(v) - cx; val dy = vy(v) - cy; val dz = vz(v) - cz
                        atan2((dx * vyAxis + dy * vzAxis + dz * vxAxis).toDouble(),
                            (dx * ux + dy * uy + dz * uz).toDouble())
                    }.toIntArray()
                    val finalFace = if (faceNormalOf(sorted).let { it[0] * n[0] + it[1] * n[1] + it[2] * n[2] } < 0f) {
                        sorted.reversedArray()
                    } else {
                        sorted
                    }
                    faces[i] = finalFace
                    faces.removeAt(j)
                    changed = true
                    dirty = true
                    break@outer
                }
            }
        }
    }

    fun copy(): PolyMesh {
        val m = PolyMesh()
        m.mergeCoplanarSelection = mergeCoplanarSelection
        m.pos.addAll(pos)
        m.weldMap.putAll(weldMap)
        for (f in faces) m.faces.add(f.copyOf())
        m.dirty = true
        return m
    }

    /**
     * Pulihkan mesh dari topologi tersimpan (file .dsform v3) tanpa welding dan
     * tanpa pemecahan n-gon. WAJIB lewat sini untuk memuat file: [addFace] akan
     * memecah face >4 sisi (hasil [divideFace] yang sah berindex 5) sehingga
     * rekonstruksi lewat addFace merusak face, edge, dan seleksi.
     */
    fun restoreTopology(positions: FloatArray, faceList: List<IntArray>) {
        pos.clear()
        for (v in positions) pos.add(v)
        weldMap.clear()
        for (i in 0 until pos.size / 3) {
            weldMap[key(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])] = i
        }
        faces.clear()
        for (f in faceList) faces.add(f.copyOf())
        dirty = true
    }

    // ═══════════════════════ operasi modeling ═══════════════════════

    /**
     * Extrude satu face sepanjang [dist] mengikuti normalnya (atau vektor eksplisit).
     * Face asli dihapus dan diganti cap baru, sisi-sisinya jadi quad — hasilnya tetap manifold.
     * Mengembalikan index face cap yang baru, atau -1 bila gagal.
     */
    fun extrudeFace(fi: Int, dist: Float, dir: FloatArray? = null): Int {
        if (fi < 0 || fi >= faces.size) return -1
        val f = faces[fi]
        val n = dir ?: faceNormal(fi)
        val dx = n[0] * dist; val dy = n[1] * dist; val dz = n[2] * dist

        // Vertex cap baru. Tidak boleh di-weld ke vertex lama, jadi dibuat langsung.
        val cap = IntArray(f.size)
        for (i in f.indices) {
            val v = f[i]
            cap[i] = rawVertex(pos[v*3] + dx, pos[v*3+1] + dy, pos[v*3+2] + dz)
        }

        // Cap menggantikan face asal di posisi yang sama. Kalau dihapus lalu ditambah
        // di akhir, semua index face sesudahnya bergeser dan seleksi jadi salah sasaran.
        faces[fi] = cap
        // Dinding samping: satu quad per edge, orientasi mengikuti face asal.
        for (i in f.indices) {
            val j = (i + 1) % f.size
            faces.add(intArrayOf(f[i], f[j], cap[j], cap[i]))
        }
        dirty = true
        return fi
    }

    /**
     * Bagi HANYA face yang dipilih; face lain tidak ikut terbagi.
     *
     * Quad/n-gon dibagi jadi n bagian lewat pusat + titik tengah tiap sisi (quad -> 2x2).
     * Triangle dibagi jadi 4 triangle.
     *
     * Tiap tetangga cuma kebagian titik tengah pada sisi yang bersentuhan, dan **tetap
     * satu face** — quad tetangga jadi face berindex 5 ("segilima" yang bentuknya masih
     * rectangle dengan satu sisi terbagi dua). Ini yang mencegah T-junction tanpa harus
     * memecah tetangga jadi triangle atau menjalarkan potongan ke seluruh objek.
     */
    fun divideFace(fi: Int): Boolean {
        if (fi < 0 || fi >= faces.size) return false
        val f = faces[fi].copyOf()
        val n = f.size

        // Titik tengah tiap sisi, di-weld supaya tetangga memakai vertex yang sama.
        val mids = IntArray(n) { i -> midVertex(f[i], f[(i + 1) % n]) }

        // Sisipkan titik tengah ke tetangga. Dikerjakan sebelum face target diganti,
        // selagi sisi-sisinya masih utuh dan bisa dicari.
        for (i in 0 until n) {
            val a = f[i]; val b = f[(i + 1) % n]
            val nb = findFaceWithEdge(a, b, fi)
            if (nb >= 0) faces[nb] = insertOnEdge(faces[nb], a, b, mids[i])
        }

        val newFaces = ArrayList<IntArray>()
        if (n == 3) {
            newFaces.add(intArrayOf(f[0], mids[0], mids[2]))
            newFaces.add(intArrayOf(mids[0], f[1], mids[1]))
            newFaces.add(intArrayOf(mids[2], mids[1], f[2]))
            newFaces.add(intArrayOf(mids[0], mids[1], mids[2]))
        } else {
            val c = faceCenter(fi)
            val ci = addVertex(c[0], c[1], c[2])
            for (i in 0 until n) {
                val prev = (i + n - 1) % n
                newFaces.add(intArrayOf(f[i], mids[i], ci, mids[prev]))
            }
        }

        faces[fi] = newFaces[0]
        for (k in 1 until newFaces.size) faces.add(newFaces[k])
        dirty = true
        return true
    }

    /**
     * Kembalikan salinan [f] dengan [m] disisipkan di antara [a] dan [b].
     * Face-nya tetap satu, hanya bertambah satu index.
     */
    private fun insertOnEdge(f: IntArray, a: Int, b: Int, m: Int): IntArray {
        val i = edgeIndexIn(f, a, b)
        if (i < 0 || m == a || m == b) return f
        val out = IntArray(f.size + 1)
        System.arraycopy(f, 0, out, 0, i + 1)
        out[i + 1] = m
        System.arraycopy(f, i + 1, out, i + 2, f.size - i - 1)
        return out
    }

    /** Titik tengah sebuah edge, di-weld agar dipakai bersama oleh kedua face. */
    private fun midVertex(a: Int, b: Int): Int = addVertex(
        (pos[a*3] + pos[b*3]) / 2f,
        (pos[a*3+1] + pos[b*3+1]) / 2f,
        (pos[a*3+2] + pos[b*3+2]) / 2f
    )

    /** Face pertama yang memuat edge (a,b), selain [exclude]. -1 bila tidak ada. */
    private fun findFaceWithEdge(a: Int, b: Int, exclude: Int): Int {
        for (fi in faces.indices) {
            if (fi == exclude) continue
            if (edgeIndexIn(faces[fi], a, b) >= 0) return fi
        }
        return -1
    }

    /** Posisi edge (a,b) di dalam face, tanpa memandang arah. -1 bila tidak ada. */
    private fun edgeIndexIn(f: IntArray, a: Int, b: Int): Int {
        for (i in f.indices) {
            val x = f[i]; val y = f[(i + 1) % f.size]
            if ((x == a && y == b) || (x == b && y == a)) return i
        }
        return -1
    }

    /**
     * Insert/Inset Face ala Blender: bikin salinan face yang mengecil di dalam face
     * asal, dan hubungkan keduanya dengan satu cincin trapesium.
     *
     * Face dalam sebangun dengan face asal — rectangle menghasilkan rectangle, triangle
     * menghasilkan triangle — karena tiap sudut ditarik ke pusat face dengan skala yang
     * sama. Tiap sisi menyumbang satu trapesium (selalu 4 sisi), jadi hasilnya rapat
     * tanpa menyentuh face tetangga sama sekali.
     *
     * [amount] adalah tebal cincin, 0..1 relatif terhadap jarak sudut ke pusat.
     * Mengembalikan index face dalam (tetap sama dengan [fi]), atau -1 bila gagal.
     */
    fun insetFace(fi: Int, amount: Float = 0.3f): Int {
        if (fi < 0 || fi >= faces.size) return -1
        val f = faces[fi].copyOf()
        // Batasi supaya face dalam tidak terbalik atau menciut jadi titik.
        val factor = (1f - amount).coerceIn(0.05f, 0.95f)
        val c = faceCenter(fi)
        val inner = IntArray(f.size)
        for (i in f.indices) {
            val v = f[i]
            // Vertex baru, tidak boleh di-weld ke vertex face asal.
            inner[i] = rawVertex(
                c[0] + (pos[v*3] - c[0]) * factor,
                c[1] + (pos[v*3+1] - c[1]) * factor,
                c[2] + (pos[v*3+2] - c[2]) * factor
            )
        }
        // Face dalam menempati posisi face asal supaya index face lain tidak bergeser
        // dan face inilah yang tetap terpilih setelah operasi.
        faces[fi] = inner
        for (i in f.indices) {
            val j = (i + 1) % f.size
            faces.add(intArrayOf(f[i], f[j], inner[j], inner[i]))
        }
        dirty = true
        return fi
    }

    /** Geser satu vertex; karena vertex di-share, seluruh face yang memakainya ikut. */
    fun translateVertex(vi: Int, dx: Float, dy: Float, dz: Float) {
        if (vi < 0 || vi >= vertexCount) return
        pos[vi*3] += dx; pos[vi*3+1] += dy; pos[vi*3+2] += dz
        dirty = true
    }

    fun translateFace(fi: Int, dx: Float, dy: Float, dz: Float) {
        if (fi < 0 || fi >= faces.size) return
        for (v in faces[fi].distinct()) translateVertex(v, dx, dy, dz)
    }

    /** Merge connected coplanar faces into one boundary face. */
    fun mergeFaces(faceIndices: Collection<Int>): Boolean {
        val selected = faceIndices.distinct().sorted()
        if (selected.size < 2 || selected.any { it !in faces.indices }) return false
        val baseNormal = faceNormal(selected.first())
        val baseFace = faces[selected.first()]
        val basePoint = vertex(baseFace[0])
        for (fi in selected.drop(1)) {
            val normal = faceNormal(fi)
            val dot = baseNormal[0] * normal[0] + baseNormal[1] * normal[1] + baseNormal[2] * normal[2]
            val f = faces[fi]
            val p = vertex(f[0])
            val planeDistance = abs(
                baseNormal[0] * (p[0] - basePoint[0]) +
                    baseNormal[1] * (p[1] - basePoint[1]) +
                    baseNormal[2] * (p[2] - basePoint[2])
            )
            if (dot < 0.999f || planeDistance > 1e-3f) return false
        }

        data class DirectedEdge(val a: Int, val b: Int)
        val boundary = ArrayList<DirectedEdge>()
        fun addOrCancel(edge: DirectedEdge) {
            val reverse = boundary.indexOfFirst { it.a == edge.b && it.b == edge.a }
            if (reverse >= 0) boundary.removeAt(reverse) else boundary.add(edge)
        }
        for (fi in selected) {
            val f = faces[fi]
            for (i in f.indices) addOrCancel(DirectedEdge(f[i], f[(i + 1) % f.size]))
        }
        if (boundary.size < 3) return false

        val ordered = ArrayList<Int>(boundary.size)
        val first = boundary.removeAt(0)
        ordered.add(first.a)
        ordered.add(first.b)
        while (ordered.last() != ordered.first() && boundary.isNotEmpty()) {
            val next = boundary.indexOfFirst { it.a == ordered.last() }
            if (next < 0) return false
            ordered.add(boundary.removeAt(next).b)
        }
        if (ordered.last() == ordered.first()) ordered.removeAt(ordered.lastIndex)
        if (ordered.size < 3 || boundary.isNotEmpty() || ordered.distinct().size != ordered.size) return false
        if (faceNormalOf(ordered.toIntArray()).let { it[0] * baseNormal[0] + it[1] * baseNormal[1] + it[2] * baseNormal[2] } < 0f) {
            ordered.reverse()
        }

        val firstIndex = selected.first()
        faces[firstIndex] = ordered.toIntArray()
        for (fi in selected.drop(1).asReversed()) faces.removeAt(fi)
        dirty = true
        return true
    }

    // ── internal ──

    /** Vertex baru tanpa welding — dipakai extrude/inset agar cap tidak menyatu dengan face asal. */
    private fun rawVertex(x: Float, y: Float, z: Float): Int {
        val idx = pos.size / 3
        pos.add(x); pos.add(y); pos.add(z)
        // Jangan timpa entri yang sudah ada: vertex lama harus tetap bisa ditemukan
        // oleh addVertex berikutnya, kalau tidak welding jadi salah sasaran.
        weldMap.putIfAbsent(key(x, y, z), idx)
        dirty = true
        return idx
    }

    companion object {
        fun edgeKey(a: Int, b: Int): Long {
            val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
            return (lo shl 32) or hi
        }
        fun edgeLo(k: Long): Int = (k ushr 32).toInt()
        fun edgeHi(k: Long): Int = (k and 0xFFFFFFFFL).toInt()
    }
}

/**
 * Generator primitive. Semua memakai skala dasar 8 unit agar cocok dengan
 * grid dan jarak kamera yang sudah dipakai halaman Polygon.
 */
object Primitives {

    private const val S = 8f

    fun create(type: String): PolyMesh? = when (type.lowercase()) {
        "rectangle", "quad", "plane" -> rectangle()
        "box", "cube" -> box()
        "cylinder" -> cylinder()
        "cone" -> cone()
        "sphere" -> uvSphere()
        "torus" -> torus()
        "prism", "polyhedron", "polyhedral" -> prism()
        else -> null
    }

    /** Satu quad di bidang X-Y. */
    fun rectangle(width: Float = S * 2, height: Float = S * 2, segX: Int = 1, segY: Int = 1): PolyMesh {
        val m = PolyMesh()
        val hw = width / 2f; val hh = height / 2f
        val grid = Array(segY + 1) { j ->
            IntArray(segX + 1) { i ->
                m.addVertex(-hw + width * i / segX, -hh + height * j / segY, 0f)
            }
        }
        for (j in 0 until segY) for (i in 0 until segX) {
            m.addFace(grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i])
        }
        return m
    }

    /** Box: 6 sisi, tiap sisi grid quad segXxsegY. Semua face quad. */
    fun box(size: Float = S * 2, segX: Int = 1, segY: Int = 1, segZ: Int = 1): PolyMesh {
        val m = PolyMesh()
        val h = size / 2f
        // (u,v) -> posisi, per sisi. Urutan menghasilkan normal keluar.
        data class Side(val su: Int, val sv: Int, val f: (Float, Float) -> FloatArray)
        val sides = listOf(
            Side(segX, segY) { u, v -> floatArrayOf(u, v,  h) },   // +Z
            Side(segX, segY) { u, v -> floatArrayOf(-u, v, -h) },  // -Z
            Side(segZ, segY) { u, v -> floatArrayOf( h, v, -u) },  // +X
            Side(segZ, segY) { u, v -> floatArrayOf(-h, v,  u) },  // -X
            Side(segX, segZ) { u, v -> floatArrayOf(u,  h, -v) },  // +Y
            Side(segX, segZ) { u, v -> floatArrayOf(u, -h,  v) },  // -Y
        )
        for (s in sides) {
            val grid = Array(s.sv + 1) { j ->
                IntArray(s.su + 1) { i ->
                    val p = s.f(-h + size * i / s.su, -h + size * j / s.sv)
                    m.addVertex(p[0], p[1], p[2])
                }
            }
            for (j in 0 until s.sv) for (i in 0 until s.su) {
                m.addFace(grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i])
            }
        }
        return m
    }

    /**
     * Cylinder: dinding quad penuh; tutup atas/bawah adalah triangle fan
     * (n-gon >4 sisi dipecah jadi triangle sesuai aturan).
     */
    fun cylinder(radius: Float = S, height: Float = S * 2, radial: Int = 16, heightSeg: Int = 1): PolyMesh {
        val m = PolyMesh()
        val hh = height / 2f
        val rings = Array(heightSeg + 1) { j ->
            val y = -hh + height * j / heightSeg
            IntArray(radial) { i ->
                val a = 2.0 * PI * i / radial
                m.addVertex((radius * cos(a)).toFloat(), y, (radius * sin(a)).toFloat())
            }
        }
        for (j in 0 until heightSeg) for (i in 0 until radial) {
            val n = (i + 1) % radial
            m.addFace(rings[j][n], rings[j][i], rings[j + 1][i], rings[j + 1][n])
        }
        val top = m.addVertex(0f, hh, 0f)
        val bot = m.addVertex(0f, -hh, 0f)
        for (i in 0 until radial) {
            val n = (i + 1) % radial
            m.addFace(rings[heightSeg][n], rings[heightSeg][i], top)
            m.addFace(rings[0][i], rings[0][n], bot)
        }
        return m
    }

    /**
     * Cone: dinding quad kecuali baris teratas yang bertemu di apex (triangle),
     * alas triangle fan. Dengan [topRadius] > 0 hasilnya frustum dan seluruh dinding jadi quad.
     */
    // heightSeg default 3: dengan 1 segmen seluruh dinding cone menjadi triangle
    // karena langsung bertemu apex. Mulai dari 3 supaya sebagian besar dinding quad.
    fun cone(radius: Float = S, height: Float = S * 2, radial: Int = 16, heightSeg: Int = 3, topRadius: Float = 0f): PolyMesh {
        val m = PolyMesh()
        val hh = height / 2f
        val sharp = topRadius <= 1e-5f
        val ringCount = if (sharp) heightSeg else heightSeg + 1
        val rings = Array(ringCount) { j ->
            val t = j.toFloat() / heightSeg
            val y = -hh + height * t
            val r = radius + (topRadius - radius) * t
            IntArray(radial) { i ->
                val a = 2.0 * PI * i / radial
                m.addVertex((r * cos(a)).toFloat(), y, (r * sin(a)).toFloat())
            }
        }
        for (j in 0 until ringCount - 1) for (i in 0 until radial) {
            val n = (i + 1) % radial
            m.addFace(rings[j][n], rings[j][i], rings[j + 1][i], rings[j + 1][n])
        }
        val last = rings[ringCount - 1]
        // Apex (cone lancip) maupun pusat tutup (frustum) sama-sama menutup ring teratas.
        val top = m.addVertex(0f, hh, 0f)
        for (i in 0 until radial) m.addFace(last[(i + 1) % radial], last[i], top)
        val bot = m.addVertex(0f, -hh, 0f)
        for (i in 0 until radial) m.addFace(rings[0][i], rings[0][(i + 1) % radial], bot)
        return m
    }

    /** UV sphere: quad di semua baris kecuali dua baris kutub yang triangle. */
    fun uvSphere(radius: Float = S, radial: Int = 20, rings: Int = 12): PolyMesh {
        val m = PolyMesh()
        val north = m.addVertex(0f, radius, 0f)
        val south = m.addVertex(0f, -radius, 0f)
        // Baris interior saja; kutub ditangani terpisah.
        val band = Array(rings - 1) { j ->
            val phi = PI * (j + 1) / rings
            val y = (radius * cos(phi)).toFloat()
            val r = (radius * sin(phi)).toFloat()
            IntArray(radial) { i ->
                val a = 2.0 * PI * i / radial
                m.addVertex((r * cos(a)).toFloat(), y, (r * sin(a)).toFloat())
            }
        }
        for (i in 0 until radial) {
            val n = (i + 1) % radial
            m.addFace(north, band[0][n], band[0][i])
            m.addFace(south, band[rings - 2][i], band[rings - 2][n])
        }
        for (j in 0 until rings - 2) for (i in 0 until radial) {
            val n = (i + 1) % radial
            m.addFace(band[j][i], band[j][n], band[j + 1][n], band[j + 1][i])
        }
        return m
    }

    /** Torus: 100% quad, tanpa kutub. */
    fun torus(majorRadius: Float = S, minorRadius: Float = S * 0.35f, radial: Int = 20, tubular: Int = 12): PolyMesh {
        val m = PolyMesh()
        val grid = Array(radial) { i ->
            val u = 2.0 * PI * i / radial
            IntArray(tubular) { j ->
                val v = 2.0 * PI * j / tubular
                val r = majorRadius + minorRadius * cos(v)
                m.addVertex((r * cos(u)).toFloat(), (minorRadius * sin(v)).toFloat(), (r * sin(u)).toFloat())
            }
        }
        for (i in 0 until radial) for (j in 0 until tubular) {
            val ni = (i + 1) % radial; val nj = (j + 1) % tubular
            m.addFace(grid[i][j], grid[i][nj], grid[ni][nj], grid[ni][j])
        }
        return m
    }

    /**
     * Prisma segi enam (hexagonal prism): dinding quad, tutup atas/bawah triangle fan.
     */
    fun prism(radius: Float = S, height: Float = S * 2, radial: Int = 3): PolyMesh {
        val m = PolyMesh()
        val hh = height / 2f
        val bottom = IntArray(radial) { i ->
            val a = 2.0 * PI * i / radial
            m.addVertex((radius * cos(a)).toFloat(), -hh, (radius * sin(a)).toFloat())
        }
        val top = IntArray(radial) { i ->
            val a = 2.0 * PI * i / radial
            m.addVertex((radius * cos(a)).toFloat(), hh, (radius * sin(a)).toFloat())
        }
        for (i in 0 until radial) {
            val n = (i + 1) % radial
            // Dinding samping quad
            m.addFace(bottom[n], bottom[i], top[i], top[n])
        }
        val topCenter = m.addVertex(0f, hh, 0f)
        val botCenter = m.addVertex(0f, -hh, 0f)
        for (i in 0 until radial) {
            val n = (i + 1) % radial
            m.addFace(top[n], top[i], topCenter)
            m.addFace(bottom[i], bottom[n], botCenter)
        }
        return m
    }

    /**
     * Polyhedron: dodecahedron (12 face pentagon). Karena pentagon >4 sisi,
     * tiap face otomatis dipecah jadi 3 triangle oleh [PolyMesh.addFace].
     */
    fun polyhedron(radius: Float = S): PolyMesh {
        val m = PolyMesh()
        val p = ((1.0 + sqrt(5.0)) / 2.0).toFloat()   // golden ratio
        val ip = 1f / p
        val raw = mutableListOf<FloatArray>()
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            raw.add(floatArrayOf(sx.toFloat(), sy.toFloat(), sz.toFloat()))
        }
        for (sa in intArrayOf(-1, 1)) for (sb in intArrayOf(-1, 1)) {
            raw.add(floatArrayOf(0f, sa * ip, sb * p))
            raw.add(floatArrayOf(sa * ip, sb * p, 0f))
            raw.add(floatArrayOf(sa * p, 0f, sb * ip))
        }
        val scale = radius / sqrt(3f)
        val idx = raw.map { m.addVertex(it[0] * scale, it[1] * scale, it[2] * scale) }

        // 12 pentagon didefinisikan lewat bidang: kumpulkan 5 vertex terdekat ke tiap
        // pusat face, lalu urutkan melingkar. Lebih ringkas dan tahan salah ketik
        // dibanding tabel index manual.
        val centers = mutableListOf<FloatArray>()
        // Normal ke-12 face dodecahedron (= vertex icosahedron dual).
        // Urutan komponen penting: (0,±φ,±1) — bukan (0,±1,±φ), yang menghasilkan
        // bidang yang bukan bidang face.
        for (sa in intArrayOf(-1, 1)) for (sb in intArrayOf(-1, 1)) {
            centers.add(floatArrayOf(0f, sa * p, sb * 1f))
            centers.add(floatArrayOf(sa * p, sb * 1f, 0f))
            centers.add(floatArrayOf(sa * 1f, 0f, sb * p))
        }
        for (c in centers) {
            val cl = sqrt(c[0]*c[0] + c[1]*c[1] + c[2]*c[2])
            val n = floatArrayOf(c[0]/cl, c[1]/cl, c[2]/cl)
            // Tiap face dodecahedron tepat memuat 5 vertex: ambil 5 proyeksi terbesar
            // terhadap normal face. Lebih kokoh daripada menyaring dengan toleransi,
            // yang jumlahnya bergantung pada skala dan galat pembulatan.
            val ring = idx.sortedByDescending { i ->
                m.vx(i) * n[0] + m.vy(i) * n[1] + m.vz(i) * n[2]
            }.take(5)
            m.addFace(sortRing(m, ring, n))
        }
        return m
    }

    /** Urutkan vertex sebuah face secara melingkar berlawanan arah jarum jam terhadap normal. */
    private fun sortRing(m: PolyMesh, ring: List<Int>, n: FloatArray): List<Int> {
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in ring) { cx += m.vx(i); cy += m.vy(i); cz += m.vz(i) }
        cx /= ring.size; cy /= ring.size; cz /= ring.size
        // Basis tangen pada bidang face.
        val ref = if (abs(n[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        val tx = n[1]*ref[2] - n[2]*ref[1]
        val ty = n[2]*ref[0] - n[0]*ref[2]
        val tz = n[0]*ref[1] - n[1]*ref[0]
        val tl = sqrt(tx*tx + ty*ty + tz*tz)
        val u = floatArrayOf(tx/tl, ty/tl, tz/tl)
        val v = floatArrayOf(n[1]*u[2] - n[2]*u[1], n[2]*u[0] - n[0]*u[2], n[0]*u[1] - n[1]*u[0])
        return ring.sortedBy {
            val dx = m.vx(it) - cx; val dy = m.vy(it) - cy; val dz = m.vz(it) - cz
            kotlin.math.atan2(dx*v[0] + dy*v[1] + dz*v[2], dx*u[0] + dy*u[1] + dz*u[2])
        }
    }
}
