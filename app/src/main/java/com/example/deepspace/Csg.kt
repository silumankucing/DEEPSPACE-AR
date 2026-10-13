package com.example.deepspace

import kotlin.math.abs
import kotlin.math.sqrt

/** Vektor/titik 3D. */
data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
    operator fun unaryMinus() = V3(-x, -y, -z)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): V3 {
        val l = length()
        return if (l < 1e-12) V3(0.0, 0.0, 1.0) else V3(x / l, y / l, z / l)
    }
    fun lerp(o: V3, t: Double) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
}

/** Segitiga bermuka tunggal; urutan a→b→c menentukan arah normal (CCW = keluar). */
data class Tri(val a: V3, val b: V3, val c: V3) {
    fun flipped() = Tri(c, b, a)
    fun normal() = (b - a).cross(c - a)
    fun area2() = normal().length()
}

/**
 * Csg — boolean solid (union / subtract / intersect) memakai BSP tree.
 *
 * Murni Kotlin, tanpa engine eksternal. Input/output berupa daftar [Tri] dari mesh
 * tertutup (watertight) dengan normal menghadap keluar. Dipakai modul mesh/Polygon di halaman
 * AR dan Project untuk operasi boolean antar mesh.
 */
object Csg {

    private const val EPS = 1e-7

    /** Bidang tak hingga: titik p ada di bidang bila normal·p = w. */
    private class Plane(val normal: V3, val w: Double) {
        fun flipped() = Plane(-normal, -w)

        companion object {
            const val COPLANAR = 0
            const val FRONT = 1
            const val BACK = 2
            const val SPANNING = 3

            fun fromPoints(a: V3, b: V3, c: V3): Plane? {
                val n = (b - a).cross(c - a)
                if (n.length() < 1e-12) return null // segitiga degenerate
                val nn = n.normalized()
                return Plane(nn, nn.dot(a))
            }
        }

        /** Bagi [poly] terhadap bidang ini, hasilnya dimasukkan ke salah satu list. */
        fun splitPolygon(
            poly: Poly,
            coplanarFront: MutableList<Poly>,
            coplanarBack: MutableList<Poly>,
            front: MutableList<Poly>,
            back: MutableList<Poly>
        ) {
            var polygonType = 0
            val types = IntArray(poly.vertices.size)
            for (i in poly.vertices.indices) {
                val t = normal.dot(poly.vertices[i]) - w
                val type = if (t < -EPS) BACK else if (t > EPS) FRONT else COPLANAR
                polygonType = polygonType or type
                types[i] = type
            }

            when (polygonType) {
                COPLANAR ->
                    if (normal.dot(poly.plane.normal) > 0) coplanarFront.add(poly) else coplanarBack.add(poly)
                FRONT -> front.add(poly)
                BACK -> back.add(poly)
                else -> {
                    val f = mutableListOf<V3>()
                    val b = mutableListOf<V3>()
                    val n = poly.vertices.size
                    for (i in 0 until n) {
                        val j = (i + 1) % n
                        val ti = types[i]
                        val tj = types[j]
                        val vi = poly.vertices[i]
                        val vj = poly.vertices[j]
                        if (ti != BACK) f.add(vi)
                        if (ti != FRONT) b.add(vi)
                        if ((ti or tj) == SPANNING) {
                            val t = (w - normal.dot(vi)) / normal.dot(vj - vi)
                            val v = vi.lerp(vj, t)
                            f.add(v)
                            b.add(v)
                        }
                    }
                    if (f.size >= 3) Poly.of(f)?.let { front.add(it) }
                    if (b.size >= 3) Poly.of(b)?.let { back.add(it) }
                }
            }
        }
    }

    /** Poligon planar konveks/simple hasil pemotongan BSP. */
    private class Poly(val vertices: List<V3>, val plane: Plane) {
        fun flipped() = Poly(vertices.reversed(), plane.flipped())

        /** Fan-triangulasi; poligon hasil split BSP selalu planar. */
        fun toTriangles(out: MutableList<Tri>) {
            for (i in 1 until vertices.size - 1) {
                val t = Tri(vertices[0], vertices[i], vertices[i + 1])
                if (t.area2() > 1e-14) out.add(t)
            }
        }

        companion object {
            fun of(verts: List<V3>): Poly? {
                if (verts.size < 3) return null
                // Cari 3 titik non-kolinear untuk menentukan bidang.
                for (i in 1 until verts.size - 1) {
                    val p = Plane.fromPoints(verts[0], verts[i], verts[i + 1])
                    if (p != null) return Poly(verts, p)
                }
                return null
            }
        }
    }

    /**
     * Node BSP. Dibangun iteratif (bukan rekursif) agar mesh besar tidak
     * menyebabkan StackOverflow di perangkat Android.
     */
    private class Node {
        var plane: Plane? = null
        var front: Node? = null
        var back: Node? = null
        val polygons = mutableListOf<Poly>()

        /** Balik orientasi seluruh subtree. */
        fun invert() {
            val stack = ArrayDeque<Node>()
            stack.addLast(this)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                for (i in n.polygons.indices) n.polygons[i] = n.polygons[i].flipped()
                n.plane = n.plane?.flipped()
                val f = n.front
                n.front = n.back
                n.back = f
                n.front?.let { stack.addLast(it) }
                n.back?.let { stack.addLast(it) }
            }
        }

        /** Buang bagian [input] yang berada di dalam subtree ini. */
        fun clipPolygons(input: List<Poly>): List<Poly> {
            val result = mutableListOf<Poly>()
            val stack = ArrayDeque<Pair<Node, List<Poly>>>()
            stack.addLast(this to input)
            while (stack.isNotEmpty()) {
                val (node, polys) = stack.removeLast()
                val pl = node.plane
                if (pl == null) {
                    result.addAll(polys)
                    continue
                }
                val front = mutableListOf<Poly>()
                val back = mutableListOf<Poly>()
                for (p in polys) pl.splitPolygon(p, front, back, front, back)
                if (node.front != null) {
                    if (front.isNotEmpty()) stack.addLast(node.front!! to front)
                } else {
                    result.addAll(front)
                }
                // Tanpa child belakang, poligon di belakang bidang berarti "di dalam" → dibuang.
                if (node.back != null && back.isNotEmpty()) stack.addLast(node.back!! to back)
            }
            return result
        }

        /** Buang bagian subtree ini yang berada di dalam [other]. */
        fun clipTo(other: Node) {
            val stack = ArrayDeque<Node>()
            stack.addLast(this)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                val clipped = other.clipPolygons(n.polygons)
                n.polygons.clear()
                n.polygons.addAll(clipped)
                n.front?.let { stack.addLast(it) }
                n.back?.let { stack.addLast(it) }
            }
        }

        fun allPolygons(): List<Poly> {
            val out = mutableListOf<Poly>()
            val stack = ArrayDeque<Node>()
            stack.addLast(this)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                out.addAll(n.polygons)
                n.front?.let { stack.addLast(it) }
                n.back?.let { stack.addLast(it) }
            }
            return out
        }

        fun build(input: List<Poly>) {
            val stack = ArrayDeque<Pair<Node, List<Poly>>>()
            stack.addLast(this to input)
            while (stack.isNotEmpty()) {
                val (node, polys) = stack.removeLast()
                if (polys.isEmpty()) continue
                if (node.plane == null) node.plane = polys[0].plane
                val pl = node.plane!!
                val front = mutableListOf<Poly>()
                val back = mutableListOf<Poly>()
                for (p in polys) pl.splitPolygon(p, node.polygons, node.polygons, front, back)
                if (front.isNotEmpty()) {
                    val f = node.front ?: Node().also { node.front = it }
                    stack.addLast(f to front)
                }
                if (back.isNotEmpty()) {
                    val b = node.back ?: Node().also { node.back = it }
                    stack.addLast(b to back)
                }
            }
        }
    }

    private fun toPolys(tris: List<Tri>): List<Poly> =
        tris.mapNotNull { if (it.area2() > 1e-14) Poly.of(listOf(it.a, it.b, it.c)) else null }

    private fun toTris(polys: List<Poly>): List<Tri> {
        val out = mutableListOf<Tri>()
        for (p in polys) p.toTriangles(out)
        return out
    }

    /** A ∪ B — dipakai fitur Boss. */
    fun union(a: List<Tri>, b: List<Tri>): List<Tri> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val na = Node().apply { build(toPolys(a)) }
        val nb = Node().apply { build(toPolys(b)) }
        na.clipTo(nb)
        nb.clipTo(na)
        nb.invert()
        nb.clipTo(na)
        nb.invert()
        na.build(nb.allPolygons())
        return toTris(na.allPolygons())
    }

    /** A − B — dipakai fitur Cut. */
    fun subtract(a: List<Tri>, b: List<Tri>): List<Tri> {
        if (a.isEmpty() || b.isEmpty()) return a
        val na = Node().apply { build(toPolys(a)) }
        val nb = Node().apply { build(toPolys(b)) }
        na.invert()
        na.clipTo(nb)
        nb.clipTo(na)
        nb.invert()
        nb.clipTo(na)
        nb.invert()
        na.build(nb.allPolygons())
        na.invert()
        return toTris(na.allPolygons())
    }

    /** Volume bertanda; negatif berarti normal menghadap ke dalam. */
    fun signedVolume(tris: List<Tri>): Double {
        var v = 0.0
        for (t in tris) v += t.a.dot(t.b.cross(t.c))
        return v / 6.0
    }

    /** Bounding box diagonal; dipakai untuk cek hasil kosong. */
    fun isEmpty(tris: List<Tri>) = tris.isEmpty() || abs(signedVolume(tris)) < 1e-12
}
