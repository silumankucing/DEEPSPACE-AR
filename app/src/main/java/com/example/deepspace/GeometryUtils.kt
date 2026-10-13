package com.example.deepspace

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Utility class for geometry operations used in polygon modeling.
 * Provides methods to create, modify, and manipulate 3D geometry using Filament.
 */
object GeometryUtils {
    private const val TAG = "GeometryUtils"

    /**
     * Create a vertex buffer and index buffer for a basic shape.
     * Returns a pair of (vertexBuffer, indexBuffer) or null on failure.
     */
    fun createBoxGeometry(
        engine: com.google.android.filament.Engine,
        width: Float = 18f,
        height: Float = 18f,
        depth: Float = 18f
    ): com.google.android.filament.VertexBuffer? {
        val hw = width / 2f
        val hh = height / 2f
        val hd = depth / 2f

        // 24 vertices (4 per face, 6 faces), each with position (3) and normal (3)
        val vertices = floatArrayOf(
            // Front face (z = +hd)
            -hw, -hh,  hd,  0f, 0f, 1f,
             hw, -hh,  hd,  0f, 0f, 1f,
             hw,  hh,  hd,  0f, 0f, 1f,
            -hw,  hh,  hd,  0f, 0f, 1f,
            // Back face (z = -hd)
             hw, -hh, -hd,  0f, 0f, -1f,
            -hw, -hh, -hd,  0f, 0f, -1f,
            -hw,  hh, -hd,  0f, 0f, -1f,
             hw,  hh, -hd,  0f, 0f, -1f,
            // Top face (y = +hh)
            -hw,  hh,  hd,  0f, 1f, 0f,
             hw,  hh,  hd,  0f, 1f, 0f,
             hw,  hh, -hd,  0f, 1f, 0f,
            -hw,  hh, -hd,  0f, 1f, 0f,
            // Bottom face (y = -hh)
            -hw, -hh, -hd,  0f, -1f, 0f,
             hw, -hh, -hd,  0f, -1f, 0f,
             hw, -hh,  hd,  0f, -1f, 0f,
            -hw, -hh,  hd,  0f, -1f, 0f,
            // Right face (x = +hw)
             hw, -hh,  hd,  1f, 0f, 0f,
             hw, -hh, -hd,  1f, 0f, 0f,
             hw,  hh, -hd,  1f, 0f, 0f,
             hw,  hh,  hd,  1f, 0f, 0f,
            // Left face (x = -hw)
            -hw, -hh, -hd, -1f, 0f, 0f,
            -hw, -hh,  hd, -1f, 0f, 0f,
            -hw,  hh,  hd, -1f, 0f, 0f,
            -hw,  hh, -hd, -1f, 0f, 0f
        )

        val vertexCount = 24
        val floatsPerVertex = 6
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(vertices).position(0)

        val vertexBuffer = com.google.android.filament.VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.POSITION, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, floatsPerVertex * 4)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.TANGENTS, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, floatsPerVertex * 4)
            .build(engine)

        vertexBuffer?.setBufferAt(engine, 0, buffer)

        return vertexBuffer
    }

    fun createBoxIndexBuffer(engine: com.google.android.filament.Engine): com.google.android.filament.IndexBuffer? {
        // 36 indices (6 faces x 2 triangles x 3 vertices)
        val indices = shortArrayOf(
            0, 1, 2, 0, 2, 3,      // front
            4, 5, 6, 4, 6, 7,      // back
            8, 9, 10, 8, 10, 11,   // top
            12, 13, 14, 12, 14, 15, // bottom
            16, 17, 18, 16, 18, 19, // right
            20, 21, 22, 20, 22, 23  // left
        )

        val buffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        buffer.put(indices).position(0)

        return com.google.android.filament.IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(com.google.android.filament.IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
            .also { it?.setBuffer(engine, buffer) }
    }

    fun createSphereGeometry(
        engine: com.google.android.filament.Engine,
        radius: Float = 12f,
        segments: Int = 24
    ): com.google.android.filament.VertexBuffer? {
        val vertices = mutableListOf<Float>()
        val rings = segments / 2

        for (j in 0..rings) {
            val theta = Math.PI * j / rings
            val sinTheta = Math.sin(theta).toFloat()
            val cosTheta = Math.cos(theta).toFloat()

            for (i in 0 until segments) {
                val phi = 2.0 * Math.PI * i / segments
                val sinPhi = Math.sin(phi).toFloat()
                val cosPhi = Math.cos(phi).toFloat()

                val x = cosPhi * sinTheta * radius
                val y = cosTheta * radius
                val z = sinPhi * sinTheta * radius

                // Position
                vertices.add(x)
                vertices.add(y)
                vertices.add(z)
                // Normal
                vertices.add(cosPhi * sinTheta)
                vertices.add(cosTheta)
                vertices.add(sinPhi * sinTheta)
            }
        }

        val vertexCount = vertices.size / 6
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(vertices.toFloatArray()).position(0)

        return com.google.android.filament.VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.POSITION, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.TANGENTS, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .build(engine)
            .also { it?.setBufferAt(engine, 0, buffer) }
    }

    fun createCylinderGeometry(
        engine: com.google.android.filament.Engine,
        radius: Float = 9f,
        height: Float = 18f,
        segments: Int = 24
    ): com.google.android.filament.VertexBuffer? {
        val hh = height / 2f
        val vertices = mutableListOf<Float>()

        // Top cap center
        vertices.addAll(listOf(0f, hh, 0f, 0f, 1f, 0f))
        // Bottom cap center
        vertices.addAll(listOf(0f, -hh, 0f, 0f, -1f, 0f))

        for (i in 0 until segments) {
            val angle = 2.0 * Math.PI * i / segments
            val cosA = Math.cos(angle).toFloat()
            val sinA = Math.sin(angle).toFloat()

            // Top ring vertex
            vertices.addAll(listOf(cosA * radius, hh, sinA * radius, cosA, 0f, sinA))
            // Bottom ring vertex
            vertices.addAll(listOf(cosA * radius, -hh, sinA * radius, cosA, 0f, sinA))
        }

        val vertexCount = vertices.size / 6
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(vertices.toFloatArray()).position(0)

        return com.google.android.filament.VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.POSITION, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.TANGENTS, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .build(engine)
            .also { it?.setBufferAt(engine, 0, buffer) }
    }

    fun createConeGeometry(
        engine: com.google.android.filament.Engine,
        radius: Float = 12f,
        height: Float = 18f,
        segments: Int = 24
    ): com.google.android.filament.VertexBuffer? {
        val hh = height / 2f
        val vertices = mutableListOf<Float>()

        // Apex (top)
        vertices.addAll(listOf(0f, hh, 0f, 0f, 1f, 0f))
        // Bottom center
        vertices.addAll(listOf(0f, -hh, 0f, 0f, -1f, 0f))

        for (i in 0 until segments) {
            val angle = 2.0 * Math.PI * i / segments
            val cosA = Math.cos(angle).toFloat()
            val sinA = Math.sin(angle).toFloat()

            // Bottom ring vertex
            vertices.addAll(listOf(cosA * radius, -hh, sinA * radius, cosA, 0f, sinA))
        }

        val vertexCount = vertices.size / 6
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(vertices.toFloatArray()).position(0)

        return com.google.android.filament.VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.POSITION, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.TANGENTS, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .build(engine)
            .also { it?.setBufferAt(engine, 0, buffer) }
    }

    /**
     * Convert vertex/index data to Filament buffers
     */
    fun createMeshBuffers(
        engine: com.google.android.filament.Engine,
        positions: FloatArray,
        normals: FloatArray?,
        indices: ShortArray
    ): Pair<com.google.android.filament.VertexBuffer?, com.google.android.filament.IndexBuffer?> {
        val vertexCount = positions.size / 3
        val interleavedSize = (3 + if (normals != null) 3 else 0) * 4
        val totalFloats = positions.size + (normals?.size ?: 0)

        val buffer = ByteBuffer.allocateDirect(totalFloats * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        if (normals != null) {
            // Interleave positions and normals
            for (i in 0 until vertexCount) {
                buffer.put(positions[i * 3])
                buffer.put(positions[i * 3 + 1])
                buffer.put(positions[i * 3 + 2])
                buffer.put(normals[i * 3])
                buffer.put(normals[i * 3 + 1])
                buffer.put(normals[i * 3 + 2])
            }
        } else {
            buffer.put(positions)
        }
        buffer.position(0)

        val vb = com.google.android.filament.VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(com.google.android.filament.VertexBuffer.VertexAttribute.POSITION, 0,
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 0, interleavedSize)
            .apply {
                if (normals != null) {
                    attribute(com.google.android.filament.VertexBuffer.VertexAttribute.TANGENTS, 0,
                        com.google.android.filament.VertexBuffer.AttributeType.FLOAT3, 12, interleavedSize)
                }
            }
            .build(engine)
        vb?.setBufferAt(engine, 0, buffer)

        val ibBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        ibBuffer.put(indices).position(0)

        val ib = com.google.android.filament.IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(com.google.android.filament.IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib?.setBuffer(engine, ibBuffer)

        return Pair(vb, ib)
    }

    /**
     * Compute face normals for a triangle mesh
     */
    fun computeNormals(positions: FloatArray, indices: ShortArray): FloatArray {
        val normals = FloatArray(positions.size)
        val triangleCount = indices.size / 3

        for (i in 0 until triangleCount) {
            val i0 = indices[i * 3].toInt() * 3
            val i1 = indices[i * 3 + 1].toInt() * 3
            val i2 = indices[i * 3 + 2].toInt() * 3

            val ax = positions[i1] - positions[i0]
            val ay = positions[i1 + 1] - positions[i0 + 1]
            val az = positions[i1 + 2] - positions[i0 + 2]
            val bx = positions[i2] - positions[i0]
            val by = positions[i2 + 1] - positions[i0 + 1]
            val bz = positions[i2 + 2] - positions[i0 + 2]

            val nx = ay * bz - az * by
            val ny = az * bx - ax * bz
            val nz = ax * by - ay * bx
            val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            if (len > 0.0001f) {
                val nxf = nx / len
                val nyf = ny / len
                val nzf = nz / len

                normals[i0] += nxf; normals[i0 + 1] += nyf; normals[i0 + 2] += nzf
                normals[i1] += nxf; normals[i1 + 1] += nyf; normals[i1 + 2] += nzf
                normals[i2] += nxf; normals[i2 + 1] += nyf; normals[i2 + 2] += nzf
            }
        }

        // Normalize
        for (i in 0 until normals.size / 3) {
            val idx = i * 3
            val nx = normals[idx]; val ny = normals[idx + 1]; val nz = normals[idx + 2]
            val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            if (len > 0.0001f) {
                normals[idx] /= len; normals[idx + 1] /= len; normals[idx + 2] /= len
            }
        }

        return normals
    }
}
