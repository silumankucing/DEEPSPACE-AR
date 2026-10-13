package com.example.deepspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolyMeshTest {

    private fun square(): PolyMesh {
        val mesh = PolyMesh()
        val a = mesh.addVertex(-1f, -1f, 0f)
        val b = mesh.addVertex(1f, -1f, 0f)
        val c = mesh.addVertex(1f, 1f, 0f)
        val d = mesh.addVertex(-1f, 1f, 0f)
        mesh.addFace(a, b, c, d)
        return mesh
    }

    @Test
    fun extrudeFaceBuildsCapAndSideWalls() {
        val mesh = square()

        val cap = mesh.extrudeFace(0, 2f)

        assertEquals(0, cap)
        assertEquals(8, mesh.vertexCount)
        assertEquals(5, mesh.faceCount)
        assertEquals(2f, mesh.vz(mesh.faces[0][0]), 1e-5f)
        assertTrue(mesh.faces.drop(1).all { it.size == 4 })
    }

    @Test
    fun extrudeFaceHonorsNegativeDistance() {
        val mesh = square()

        mesh.extrudeFace(0, -2f)

        assertEquals(-2f, mesh.vz(mesh.faces[0][0]), 1e-5f)
    }

    @Test
    fun duplicateBoundaryFacesAreRemovedAfterCombine() {
        val mesh = PolyMesh()
        val a = mesh.addVertex(0f, 0f, 0f)
        val b = mesh.addVertex(1f, 0f, 0f)
        val c = mesh.addVertex(1f, 1f, 0f)
        val d = mesh.addVertex(0f, 1f, 0f)
        mesh.addFace(a, b, c, d)
        mesh.addFace(d, c, b, a)

        mesh.removeDuplicateFaces()

        assertEquals(0, mesh.faceCount)
    }

    @Test
    fun coplanarTrianglesMergeBackIntoOneQuad() {
        val mesh = PolyMesh()
        val a = mesh.addVertex(0f, 0f, 0f)
        val b = mesh.addVertex(1f, 0f, 0f)
        val c = mesh.addVertex(1f, 1f, 0f)
        val d = mesh.addVertex(0f, 1f, 0f)
        mesh.addFace(a, b, c)
        mesh.addFace(a, c, d)

        mesh.mergeCoplanarTriangles()

        assertEquals(1, mesh.faceCount)
        assertEquals(4, mesh.faces[0].size)
    }

    @Test
    fun primitiveBoxesProduceAValidBooleanUnion() {
        fun triangles(mesh: PolyMesh, offsetX: Double): List<Tri> = mesh.faces.mapNotNull { face ->
            if (face.size < 3) return@mapNotNull null
            val points = face.map { v -> V3(mesh.vx(v).toDouble() + offsetX, mesh.vy(v).toDouble(), mesh.vz(v).toDouble()) }
            Tri(points[0], points[1], points[2])
        }
        val first = triangles(Primitives.box(2f), 0.0)
        val second = triangles(Primitives.box(2f), 1.0)
        val union = Csg.union(first, second)

        assertTrue(union.isNotEmpty())
        assertTrue(Csg.signedVolume(union) > 0.0)
    }

    @Test
    fun mergeFacesCombinesConnectedCoplanarQuads() {
        val mesh = PolyMesh()
        val a = mesh.addVertex(0f, 0f, 0f)
        val b = mesh.addVertex(1f, 0f, 0f)
        val c = mesh.addVertex(1f, 1f, 0f)
        val d = mesh.addVertex(0f, 1f, 0f)
        val e = mesh.addVertex(2f, 0f, 0f)
        val f = mesh.addVertex(2f, 1f, 0f)
        mesh.addFace(a, b, c, d)
        mesh.addFace(b, e, f, c)

        assertTrue(mesh.mergeFaces(listOf(0, 1)))
        assertEquals(1, mesh.faceCount)
        assertEquals(6, mesh.faces[0].size)
    }

    @Test
    fun mergeFacesRejectsNonCoplanarFaces() {
        val mesh = Primitives.box(2f)

        assertTrue(!mesh.mergeFaces(listOf(0, 1)))
    }
}