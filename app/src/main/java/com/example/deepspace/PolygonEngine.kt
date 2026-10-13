package com.example.deepspace

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PolygonEngine(private val context: Context, private val glSurfaceView: GLSurfaceView) {

    // â”€â”€ Scene object management â”€â”€
        data class SceneObject(
        val name: String,
        var type: String,
        var vertices: FloatArray,    // triangle vertex data (x,y,z repeats)
        var vertexCount: Int,        // number of vertices (not floats)
        val modelMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, 0f, 8f, 0f) },
        var selected: Boolean = false,
        var visible: Boolean = true,
        var color: FloatArray = floatArrayOf(0.55f, 0.55f, 0.58f), // default grey (r,g,b)
        var children: List<SceneObject>? = null,
        /**
         * Topologi indexed (quad + triangle). Bila terisi, ini adalah sumber kebenaran:
         * [vertices] hanyalah triangle soup turunannya. Objek lama tanpa mesh tetap
         * jalan lewat jalur triangle-soup yang lama.
         */
        var mesh: PolyMesh? = null,
        /** Per-face color overrides: faceIndex -> RGB float array */
        var faceColors: MutableMap<Int, FloatArray>? = null,
    ) {
        /** Sinkronkan buffer render dari topologi. Panggil setelah mengubah [mesh]. */
        fun syncFromMesh() {
            val m = mesh ?: return
            vertices = m.soup
            vertexCount = vertices.size / 3
        }
    }

    private val objects = mutableListOf<SceneObject>()
    var onObjectsChanged: ((List<SceneObject>) -> Unit)? = null
    var objectCounter = 0
    var groupCounter = 0

    // ── Undo / Redo ──
    /** Dipanggil setiap kali ketersediaan undo/redo berubah (canUndo, canRedo). */
    var onHistoryChanged: ((Boolean, Boolean) -> Unit)? = null

    /**
     * Snapshot satu objek. [matrix], [vertices], [mesh], [color], dan [faceColors]
     * di-deep-copy agar riwayat undo/redo tetap valid walaupun ada operasi yang
     * memutasi data secara in-place (mis. mergeFaces/translateFace).
     */
    private class ObjSnap(
        val matrix: FloatArray,
        val selected: Boolean,
        val visible: Boolean,
        val color: FloatArray,
        val type: String,
        val mesh: PolyMesh?,
        val vertices: FloatArray,
        val vertexCount: Int,
        val faceColors: MutableMap<Int, FloatArray>?,
        val children: List<SceneObject>?,
    )

    private class Snapshot(
        val objects: ArrayList<SceneObject>,
        val snaps: ArrayList<ObjSnap>,
        val objectCounter: Int,
        val groupCounter: Int,
        val selObj: Int,
        val selNode: Int,
        val selEdge: Int,
        val selFace: Int,
        val selNodes: Set<Int>,
        val selEdges: Set<Int>,
        val selFaces: Set<Int>,
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private val maxHistory = 50

    private fun snapshotScene(): Snapshot {
        val objs = ArrayList(objects)
        val snaps = ArrayList<ObjSnap>(objs.size)
        for (o in objs) {
            // Deep-copy data yang bisa dimutasi in-place (mesh/vertices/color/faceColors)
            // supaya snapshot selalu valid untuk undo/redo, terlepas dari operasi yang
            // memakai copy-on-write maupun mutasi in-place (mis. mergeFaces/translateFace).
            snaps.add(ObjSnap(
                o.modelMatrix.copyOf(),
                o.selected,
                o.visible,
                o.color.copyOf(),
                o.type,
                o.mesh?.copy(),
                o.vertices.copyOf(),
                o.vertexCount,
                o.faceColors?.let { map ->
                    mutableMapOf<Int, FloatArray>().apply {
                        for ((k, v) in map) put(k, v.copyOf())
                    }
                },
                o.children?.map { c -> copySceneObjectDeep(c) },
            ))
        }
        return Snapshot(
            objs, snaps, objectCounter, groupCounter,
            selectedObjectIndex, selectedNodeIndex, selectedEdgeIndex, selectedFaceIndex,
            selectedNodeIndices.toSet(), selectedEdgeIndices.toSet(), selectedFaceIndices.toSet()
        )
    }

    /** Deep-copy rekursif satu SceneObject beserta children-nya. */
    private fun copySceneObjectDeep(src: SceneObject): SceneObject {
        val mat = FloatArray(16)
        System.arraycopy(src.modelMatrix, 0, mat, 0, 16)
        return SceneObject(
            name = src.name,
            type = src.type,
            vertices = src.vertices.copyOf(),
            vertexCount = src.vertexCount,
            modelMatrix = mat,
            selected = src.selected,
            visible = src.visible,
            color = src.color.copyOf(),
            children = src.children?.map { copySceneObjectDeep(it) },
            mesh = src.mesh?.copy(),
            faceColors = src.faceColors?.let { map ->
                mutableMapOf<Int, FloatArray>().apply {
                    for ((k, v) in map) put(k, v.copyOf())
                }
            },
        )
    }

    private fun restoreScene(s: Snapshot) {
        objects.clear()
        objects.addAll(s.objects)
        for (i in s.objects.indices) {
            val o = s.objects[i]
            val snap = s.snaps[i]
            System.arraycopy(snap.matrix, 0, o.modelMatrix, 0, 16)
            o.selected = snap.selected
            o.visible = snap.visible
            o.color = snap.color
            o.type = snap.type
            o.mesh = snap.mesh
            o.vertices = snap.vertices
            o.vertexCount = snap.vertexCount
            o.faceColors = snap.faceColors
            o.children = snap.children?.map { copySceneObjectDeep(it) }
        }
        objectCounter = s.objectCounter
        groupCounter = s.groupCounter
        selectedObjectIndex = s.selObj
        selectedNodeIndex = s.selNode
        selectedEdgeIndex = s.selEdge
        selectedFaceIndex = s.selFace
        // Restore multi-select sets dari snapshot (bukan hanya single index).
        selectedNodeIndices.clear()
        selectedEdgeIndices.clear()
        selectedFaceIndices.clear()
        selectedNodeIndices.addAll(s.selNodes)
        selectedEdgeIndices.addAll(s.selEdges)
        selectedFaceIndices.addAll(s.selFaces)
        selectedObjectForHighlight = objects.getOrNull(s.selObj)
        if (selectedObjectForHighlight == null) gizmoVisible = false
        isTransformGrabbed = false
        grabbedAxis = "NONE"
        dragBaselineTaken = false
        gizmoWorldPos = getGizmoTargetWorldPos()
        onObjectsChanged?.invoke(objects.toList())
        onEncloseStateChanged?.invoke(objects.any { it.type == "enclose" })
        glSurfaceView.requestRender()
        onHistoryChanged?.invoke(canUndo(), canRedo())
    }

    /**
     * Simpan state saat ini ke undo stack dan kosongkan redo stack.
     * Dipanggil SEBELUM operasi apa pun yang mengubah scene.
     */
    fun pushUndo() {
        undoStack.addLast(snapshotScene())
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        android.util.Log.d("UndoRedo", "pushUndo: undoStack=${undoStack.size} objects=${objects.size}")
        onHistoryChanged?.invoke(canUndo(), canRedo())
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo(): Boolean {
        val s = undoStack.removeLastOrNull() ?: run {
            android.util.Log.d("UndoRedo", "undo: stack kosong")
            return false
        }
        redoStack.addLast(snapshotScene())
        restoreScene(s)
        android.util.Log.d("UndoRedo", "undo: restored objects=${objects.size}")
        return true
    }

    fun redo(): Boolean {
        val s = redoStack.removeLastOrNull() ?: run {
            android.util.Log.d("UndoRedo", "redo: stack kosong")
            return false
        }
        undoStack.addLast(snapshotScene())
        restoreScene(s)
        android.util.Log.d("UndoRedo", "redo: restored objects=${objects.size}")
        return true
    }

    /** Kosongkan seluruh riwayat (dipakai saat New / Open project). */
    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        onHistoryChanged?.invoke(canUndo(), canRedo())
    }

    // Drag gizmo: baseline diambil sekali di awal gesture supaya satu drag = satu langkah undo
    private var dragBaselineTaken = false

    private fun ensureDragBaseline() {
        if (dragBaselineTaken) return
        dragBaselineTaken = true
        pushUndo()
        // Lepaskan referensi array/mesh yang bisa dimutasi in-place selama drag,
        // supaya snapshot baseline tetap menyimpan nilai sebelum drag.
        for (o in objects) {
            o.vertices = o.vertices.copyOf()
            o.mesh?.let { o.mesh = it.copy() }
        }
    }

    // â”€â”€ Select modes â”€â”€
    enum class SelectMode { NODE, EDGE, SURFACE, OBJECT, NONE }
        var selectMode: SelectMode = SelectMode.NONE
    var isMultiSelectMode: Boolean = true

    //  â”€â”€ Picking result â”€â”€
    var selectedNodeIndex: Int = -1
    var selectedEdgeIndex: Int = -1
    var selectedFaceIndex: Int = -1
    var selectedObjectIndex: Int = -1
    /** Multi-select sets for NODE/EDGE/SURFACE modes */
    val selectedNodeIndices: MutableSet<Int> = mutableSetOf()
    val selectedEdgeIndices: MutableSet<Int> = mutableSetOf()
    val selectedFaceIndices: MutableSet<Int> = mutableSetOf()
    var selectedObjectForHighlight: SceneObject? = null  // object yang sedang di-highlight face-nya
        var onEncloseStateChanged: ((Boolean) -> Unit)? = null
        var isPinViewActive = false
        var onPinViewStateChanged: ((Boolean) -> Unit)? = null
        var onObjectSelectedIndex: ((Int) -> Unit)? = null
        var onSelectionChanged: ((String) -> Unit)? = null // info message
        var isSymmetryActive = false
        var symmetryPlane: String = "XZ"  // default symmetry plane
        var onSymmetryStateChanged: ((Boolean) -> Unit)? = null

    // â”€â”€ Orbit â”€â”€
    var orbitTheta = 0.0
    var orbitPhi = 0.55  // ~32 degrees from front (default front-looking view)
    var orbitRadius = 100.0
    var orbitTargetX = 0f
    var orbitTargetY = 0f
    var orbitTargetZ = 0f

    private var prevX = 0f
    private var prevY = 0f
    private var isDragging = false
    private var prevPinchDist = 0f
    private var isPinching = false
    private var prevPanX = 0f
    private var prevPanY = 0f

    var onTapCallback: ((Float, Float) -> Unit)? = null

    // â”€â”€ Grid visibility â”€â”€
    var gridVisible: Boolean = true

    /**
     * Dark mode: viewport dicat abu-abu gelap dan garis grid dibalik agar tetap terbaca.
     * Di-set dari preferensi Setting > Dark Mode.
     */
    var isDarkTheme: Boolean = false

    // â”€â”€ Transform mode & Gizmo â”€â”€
    var transformMode: String = "none"
    var gizmoWorldPos: FloatArray = floatArrayOf(0f, 8f, 0f)
    var gizmoVisible: Boolean = false
    var isTransformGrabbed: Boolean = false
    /** Track which gizmo axis (X, Y, Z, or NONE) was grabbed on touch down */
    var grabbedAxis: String = "NONE"
    /** Whether the user is in extrude-drag mode (not regular translate) */
    var isExtrudeMode: Boolean = false
    private var extrudeAccum: Float = 0f
    private var extrudeBaseMesh: PolyMesh? = null
    private var extrudeBaseFaceIndex: Int = -1
    /** Face normal direction used during extrude drag */
    var extrudeNormal: FloatArray = floatArrayOf(0f, 0f, 1f)
    /** Rotation matrix to align gizmo Z axis with extrudeNormal */
    private var gizmoRotMatrix: FloatArray = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    /** Track the last selection info to know when a new selection is made vs dragging existing */
    var lastSelectionTime: Long = 0
    private var gizmoVao = 0
    private var gizmoVbo = 0

    // Matrices
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private var viewportWidth = 1f
    private var viewportHeight = 1f

    // Shaders
    private var gridProgram = 0
    private var axisProgram = 0
    private var boxProgram = 0

    // VAOs/VBOs
    private var gridVao = 0
    private var gridVbo = 0
    private var gridCount = 0
    private var axisVao = 0
    private var axisVbo = 0
    private var boxVao = 0
    private var boxVbo = 0
    private var boxCount = 0

    // â”€â”€ Mesh data for picking (8-node box) â”€â”€
    private val boxVertices = floatArrayOf(
        -8f,-8f, 8f, // 0
         8f,-8f, 8f, // 1
         8f, 8f, 8f, // 2
        -8f, 8f, 8f, // 3
        -8f,-8f,-8f, // 4
         8f,-8f,-8f, // 5
         8f, 8f,-8f, // 6
        -8f, 8f,-8f, // 7
    )
    private val modelMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, 0f, 4f, 0f) }

    // Edge pairs (12 edges)
    private val boxEdges = arrayOf(
        intArrayOf(0,1), intArrayOf(1,2), intArrayOf(2,3), intArrayOf(3,0),
        intArrayOf(4,5), intArrayOf(5,6), intArrayOf(6,7), intArrayOf(7,4),
        intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7)
    )

    // Face triangles (12 triangles, 2 per face)
    private val boxTriangles = arrayOf(
        intArrayOf(0,1,2), intArrayOf(0,2,3),       // front
        intArrayOf(4,5,6), intArrayOf(4,6,7),       // back
        intArrayOf(3,2,6), intArrayOf(3,6,7),       // top
        intArrayOf(0,1,5), intArrayOf(0,5,4),       // bottom
        intArrayOf(1,2,6), intArrayOf(1,6,5),       // right
        intArrayOf(0,3,7), intArrayOf(0,7,4),       // left
    )

    // Face centers for picking
    private val boxFaceCenters = arrayOf(
        floatArrayOf(0f,0f,8f), floatArrayOf(0f,0f,-8f),
        floatArrayOf(0f,8f,0f), floatArrayOf(0f,-8f,0f),
        floatArrayOf(8f,0f,0f), floatArrayOf(-8f,0f,0f)
    )

    // â”€â”€ Face grouping per shape type â”€â”€
    // Returns the number of triangles per face for a given shape type.
    // Most shapes have 1 triangle per face (sphere, cone sides, torus quads).
    // Box has 2 triangles per face.
                private fun trianglesPerFace(type: String): Int {
        return when (type.lowercase()) {
            "box", "cylinder", "sphere", "torus", "subdivided", "rectangle", "quad", "quadmesh" -> 2
            else -> 1
        }
    }

    // Helper: draw wireframe while hiding diagonals by pairing adjacent coplanar triangles into quads
    private fun drawWireframeHideDiagonals(obj: SceneObject) {
        val v = obj.vertices
        val triCount = obj.vertexCount / 3
        val used = BooleanArray(triCount)
        val eps = 0.0001f

        fun pt(base: Int) = floatArrayOf(v[base], v[base+1], v[base+2])
        fun eq(a: FloatArray, b: FloatArray) = kotlin.math.abs(a[0]-b[0])<eps && kotlin.math.abs(a[1]-b[1])<eps && kotlin.math.abs(a[2]-b[2])<eps
        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0]-b[0], a[1]-b[1], a[2]-b[2])
        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
        fun dot(a: FloatArray, b: FloatArray) = a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
        fun len(a: FloatArray) = kotlin.math.sqrt(dot(a,a).toDouble()).toFloat()
        fun normalize(a: FloatArray): FloatArray { val l = len(a); return if (l>1e-6f) floatArrayOf(a[0]/l,a[1]/l,a[2]/l) else floatArrayOf(0f,0f,0f) }

        for (i in 0 until triCount) {
            if (used[i]) continue
            val bi = i*9
            val a0 = pt(bi); val a1 = pt(bi+3); val a2 = pt(bi+6)
            var paired = -1
            var bj = 0
            for (j in i+1 until triCount) {
                if (used[j]) continue
                val b = j*9
                val b0 = pt(b); val b1 = pt(b+3); val b2 = pt(b+6)
                var shared = 0
                if (eq(a0,b0)||eq(a0,b1)||eq(a0,b2)) shared++
                if (eq(a1,b0)||eq(a1,b1)||eq(a1,b2)) shared++
                if (eq(a2,b0)||eq(a2,b1)||eq(a2,b2)) shared++
                if (shared>=2) {
                    // check coplanar
                    val n1 = normalize(cross(sub(a1,a0), sub(a2,a0)))
                    val n2 = normalize(cross(sub(b1,b0), sub(b2,b0)))
                    if (dot(n1,n2) > 0.999f) { paired=j; bj=b; break }
                }
            }

            if (paired >= 0) {
                // collect unique verts of both triangles
                val list = mutableListOf<FloatArray>()
                fun addUnique(p: FloatArray){ if (list.none { eq(it,p) }) list.add(p) }
                addUnique(a0); addUnique(a1); addUnique(a2); addUnique(pt(bj)); addUnique(pt(bj+3)); addUnique(pt(bj+6))
                if (list.size == 4) {
                    // order around centroid
                    val cx = (list[0][0]+list[1][0]+list[2][0]+list[3][0])/4f
                    val cy = (list[0][1]+list[1][1]+list[2][1]+list[3][1])/4f
                    val cz = (list[0][2]+list[1][2]+list[2][2]+list[3][2])/4f
                    val n = normalize(cross(sub(a1,a0), sub(a2,a0)))
                    var u = sub(list[0], floatArrayOf(cx,cy,cz)); if (len(u) < 1e-6f) u = sub(list[1], floatArrayOf(cx,cy,cz))
                    u = normalize(u); val w = normalize(cross(n,u))
                    val angles = list.map { p -> kotlin.math.atan2((dot(w, sub(p,floatArrayOf(cx,cy,cz)))).toDouble(), (dot(u, sub(p,floatArrayOf(cx,cy,cz)))).toDouble()) }
                    val order = angles.mapIndexed{idx, a-> Pair(idx,a)}.sortedBy{it.second}.map{it.first}
                    val ordered = order.map { list[it] }
                    val edge = FloatArray(15)
                    for (k in 0 until 5) {
                        val p = if (k<4) ordered[k] else ordered[0]
                        edge[k*3] = p[0]; edge[k*3+1] = p[1]; edge[k*3+2] = p[2]
                    }
                    val buf = ByteBuffer.allocateDirect(edge.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); buf.put(edge).position(0)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, edge.size*4, buf, GLES30.GL_DYNAMIC_DRAW)
                    GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,0); GLES30.glEnableVertexAttribArray(0)
                    GLES30.glDrawArrays(GLES30.GL_LINE_STRIP,0,5)
                } else {
                    // fallback: draw two triangle outlines
                    val t1 = floatArrayOf(a0[0],a0[1],a0[2], a1[0],a1[1],a1[2], a2[0],a2[1],a2[2])
                    val buf1 = ByteBuffer.allocateDirect(t1.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); buf1.put(t1).position(0)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, t1.size*4, buf1, GLES30.GL_DYNAMIC_DRAW)
                    GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,0); GLES30.glEnableVertexAttribArray(0)
                    GLES30.glDrawArrays(GLES30.GL_LINE_LOOP,0,3)
                    val b0 = pt(bj); val b1 = pt(bj+3); val b2 = pt(bj+6)
                    val t2 = floatArrayOf(b0[0],b0[1],b0[2], b1[0],b1[1],b1[2], b2[0],b2[1],b2[2])
                    val buf2 = ByteBuffer.allocateDirect(t2.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); buf2.put(t2).position(0)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, t2.size*4, buf2, GLES30.GL_DYNAMIC_DRAW)
                    GLES30.glDrawArrays(GLES30.GL_LINE_LOOP,0,3)
                }
                used[i] = true; used[paired] = true
            } else {
                // single triangle outline
                val tri = floatArrayOf(a0[0],a0[1],a0[2], a1[0],a1[1],a1[2], a2[0],a2[1],a2[2])
                val buf = ByteBuffer.allocateDirect(tri.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); buf.put(tri).position(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.size*4, buf, GLES30.GL_DYNAMIC_DRAW)
                GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,0); GLES30.glEnableVertexAttribArray(0)
                GLES30.glDrawArrays(GLES30.GL_LINE_LOOP,0,3)
                used[i] = true
            }
        }
        // restore buffer
        val restore = ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); restore.put(v).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size*4, restore, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,0,0); GLES30.glEnableVertexAttribArray(0)
    }

    /** Create a single rectangle (quad) object centered at origin on the X-Y plane */
    fun createRectangle(width: Float = 16f, height: Float = 16f): SceneObject? {
        pushUndo()
        val pm = Primitives.rectangle(width, height)
        val verts = pm.soup
        objectCounter++
        val name = "rectangle $objectCounter"
        val vCount = verts.size / 3
        val obj = SceneObject(
            name = name,
            type = "rectangle",
            vertices = verts,
            vertexCount = vCount,
            mesh = pm
        )
        objects.add(obj)
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
        return obj
    }

    /** Subdivide a face (faceIndex) on object (objIndex) into 4 smaller quads.
     *  This replaces the single face's triangle verts with 4 quads (each 2 triangles).
     *  Note: adjacency is not updated â€” neighboring faces may become non-rectangular (pentagon-like).
     */
    fun subdivideFace(objIndex: Int, faceIndex: Int): Boolean {
        if (objIndex < 0 || objIndex >= objects.size) return false
        val obj = objects[objIndex]
        val tpf = trianglesPerFace(obj.type)
        if (tpf != 2) return false // hanya untuk rectangle (2 triangle)
        val triStart = faceIndex * tpf
        val triCount = obj.vertexCount / 3
        if (triStart < 0 || triStart + tpf > triCount) return false
        pushUndo()

        val baseFloat = triStart * 9
        val v = obj.vertices

        // Ambil 4 corner
        val v0 = floatArrayOf(v[baseFloat], v[baseFloat+1], v[baseFloat+2])
        val v1 = floatArrayOf(v[baseFloat+3], v[baseFloat+4], v[baseFloat+5])
        val v2 = floatArrayOf(v[baseFloat+6], v[baseFloat+7], v[baseFloat+8])
        val v3 = floatArrayOf(v[baseFloat+15], v[baseFloat+16], v[baseFloat+17])

        // Midpoints
        val m01 = floatArrayOf((v0[0]+v1[0])/2f, (v0[1]+v1[1])/2f, (v0[2]+v1[2])/2f)
        val m12 = floatArrayOf((v1[0]+v2[0])/2f, (v1[1]+v2[1])/2f, (v1[2]+v2[2])/2f)
        val m23 = floatArrayOf((v2[0]+v3[0])/2f, (v2[1]+v3[1])/2f, (v2[2]+v3[2])/2f)
        val m30 = floatArrayOf((v3[0]+v0[0])/2f, (v3[1]+v0[1])/2f, (v3[2]+v0[2])/2f)

        // Center
        val cx = (v0[0]+v1[0]+v2[0]+v3[0])/4f
        val cy = (v0[1]+v1[1]+v2[1]+v3[1])/4f
        val cz = (v0[2]+v1[2]+v2[2]+v3[2])/4f
        val c = floatArrayOf(cx, cy, cz)

        // Build 4 quads (masing-masing 2 triangles)
        val newVerts = mutableListOf<Float>()
        val quads = listOf(
            arrayOf(v0, m01, c, m30),
            arrayOf(m01, v1, m12, c),
            arrayOf(c, m12, v2, m23),
            arrayOf(m30, c, m23, v3)
        )
        for (q in quads) {
            val a = q[0]; val b = q[1]; val cc = q[2]; val d = q[3]
            newVerts.addAll(listOf(a[0],a[1],a[2], b[0],b[1],b[2], cc[0],cc[1],cc[2]))
            newVerts.addAll(listOf(a[0],a[1],a[2], cc[0],cc[1],cc[2], d[0],d[1],d[2]))
        }

        // Compose new vertex array
        val faceFloats = tpf * 9
        val before = v.sliceArray(0 until baseFloat)
        val after = v.sliceArray((baseFloat + faceFloats) until v.size)
        val combined = FloatArray(before.size + newVerts.size + after.size)
        var pos = 0
        System.arraycopy(before, 0, combined, pos, before.size); pos += before.size
        for (f in newVerts) { combined[pos++] = f }
        System.arraycopy(after, 0, combined, pos, after.size)

        obj.vertices = combined
        obj.vertexCount = combined.size / 3
        obj.type = "subdivided"
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
        return true
    }
    private val gridVS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uMVP;
void main(){gl_Position=uMVP*vec4(aPos,1.0);}"""

    private val gridFS = """#version 300 es
precision mediump float;
uniform vec4 uCol;
layout(location=0) out vec4 o;
void main(){o=uCol;}"""

    fun init() {
        glSurfaceView.setEGLContextClientVersion(3)
        glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                applyClearColor()
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                buildShaders()
                buildGrid()
            }
            override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
                viewportWidth = w.toFloat(); viewportHeight = h.toFloat()
                GLES30.glViewport(0, 0, w, h)
                Matrix.perspectiveM(projMatrix, 0, 45f, w.toFloat() / h.toFloat(), 0.1f, 500f)
            }
                        override fun onDrawFrame(gl: GL10?) {
                  applyClearColor()
                  GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
                  GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                  GLES30.glDisable(GLES30.GL_CULL_FACE)
                  updateView()
                  Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
                  drawGrid()
                  drawObjects()
                  drawHighlight()
                  drawGizmo()
              }
        })
                glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        glSurfaceView.setOnTouchListener { _, e ->
            // Touch callbacks arrive on the UI thread, while mesh rendering runs
            // on the GL thread. Queue the complete event sequence so topology
            // edits cannot race drawObjects()/drawHighlight().
            val queuedEvent = MotionEvent.obtain(e)
            glSurfaceView.queueEvent {
                try {
                    handleTouch(queuedEvent)
                    glSurfaceView.requestRender()
                } finally {
                    queuedEvent.recycle()
                }
            }
            true
        }
    }

    /** Warna latar viewport: abu-abu gelap (#262626) saat dark mode, abu-abu terang saat light. */
    private fun applyClearColor() {
        if (isDarkTheme) {
            GLES30.glClearColor(0.149f, 0.149f, 0.149f, 1.0f) // dark grey background
        } else {
            GLES30.glClearColor(0.92f, 0.92f, 0.94f, 1.0f) // light background
        }
    }

    private fun updateView() {
        val t = orbitTheta; val p = orbitPhi; val r = orbitRadius
        val ex = orbitTargetX + (r * sin(t) * cos(p)).toFloat()
        val ey = orbitTargetY + (r * sin(p)).toFloat()
        val ez = orbitTargetZ + (r * cos(t) * cos(p)).toFloat()
        Matrix.setLookAtM(viewMatrix, 0, ex, ey, ez, orbitTargetX, orbitTargetY, orbitTargetZ, 0f, 1f, 0f)
    }

    // ===== SHADER =====
    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { GLES30.glDeleteShader(s); return 0 }
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    private fun buildShaders() {
        gridProgram = program(gridVS, gridFS)
        axisProgram = program(gridVS, gridFS)
        boxProgram = program(gridVS, gridFS)
    }

    // ===== GRID =====
    private fun buildGrid() {
        val size = 50; val step = 4f; val half = size * step / 2f
        val verts = mutableListOf<Float>()
        for (i in 0..size) {
            val p = -half + i * step
            verts.addAll(listOf(-half, 0f, p, half, 0f, p))
            verts.addAll(listOf(p, 0f, -half, p, 0f, half))
        }
        gridCount = verts.size / 3
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts.toFloatArray()).position(0)
        val vao = IntArray(1); val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(1, vbo, 0)
        gridVao = vao[0]; gridVbo = vbo[0]
        GLES30.glBindVertexArray(gridVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    private fun drawGrid() {
        if (!gridVisible) return
        if (gridProgram == 0) return
        GLES30.glUseProgram(gridProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gridProgram, "uMVP"), 1, false, vpMatrix, 0)
        GLES30.glLineWidth(1.5f)
        GLES30.glBindVertexArray(gridVao)
        // Grid dibuat lebih terang dari latar pada dark mode agar tetap terlihat
        if (isDarkTheme) {
            GLES30.glUniform4f(GLES30.glGetUniformLocation(gridProgram, "uCol"), 0.32f, 0.32f, 0.35f, 1f) // dark grid
        } else {
            GLES30.glUniform4f(GLES30.glGetUniformLocation(gridProgram, "uCol"), 0.65f, 0.65f, 0.7f, 1f) // light grid
        }
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridCount)
        GLES30.glBindVertexArray(0)
    }

    // ===== AXIS =====
    private fun buildAxis() {
        val verts = floatArrayOf(
            0f,0f,0f, 30f,0f,0f,  0f,0f,0f, 0f,30f,0f,  0f,0f,0f, 0f,0f,30f,
            28f,-2f,0f, 30f,0f,0f,  28f,2f,0f, 30f,0f,0f,
            -2f,28f,0f, 0f,30f,0f,  2f,28f,0f, 0f,30f,0f,
            0f,-2f,28f, 0f,0f,30f,  0f,2f,28f, 0f,0f,30f
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts).position(0)
        val vao = IntArray(1); val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(1, vbo, 0)
        axisVao = vao[0]; axisVbo = vbo[0]
        GLES30.glBindVertexArray(axisVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, axisVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    private fun drawAxis() {
        if (axisProgram == 0) return
        GLES30.glUseProgram(axisProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(axisProgram, "uMVP"), 1, false, vpMatrix, 0)
        GLES30.glLineWidth(3f)
        GLES30.glBindVertexArray(axisVao)

        GLES30.glUniform4f(GLES30.glGetUniformLocation(axisProgram, "uCol"), 0.8f, 0.15f, 0.15f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(axisProgram, "uCol"), 0.15f, 0.7f, 0.15f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 2, 2)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(axisProgram, "uCol"), 0.15f, 0.3f, 0.8f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 4, 2)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(axisProgram, "uCol"), 0.8f, 0.15f, 0.15f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 6, 4)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(axisProgram, "uCol"), 0.15f, 0.7f, 0.15f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 10, 4)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(axisProgram, "uCol"), 0.15f, 0.3f, 0.8f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 14, 4)

        GLES30.glBindVertexArray(0)
    }

    // ===== BOX =====
    private fun buildBox() {
        // Triangle-vertex data for the box (36 vertices, 12 triangles)
        val verts = floatArrayOf(
            // front
            -8f,-8f, 8f,  8f,-8f, 8f,  8f, 8f, 8f,
            -8f,-8f, 8f,  8f, 8f, 8f, -8f, 8f, 8f,
            // back
            -8f,-8f,-8f, -8f, 8f,-8f,  8f, 8f,-8f,
            -8f,-8f,-8f,  8f, 8f,-8f,  8f,-8f,-8f,
            // top
            -8f, 8f,-8f, -8f, 8f, 8f,  8f, 8f, 8f,
            -8f, 8f,-8f,  8f, 8f, 8f,  8f, 8f,-8f,
            // bottom
            -8f,-8f,-8f,  8f,-8f,-8f,  8f,-8f, 8f,
            -8f,-8f,-8f,  8f,-8f, 8f, -8f,-8f, 8f,
            // right
            8f,-8f,-8f,  8f, 8f,-8f,  8f, 8f, 8f,
            8f,-8f,-8f,  8f, 8f, 8f,  8f,-8f, 8f,
            // left
            -8f,-8f,-8f, -8f,-8f, 8f, -8f, 8f, 8f,
            -8f,-8f,-8f, -8f, 8f, 8f, -8f, 8f,-8f,
        )
        boxCount = verts.size / 3
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts).position(0)
        val vao = IntArray(1); val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(1, vbo, 0)
        boxVao = vao[0]; boxVbo = vbo[0]
        GLES30.glBindVertexArray(boxVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, boxVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    private fun drawBox() {
        if (boxProgram == 0) return
        GLES30.glUseProgram(boxProgram)
        GLES30.glBindVertexArray(boxVao)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vpMatrix, 0, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(boxProgram, "uMVP"), 1, false, mvp, 0)

        // Fill - solid grey
        GLES30.glUniform4f(GLES30.glGetUniformLocation(boxProgram, "uCol"), 0.55f, 0.55f, 0.58f, 1.0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, boxCount)

        // Wireframe edges - dark grey
        GLES30.glUniform4f(GLES30.glGetUniformLocation(boxProgram, "uCol"), 0.25f, 0.25f, 0.28f, 1f)
        for (i in 0 until 12) {
            GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, i * 3, 3)
        }

        GLES30.glBindVertexArray(0)
    }

    // ===== OBJECT RENDERING =====
    // Simple per-frame VBO for drawing objects (since we have few objects)
    private var objectVao = 0
    private var objectVbo = 0
    private var objectBuffer: java.nio.FloatBuffer? = null
    private var objectBufferCapacity = 0

    private fun drawObjects() {
        if (objects.isEmpty()) return
        if (boxProgram == 0) return

        GLES30.glUseProgram(boxProgram)

        if (objectVao == 0) {
            val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); objectVao = vaos[0]
            val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); objectVbo = vbos[0]
        }

        // Flatten: group dilewati, children-nya yang digambar (tidak hilang).
        // Anak mewarisi visibility parent group.
        val renderList = flattenForRender()

        for (obj in renderList) {
            val mvp = FloatArray(16)
            Matrix.multiplyMM(mvp, 0, vpMatrix, 0, obj.modelMatrix, 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(boxProgram, "uMVP"), 1, false, mvp, 0)

            // Reuse buffer untuk hindari alokasi tiap frame
            val byteCount = obj.vertices.size * 4
            if (objectBuffer == null || objectBufferCapacity < byteCount) {
                objectBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).asFloatBuffer()
                objectBufferCapacity = byteCount
            }
            val buf = objectBuffer!!
            buf.clear()
            buf.put(obj.vertices)
            buf.position(0)

            GLES30.glBindVertexArray(objectVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, obj.vertices.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                        GLES30.glEnableVertexAttribArray(0)

            if (obj.type == "enclose") {
                // Enclose: hanya wireframe hijau, tanpa surface fill
                GLES30.glUniform4f(GLES30.glGetUniformLocation(boxProgram, "uCol"), obj.color[0], obj.color[1], obj.color[2], 1f)
                GLES30.glLineWidth(2.5f)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, obj.vertexCount)
            } else {
                // Fill - object color
                GLES30.glUniform4f(GLES30.glGetUniformLocation(boxProgram, "uCol"), obj.color[0], obj.color[1], obj.color[2], 1.0f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, obj.vertexCount)

                // Per-face color overlays: re-draw faces that have custom colors
                val fc = obj.faceColors
                if (fc != null && fc.isNotEmpty()) {
                    val pmMesh = obj.mesh
                    if (pmMesh != null) {
                        for ((faceIdx, color) in fc) {
                            if (faceIdx < 0 || faceIdx >= pmMesh.faceCount) continue
                            GLES30.glUniform4f(GLES30.glGetUniformLocation(boxProgram, "uCol"), color[0], color[1], color[2], 1.0f)
                            val tf = pmMesh.triFace
                            for (ti in tf.indices) {
                                if (tf[ti] == faceIdx) {
                                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, ti * 3, 3)
                                }
                            }
                        }
                    } else {
                        val tpf = trianglesPerFace(obj.type)
                        for ((faceIdx, color) in fc) {
                            GLES30.glUniform4f(GLES30.glGetUniformLocation(boxProgram, "uCol"), color[0], color[1], color[2], 1.0f)
                            val triStart = faceIdx * tpf
                            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, triStart * 3, tpf * 3)
                        }
                    }
                }


                                // Wireframe edges - dark grey, hairline (1.5dp) supaya rapi di semua tipe object,
                // termasuk mesh hasil import (PolyMesh). Nilai lama 6f terlalu tebal & menumpuk visual.
                GLES30.glUniform4f(GLES30.glGetUniformLocation(boxProgram, "uCol"), 0.15f, 0.15f, 0.18f, 1f)
                GLES30.glLineWidth(1.5f)
                val pm = obj.mesh
                if (pm != null) {
                    // Wireframe dari topologi: satu LINE_LOOP per face, jadi diagonal
                    // triangulasi tidak pernah tergambar. Semua edge unik, tanpa duplikat.
                    val edges = if (obj.type == "combined") {
                        pm.uniqueEdgesWithoutCoplanarSeams()
                    } else {
                        pm.uniqueEdges()
                    }
                    val ev = FloatArray(edges.size * 6)
                    var o = 0
                    for (e in edges) {
                        ev[o++] = pm.vx(e[0]); ev[o++] = pm.vy(e[0]); ev[o++] = pm.vz(e[0])
                        ev[o++] = pm.vx(e[1]); ev[o++] = pm.vy(e[1]); ev[o++] = pm.vz(e[1])
                    }
                    val eb = ByteBuffer.allocateDirect(ev.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    eb.put(ev).position(0)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, ev.size * 4, eb, GLES30.GL_DYNAMIC_DRAW)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glDrawArrays(GLES30.GL_LINES, 0, edges.size * 2)
                } else if (obj.type == "box" || obj.type == "rectangle" || obj.type == "quad" || obj.type == "quadmesh" || obj.type == "subdivided") {
                    // Box: gambar per-face rectangle (4 vertex unik per face) tanpa garis diagonal
                    // Setiap face punya 2 triangle = 6 vertex, vertex ke-0,1,2,5 membentuk rectangle
                    val faceCount = obj.vertexCount / 6  // 6 vertex per face
                    for (fi in 0 until faceCount) {
                        val base = fi * 18  // 6 vertex * 3 coords
                        val i0 = base      // vertex0 triangle0
                        val i1 = base + 3  // vertex1 triangle0
                        val i2 = base + 6  // vertex2 triangle0
                        val i5 = base + 15 // vertex2 triangle1 (vertex ke-5 dari 6)
                        // Rectangle: i0 -> i1 -> i2 -> i5 -> i0
                        val idx = intArrayOf(i0, i1, i2, i5, i0)
                        // Upload 5 vertex untuk LINE_STRIP
                        val edgeVerts = FloatArray(15)
                        for (k in 0 until 5) {
                            edgeVerts[k*3] = obj.vertices[idx[k]]
                            edgeVerts[k*3+1] = obj.vertices[idx[k]+1]
                            edgeVerts[k*3+2] = obj.vertices[idx[k]+2]
                        }
                        val edgeBuf = ByteBuffer.allocateDirect(edgeVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        edgeBuf.put(edgeVerts).position(0)
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
                        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, edgeVerts.size * 4, edgeBuf, GLES30.GL_DYNAMIC_DRAW)
                        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                        GLES30.glEnableVertexAttribArray(0)
                        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, 5)
                    }
                    // Restore buffer ke vertex data asli
                    val restoreBuf = ByteBuffer.allocateDirect(obj.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    restoreBuf.put(obj.vertices).position(0)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, obj.vertices.size * 4, restoreBuf, GLES30.GL_DYNAMIC_DRAW)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                    GLES30.glEnableVertexAttribArray(0)
                } else if (obj.type == "cylinder" || obj.type == "prism") {
                    // Cylinder & Prism: sisi samping rectangle (wireframe tanpa diagonal), top/bottom cap tetap triangle
                    // Setiap segmen: 6 vertex side + 3 vertex top + 3 vertex bottom = 12 vertex
                    val vertsPerSeg = 12
                    val segs = obj.vertexCount / vertsPerSeg
                    for (si in 0 until segs) {
                        val base = si * vertsPerSeg * 3  // base index dalam float array
                        // Side (6 vertex = 2 segitiga) -> rectangle
                        // Vertex: i0=base(bottom-left), i1=base+3(bottom-right), i2=base+6(top-right), i5=base+15(top-left)
                        val i0 = base
                        val i1 = base + 3
                        val i2 = base + 6
                        val i5 = base + 15
                        val idx = intArrayOf(i0, i1, i2, i5, i0)
                        val edgeVerts = FloatArray(15)
                        for (k in 0 until 5) {
                            edgeVerts[k*3] = obj.vertices[idx[k]]
                            edgeVerts[k*3+1] = obj.vertices[idx[k]+1]
                            edgeVerts[k*3+2] = obj.vertices[idx[k]+2]
                        }
                        val edgeBuf = ByteBuffer.allocateDirect(edgeVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        edgeBuf.put(edgeVerts).position(0)
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
                        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, edgeVerts.size * 4, edgeBuf, GLES30.GL_DYNAMIC_DRAW)
                        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                        GLES30.glEnableVertexAttribArray(0)
                        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, 5)

                        // Top cap triangle (3 vertex setelah side: offset 6*3 = 18 floats)
                        val topBase = base + 18
                        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, topBase / 3, 3)

                        // Bottom cap triangle (3 vertex setelah top cap: offset 3*3 = 9 floats)
                        val botBase = topBase + 9
                        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, botBase / 3, 3)
                    }
                    // Restore buffer ke vertex data asli
                    val restoreBuf2 = ByteBuffer.allocateDirect(obj.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    restoreBuf2.put(obj.vertices).position(0)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, obj.vertices.size * 4, restoreBuf2, GLES30.GL_DYNAMIC_DRAW)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                    GLES30.glEnableVertexAttribArray(0)
                } else if (obj.type == "torus" || obj.type == "sphere") {
                    // Torus & Sphere: setiap segmen adalah rectangle (6 vertex = 2 segitiga) tanpa diagonal
                    val vertsPerSeg = 6
                    val segs = obj.vertexCount / vertsPerSeg
                    for (si in 0 until segs) {
                        val base = si * vertsPerSeg * 3  // base index dalam float array
                        // 6 vertex: p0,p1,p2, p0,p2,p3 -> rectangle dari p0,p1,p2,p3
                        // i0=base(p0), i1=base+3(p1), i2=base+6(p2), i5=base+15(p3)
                        val i0 = base
                        val i1 = base + 3
                        val i2 = base + 6
                        val i5 = base + 15
                        val idx = intArrayOf(i0, i1, i2, i5, i0)
                        val edgeVerts = FloatArray(15)
                        for (k in 0 until 5) {
                            edgeVerts[k*3] = obj.vertices[idx[k]]
                            edgeVerts[k*3+1] = obj.vertices[idx[k]+1]
                            edgeVerts[k*3+2] = obj.vertices[idx[k]+2]
                        }
                        val edgeBuf = ByteBuffer.allocateDirect(edgeVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        edgeBuf.put(edgeVerts).position(0)
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
                        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, edgeVerts.size * 4, edgeBuf, GLES30.GL_DYNAMIC_DRAW)
                        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                        GLES30.glEnableVertexAttribArray(0)
                        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, 5)
                    }
                    // Restore buffer ke vertex data asli
                    val restoreBuf3 = ByteBuffer.allocateDirect(obj.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    restoreBuf3.put(obj.vertices).position(0)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, objectVbo)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, obj.vertices.size * 4, restoreBuf3, GLES30.GL_DYNAMIC_DRAW)
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                    GLES30.glEnableVertexAttribArray(0)
                                                                
                }
            }
        }

        GLES30.glBindVertexArray(0)
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  SCENE OBJECT MANAGEMENT
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /** Generate mesh vertices for a basic shape */
    private fun generateShapeVertices(type: String): FloatArray {
        val s = 8f // base size
        return when (type.lowercase()) {
            "box" -> {
                floatArrayOf(
                    -s,-s, s,  s,-s, s,  s, s, s,   -s,-s, s,  s, s, s, -s, s, s,  // front
                    -s,-s,-s, -s, s,-s,  s, s,-s,   -s,-s,-s,  s, s,-s,  s,-s,-s,  // back
                    -s, s,-s, -s, s, s,  s, s, s,   -s, s,-s,  s, s, s,  s, s,-s,  // top
                    -s,-s,-s,  s,-s,-s,  s,-s, s,   -s,-s,-s,  s,-s, s, -s,-s, s,  // bottom
                     s,-s,-s,  s, s,-s,  s, s, s,    s,-s,-s,  s, s, s,  s,-s, s,  // right
                    -s,-s,-s, -s,-s, s, -s, s, s,   -s,-s,-s, -s, s, s, -s, s,-s,  // left
                )
            }
            "sphere" -> {
                // UV Sphere approximation dengan quad grid (rectangle per segmen)
                val verts = mutableListOf<Float>()
                val segH = 16  // horizontal segments
                val segV = 12  // vertical segments
                val r = s
                for (i in 0 until segH) {
                    val u0 = 2f * kotlin.math.PI.toFloat() * i / segH
                    val u1 = 2f * kotlin.math.PI.toFloat() * (i+1) / segH
                    for (j in 0 until segV) {
                        val v0 = kotlin.math.PI.toFloat() * j / segV
                        val v1 = kotlin.math.PI.toFloat() * (j+1) / segV
                        // 4 corner points of the quad
                        val p0 = floatArrayOf(r*sin(v0)*cos(u0), r*cos(v0), r*sin(v0)*sin(u0))
                        val p1 = floatArrayOf(r*sin(v1)*cos(u0), r*cos(v1), r*sin(v1)*sin(u0))
                        val p2 = floatArrayOf(r*sin(v1)*cos(u1), r*cos(v1), r*sin(v1)*sin(u1))
                        val p3 = floatArrayOf(r*sin(v0)*cos(u1), r*cos(v0), r*sin(v0)*sin(u1))
                        // Dua segitiga membentuk rectangle: p0-p1-p2 dan p0-p2-p3
                        verts.addAll(p0.toList()); verts.addAll(p1.toList()); verts.addAll(p2.toList())
                        verts.addAll(p0.toList()); verts.addAll(p2.toList()); verts.addAll(p3.toList())
                    }
                }
                verts.toFloatArray()
            }
            "cylinder" -> {
                val verts = mutableListOf<Float>()
                val segs = 16
                val r = s * 0.7f; val h = s
                // Side
                for (i in 0 until segs) {
                    val a0 = 2f * kotlin.math.PI.toFloat() * i / segs
                    val a1 = 2f * kotlin.math.PI.toFloat() * (i+1) / segs
                    val c0 = cos(a0)*r; val s0 = sin(a0)*r
                    val c1 = cos(a1)*r; val s1 = sin(a1)*r
                    verts.addAll(listOf(c0,-h,s0, c1,-h,s1, c1, h,s1))
                    verts.addAll(listOf(c0,-h,s0, c1, h,s1, c0, h,s0))
                }
                // Top cap
                for (i in 0 until segs) {
                    val a0 = 2f * kotlin.math.PI.toFloat() * i / segs
                    val a1 = 2f * kotlin.math.PI.toFloat() * (i+1) / segs
                    val c0 = cos(a0)*r; val s0 = sin(a0)*r
                    val c1 = cos(a1)*r; val s1 = sin(a1)*r
                    verts.addAll(listOf(0f,h,0f, c0,h,s0, c1,h,s1))
                }
                // Bottom cap
                for (i in 0 until segs) {
                    val a0 = 2f * kotlin.math.PI.toFloat() * i / segs
                    val a1 = 2f * kotlin.math.PI.toFloat() * (i+1) / segs
                    val c0 = cos(a0)*r; val s0 = sin(a0)*r
                    val c1 = cos(a1)*r; val s1 = sin(a1)*r
                    verts.addAll(listOf(0f,-h,0f, c1,-h,s1, c0,-h,s0))
                }
                verts.toFloatArray()
            }
            "cone" -> {
                val verts = mutableListOf<Float>()
                val segs = 16
                val r = s * 0.7f; val h = s
                // Side
                for (i in 0 until segs) {
                    val a0 = 2f * kotlin.math.PI.toFloat() * i / segs
                    val a1 = 2f * kotlin.math.PI.toFloat() * (i+1) / segs
                    val c0 = cos(a0)*r; val s0 = sin(a0)*r
                    val c1 = cos(a1)*r; val s1 = sin(a1)*r
                    verts.addAll(listOf(c0,-h,s0, c1,-h,s1, 0f,h,0f))
                }
                // Bottom cap
                for (i in 0 until segs) {
                    val a0 = 2f * kotlin.math.PI.toFloat() * i / segs
                    val a1 = 2f * kotlin.math.PI.toFloat() * (i+1) / segs
                    val c0 = cos(a0)*r; val s0 = sin(a0)*r
                    val c1 = cos(a1)*r; val s1 = sin(a1)*r
                    verts.addAll(listOf(0f,-h,0f, c1,-h,s1, c0,-h,s0))
                }
                verts.toFloatArray()
            }
            "torus" -> {
                val verts = mutableListOf<Float>()
                val segR = 12; val segT = 8
                val R = s * 0.6f; val r = s * 0.3f
                for (i in 0 until segR) {
                    val u0 = 2f * kotlin.math.PI.toFloat() * i / segR
                    val u1 = 2f * kotlin.math.PI.toFloat() * (i+1) / segR
                    for (j in 0 until segT) {
                        val v0 = 2f * kotlin.math.PI.toFloat() * j / segT
                        val v1 = 2f * kotlin.math.PI.toFloat() * (j+1) / segT
                        val p0 = floatArrayOf((R + r*cos(v0))*cos(u0), r*sin(v0), (R + r*cos(v0))*sin(u0))
                        val p1 = floatArrayOf((R + r*cos(v1))*cos(u0), r*sin(v1), (R + r*cos(v1))*sin(u0))
                        val p2 = floatArrayOf((R + r*cos(v1))*cos(u1), r*sin(v1), (R + r*cos(v1))*sin(u1))
                        val p3 = floatArrayOf((R + r*cos(v0))*cos(u1), r*sin(v0), (R + r*cos(v0))*sin(u1))
                        verts.addAll(p0.toList()); verts.addAll(p1.toList()); verts.addAll(p2.toList())
                        verts.addAll(p0.toList()); verts.addAll(p2.toList()); verts.addAll(p3.toList())
                    }
                }
                verts.toFloatArray()
            }
            "prism", "polyhedron" -> {
                val verts = mutableListOf<Float>()
                val h = s * 0.6f
                // Hexagonal prism approximation (6-sided)
                for (i in 0 until 6) {
                    val a0 = 2f * kotlin.math.PI.toFloat() * i / 6
                    val a1 = 2f * kotlin.math.PI.toFloat() * (i+1) / 6
                    val c0 = cos(a0)*s; val s0 = sin(a0)*s
                    val c1 = cos(a1)*s; val s1 = sin(a1)*s
                    // Side quad
                    verts.addAll(listOf(c0,-h,s0, c1,-h,s1, c1, h,s1))
                    verts.addAll(listOf(c0,-h,s0, c1, h,s1, c0, h,s0))
                    // Top triangle
                    verts.addAll(listOf(0f, h,0f, c1, h,s1, c0, h,s0))
                    // Bottom triangle
                    verts.addAll(listOf(0f,-h,0f, c0,-h,s0, c1,-h,s1))
                }
                verts.toFloatArray()
            }
            else -> floatArrayOf() // empty
        }
    }

    /** Create a new shape and add it to the scene. Returns the created object or null. */
    fun createShape(type: String): SceneObject? {
        // Jalur baru: primitive dibangun sebagai topologi indexed (quad + triangle).
        val pm = Primitives.create(type)
        val verts = pm?.soup ?: generateShapeVertices(type)
        if (verts.isEmpty()) return null
        pushUndo()

        objectCounter++
        val name = "$type $objectCounter"
        val vCount = verts.size / 3

        val obj = SceneObject(
            name = name,
            type = type,
            vertices = verts,
            vertexCount = vCount,
            mesh = pm,
        )
        objects.add(obj)
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
        return obj
    }

    /**
     * Daftar datar untuk render & picking: object biasa apa adanya,
     * group (type == "group" & punya children) diganti children-nya
     * (rekursif untuk nested group). Anak mewarisi visibility parent.
     */
    private fun flattenForRender(): List<SceneObject> {
        val out = mutableListOf<SceneObject>()
        fun addRec(o: SceneObject, parentVisible: Boolean) {
            val vis = parentVisible && o.visible
            if (o.type == "group" && !o.children.isNullOrEmpty()) {
                for (c in o.children!!) addRec(c, vis)
                return
            }
            // Group kosong (tanpa child) dilewati agar tidak menggambar apa-apa.
            if (o.type == "group") return
            if (!vis) return
            out.add(o)
        }
        for (o in objects) addRec(o, true)
        return out
    }

    /** Get a copy of the objects list */
    fun getObjects(): List<SceneObject> = objects.toList()

    // â”€â”€ Draw selected element highlight (orange fill/wireframe) â”€â”€
    private var highlightVao = 0
    private var highlightVbo = 0
    private var highlightVertexCount = 0
    private var highlightDrawMode = GLES30.GL_TRIANGLES

    private fun buildHighlightMesh(): FloatArray? {
        val verts = mutableListOf<Float>()
        highlightDrawMode = GLES30.GL_TRIANGLES

        when (selectMode) {
            SelectMode.NODE -> {
                if (selectedNodeIndices.isEmpty() || selectedObjectForHighlight == null) return null
                val obj = selectedObjectForHighlight!!
                val vArray = obj.vertices
                val r = 2.5f
                for (vi in selectedNodeIndices) {
                    if (vi < 0 || vi * 3 + 2 >= vArray.size) continue
                    val base = vi * 3
                    val cx = vArray[base]; val cy = vArray[base+1]; val cz = vArray[base+2]
                    verts.addAll(listOf(cx-r, cy, cz, cx+r, cy, cz))
                    verts.addAll(listOf(cx, cy-r, cz, cx, cy+r, cz))
                    verts.addAll(listOf(cx, cy, cz-r, cx, cy, cz+r))
                }
                highlightDrawMode = GLES30.GL_LINES
            }
            SelectMode.EDGE -> {
                if (selectedEdgeIndices.isEmpty() || selectedObjectForHighlight == null) return null
                val obj = selectedObjectForHighlight!!
                val vArray = obj.vertices
                for (ei in selectedEdgeIndices) {
                    if (ei < 0) continue
                    val triIdx = ei / 3
                    val edgeIdx = ei % 3
                    val base = triIdx * 9
                    if (base + 9 > vArray.size) continue
                    val v0x = vArray[base]; val v0y = vArray[base+1]; val v0z = vArray[base+2]
                    val v1x = vArray[base+3]; val v1y = vArray[base+4]; val v1z = vArray[base+5]
                    val v2x = vArray[base+6]; val v2y = vArray[base+7]; val v2z = vArray[base+8]
                    var ax = v0x; var ay = v0y; var az = v0z
                    var bx = v1x; var by = v1y; var bz = v1z
                    if (edgeIdx == 1) {
                        ax = v1x; ay = v1y; az = v1z
                        bx = v2x; by = v2y; bz = v2z
                    } else if (edgeIdx >= 2) {
                        ax = v2x; ay = v2y; az = v2z
                        bx = v0x; by = v0y; bz = v0z
                    }
                    verts.addAll(listOf(ax, ay, az, bx, by, bz))
                }
                highlightDrawMode = GLES30.GL_LINES
            }
            SelectMode.SURFACE -> {
                if (selectedFaceIndices.isEmpty() || selectedObjectForHighlight == null) return null
                val obj = selectedObjectForHighlight!!
                val vertArray = obj.vertices
                val pm = obj.mesh
                for (fi in selectedFaceIndices) {
                    if (fi < 0) continue
                    if (pm != null) {
                        if (fi >= pm.faceCount) continue
                        val faceIndices = if (pm.mergeCoplanarSelection) pm.faceGroupMembers(fi) else intArrayOf(fi)
                        for (member in faceIndices) {
                            val f = pm.faces[member]
                            for (k in 1 until f.size - 1) {
                                for (vi in intArrayOf(f[0], f[k], f[k + 1])) {
                                    verts.add(pm.vx(vi)); verts.add(pm.vy(vi)); verts.add(pm.vz(vi))
                                }
                            }
                        }
                    } else {
                        val tpf = trianglesPerFace(obj.type)
                        val triStart = fi * tpf
                        for (ti in 0 until tpf) {
                            val base = (triStart + ti) * 9
                            if (base + 9 > vertArray.size) break
                            for (i in 0 until 9) {
                                verts.add(vertArray[base + i])
                            }
                        }
                    }
                }
                highlightDrawMode = GLES30.GL_TRIANGLES
            }
                        SelectMode.OBJECT -> {
                // Kumpulkan object terpilih termasuk seluruh ANGGOTA group yang terpilih
                // (saat "Group 1" dipilih, semua object anggotanya ikut ter-highlight).
                val selObjs = mutableListOf<SceneObject>()
                fun collectSelected(o: SceneObject, ancestorSelected: Boolean) {
                    val effSelected = o.selected || ancestorSelected
                    if (o.type != "group" && effSelected) selObjs.add(o)
                    o.children?.forEach { collectSelected(it, effSelected) }
                }
                for (obj in objects) collectSelected(obj, false)
                if (selObjs.isEmpty()) return null
                // For multi-object highlight, transform each object's vertices to world space
                for (obj in selObjs) {
                    val mat = obj.modelMatrix
                    if (obj.type == "enclose") {
                        for (i in 0 until obj.vertexCount) {
                            val b = i * 3
                            val w = localToWorld(floatArrayOf(obj.vertices[b], obj.vertices[b+1], obj.vertices[b+2]), mat)
                            verts.add(w[0]); verts.add(w[1]); verts.add(w[2])
                        }
                        highlightDrawMode = GLES30.GL_LINES
                    } else {
                        for (i in 0 until obj.vertexCount) {
                            val b = i * 3
                            val w = localToWorld(floatArrayOf(obj.vertices[b], obj.vertices[b+1], obj.vertices[b+2]), mat)
                            verts.add(w[0]); verts.add(w[1]); verts.add(w[2])
                        }
                        highlightDrawMode = GLES30.GL_TRIANGLES
                    }
                }
            }
            else -> return null
        }

        return if (verts.isEmpty()) null else verts.toFloatArray()
    }

    private fun drawHighlight() {
        val hlVerts = buildHighlightMesh() ?: return
        highlightVertexCount = hlVerts.size / 3

        // OBJECT mode: vertices already in world space, use identity model matrix
        // NODE/EDGE/SURFACE: vertices in local space, use object's model matrix
        val objMat = if (selectMode == SelectMode.OBJECT) {
            FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        } else {
            selectedObjectForHighlight?.modelMatrix ?: modelMatrix
        }
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vpMatrix, 0, objMat, 0)

        val buf = ByteBuffer.allocateDirect(hlVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(hlVerts).position(0)

        if (highlightVao == 0) {
            val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); highlightVao = vaos[0]
            val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); highlightVbo = vbos[0]
        }
        GLES30.glBindVertexArray(highlightVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, highlightVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, hlVerts.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(0)

        GLES30.glUseProgram(gridProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gridProgram, "uMVP"), 1, false, mvp, 0)

        if (highlightDrawMode == GLES30.GL_LINES) {
            // Orange wireframe for NODE and EDGE modes
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glLineWidth(4f)
            GLES30.glUniform4f(GLES30.glGetUniformLocation(gridProgram, "uCol"), 1f, 0.6f, 0f, 1f)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, highlightVertexCount)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        } else {
            if (selectMode == SelectMode.SURFACE) {
                // Solid orange fill for surface
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                GLES30.glDisable(GLES30.GL_CULL_FACE)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(gridProgram, "uCol"), 1f, 0.6f, 0f, 0.9f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, highlightVertexCount)
                // Wireframe outline on top
                GLES30.glLineWidth(3f)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(gridProgram, "uCol"), 1f, 0.6f, 0f, 1f)
                GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, highlightVertexCount)
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            } else { // OBJECT mode
                // Orange fill (semi-transparent) with wireframe outline
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                GLES30.glDisable(GLES30.GL_CULL_FACE)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(gridProgram, "uCol"), 1f, 0.6f, 0f, 0.5f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, highlightVertexCount)
                // Wireframe outline on top
                GLES30.glLineWidth(3f)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(gridProgram, "uCol"), 1f, 0.6f, 0f, 1f)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, highlightVertexCount)
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            }
        }

        GLES30.glBindVertexArray(0)
    }

    // â”€â”€ GIZMO â”€â”€
    // â”€â”€ GIZMO 3D â”€â”€
    // Data class to hold vertices per axis with draw mode
    private data class AxisMesh(val vertices: FloatArray, val count: Int, val drawMode: Int = GLES30.GL_TRIANGLES)

        private fun buildAxisMesh(axis: String): List<AxisMesh> {
        val px = gizmoWorldPos[0]; val py = gizmoWorldPos[1]; val pz = gizmoWorldPos[2]
        val len = 18f
        val result = mutableListOf<AxisMesh>()

        when (transformMode) {
            "translate", "extrude" -> {
                                // 3D cone arrow for each axis
                val segs = 12
                val shaftLen = len * 0.65f
                val coneLen = 1.5f
                val shaftR = 0.25f
                val coneR = 0.7f

                when (axis) {
                    "x" -> {
                        val shaft = mutableListOf<Float>()
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val y1 = py + shaftR * cos(a1); val z1 = pz + shaftR * sin(a1)
                            val y2 = py + shaftR * cos(a2); val z2 = pz + shaftR * sin(a2)
                            val ex = px + shaftLen
                            shaft.addAll(listOf(px, y1, z1, px, y2, z2, ex, y2, z2))
                            shaft.addAll(listOf(px, y1, z1, ex, y2, z2, ex, y1, z1))
                        }
                        result.add(AxisMesh(shaft.toFloatArray(), shaft.size / 3, GLES30.GL_TRIANGLES))
                        val cone = mutableListOf<Float>()
                        val bx = px + shaftLen; val tx = px + shaftLen + coneLen
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val y1 = py + coneR * cos(a1); val z1 = pz + coneR * sin(a1)
                            val y2 = py + coneR * cos(a2); val z2 = pz + coneR * sin(a2)
                            cone.addAll(listOf(bx, y1, z1, bx, y2, z2, tx, py, pz))
                        }
                        result.add(AxisMesh(cone.toFloatArray(), cone.size / 3, GLES30.GL_TRIANGLES))
                    }
                    "y" -> {
                        val shaft = mutableListOf<Float>()
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val x1 = px + shaftR * cos(a1); val z1 = pz + shaftR * sin(a1)
                            val x2 = px + shaftR * cos(a2); val z2 = pz + shaftR * sin(a2)
                            val ey = py + shaftLen
                            shaft.addAll(listOf(x1, py, z1, x2, py, z2, x2, ey, z2))
                            shaft.addAll(listOf(x1, py, z1, x2, ey, z2, x1, ey, z1))
                        }
                        result.add(AxisMesh(shaft.toFloatArray(), shaft.size / 3, GLES30.GL_TRIANGLES))
                        val cone = mutableListOf<Float>()
                        val by = py + shaftLen; val ty = py + shaftLen + coneLen
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val x1 = px + coneR * cos(a1); val z1 = pz + coneR * sin(a1)
                            val x2 = px + coneR * cos(a2); val z2 = pz + coneR * sin(a2)
                            cone.addAll(listOf(x1, by, z1, x2, by, z2, px, ty, pz))
                        }
                        result.add(AxisMesh(cone.toFloatArray(), cone.size / 3, GLES30.GL_TRIANGLES))
                    }
                    "z" -> {
                        val shaft = mutableListOf<Float>()
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val x1 = px + shaftR * cos(a1); val y1 = py + shaftR * sin(a1)
                            val x2 = px + shaftR * cos(a2); val y2 = py + shaftR * sin(a2)
                            val ez = pz + shaftLen
                            shaft.addAll(listOf(x1, y1, pz, x2, y2, pz, x2, y2, ez))
                            shaft.addAll(listOf(x1, y1, pz, x2, y2, ez, x1, y1, ez))
                        }
                        result.add(AxisMesh(shaft.toFloatArray(), shaft.size / 3, GLES30.GL_TRIANGLES))
                        val cone = mutableListOf<Float>()
                        val bz = pz + shaftLen; val tz = pz + shaftLen + coneLen
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val x1 = px + coneR * cos(a1); val y1 = py + coneR * sin(a1)
                            val x2 = px + coneR * cos(a2); val y2 = py + coneR * sin(a2)
                            cone.addAll(listOf(x1, y1, bz, x2, y2, bz, px, py, tz))
                        }
                        result.add(AxisMesh(cone.toFloatArray(), cone.size / 3, GLES30.GL_TRIANGLES))
                    }
                }
            }
            "rotate" -> {
                // 3D thick torus-like circle for each axis
                val segs = 48
                val circleR = len
                val tubeR = 0.3f
                when (axis) {
                    "x" -> { // Y-Z plane
                        val v = mutableListOf<Float>()
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val c1 = cos(a1); val s1 = sin(a1); val c2 = cos(a2); val s2 = sin(a2)
                            val iy1 = py + (circleR - tubeR) * c1; val iz1 = pz + (circleR - tubeR) * s1
                            val oy1 = py + (circleR + tubeR) * c1; val oz1 = pz + (circleR + tubeR) * s1
                            val iy2 = py + (circleR - tubeR) * c2; val iz2 = pz + (circleR - tubeR) * s2
                            val oy2 = py + (circleR + tubeR) * c2; val oz2 = pz + (circleR + tubeR) * s2
                            v.addAll(listOf(px, iy1, iz1, px, iy2, iz2, px, oy2, oz2))
                            v.addAll(listOf(px, iy1, iz1, px, oy2, oz2, px, oy1, oz1))
                        }
                        result.add(AxisMesh(v.toFloatArray(), v.size / 3, GLES30.GL_TRIANGLES))
                    }
                    "y" -> { // X-Z plane
                        val v = mutableListOf<Float>()
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val c1 = cos(a1); val s1 = sin(a1); val c2 = cos(a2); val s2 = sin(a2)
                            val ix1 = px + (circleR - tubeR) * c1; val iz1 = pz + (circleR - tubeR) * s1
                            val ox1 = px + (circleR + tubeR) * c1; val oz1 = pz + (circleR + tubeR) * s1
                            val ix2 = px + (circleR - tubeR) * c2; val iz2 = pz + (circleR - tubeR) * s2
                            val ox2 = px + (circleR + tubeR) * c2; val oz2 = pz + (circleR + tubeR) * s2
                            v.addAll(listOf(ix1, py, iz1, ix2, py, iz2, ox2, py, oz2))
                            v.addAll(listOf(ix1, py, iz1, ox2, py, oz2, ox1, py, oz1))
                        }
                        result.add(AxisMesh(v.toFloatArray(), v.size / 3, GLES30.GL_TRIANGLES))
                    }
                    "z" -> { // X-Y plane
                        val v = mutableListOf<Float>()
                        for (i in 0 until segs) {
                            val a1 = 2f * kotlin.math.PI.toFloat() * i / segs
                            val a2 = 2f * kotlin.math.PI.toFloat() * (i + 1) / segs
                            val c1 = cos(a1); val s1 = sin(a1); val c2 = cos(a2); val s2 = sin(a2)
                            val ix1 = px + (circleR - tubeR) * c1; val iy1 = py + (circleR - tubeR) * s1
                            val ox1 = px + (circleR + tubeR) * c1; val oy1 = py + (circleR + tubeR) * s1
                            val ix2 = px + (circleR - tubeR) * c2; val iy2 = py + (circleR - tubeR) * s2
                            val ox2 = px + (circleR + tubeR) * c2; val oy2 = py + (circleR + tubeR) * s2
                            v.addAll(listOf(ix1, iy1, pz, ix2, iy2, pz, ox2, oy2, pz))
                            v.addAll(listOf(ix1, iy1, pz, ox2, oy2, pz, ox1, oy1, pz))
                        }
                        result.add(AxisMesh(v.toFloatArray(), v.size / 3, GLES30.GL_TRIANGLES))
                    }
                }
            }
            "scale" -> {
                // Shaft line + 3D cube at end for each axis
                val half = 0.9f
                when (axis) {
                    "x" -> {
                        val v = mutableListOf<Float>()
                        v.addAll(listOf(px, py, pz, px + len, py, pz)) // shaft line
                        val bx = px + len; val h = half
                        // 6 faces, 2 triangles each
                        v.addAll(listOf(bx-h, py-h, pz+h, bx+h, py-h, pz+h, bx+h, py+h, pz+h))
                        v.addAll(listOf(bx-h, py-h, pz+h, bx+h, py+h, pz+h, bx-h, py+h, pz+h))
                        v.addAll(listOf(bx-h, py-h, pz-h, bx+h, py+h, pz-h, bx+h, py-h, pz-h))
                        v.addAll(listOf(bx-h, py-h, pz-h, bx-h, py+h, pz-h, bx+h, py+h, pz-h))
                        v.addAll(listOf(bx-h, py+h, pz-h, bx+h, py+h, pz-h, bx+h, py+h, pz+h))
                        v.addAll(listOf(bx-h, py+h, pz-h, bx+h, py+h, pz+h, bx-h, py+h, pz+h))
                        v.addAll(listOf(bx-h, py-h, pz-h, bx+h, py-h, pz+h, bx+h, py-h, pz-h))
                        v.addAll(listOf(bx-h, py-h, pz-h, bx-h, py-h, pz+h, bx+h, py-h, pz+h))
                        v.addAll(listOf(bx+h, py-h, pz-h, bx+h, py+h, pz-h, bx+h, py+h, pz+h))
                        v.addAll(listOf(bx+h, py-h, pz-h, bx+h, py+h, pz+h, bx+h, py-h, pz+h))
                        v.addAll(listOf(bx-h, py-h, pz-h, bx-h, py+h, pz+h, bx-h, py+h, pz-h))
                        v.addAll(listOf(bx-h, py-h, pz-h, bx-h, py-h, pz+h, bx-h, py+h, pz+h))
                        result.add(AxisMesh(v.toFloatArray(), v.size / 3))
                    }
                    "y" -> {
                        val v = mutableListOf<Float>()
                        v.addAll(listOf(px, py, pz, px, py + len, pz))
                        val by = py + len; val h = half
                        v.addAll(listOf(px-h, by-h, pz+h, px+h, by-h, pz+h, px+h, by+h, pz+h))
                        v.addAll(listOf(px-h, by-h, pz+h, px+h, by+h, pz+h, px-h, by+h, pz+h))
                        v.addAll(listOf(px-h, by-h, pz-h, px+h, by+h, pz-h, px+h, by-h, pz-h))
                        v.addAll(listOf(px-h, by-h, pz-h, px-h, by+h, pz-h, px+h, by+h, pz-h))
                        v.addAll(listOf(px-h, by+h, pz-h, px+h, by+h, pz-h, px+h, by+h, pz+h))
                        v.addAll(listOf(px-h, by+h, pz-h, px+h, by+h, pz+h, px-h, by+h, pz+h))
                        v.addAll(listOf(px-h, by-h, pz-h, px+h, by-h, pz+h, px+h, by-h, pz-h))
                        v.addAll(listOf(px-h, by-h, pz-h, px-h, by-h, pz+h, px+h, by-h, pz+h))
                        v.addAll(listOf(px+h, by-h, pz-h, px+h, by+h, pz-h, px+h, by+h, pz+h))
                        v.addAll(listOf(px+h, by-h, pz-h, px+h, by+h, pz+h, px+h, by-h, pz+h))
                        v.addAll(listOf(px-h, by-h, pz-h, px-h, by+h, pz+h, px-h, by+h, pz-h))
                        v.addAll(listOf(px-h, by-h, pz-h, px-h, by-h, pz+h, px-h, by+h, pz+h))
                        result.add(AxisMesh(v.toFloatArray(), v.size / 3))
                    }
                    "z" -> {
                        val v = mutableListOf<Float>()
                        v.addAll(listOf(px, py, pz, px, py, pz + len))
                        val bz = pz + len; val h = half
                        v.addAll(listOf(px-h, py-h, bz+h, px+h, py-h, bz+h, px+h, py+h, bz+h))
                        v.addAll(listOf(px-h, py-h, bz+h, px+h, py+h, bz+h, px-h, py+h, bz+h))
                        v.addAll(listOf(px-h, py-h, bz-h, px+h, py+h, bz-h, px+h, py-h, bz-h))
                        v.addAll(listOf(px-h, py-h, bz-h, px-h, py+h, bz-h, px+h, py+h, bz-h))
                        v.addAll(listOf(px-h, py+h, bz-h, px+h, py+h, bz-h, px+h, py+h, bz+h))
                        v.addAll(listOf(px-h, py+h, bz-h, px+h, py+h, bz+h, px-h, py+h, bz+h))
                        v.addAll(listOf(px-h, py-h, bz-h, px+h, py-h, bz+h, px+h, py-h, bz-h))
                        v.addAll(listOf(px-h, py-h, bz-h, px-h, py-h, bz+h, px+h, py-h, bz+h))
                        v.addAll(listOf(px+h, py-h, bz-h, px+h, py+h, bz-h, px+h, py+h, bz+h))
                        v.addAll(listOf(px+h, py-h, bz-h, px+h, py+h, bz+h, px+h, py-h, bz+h))
                        v.addAll(listOf(px-h, py-h, bz-h, px-h, py+h, bz+h, px-h, py+h, bz-h))
                        v.addAll(listOf(px-h, py-h, bz-h, px-h, py-h, bz+h, px-h, py+h, bz+h))
                        result.add(AxisMesh(v.toFloatArray(), v.size / 3))
                    }
                }
            }
        }
        return result
    }

    private fun drawGizmo() {
        if (!gizmoVisible) return

        GLES30.glUseProgram(gridProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gridProgram, "uMVP"), 1, false, vpMatrix, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Axis colors: X=Red, Y=Green, Z=Blue
        val axisColors = mapOf(
            "x" to floatArrayOf(1f, 0.2f, 0.2f, 1f),
            "y" to floatArrayOf(0.2f, 1f, 0.2f, 1f),
            "z" to floatArrayOf(0.2f, 0.4f, 1f, 1f)
        )

        // Pada extrude-mode, gizmo membawa SATU axis arah normal face saja
        // (axis Z yang di-rotat sejajar normal). X/Y tidak dipakain.
        val axesToDraw = if (isExtrudeMode) listOf("z") else listOf("x", "y", "z")

        for (axis in axesToDraw) {
            val color = axisColors[axis] ?: continue
            val meshes = buildAxisMesh(axis)
            if (meshes.isEmpty()) continue
            
            // Gizmo di-rotat ke normal face saat extrude-mode ATAU saat select surface
            // (Z sejajar normal permukaan, X/Y tegak lurus).
            val gizmoOriented = isExtrudeMode || selectMode == SelectMode.SURFACE
            val useMVP = if (gizmoOriented) {
                val model = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
                android.opengl.Matrix.translateM(model, 0, gizmoWorldPos[0], gizmoWorldPos[1], gizmoWorldPos[2])
                android.opengl.Matrix.multiplyMM(model, 0, model, 0, gizmoRotMatrix, 0)
                android.opengl.Matrix.translateM(model, 0, -gizmoWorldPos[0], -gizmoWorldPos[1], -gizmoWorldPos[2])
                val mvp = FloatArray(16)
                android.opengl.Matrix.multiplyMM(mvp, 0, vpMatrix, 0, model, 0)
                mvp
            } else {
                vpMatrix
            }

            for (mesh in meshes) {
                val buf = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                buf.put(mesh.vertices).position(0)

                if (gizmoVao == 0) {
                    val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); gizmoVao = vaos[0]
                    val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); gizmoVbo = vbos[0]
                }
                GLES30.glBindVertexArray(gizmoVao)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gizmoVbo)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
                GLES30.glEnableVertexAttribArray(0)

                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gridProgram, "uMVP"), 1, false, useMVP, 0)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(gridProgram, "uCol"), color[0], color[1], color[2], color[3])
                GLES30.glLineWidth(3f)
                GLES30.glDrawArrays(mesh.drawMode, 0, mesh.count)
            }
        }

        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindVertexArray(0)
    }

    // ===== TOUCH =====
    fun handleTouch(event: MotionEvent): Boolean {
        val pc = event.pointerCount
        when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                isDragging = false; prevX = event.x; prevY = event.y
                dragBaselineTaken = false
                // Hanya aktifkan transform jika user klik tepat pada gizmo
                // (skip detection during extrude mode - grab is pre-set by startExtrude)
                if (!isExtrudeMode) {
                if (gizmoVisible) {
                    val detected = detectGrabbedAxis(event.x, event.y)
                    if (detected != "NONE") {
                        isTransformGrabbed = true
                        grabbedAxis = detected
                    } else {
                        isTransformGrabbed = false
                        grabbedAxis = "NONE"
                    }
                } else {
                    isTransformGrabbed = false
                    grabbedAxis = "NONE"
                }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { if (pc >= 2) { isPinching = true; isDragging = false; prevPinchDist = pinchDistance(event); prevPanX = (event.getX(0) + event.getX(1)) / 2f; prevPanY = (event.getY(0) + event.getY(1)) / 2f } }
            MotionEvent.ACTION_MOVE -> {
                if (isPinching && pc >= 2) {
                    val d = pinchDistance(event)
                    if (prevPinchDist > 0f) {
                        // Zoom: smooth ratio, clamp per frame change
                        val rawRatio = prevPinchDist / d
                        val clampedRatio = rawRatio.coerceIn(0.92f, 1.08f)
                        val newRadius = orbitRadius * clampedRatio.toDouble()
                        orbitRadius = newRadius.coerceIn(5.0, 500.0)
                    }
                    prevPinchDist = d
                    // Pan with 2 fingers â€” fix direction and reduce sensitivity
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val panDx = (midX - prevPanX) * (orbitRadius * 0.0008).toFloat()
                    val panDy = (midY - prevPanY) * (orbitRadius * 0.0008).toFloat()
                    orbitTargetX -= (panDx * cos(orbitTheta)).toFloat()
                    orbitTargetY += panDy
                    orbitTargetZ += (panDx * sin(orbitTheta)).toFloat()
                    prevPanX = midX; prevPanY = midY
                } else if (pc == 1 && isTransformGrabbed) {
                    // Transform drag: deformasi hanya jika user TEPAT grab gizmo axis
                    if (grabbedAxis != "NONE") {
                        val dx = event.x - prevX; val dy = event.y - prevY
                        isDragging = true
                        applyTransformDrag(event.x, event.y, dx, dy)
                        prevX = event.x; prevY = event.y
                    }
                } else if (pc == 1) {
                    // Default: single finger drag = orbit camera
                    val dx = event.x - prevX; val dy = event.y - prevY
                    if (sqrt((dx*dx+dy*dy).toDouble()).toFloat() > 8f) isDragging = true
                    if (isDragging) {
                        orbitTheta -= dx * 0.004; orbitPhi = (orbitPhi + dy * 0.004).coerceIn(-1.5, 1.5)
                        prevX = event.x; prevY = event.y
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging && pc == 1) {
                    val ndcX = (event.x/viewportWidth)*2f-1f
                    val ndcY = -(event.y/viewportHeight)*2f+1f
                    pick(ndcX, ndcY)

                    // After picking, auto-activate gizmo when a mesh element is selected
                    val hasValidSelection = when (selectMode) {
                        SelectMode.NODE -> selectedNodeIndex >= 0 && selectedObjectForHighlight != null
                        SelectMode.EDGE -> selectedEdgeIndex >= 0 && selectedObjectForHighlight != null
                        SelectMode.SURFACE -> selectedFaceIndex >= 0 && selectedObjectForHighlight != null
                        SelectMode.OBJECT -> selectedObjectIndex >= 0 && selectedObjectForHighlight != null
                        SelectMode.NONE -> false
                    }

                                        if (hasValidSelection) {
                        // Auto-set transformMode to "translate" for mesh deformation on NODE/EDGE/SURFACE
                        if (selectMode != SelectMode.OBJECT && transformMode == "none") {
                            transformMode = "translate"
                        }
                        // Show gizmo but don't grab until user clicks on it
                        gizmoVisible = true
                        gizmoWorldPos = getGizmoTargetWorldPos()
                        // Saat select surface, orient gizmo tegak lurus permukaan (Z sejajar normal face).
                        if (selectMode == SelectMode.SURFACE) {
                            val obj = selectedObjectForHighlight
                            val pm = obj?.mesh
                            if (obj != null && pm != null && selectedFaceIndex in 0 until pm.faceCount) {
                                val n = pm.faceNormal(selectedFaceIndex)
                                gizmoRotMatrix = buildRotationFromZ(n)
                            }
                        }
                        isTransformGrabbed = false
                        lastSelectionTime = System.currentTimeMillis()
                                        } else {
                        gizmoVisible = false
                        isTransformGrabbed = false
                    }
                    onTapCallback?.invoke(ndcX, ndcY)
                } else if (isDragging) {
                    // End of a drag operation â€” keep gizmo visible but release grab
                    // Gizmo stays visible so user can continue dragging
                    isTransformGrabbed = false  // User must click on gizmo again
                    endExtrude()
                }
                isDragging = false; isPinching = false
                dragBaselineTaken = false
            }
            MotionEvent.ACTION_POINTER_UP -> { isPinching = false }
        }
        return true
    }

    /** Check if there is an active selection (object, node, edge, or face) */
    private fun hasSelection(): Boolean {
        return when (selectMode) {
            SelectMode.NODE -> selectedNodeIndex >= 0 && selectedObjectForHighlight != null
            SelectMode.EDGE -> selectedEdgeIndex >= 0 && selectedObjectForHighlight != null
            SelectMode.SURFACE -> selectedFaceIndex >= 0 && selectedObjectForHighlight != null
            SelectMode.OBJECT -> selectedObjectIndex >= 0 && selectedObjectForHighlight != null
            SelectMode.NONE -> false
        }
    }

    /** Clear all selections across all modes */
    fun clearSelection() {
        selectedNodeIndex = -1
        selectedEdgeIndex = -1
        selectedFaceIndex = -1
        selectedObjectIndex = -1
        selectedNodeIndices.clear()
        selectedEdgeIndices.clear()
        selectedFaceIndices.clear()
        // Clear object selected flags
        for (obj in objects) { obj.selected = false }
        selectedObjectForHighlight = null
        gizmoVisible = false
        isTransformGrabbed = false
        endExtrude()
    }

    /** Get the active selected object (from any select mode) */
    fun getSelectedObject(): SceneObject? = selectedObjectForHighlight

    /**
     * Get the world-space position where the gizmo should be placed,
     * based on the current selectMode and selection indices.
     */
    fun getGizmoTargetWorldPos(): FloatArray {
        val obj = selectedObjectForHighlight ?: return floatArrayOf(0f, 8f, 0f)
        val mat = obj.modelMatrix

        when (selectMode) {
            SelectMode.NODE -> {
                // Multi-select: centroid of all selected nodes
                if (selectedNodeIndices.size > 1) {
                    var sumX = 0f; var sumY = 0f; var sumZ = 0f
                    for (ni in selectedNodeIndices) {
                        val base = ni * 3
                        if (base + 2 >= obj.vertices.size) continue  // guard: index stale / objek kosong
                        sumX += obj.vertices[base]
                        sumY += obj.vertices[base + 1]
                        sumZ += obj.vertices[base + 2]
                    }
                    val n = selectedNodeIndices.size.toFloat()
                    val centroid = floatArrayOf(sumX/n, sumY/n, sumZ/n)
                    val world = FloatArray(4)
                    Matrix.multiplyMV(world, 0, mat, 0, floatArrayOf(centroid[0], centroid[1], centroid[2], 1f), 0)
                    return floatArrayOf(world[0]/world[3], world[1]/world[3], world[2]/world[3])
                }
                if (selectedNodeIndex >= 0) {
                    val base = selectedNodeIndex * 3
                    if (base + 2 >= obj.vertices.size) return floatArrayOf(mat[12], mat[13], mat[14])
                    val local = floatArrayOf(obj.vertices[base], obj.vertices[base+1], obj.vertices[base+2])
                    val world = FloatArray(4)
                    Matrix.multiplyMV(world, 0, mat, 0, floatArrayOf(local[0], local[1], local[2], 1f), 0)
                    return floatArrayOf(world[0]/world[3], world[1]/world[3], world[2]/world[3])
                }
            }
            SelectMode.EDGE -> {
                // Multi-select: centroid of all selected edge midpoints
                if (selectedEdgeIndices.size > 1) {
                    var sumX = 0f; var sumY = 0f; var sumZ = 0f
                    for (ei in selectedEdgeIndices) {
                        val triIdx = ei / 3
                        val edgeInTri = ei % 3
                        val base = triIdx * 9
                        val vIdx0: Int; val vIdx1: Int
                        when (edgeInTri) {
                            0 -> { vIdx0 = 0; vIdx1 = 1 }
                            1 -> { vIdx0 = 1; vIdx1 = 2 }
                            2 -> { vIdx0 = 2; vIdx1 = 0 }
                            else -> { vIdx0 = 0; vIdx1 = 1 }
                        }
                        if (base + vIdx1 * 3 + 2 >= obj.vertices.size) continue  // guard: index stale
                        val v0 = floatArrayOf(obj.vertices[base + vIdx0*3], obj.vertices[base + vIdx0*3+1], obj.vertices[base + vIdx0*3+2])
                        val v1 = floatArrayOf(obj.vertices[base + vIdx1*3], obj.vertices[base + vIdx1*3+1], obj.vertices[base + vIdx1*3+2])
                        sumX += (v0[0] + v1[0]) / 2f
                        sumY += (v0[1] + v1[1]) / 2f
                        sumZ += (v0[2] + v1[2]) / 2f
                    }
                    val n = selectedEdgeIndices.size.toFloat()
                    val centroid = floatArrayOf(sumX/n, sumY/n, sumZ/n)
                    val world = FloatArray(4)
                    Matrix.multiplyMV(world, 0, mat, 0, floatArrayOf(centroid[0], centroid[1], centroid[2], 1f), 0)
                    return floatArrayOf(world[0]/world[3], world[1]/world[3], world[2]/world[3])
                }
                if (selectedEdgeIndex >= 0) {
                    // Decode edge index: triangle index * 3 + edge-in-triangle index
                    val triIdx = selectedEdgeIndex / 3
                    val edgeInTri = selectedEdgeIndex % 3
                    val base = triIdx * 9
                    // Get the two vertices that form this edge
                    val vIdx0: Int; val vIdx1: Int
                    when (edgeInTri) {
                        0 -> { vIdx0 = 0; vIdx1 = 1 }
                        1 -> { vIdx0 = 1; vIdx1 = 2 }
                        2 -> { vIdx0 = 2; vIdx1 = 0 }
                        else -> return floatArrayOf(mat[12], mat[13], mat[14])
                    }
                    if (base + vIdx1 * 3 + 2 >= obj.vertices.size) return floatArrayOf(mat[12], mat[13], mat[14])
                    val v0 = floatArrayOf(obj.vertices[base + vIdx0*3], obj.vertices[base + vIdx0*3+1], obj.vertices[base + vIdx0*3+2])
                    val v1 = floatArrayOf(obj.vertices[base + vIdx1*3], obj.vertices[base + vIdx1*3+1], obj.vertices[base + vIdx1*3+2])
                    // Midpoint
                    val mid = floatArrayOf((v0[0]+v1[0])/2f, (v0[1]+v1[1])/2f, (v0[2]+v1[2])/2f)
                    val world = FloatArray(4)
                    Matrix.multiplyMV(world, 0, mat, 0, floatArrayOf(mid[0], mid[1], mid[2], 1f), 0)
                    return floatArrayOf(world[0]/world[3], world[1]/world[3], world[2]/world[3])
                }
            }
            SelectMode.SURFACE -> {
                // Multi-select: centroid of all selected face centers
                if (selectedFaceIndices.size > 1) {
                    var sumX = 0f; var sumY = 0f; var sumZ = 0f; var count = 0
                    val pm = obj.mesh
                    for (fi in selectedFaceIndices) {
                        if (pm != null && fi < pm.faceCount) {
                            val c = pm.faceCenter(fi)
                            sumX += c[0]; sumY += c[1]; sumZ += c[2]; count++
                        } else {
                            val tpf = trianglesPerFace(obj.type)
                            val triStart = fi * tpf
                            val base = triStart * 9
                            if (base < 0 || base + tpf * 9 > obj.vertices.size) continue  // guard: index stale
                            for (ti in 0 until tpf) {
                                val b = base + ti * 9
                                for (i in 0 until 3) {
                                    sumX += obj.vertices[b + i*3]
                                    sumY += obj.vertices[b + i*3 + 1]
                                    sumZ += obj.vertices[b + i*3 + 2]
                                    count++
                                }
                            }
                        }
                    }
                    if (count > 0) {
                        val center = floatArrayOf(sumX/count, sumY/count, sumZ/count)
                        val w = FloatArray(4)
                        Matrix.multiplyMV(w, 0, mat, 0, floatArrayOf(center[0], center[1], center[2], 1f), 0)
                        return floatArrayOf(w[0]/w[3], w[1]/w[3], w[2]/w[3])
                    }
                }
                if (selectedFaceIndex >= 0) {
                    val pm = obj.mesh
                    if (pm != null) {
                        if (selectedFaceIndex < pm.faceCount) {
                            val c = pm.faceCenter(selectedFaceIndex)
                            val w = FloatArray(4)
                            Matrix.multiplyMV(w, 0, mat, 0, floatArrayOf(c[0], c[1], c[2], 1f), 0)
                            return floatArrayOf(w[0]/w[3], w[1]/w[3], w[2]/w[3])
                        }
                        // A topology edit can invalidate an old face selection.
                        // Do not interpret indexed mesh faces as triangle-soup indices.
                        return floatArrayOf(mat[12], mat[13], mat[14])
                    }
                    val tpf = trianglesPerFace(obj.type)
                    val triStart = selectedFaceIndex * tpf
                    val base = triStart * 9
                    if (base < 0 || base + tpf * 9 > obj.vertices.size) {
                        // Guard: face selection stale atau objek tanpa geometry (mis. group).
                        return floatArrayOf(mat[12], mat[13], mat[14])
                    }
                    var sumX = 0f; var sumY = 0f; var sumZ = 0f; var count = 0
                    for (ti in 0 until tpf) {
                        val b = base + ti * 9
                        for (i in 0 until 3) {
                            sumX += obj.vertices[b + i*3]
                            sumY += obj.vertices[b + i*3 + 1]
                            sumZ += obj.vertices[b + i*3 + 2]
                            count++
                        }
                    }
                    val center = floatArrayOf(sumX/count, sumY/count, sumZ/count)
                    val world = FloatArray(4)
                    Matrix.multiplyMV(world, 0, mat, 0, floatArrayOf(center[0], center[1], center[2], 1f), 0)
                    return floatArrayOf(world[0]/world[3], world[1]/world[3], world[2]/world[3])
                }
            }
            SelectMode.OBJECT -> {
                // fall through to default (object center)
            }
            SelectMode.NONE -> {}
        }
        // Default: object center
        val pos = FloatArray(4)
        Matrix.multiplyMV(pos, 0, mat, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
        return floatArrayOf(pos[0]/pos[3], pos[1]/pos[3], pos[2]/pos[3])
    }

    /**
     * Apply transform based on drag delta.
     * Uses the active transformMode and the current selection.
     * Supports mesh deformation for Node, Edge, Surface modes (vertex-level translation).
     */
    private fun applyTransformDrag(currentX: Float, currentY: Float, dx: Float, dy: Float) {
        val obj = selectedObjectForHighlight ?: return
        ensureDragBaseline()
        val mat = obj.modelMatrix

        // Gunakan axis gizmo yang sudah dideteksi saat ACTION_DOWN
        // Gerakan mouse diproyeksikan ke axis tersebut, deformasi hanya di axis itu
        val sensitivity = (orbitRadius / 600.0).toFloat()
          
        // Arah axis di world
        val axisWorldX: Float
        val axisWorldY: Float
        val axisWorldZ: Float
        when (grabbedAxis) {
            "X" -> { axisWorldX = 1f; axisWorldY = 0f; axisWorldZ = 0f }
            "Y" -> { axisWorldX = 0f; axisWorldY = 1f; axisWorldZ = 0f }
            "Z" -> { axisWorldX = 0f; axisWorldY = 0f; axisWorldZ = 1f }
            "N" -> { axisWorldX = extrudeNormal[0]; axisWorldY = extrudeNormal[1]; axisWorldZ = extrudeNormal[2] }
            else -> { axisWorldX = 0f; axisWorldY = 0f; axisWorldZ = 0f }
        }
          
        // Proyeksikan arah axis world ke screen
        val gizmoScreen = worldToScreen(gizmoWorldPos[0], gizmoWorldPos[1], gizmoWorldPos[2])
        val endScreen = worldToScreen(
            gizmoWorldPos[0] + axisWorldX,
            gizmoWorldPos[1] + axisWorldY,
            gizmoWorldPos[2] + axisWorldZ
        )
        var axisScreenX = endScreen[0] - gizmoScreen[0]
        var axisScreenY = endScreen[1] - gizmoScreen[1]
        val axisLen = sqrt(axisScreenX*axisScreenX + axisScreenY*axisScreenY)
        if (axisLen > 1e-6f) { axisScreenX /= axisLen; axisScreenY /= axisLen }
          
        // Proyeksikan drag screen (dx, dy) ke arah axis screen
        val dragAlongAxis = dx * axisScreenX + (-dy) * axisScreenY
          
        val scale = sensitivity
        var tx = 0f; var ty = 0f; var tz = 0f
          
        when (grabbedAxis) {
                        "X" -> { tx = dragAlongAxis * scale }
                        "Y" -> { ty = -dragAlongAxis * scale }
                        "Z" -> { tz = dragAlongAxis * scale }
                        "N" -> { tx = extrudeNormal[0] * dragAlongAxis * scale; ty = extrudeNormal[1] * dragAlongAxis * scale; tz = extrudeNormal[2] * dragAlongAxis * scale }
            else -> {
                // Fallback: jika axis tidak terdeteksi, default ke X
                tx = dragAlongAxis * scale
            }
        }

        if (isExtrudeMode) {
            val baseMesh = extrudeBaseMesh
            if (baseMesh != null && extrudeBaseFaceIndex in 0 until baseMesh.faceCount) {
                val dist = kotlin.math.sqrt(tx*tx + ty*ty + tz*tz)
                if (dist > 1e-6f) {
                    val signedDist = if (dragAlongAxis >= 0f) dist else -dist
                    extrudeAccum += signedDist
                }

                // Rebuild from the drag baseline so one drag creates one new cap
                // and one side ring instead of accumulating many side strips.
                val workingMesh = baseMesh.copy()
                val cap = if (kotlin.math.abs(extrudeAccum) > 1e-6f) {
                    workingMesh.extrudeFace(extrudeBaseFaceIndex, extrudeAccum, extrudeNormal)
                } else {
                    extrudeBaseFaceIndex
                }
                obj.mesh = workingMesh
                obj.syncFromMesh()
                selectedFaceIndex = cap
                selectedObjectForHighlight = obj
                onObjectsChanged?.invoke(objects.toList())
            }
            gizmoWorldPos = getGizmoTargetWorldPos()
            glSurfaceView.requestRender()
            return
        }

        when (transformMode) {
            "translate" -> {

                // Helper: cari semua vertex yang posisinya sama dengan vBase, lalu geser semuanya
                fun moveSharedVertices(vBase: Int) {
                    val px = obj.vertices[vBase]
                    val py = obj.vertices[vBase + 1]
                    val pz = obj.vertices[vBase + 2]
                    val eps = 0.01f
                    val mesh = obj.mesh
                    if (mesh != null) {
                        for (vi in 0 until mesh.vertexCount) {
                            val dx = mesh.vx(vi) - px; val dy = mesh.vy(vi) - py; val dz = mesh.vz(vi) - pz
                            if (dx * dx + dy * dy + dz * dz < eps) mesh.translateVertex(vi, tx, ty, tz)
                        }
                        return
                    }
                    for (i in 0 until obj.vertexCount) {
                        val b = i * 3
                        if (kotlin.math.abs(obj.vertices[b] - px) < eps &&
                            kotlin.math.abs(obj.vertices[b + 1] - py) < eps &&
                            kotlin.math.abs(obj.vertices[b + 2] - pz) < eps) {
                            obj.vertices[b] += tx
                            obj.vertices[b + 1] += ty
                            obj.vertices[b + 2] += tz
                        }
                    }
                }

                // Helper: cari semua vertex yang membentuk edge (2 vertex endpoint), geser semuanya
                fun moveSharedEdgeVertices(vBase0: Int, vBase1: Int) {
                    val px0 = obj.vertices[vBase0]; val py0 = obj.vertices[vBase0+1]; val pz0 = obj.vertices[vBase0+2]
                    val px1 = obj.vertices[vBase1]; val py1 = obj.vertices[vBase1+1]; val pz1 = obj.vertices[vBase1+2]
                    val eps = 0.01f
                    val mesh = obj.mesh
                    if (mesh != null) {
                        for (vi in 0 until mesh.vertexCount) {
                            val vx = mesh.vx(vi); val vy = mesh.vy(vi); val vz = mesh.vz(vi)
                            val d0x = vx - px0; val d0y = vy - py0; val d0z = vz - pz0
                            val d1x = vx - px1; val d1y = vy - py1; val d1z = vz - pz1
                            if (d0x * d0x + d0y * d0y + d0z * d0z < eps ||
                                d1x * d1x + d1y * d1y + d1z * d1z < eps) {
                                mesh.translateVertex(vi, tx, ty, tz)
                            }
                        }
                        return
                    }
                    for (i in 0 until obj.vertexCount) {
                        val b = i * 3
                        val vx = obj.vertices[b]; val vy = obj.vertices[b+1]; val vz = obj.vertices[b+2]
                        val d0 = (vx-px0)*(vx-px0)+(vy-py0)*(vy-py0)+(vz-pz0)*(vz-pz0)
                        val d1 = (vx-px1)*(vx-px1)+(vy-py1)*(vy-py1)+(vz-pz1)*(vz-pz1)
                        if (d0 < eps || d1 < eps) {
                            obj.vertices[b] += tx
                            obj.vertices[b + 1] += ty
                            obj.vertices[b + 2] += tz
                        }
                    }
                }

                when (selectMode) {
                    SelectMode.NODE -> {
                        if (selectedNodeIndices.isNotEmpty()) {
                            for (nodeIdx in selectedNodeIndices) {
                                moveSharedVertices(nodeIdx * 3)
                            }
                        } else if (selectedNodeIndex >= 0) {
                            val base = selectedNodeIndex * 3
                            moveSharedVertices(base)
                        }
                    }
                    SelectMode.EDGE -> {
                        if (selectedEdgeIndices.isNotEmpty()) {
                            for (edgeIdx in selectedEdgeIndices) {
                                val triIdx = edgeIdx / 3
                                val edgeInTri = edgeIdx % 3
                                val base = triIdx * 9
                                val i0: Int; val i1: Int
                                when (edgeInTri) {
                                    0 -> { i0 = 0; i1 = 1 }
                                    1 -> { i0 = 1; i1 = 2 }
                                    2 -> { i0 = 2; i1 = 0 }
                                    else -> { i0 = 0; i1 = 1 }
                                }
                                moveSharedEdgeVertices(base + i0 * 3, base + i1 * 3)
                            }
                        } else if (selectedEdgeIndex >= 0) {
                            val triIdx = selectedEdgeIndex / 3
                            val edgeInTri = selectedEdgeIndex % 3
                            val base = triIdx * 9
                            val i0: Int; val i1: Int
                            when (edgeInTri) {
                                0 -> { i0 = 0; i1 = 1 }
                                1 -> { i0 = 1; i1 = 2 }
                                2 -> { i0 = 2; i1 = 0 }
                                else -> { i0 = 0; i1 = 1 }
                            }
                            moveSharedEdgeVertices(base + i0 * 3, base + i1 * 3)
                        }
                    }
                    SelectMode.SURFACE -> {
                        val pmDrag = obj.mesh
                        if (selectedFaceIndices.isNotEmpty()) {
                            for (faceIdx in selectedFaceIndices) {
                                if (pmDrag != null && faceIdx in 0 until pmDrag.faceCount) {
                                    pmDrag.translateFace(faceIdx, tx, ty, tz)
                                } else {
                                    // Legacy: move all vertices of this face
                                    val tpf = trianglesPerFace(obj.type)
                                    val triStart = faceIdx * tpf
                                    val facePositions = mutableListOf<Triple<Float,Float,Float>>()
                                    for (ti in 0 until tpf) {
                                        val fbase = (triStart + ti) * 9
                                        for (v in 0 until 3) {
                                            val b = fbase + v * 3
                                            facePositions.add(Triple(obj.vertices[b], obj.vertices[b+1], obj.vertices[b+2]))
                                        }
                                    }
                                    val eps = 0.01f
                                    for (i in 0 until obj.vertexCount) {
                                        val b = i * 3
                                        val vx = obj.vertices[b]; val vy = obj.vertices[b+1]; val vz = obj.vertices[b+2]
                                        for (fp in facePositions) {
                                            val d = (vx-fp.first)*(vx-fp.first)+(vy-fp.second)*(vy-fp.second)+(vz-fp.third)*(vz-fp.third)
                                            if (d < eps) {
                                                obj.vertices[b] += tx
                                                obj.vertices[b + 1] += ty
                                                obj.vertices[b + 2] += tz
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                            if (pmDrag != null) obj.syncFromMesh()
                        } else if (pmDrag != null && selectedFaceIndex in 0 until pmDrag.faceCount) {
                            // Vertex sudah shared, jadi cukup geser index face-nya —
                            // tidak perlu pencocokan posisi dan mesh tetap tersambung.
                            pmDrag.translateFace(selectedFaceIndex, tx, ty, tz)
                            obj.syncFromMesh()
                        } else if (selectedFaceIndex >= 0) {
                            val tpf = trianglesPerFace(obj.type)
                            val triStart = selectedFaceIndex * tpf
                            // Kumpulkan posisi vertex unik dari face yang dipilih
                            val facePositions = mutableListOf<Triple<Float,Float,Float>>()
                            for (ti in 0 until tpf) {
                                val base = (triStart + ti) * 9
                                for (v in 0 until 3) {
                                    val b = base + v * 3
                                    facePositions.add(Triple(obj.vertices[b], obj.vertices[b+1], obj.vertices[b+2]))
                                }
                            }
                            // Geser semua vertex (di seluruh array) yang posisinya cocok
                            // Ini mempertahankan shared vertex agar mesh tetap connected
                            val eps = 0.01f
                            for (i in 0 until obj.vertexCount) {
                                val b = i * 3
                                val vx = obj.vertices[b]; val vy = obj.vertices[b+1]; val vz = obj.vertices[b+2]
                                for (fp in facePositions) {
                                    val d = (vx-fp.first)*(vx-fp.first)+(vy-fp.second)*(vy-fp.second)+(vz-fp.third)*(vz-fp.third)
                                    if (d < eps) {
                                        obj.vertices[b] += tx
                                        obj.vertices[b + 1] += ty
                                        obj.vertices[b + 2] += tz
                                        break
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        // Move the whole object (OBJECT mode or NONE)
                        Matrix.translateM(mat, 0, tx, ty, tz)
                    }
                }
                if (obj.mesh != null && (selectMode == SelectMode.NODE || selectMode == SelectMode.EDGE)) {
                    obj.syncFromMesh()
                }
                // Update gizmo position to follow the moved element
                gizmoWorldPos = getGizmoTargetWorldPos()
                // Force immediate visual update
                glSurfaceView.requestRender()
            }
            "rotate" -> {
                // Rotate only applies to whole object level (not vertex deformation)
                // Rotate around world Y (horizontal drag) and local X (vertical drag)
                val angleY = -dx * 0.02f
                val angleX = dy * 0.02f
                val pivot = floatArrayOf(
                    mat[12], mat[13], mat[14]
                )
                // Translate to origin, rotate, translate back
                val tempMat = FloatArray(16)
                Matrix.setIdentityM(tempMat, 0)
                Matrix.translateM(tempMat, 0, pivot[0], pivot[1], pivot[2])
                Matrix.rotateM(tempMat, 0, angleY, 0f, 1f, 0f)
                Matrix.rotateM(tempMat, 0, angleX, 1f, 0f, 0f)
                Matrix.translateM(tempMat, 0, -pivot[0], -pivot[1], -pivot[2])

                val result = FloatArray(16)
                Matrix.multiplyMM(result, 0, tempMat, 0, mat, 0)
                System.arraycopy(result, 0, mat, 0, 16)
            }
            "scale" -> {
                // Uniform scale based on vertical drag
                val scaleFactor = 1f + dy * 0.01f
                val pivot = floatArrayOf(
                    mat[12], mat[13], mat[14]
                )
                val tempMat = FloatArray(16)
                Matrix.setIdentityM(tempMat, 0)
                Matrix.translateM(tempMat, 0, pivot[0], pivot[1], pivot[2])
                Matrix.scaleM(tempMat, 0, scaleFactor, scaleFactor, scaleFactor)
                Matrix.translateM(tempMat, 0, -pivot[0], -pivot[1], -pivot[2])

                val result = FloatArray(16)
                Matrix.multiplyMM(result, 0, tempMat, 0, mat, 0)
                                System.arraycopy(result, 0, mat, 0, 16)
            }
        }

        // Apply symmetry mirroring if active
        if (isSymmetryActive && selectMode != SelectMode.NONE) {
            applySymmetry()
        }

        glSurfaceView.requestRender()
    }

    /** Detect which gizmo axis (X/Y/Z) is nearest to the given screen tap point.
     *  Uses a large threshold so user can easily grab an axis.
     *  Returns "X", "Y", "Z", or "NONE" if none are close.
     */
    private fun detectGrabbedAxis(screenX: Float, screenY: Float): String {
        if (!gizmoVisible) return "NONE"

        // Project gizmo origin to screen
        val gx = gizmoWorldPos[0]; val gy = gizmoWorldPos[1]; val gz = gizmoWorldPos[2]
        val len = 6f  // gizmo length in world units
        val originScreen = worldToScreen(gx, gy, gz)

        // If gizmo origin itself is behind camera or invalid, bail out
        if (originScreen[0] < -1000f || originScreen[1] < -1000f) return "NONE"

        // Endpoints for each axis in world
        val endpoints = mapOf(
            "X" to floatArrayOf(gx + len, gy, gz),
            "Y" to floatArrayOf(gx, gy + len, gz),
            "Z" to floatArrayOf(gx, gy, gz + len)
        )

        var bestAxis = "NONE"
        var bestDist = Float.MAX_VALUE

        for ((axis, wp) in endpoints) {
            val endScreen = worldToScreen(wp[0], wp[1], wp[2])
            // Distance to the full line (infinite), not segment
            // This makes it easier to grab even if touch is beyond the arrow tip
            val dx = endScreen[0] - originScreen[0]
            val dy = endScreen[1] - originScreen[1]
            val lineLen2 = dx*dx + dy*dy
            if (lineLen2 < 1f) continue
            val t = ((screenX - originScreen[0])*dx + (screenY - originScreen[1])*dy) / lineLen2
            val closestX = originScreen[0] + t * dx
            val closestY = originScreen[1] + t * dy
            val dist = sqrt((screenX-closestX)*(screenX-closestX) + (screenY-closestY)*(screenY-closestY))
            if (dist < bestDist) {
                bestDist = dist
                bestAxis = axis
            }
        }

        // Large threshold (80px) so user can easily grab gizmo axis without precise tapping
        return if (bestDist < 80f) bestAxis else "NONE"
    }

    private fun pinchDistance(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return sqrt((dx*dx+dy*dy).toDouble()).toFloat()
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  PICKING (Ray cast)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /** Convert normalized-device coords [ndcX, ndcY] into a world-space ray (origin + dir). */
    private fun screenToRay(ndcX: Float, ndcY: Float): Pair<FloatArray, FloatArray> {
        val invPV = FloatArray(16)
        android.opengl.Matrix.invertM(invPV, 0, vpMatrix, 0)

        val near = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val far = floatArrayOf(ndcX, ndcY, 1f, 1f)

        val nearW = FloatArray(4)
        val farW = FloatArray(4)
        android.opengl.Matrix.multiplyMV(nearW, 0, invPV, 0, near, 0)
        android.opengl.Matrix.multiplyMV(farW, 0, invPV, 0, far, 0)

        val origin = floatArrayOf(nearW[0]/nearW[3], nearW[1]/nearW[3], nearW[2]/nearW[3])
        val farP = floatArrayOf(farW[0]/farW[3], farW[1]/farW[3], farW[2]/farW[3])
        val dir = floatArrayOf(farP[0]-origin[0], farP[1]-origin[1], farP[2]-origin[2])
        val len = sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])
        if (len > 1e-8f) { dir[0]/=len; dir[1]/=len; dir[2]/=len }
        return Pair(origin, dir)
    }

    /** Convert world-space point to screen coordinates (pixel). */
    private fun worldToScreen(wx: Float, wy: Float, wz: Float): FloatArray {
        // Transform world â†’ clip â†’ NDC â†’ screen
        val clip = FloatArray(4)
        android.opengl.Matrix.multiplyMV(clip, 0, vpMatrix, 0, floatArrayOf(wx, wy, wz, 1f), 0)
        if (kotlin.math.abs(clip[3]) < 1e-10f) return floatArrayOf(0f, 0f)
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        val screenX = (ndcX + 1f) * 0.5f * viewportWidth
        val screenY = (1f - ndcY) * 0.5f * viewportHeight
        return floatArrayOf(screenX, screenY)
    }

    /** Distance from point to line segment */
    private fun pointToSegmentDist(p: FloatArray, a: FloatArray, b: FloatArray): Float {
        val abx = b[0]-a[0]; val aby = b[1]-a[1]; val abz = b[2]-a[2]
        val apx = p[0]-a[0]; val apy = p[1]-a[1]; val apz = p[2]-a[2]
        val t = (apx*abx + apy*aby + apz*abz) / (abx*abx + aby*aby + abz*abz).coerceAtLeast(1e-8f)
        val ct = t.coerceIn(0f, 1f)
        val cx = a[0] + ct*abx; val cy = a[1] + ct*aby; val cz = a[2] + ct*abz
        return sqrt((p[0]-cx)*(p[0]-cx) + (p[1]-cy)*(p[1]-cy) + (p[2]-cz)*(p[2]-cz))
    }

    /** Ray-triangle intersection (MÃ¶llerâ€“Trumbore). Returns distance or -1. */
    private fun rayTriangleIntersect(origin: FloatArray, dir: FloatArray, v0: FloatArray, v1: FloatArray, v2: FloatArray): Float {
        val e1x = v1[0]-v0[0]; val e1y = v1[1]-v0[1]; val e1z = v1[2]-v0[2]
        val e2x = v2[0]-v0[0]; val e2y = v2[1]-v0[1]; val e2z = v2[2]-v0[2]

        val px = dir[1]*e2z - dir[2]*e2y
        val py = dir[2]*e2x - dir[0]*e2z
        val pz = dir[0]*e2y - dir[1]*e2x

        val det = e1x*px + e1y*py + e1z*pz
        if (det > -1e-6f && det < 1e-6f) return -1f
        val invDet = 1f/det

        val tx = origin[0] - v0[0]; val ty = origin[1] - v0[1]; val tz = origin[2] - v0[2]

        val u = (tx*px + ty*py + tz*pz) * invDet
        if (u < 0f || u > 1f) return -1f

        val qx = ty*e1z - tz*e1y
        val qy = tz*e1x - tx*e1z
        val qz = tx*e1y - ty*e1x

        val v = (dir[0]*qx + dir[1]*qy + dir[2]*qz) * invDet
        if (v < 0f || u+v > 1f) return -1f

        val t = (e2x*qx + e2y*qy + e2z*qz) * invDet
        return if (t > 1e-6f) t else -1f
    }

    /** Transform a local mesh vertex to world space using the given model matrix */
    private fun localToWorld(local: FloatArray, mat: FloatArray = modelMatrix): FloatArray {
        val v = floatArrayOf(local[0], local[1], local[2], 1f)
        val out = FloatArray(4)
        android.opengl.Matrix.multiplyMV(out, 0, mat, 0, v, 0)
        return floatArrayOf(out[0]/out[3], out[1]/out[3], out[2]/out[3])
    }

    /** Perform picking based on current [selectMode] and tap NDC. */
    private fun pick(ndcX: Float, ndcY: Float) {
        if (selectMode == SelectMode.NONE) return

        val (rayOrigin, rayDir) = screenToRay(ndcX, ndcY)

        // Reset hanya index seleksi, pertahankan selectedObjectForHighlight
        // NOTE: jangan reset langsung; multi-select logic akan handle via sets

        when (selectMode) {
            SelectMode.NODE -> {
                var bestDist = Float.MAX_VALUE
                var bestObj: SceneObject? = null
                var bestVertIdx = -1
                for ((objIdx, obj) in objects.withIndex()) {
                    val verts = obj.vertices
                    val mat = obj.modelMatrix
                    // Check each unique vertex
                    for (vi in 0 until obj.vertexCount) {
                        val base = vi * 3
                        val local = floatArrayOf(verts[base], verts[base+1], verts[base+2])
                        val world = localToWorld(local, mat)
                        // Distance from ray to point
                        val ocx = rayOrigin[0] - world[0]; val ocy = rayOrigin[1] - world[1]; val ocz = rayOrigin[2] - world[2]
                        val t = -(ocx*rayDir[0] + ocy*rayDir[1] + ocz*rayDir[2])
                        val closestX = rayOrigin[0] + t * rayDir[0]
                        val closestY = rayOrigin[1] + t * rayDir[1]
                        val closestZ = rayOrigin[2] + t * rayDir[2]
                        val d = sqrt(
                            (closestX-world[0])*(closestX-world[0]) +
                            (closestY-world[1])*(closestY-world[1]) +
                            (closestZ-world[2])*(closestZ-world[2])
                        )
                        if (d < 6f && d < bestDist) {
                            bestDist = d
                            bestObj = obj
                            bestVertIdx = vi
                        }
                    }
                }
                if (bestObj != null) {
                    selectedObjectIndex = objects.indexOf(bestObj)
                    selectedObjectForHighlight = bestObj
                    // Multi-select: toggle node selection
                    if (selectedNodeIndices.contains(bestVertIdx)) {
                        selectedNodeIndices.remove(bestVertIdx)
                        if (selectedNodeIndex == bestVertIdx) {
                            selectedNodeIndex = selectedNodeIndices.lastOrNull() ?: -1
                        }
                        onSelectionChanged?.invoke("Deselected Node $bestVertIdx on ${bestObj.name} (${selectedNodeIndices.size} selected)")
                    } else {
                        selectedNodeIndices.add(bestVertIdx)
                        selectedNodeIndex = bestVertIdx
                        onSelectionChanged?.invoke("Selected Node $bestVertIdx on ${bestObj.name} (${selectedNodeIndices.size} selected)")
                    }
                    onObjectSelectedIndex?.invoke(selectedObjectIndex)
                } else {
                    // No hit on empty area: clear all selections
                    clearSelection()
                    onSelectionChanged?.invoke("Selection cleared")
                    onObjectSelectedIndex?.invoke(-1)
                }
            }
            SelectMode.EDGE -> {
                var bestDist = Float.MAX_VALUE
                var bestObj: SceneObject? = null
                var bestEdgeIdx = -1
                for ((objIdx, obj) in objects.withIndex()) {
                    val verts = obj.vertices
                    val mat = obj.modelMatrix
                    val triCount = verts.size / 9
                    for (ti in 0 until triCount) {
                        val base = ti * 9
                        val v0 = floatArrayOf(verts[base], verts[base+1], verts[base+2])
                        val v1 = floatArrayOf(verts[base+3], verts[base+4], verts[base+5])
                        val v2 = floatArrayOf(verts[base+6], verts[base+7], verts[base+8])
                        val w0 = localToWorld(v0, mat)
                        val w1 = localToWorld(v1, mat)
                        val w2 = localToWorld(v2, mat)
                        // For each of 3 edges of this triangle
                                                val triEdges = listOf(Pair(w0,w1), Pair(w1,w2), Pair(w2,w0))
                        for (ei in 0 until 3) {
                            val (a, b) = triEdges[ei]
                            val abx = b[0] - a[0]; val aby = b[1] - a[1]; val abz = b[2] - a[2]
                            // Edge direction
                            val eLen2 = abx*abx + aby*aby + abz*abz
                            if (eLen2 < 1e-8f) continue
                            // Skip diagonal edges for box/cylinder (2 triangles per face)
                            if (trianglesPerFace(obj.type) > 1) {
                                val eLen = sqrt(eLen2)
                                // Diagonal is longer than regular edge; box side ~16, diagonal ~22.6
                                // Approx threshold: if this edge is significantly longer than shortest edge of this triangle
                                val edgeLens = floatArrayOf(
                                    sqrt((w1[0]-w0[0])*(w1[0]-w0[0])+(w1[1]-w0[1])*(w1[1]-w0[1])+(w1[2]-w0[2])*(w1[2]-w0[2])),
                                    sqrt((w2[0]-w1[0])*(w2[0]-w1[0])+(w2[1]-w1[1])*(w2[1]-w1[1])+(w2[2]-w1[2])*(w2[2]-w1[2])),
                                    sqrt((w0[0]-w2[0])*(w0[0]-w2[0])+(w0[1]-w2[1])*(w0[1]-w2[1])+(w0[2]-w2[2])*(w0[2]-w2[2]))
                                )
                                val minLen = edgeLens.minOrNull() ?: 0f
                                val maxLen = edgeLens.maxOrNull() ?: 0f
                                if (maxLen > minLen * 1.3f && eLen > minLen * 1.2f) continue
                            }
                            // Vector from edge start to ray origin
                            val wx = rayOrigin[0] - a[0]
                            val wy = rayOrigin[1] - a[1]
                            val wz = rayOrigin[2] - a[2]
                            // Find closest point on edge segment to the ray
                            // Solve for parameter u along edge where distance is minimized
                            val dDotD = rayDir[0]*rayDir[0] + rayDir[1]*rayDir[1] + rayDir[2]*rayDir[2]
                            val eDotE = eLen2
                            val dDotE = rayDir[0]*abx + rayDir[1]*aby + rayDir[2]*abz
                            val wDotD = wx*rayDir[0] + wy*rayDir[1] + wz*rayDir[2]
                            val wDotE = wx*abx + wy*aby + wz*abz
                            val denom = dDotD * eDotE - dDotE * dDotE
                            if (kotlin.math.abs(denom) < 1e-8f) continue // parallel
                            val u = (dDotD * wDotE - wDotD * dDotE) / denom
                            val clampedU = u.coerceIn(0f, 1f)
                            // Closest point on the edge segment
                            val closestOnEdge = floatArrayOf(
                                a[0] + clampedU*abx,
                                a[1] + clampedU*aby,
                                a[2] + clampedU*abz
                            )
                            // Distance from ray to this edge point
                            val ecx = rayOrigin[0] - closestOnEdge[0]
                            val ecy = rayOrigin[1] - closestOnEdge[1]
                            val ecz = rayOrigin[2] - closestOnEdge[2]
                            val rayT = -(ecx*rayDir[0] + ecy*rayDir[1] + ecz*rayDir[2]) / dDotD
                            val closestOnRay = floatArrayOf(
                                rayOrigin[0] + rayT*rayDir[0],
                                rayOrigin[1] + rayT*rayDir[1],
                                rayOrigin[2] + rayT*rayDir[2]
                            )
                            val dx = closestOnRay[0] - closestOnEdge[0]
                            val dy = closestOnRay[1] - closestOnEdge[1]
                            val dz = closestOnRay[2] - closestOnEdge[2]
                            val d = sqrt(dx*dx + dy*dy + dz*dz)
                            if (d < 4f && d < bestDist) {
                                bestDist = d
                                bestObj = obj
                                bestEdgeIdx = ti * 3 + ei
                            }
                        }
                    }
                }
                                if (bestObj != null) {
                    selectedObjectIndex = objects.indexOf(bestObj)
                    selectedObjectForHighlight = bestObj
                    // Multi-select: toggle edge selection
                    if (selectedEdgeIndices.contains(bestEdgeIdx)) {
                        selectedEdgeIndices.remove(bestEdgeIdx)
                        if (selectedEdgeIndex == bestEdgeIdx) {
                            selectedEdgeIndex = selectedEdgeIndices.lastOrNull() ?: -1
                        }
                        onSelectionChanged?.invoke("Deselected Edge on ${bestObj.name} (${selectedEdgeIndices.size} selected)")
                    } else {
                        selectedEdgeIndices.add(bestEdgeIdx)
                        selectedEdgeIndex = bestEdgeIdx
                        onSelectionChanged?.invoke("Selected Edge on ${bestObj.name} (${selectedEdgeIndices.size} selected)")
                    }
                    onObjectSelectedIndex?.invoke(selectedObjectIndex)
                                } else {
                    clearSelection()
                    onSelectionChanged?.invoke("Selection cleared")
                    onObjectSelectedIndex?.invoke(-1)
                }
            }
            SelectMode.SURFACE -> {
                var bestDist = Float.MAX_VALUE
                var bestObjIdx = -1
                var bestTriIdx = -1
                var bestObj: SceneObject? = null

                // Iterate all objects and all their triangles
                for ((objIdx, obj) in objects.withIndex()) {
                    val verts = obj.vertices
                    val triCount = verts.size / 9  // 3 vertices * 3 coords = 9 per triangle
                    for (ti in 0 until triCount) {
                        val base = ti * 9
                        val v0 = localToWorld(floatArrayOf(verts[base], verts[base+1], verts[base+2]), obj.modelMatrix)
                        val v1 = localToWorld(floatArrayOf(verts[base+3], verts[base+4], verts[base+5]), obj.modelMatrix)
                        val v2 = localToWorld(floatArrayOf(verts[base+6], verts[base+7], verts[base+8]), obj.modelMatrix)
                        val d = rayTriangleIntersect(rayOrigin, rayDir, v0, v1, v2)
                        if (d > 0f && d < bestDist) {
                            bestDist = d
                            bestObjIdx = objIdx
                            bestTriIdx = ti
                            bestObj = obj
                        }
                    }
                }
                                if (bestObj != null) {
                    selectedObjectIndex = bestObjIdx
                    selectedObjectForHighlight = bestObj
                    // Triangle -> face. Mesh indexed punya peta eksplisit; objek lama
                    // masih memakai asumsi triangle-per-face berbasis tipe.
                    val tf = bestObj.mesh?.triFace
                    val faceIdx = if (tf != null && bestTriIdx < tf.size) tf[bestTriIdx]
                                  else bestTriIdx / trianglesPerFace(bestObj.type)
                    // Multi-select: toggle face selection
                    if (selectedFaceIndices.contains(faceIdx)) {
                        selectedFaceIndices.remove(faceIdx)
                        if (selectedFaceIndex == faceIdx) {
                            selectedFaceIndex = selectedFaceIndices.lastOrNull() ?: -1
                        }
                        onSelectionChanged?.invoke("Deselected Face $faceIdx on ${bestObj.name} (${selectedFaceIndices.size} selected)")
                    } else {
                        selectedFaceIndices.add(faceIdx)
                        selectedFaceIndex = faceIdx
                        onSelectionChanged?.invoke("Selected Face $faceIdx on ${bestObj.name} (${selectedFaceIndices.size} selected)")
                    }
                    onObjectSelectedIndex?.invoke(selectedObjectIndex)
                                } else {
                    clearSelection()
                    onSelectionChanged?.invoke("Selection cleared")
                    onObjectSelectedIndex?.invoke(-1)
                }
            }
            SelectMode.OBJECT -> {
                var bestDist = Float.MAX_VALUE
                var bestObjIdx = -1
                var bestObj: SceneObject? = null
                // Children group ikut bisa di-pick; indeks yang dipakai tetap indeks
                // object level-atas (parent group) agar seleksi konsisten dengan tree.
                val pickList: List<Pair<Int, SceneObject>> = run {
                    val list = mutableListOf<Pair<Int, SceneObject>>()
                    for ((objIdx, obj) in objects.withIndex()) {
                        if (obj.type == "group" && !obj.children.isNullOrEmpty()) {
                            fun addRec(o: SceneObject) {
                                if (o.type == "group" && !o.children.isNullOrEmpty()) {
                                    for (c in o.children!!) addRec(c)
                                    return
                                }
                                if (o.vertexCount > 0) list.add(objIdx to o)
                            }
                            for (c in obj.children!!) addRec(c)
                        } else {
                            list.add(objIdx to obj)
                        }
                    }
                    list
                }
                for ((objIdx, obj) in pickList) {
                    // Use the actual mesh bounds. Combined meshes store vertices in
                    // world-space with an identity model matrix, so origin-based
                    // picking would miss them entirely.
                    var centerLocal = floatArrayOf(0f, 0f, 0f)
                    var radius = if (obj.type == "torus") 10f else 8f
                    obj.mesh?.let { mesh ->
                        if (mesh.vertexCount > 0) {
                            var minX = mesh.vx(0); var minY = mesh.vy(0); var minZ = mesh.vz(0)
                            var maxX = minX; var maxY = minY; var maxZ = minZ
                            for (vi in 1 until mesh.vertexCount) {
                                minX = minOf(minX, mesh.vx(vi)); minY = minOf(minY, mesh.vy(vi)); minZ = minOf(minZ, mesh.vz(vi))
                                maxX = maxOf(maxX, mesh.vx(vi)); maxY = maxOf(maxY, mesh.vy(vi)); maxZ = maxOf(maxZ, mesh.vz(vi))
                            }
                            centerLocal = floatArrayOf(
                                (minX + maxX) / 2f,
                                (minY + maxY) / 2f,
                                (minZ + maxZ) / 2f
                            )
                            val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
                            radius = sqrt(dx * dx + dy * dy + dz * dz) / 2f + 2f
                        }
                    }
                    val center = localToWorld(centerLocal, obj.modelMatrix)
                    val ocx = rayOrigin[0] - center[0]; val ocy = rayOrigin[1] - center[1]; val ocz = rayOrigin[2] - center[2]
                    val t = -(ocx*rayDir[0] + ocy*rayDir[1] + ocz*rayDir[2])
                    val closestX = rayOrigin[0] + t * rayDir[0]
                    val closestY = rayOrigin[1] + t * rayDir[1]
                    val closestZ = rayOrigin[2] + t * rayDir[2]
                    val d = sqrt(
                        (closestX-center[0])*(closestX-center[0]) +
                        (closestY-center[1])*(closestY-center[1]) +
                        (closestZ-center[2])*(closestZ-center[2])
                    )
                    if (d < radius && d < bestDist) {
                        bestDist = d
                        bestObjIdx = objIdx
                        bestObj = obj
                    }
                }
                                if (bestObj != null) {
                    if (isMultiSelectMode) {
                        // Multi-select: toggle selection
                        bestObj.selected = !bestObj.selected
                        if (bestObj.selected) {
                            if (selectedObjectForHighlight == null) {
                                selectedObjectIndex = bestObjIdx
                                selectedObjectForHighlight = bestObj
                                onObjectSelectedIndex?.invoke(bestObjIdx)
                            }
                            onSelectionChanged?.invoke("Selected: ${bestObj.name} (multi)")
                        } else {
                            onSelectionChanged?.invoke("Deselected: ${bestObj.name} (multi)")
                            if (selectedObjectForHighlight === bestObj) {
                                val anySelected = objects.indexOfFirst { it.selected }
                                if (anySelected >= 0) {
                                    selectedObjectIndex = anySelected
                                    selectedObjectForHighlight = objects[anySelected]
                                    onObjectSelectedIndex?.invoke(anySelected)
                                } else {
                                    selectedObjectIndex = -1
                                    selectedObjectForHighlight = null
                                    gizmoVisible = false
                                    isTransformGrabbed = false
                                    onObjectSelectedIndex?.invoke(-1)
                                }
                            }
                        }
                    } else {
                        objects.forEach { it.selected = false }
                        bestObj.selected = true
                        selectedObjectIndex = bestObjIdx
                        selectedObjectForHighlight = bestObj
                        onObjectSelectedIndex?.invoke(bestObjIdx)
                        onSelectionChanged?.invoke("Selected Object: ${bestObj.name}")
                    }
                } else {
                    clearSelection()
                    onSelectionChanged?.invoke("Selection cleared")
                    onObjectSelectedIndex?.invoke(-1)
                }
            }
            else -> {}
        }

    }

    /** Public: call from activity on tap */
    fun handlePick(ndcX: Float, ndcY: Float) {
        pick(ndcX, ndcY)
    }

    /** Clear all objects from the scene */
    /** Add an already-constructed SceneObject (e.g. loaded from file) */
    fun addExistingObject(obj: SceneObject) {
        pushUndo()
        objects.add(obj)
        if (obj.name.startsWith("Group") || obj.name.contains("Group")) {
            // leave counter unchanged
        } else {
            objectCounter = maxOf(objectCounter, extractNumber(obj.name) + 1)
        }
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }

    private fun extractNumber(name: String): Int {
        val num = name.filter { it.isDigit() }
        return if (num.isEmpty()) 0 else num.toInt()
    }

    fun clearObjects() {
        pushUndo()
        objects.clear()
        selectedObjectIndex = -1
        selectedNodeIndex = -1
        selectedEdgeIndex = -1
        selectedFaceIndex = -1
        selectedNodeIndices.clear()
        selectedEdgeIndices.clear()
        selectedFaceIndices.clear()
        selectedObjectForHighlight = null
        objectCounter = 0
        groupCounter = 0
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }

    /**
     * Tampilkan enclose wireframe hijau mengelilingi object yang sedang dipilih.
     * Menghitung bounding box otomatis dari vertex object.
     */
    fun showEncloseForSelectedObject(): Boolean {
        val obj = selectedObjectForHighlight ?: return false
        if (obj.type == "enclose") return false
        val verts = obj.vertices
        if (verts.size < 3) return false
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = Float.MIN_VALUE
        var i = 0
        while (i + 2 < verts.size) {
            val vx = verts[i]; val vy = verts[i + 1]; val vz = verts[i + 2]
            if (vx < minX) minX = vx
            if (vx > maxX) maxX = vx
            if (vy < minY) minY = vy
            if (vy > maxY) maxY = vy
            if (vz < minZ) minZ = vz
            if (vz > maxZ) maxZ = vz
            i += 3
        }
        val panjang = maxX - minX
        val lebar = maxZ - minZ
        val tinggi = maxY - minY
        val padding = 1.1f
        showEncloseBox(panjang * padding, lebar * padding, tinggi * padding)
        return true
    }

    /**
     * Tampilkan kotak wireframe hijau dengan dimensi panjang x lebar x tinggi.
     * Menghapus enclose box sebelumnya jika ada, lalu membuat yang baru.
     */
    fun showEncloseBox(panjang: Float, lebar: Float, tinggi: Float) {
        pushUndo()
        // Hapus enclose box sebelumnya jika ada
        val encloseIndex = objects.indexOfFirst { it.type == "enclose" }
                if (encloseIndex >= 0) {
            objects.removeAt(encloseIndex)
            onEncloseStateChanged?.invoke(false)
            onObjectsChanged?.invoke(objects.toList())
            glSurfaceView.requestRender()
            return
        }

        val hx = panjang / 2f
        val hy = tinggi / 2f
        val hz = lebar / 2f

        // 8 vertices untuk box dari -hx,-hy,-hz ke +hx,+hy,+hz
        val verts = floatArrayOf(
            // Bottom face
            -hx, -hy, -hz,  // 0
             hx, -hy, -hz,  // 1
             hx, -hy,  hz,  // 2
            -hx, -hy,  hz,  // 3
            // Top face
            -hx,  hy, -hz,  // 4
             hx,  hy, -hz,  // 5
             hx,  hy,  hz,  // 6
            -hx,  hy,  hz,  // 7
        )

        // Hitung vertexCount: 36 indices untuk triangle mesh (12 triangles)
        // Tapi untuk Line Loop, kita butuh 24 vertices (edges)
        val edgeVerts = floatArrayOf(
            // Bottom edges (4)
            -hx, -hy, -hz,   hx, -hy, -hz,
             hx, -hy, -hz,   hx, -hy,  hz,
             hx, -hy,  hz,  -hx, -hy,  hz,
            -hx, -hy,  hz,  -hx, -hy, -hz,
            // Top edges (4)
            -hx,  hy, -hz,   hx,  hy, -hz,
             hx,  hy, -hz,   hx,  hy,  hz,
             hx,  hy,  hz,  -hx,  hy,  hz,
            -hx,  hy,  hz,  -hx,  hy, -hz,
            // Vertical edges (4)
            -hx, -hy, -hz,  -hx,  hy, -hz,
             hx, -hy, -hz,   hx,  hy, -hz,
             hx, -hy,  hz,   hx,  hy,  hz,
            -hx, -hy,  hz,  -hx,  hy,  hz,
        )

        objectCounter++
        val name = "Enclose $objectCounter"
        
        val obj = SceneObject(
            name = name,
            type = "enclose",
            vertices = edgeVerts,
            vertexCount = 24,  // 12 edges * 2 vertices
            color = floatArrayOf(0.0f, 1.0f, 0.0f)  // hijau
        )

        objects.add(obj)
        onEncloseStateChanged?.invoke(true)
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }

    /** Delete the object at the given index */
    fun deleteObject(objIndex: Int): Boolean {
        if (objIndex < 0 || objIndex >= objects.size) return false
        pushUndo()
        objects.removeAt(objIndex)
        // Reset selection if it pointed to the deleted object
        if (selectedObjectIndex == objIndex) {
            selectedObjectIndex = -1
            selectedObjectForHighlight = null
            selectedNodeIndex = -1
            selectedEdgeIndex = -1
            selectedFaceIndex = -1
            selectedNodeIndices.clear()
            selectedEdgeIndices.clear()
            selectedFaceIndices.clear()
        } else if (selectedObjectIndex > objIndex) {
            selectedObjectIndex--
        }
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
        return true
    }

    /** Duplicate the object at the given index. Returns the new object or null. */
    fun duplicateObject(objIndex: Int): SceneObject? {
        if (objIndex < 0 || objIndex >= objects.size) return null
        pushUndo()
        val src = objects[objIndex]

        objectCounter++
        val newName = "${src.name} copy"

        // Deep copy the vertices array
        val newVerts = src.vertices.copyOf()
        val newMat = FloatArray(16)
        System.arraycopy(src.modelMatrix, 0, newMat, 0, 16)
        val newMesh = src.mesh?.copy()

        val newObj = SceneObject(
            name = newName,
            type = src.type,
            vertices = newVerts,
            vertexCount = src.vertexCount,
            modelMatrix = newMat,
            color = src.color.copyOf(),
            mesh = newMesh,
        )
        objects.add(newObj)
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
        return newObj
    }

    fun setGridVisibility(visible: Boolean) {
        gridVisible = visible
        glSurfaceView.requestRender()
    }

    fun fitView() { orbitTargetX=0f; orbitTargetY=0f; orbitTargetZ=0f; orbitRadius=80.0 }

    /**
     * Toggle Pin View active state.
     */
        fun togglePinView() {
        isPinViewActive = !isPinViewActive
        onPinViewStateChanged?.invoke(isPinViewActive)
    }

    /**
     * Mirror the selected object across the given plane.
     * Creates a new mirrored copy and adds it to the scene.
     * @param plane "XY", "XZ", or "YZ"
     */
    fun mirrorSelectedObject(plane: String): Boolean {
        val obj = selectedObjectForHighlight ?: return false
        if (obj.type == "enclose") return false
        if (obj.vertexCount < 3) return false
        pushUndo()

        val verts = obj.vertices.copyOf()
        val n = obj.vertexCount

        when (plane) {
            "XY" -> {
                for (i in 0 until n) {
                    verts[i * 3 + 2] = -verts[i * 3 + 2]
                }
            }
            "XZ" -> {
                for (i in 0 until n) {
                    verts[i * 3 + 1] = -verts[i * 3 + 1]
                }
            }
            "YZ" -> {
                for (i in 0 until n) {
                    verts[i * 3] = -verts[i * 3]
                }
            }
        }

        objectCounter++
        val mirrorName = "${obj.name}_mirror"

        val mirroredObj = SceneObject(
            name = mirrorName,
            type = obj.type,
            vertices = verts,
            vertexCount = n,
            color = obj.color.copyOf(),
            modelMatrix = obj.modelMatrix.copyOf(),
            mesh = obj.mesh?.copy(),
        )

        objects.add(mirroredObj)
        selectedObjectIndex = objects.size - 1
        selectedObjectForHighlight = mirroredObj
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
        return true
    }

    /**
     * Toggle symmetry editing for the active plane.
     */
    fun toggleSymmetry(plane: String) {
        if (isSymmetryActive && symmetryPlane == plane) {
            isSymmetryActive = false
        } else {
            isSymmetryActive = true
            symmetryPlane = plane
        }
                onSymmetryStateChanged?.invoke(isSymmetryActive)
    }

    /**
     * Apply symmetry by mirroring all vertices across the symmetry plane.
     * Called after each vertex deformation to keep both sides in sync.
     * This mirrors vertex positions of the currently selected object.
     */
        fun applySymmetry() {
        if (!isSymmetryActive) return
        val obj = selectedObjectForHighlight ?: return

        val n = obj.vertexCount
        val verts = obj.vertices

        // Buat mirror dari current vertices
        val mirrored = verts.copyOf()
        when (symmetryPlane) {
            "XY" -> { for (i in 0 until n) { mirrored[i * 3 + 2] = -mirrored[i * 3 + 2] } }
            "XZ" -> { for (i in 0 until n) { mirrored[i * 3 + 1] = -mirrored[i * 3 + 1] } }
            "YZ" -> { for (i in 0 until n) { mirrored[i * 3] = -mirrored[i * 3] } }
        }

        // Gabungkan: untuk setiap vertex, ambil rata-rata posisi asli dan mirror-nya
        // Ini menjaga kedua sisi tetap sinkron
        for (i in 0 until n * 3) {
            verts[i] = (verts[i] + mirrored[i]) / 2f
        }
    }
    fun setView(dir: String) {
        orbitTargetX=0f; orbitTargetY=0f; orbitTargetZ=0f; orbitRadius=80.0
        when (dir) {
            "front"->{orbitTheta=0.0;orbitPhi=0.0}
            "back"->{orbitTheta= kotlin.math.PI;orbitPhi=0.0}
            "top"->{orbitTheta=0.0;orbitPhi=1.57}
            "bottom"->{orbitTheta=0.0;orbitPhi=-1.57}
            "right"->{orbitTheta=1.57;orbitPhi=0.0}
            "left"->{orbitTheta=-1.57;orbitPhi=0.0}
            "isometric"->{orbitTheta=0.785;orbitPhi=0.615}
        }
    }
    /** Set the color of a specific object */
    fun setObjectColor(objIndex: Int, r: Float, g: Float, b: Float) {
        if (objIndex < 0 || objIndex >= objects.size) return
        pushUndo()
        val obj = objects[objIndex]
        obj.color = floatArrayOf(r, g, b)
        glSurfaceView.requestRender()
    }

    /** Set the color of a specific face on an object */
    fun setFaceColor(objIndex: Int, faceIndex: Int, r: Float, g: Float, b: Float) {
        if (objIndex < 0 || objIndex >= objects.size) return
        pushUndo()
        val obj = objects[objIndex]
        // Copy-on-write: create new map so snapshot retains old reference
        val oldMap = obj.faceColors ?: mutableMapOf()
        obj.faceColors = mutableMapOf<Int, FloatArray>().apply {
            putAll(oldMap)
            put(faceIndex, floatArrayOf(r, g, b))
        }
        glSurfaceView.requestRender()
    }

    /** Kembalikan warna object ke default (menghilangkan warna custom). */
    fun resetObjectColor(objIndex: Int) {
        if (objIndex < 0 || objIndex >= objects.size) return
        pushUndo()
        objects[objIndex].color = floatArrayOf(0.55f, 0.55f, 0.58f)
        glSurfaceView.requestRender()
    }

    /** Hapus warna custom pada satu face (kembali ke warna object). */
    fun clearFaceColor(objIndex: Int, faceIndex: Int) {
        if (objIndex < 0 || objIndex >= objects.size || faceIndex < 0) return
        val obj = objects[objIndex]
        val map = obj.faceColors ?: return
        if (!map.containsKey(faceIndex)) return
        pushUndo()
        val newMap = mutableMapOf<Int, FloatArray>().apply {
            putAll(map)
            remove(faceIndex)
        }
        obj.faceColors = if (newMap.isEmpty()) null else newMap
        glSurfaceView.requestRender()
    }


    /** Isolate: hide all objects except the selected one */
    fun isolateObject(objIndex: Int) {
        if (objIndex < 0 || objIndex >= objects.size) return
        pushUndo()
        for ((i, obj) in objects.withIndex()) {
            obj.visible = (i == objIndex)
        }
        glSurfaceView.requestRender()
    }
    /** Exit isolate: show all objects */
    fun exitIsolate() {
        pushUndo()
        for (obj in objects) {
            obj.visible = true
        }
        glSurfaceView.requestRender()
    }

    /** Request a render update */
    fun requestRender() {
        glSurfaceView.requestRender()
    }

    /**
     * Capture current GL framebuffer as a Bitmap.
     * Must be called from the GL thread (inside a render pass).
     */
    fun captureFramebuffer(): android.graphics.Bitmap? {
        try {
            val w = glSurfaceView.width
            val h = glSurfaceView.height
            if (w <= 0 || h <= 0) return null
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            buf.position(0)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            // Flip vertical (glReadPixels returns bottom-up)
            val matrix = android.graphics.Matrix()
            matrix.postScale(1f, -1f, w / 2f, h / 2f)
            return android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h, matrix, true)
        } catch (e: Exception) {
            android.util.Log.e("PolygonEngine", "captureFramebuffer failed: " + e.message)
            return null
        }
    }

    /** Check whether we are currently in a deformation drag (for UI state) */
    fun isDeforming(): Boolean {
        return isTransformGrabbed && transformMode == "translate" &&
            (selectMode == SelectMode.NODE || selectMode == SelectMode.EDGE || selectMode == SelectMode.SURFACE)
    }

    /** Get a description of what is currently selected for deformation UI */
    fun getSelectionDescription(): String {
        return when (selectMode) {
            SelectMode.NODE -> {
                val count = selectedNodeIndices.size
                if (count > 1) "$count nodes selected"
                else if (selectedNodeIndex >= 0) "Node $selectedNodeIndex" else "No node"
            }
            SelectMode.EDGE -> {
                val count = selectedEdgeIndices.size
                if (count > 1) "$count edges selected"
                else if (selectedEdgeIndex >= 0) "Edge" else "No edge"
            }
            SelectMode.SURFACE -> {
                val count = selectedFaceIndices.size
                if (count > 1) "$count faces selected"
                else if (selectedFaceIndex >= 0) "Face $selectedFaceIndex" else "No face"
            }
            SelectMode.OBJECT -> selectedObjectForHighlight?.name ?: "No object"
            SelectMode.NONE -> "No selection"
        }
    }

    /** Rename object at the given index */
    fun renameObject(objIndex: Int, newName: String) {
        if (objIndex < 0 || objIndex >= objects.size) return
        pushUndo()
        objects[objIndex] = objects[objIndex].copy(name = newName)
        // Update selectedObjectForHighlight if it matches
        if (selectedObjectIndex == objIndex && selectedObjectForHighlight != null) {
            selectedObjectForHighlight = objects[objIndex]
        }
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }

    /** Build a 4x4 rotation matrix that rotates world +Z into the given direction. */
    private fun buildRotationFromZ(dir: FloatArray): FloatArray {
        val m = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        val dz = dir[2]
        if (dz > 0.9999f) return m // already aligned
        if (dz < -0.9999f) { android.opengl.Matrix.rotateM(m, 0, 180f, 1f, 0f, 0f); return m }
        val crossX = -dir[1]; val crossY = dir[0]; val crossZ = 0f
        val angle = kotlin.math.acos(kotlin.math.min(1f, kotlin.math.max(-1f, dz))).let { (it * 180f / kotlin.math.PI.toFloat()).toFloat() }
        android.opengl.Matrix.rotateM(m, 0, angle, crossX, crossY, crossZ)
        return m
    }

    /** Interactive extrude: enter extrude-drag mode on selected face. */
    fun startExtrude() {
        if (selectMode != SelectMode.SURFACE || selectedFaceIndex < 0) return
        val obj = selectedObjectForHighlight ?: return
        val pm = obj.mesh ?: return
        if (selectedFaceIndex !in 0 until pm.faceCount) return
        pushUndo()
        extrudeNormal = pm.faceNormal(selectedFaceIndex)
        gizmoRotMatrix = buildRotationFromZ(extrudeNormal)
        extrudeBaseMesh = pm.copy()
        extrudeBaseFaceIndex = selectedFaceIndex
        isExtrudeMode = true
        transformMode = "extrude"
        gizmoVisible = true
        gizmoWorldPos = getGizmoTargetWorldPos()
        isTransformGrabbed = true
        grabbedAxis = "N"
        extrudeAccum = 0f
        glSurfaceView.requestRender()
    }

    /** Exit extrude-drag mode. */
    fun endExtrude() {
        isExtrudeMode = false
        extrudeAccum = 0f
        extrudeBaseMesh = null
        extrudeBaseFaceIndex = -1
    }

    /** Set an object visible or hidden. */
    fun setObjectVisible(objIndex: Int, visible: Boolean) {
        if (objIndex < 0 || objIndex >= objects.size) return
        pushUndo()
        objects[objIndex].visible = visible
        glSurfaceView.requestRender()
    }

    // ===== EXTRUDE / SUBDIVIDE / GROUP =====

    /** Extrude the currently selected face by a given offset vector */
    /** Extrude the selected face: duplicate face + offset + side faces */
    fun extrudeFace(dx: Float, dy: Float, dz: Float) {
        val obj = selectedObjectForHighlight ?: return
        if (selectMode != SelectMode.SURFACE || selectedFaceIndex < 0) return
        pushUndo()

        obj.mesh?.let { pm ->
            if (selectedFaceIndex >= pm.faceCount) return
            // Panjang perpindahan dipakai sebagai jarak; arah default = normal face.
            val len = sqrt(dx*dx + dy*dy + dz*dz)
            val dist = if (len > 1e-5f) len else pm.faceEdgeAverage(selectedFaceIndex)
            val dir = if (len > 1e-5f) floatArrayOf(dx/len, dy/len, dz/len) else null
            val cap = pm.extrudeFace(selectedFaceIndex, dist, dir)
            if (cap < 0) return
            obj.syncFromMesh()
            selectedFaceIndex = cap
            selectedObjectForHighlight = obj
            gizmoWorldPos = getGizmoTargetWorldPos()
            onObjectsChanged?.invoke(objects.toList())
            glSurfaceView.requestRender()
            return
        }

        val tpf = trianglesPerFace(obj.type)
        val triStart = selectedFaceIndex * tpf

        // Collect unique vertex positions of the selected face
        val faceVerts = mutableListOf<Float>()
        val faceVertIndices = mutableListOf<Int>()
        val eps = 0.001f
        for (ti in 0 until tpf) {
            val base = (triStart + ti) * 9
            for (v in 0 until 3) {
                val b = base + v * 3
                val vx = obj.vertices[b]; val vy = obj.vertices[b+1]; val vz = obj.vertices[b+2]
                var found = false
                for (fi in 0 until faceVerts.size / 3) {
                    val d = (vx - faceVerts[fi*3]) + (vy - faceVerts[fi*3+1]) + (vz - faceVerts[fi*3+2])
                    if (d < eps && d > -eps) { found = true; break }
                }
                if (!found) {
                    faceVerts.addAll(listOf(vx, vy, vz))
                    faceVertIndices.add(b / 3)
                }
            }
        }
        val faceVertCount = faceVerts.size / 3
        if (faceVertCount < 3) return

        // Create offset face vertices
        val offsetVerts = FloatArray(faceVerts.size)
        for (i in 0 until faceVertCount) {
            offsetVerts[i*3] = faceVerts[i*3] + dx
            offsetVerts[i*3+1] = faceVerts[i*3+1] + dy
            offsetVerts[i*3+2] = faceVerts[i*3+2] + dz
        }

        // Build new vertex list: original + offset
        val newVerts = obj.vertices.toMutableList()
        val offsetStartIdx = obj.vertexCount
        newVerts.addAll(offsetVerts.toList())

        // Side faces: quad for each edge of the face (2 triangles each)
        for (i in 0 until faceVertCount) {
            val next = (i + 1) % faceVertCount
            val i0 = faceVertIndices[i]
            val i1 = faceVertIndices[next]
            val i2 = offsetStartIdx + next
            val i3 = offsetStartIdx + i
            // Triangle 1: i0-i1-i2, Triangle 2: i0-i2-i3
            for (vi in listOf(i0, i1, i2, i0, i2, i3)) {
                newVerts.addAll(listOf(obj.vertices[vi*3], obj.vertices[vi*3+1], obj.vertices[vi*3+2]))
            }
        }

        // Update the object
        val idx = objects.indexOf(obj)
        val newObj = obj.copy(
            vertices = newVerts.toFloatArray(),
            vertexCount = newVerts.size / 3
        )
        objects[idx] = newObj
        selectedObjectForHighlight = newObj

        gizmoWorldPos = getGizmoTargetWorldPos()
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }
                                                /** Subdivide the selected face: bagi rectangle jadi 4 face baru */
            
    
    
    
    
    
    
    
    
    
    fun subdivideFace() {
        val obj = selectedObjectForHighlight ?: return
        if (selectMode != SelectMode.SURFACE || selectedFaceIndex < 0) return
        pushUndo()

        obj.mesh?.let { pm ->
            if (selectedFaceIndex >= pm.faceCount) return
            if (!pm.divideFace(selectedFaceIndex)) return
            obj.syncFromMesh()
            gizmoWorldPos = getGizmoTargetWorldPos()
            onObjectsChanged?.invoke(objects.toList())
            glSurfaceView.requestRender()
            return
        }

        val tpf = trianglesPerFace(obj.type)
        if (tpf != 2) return

        val oldVerts = obj.vertices.copyOf()
        val triStart = selectedFaceIndex * tpf
        val base = triStart * 9

        val v0 = floatArrayOf(oldVerts[base], oldVerts[base+1], oldVerts[base+2])
        val v1 = floatArrayOf(oldVerts[base+3], oldVerts[base+4], oldVerts[base+5])
        val v2 = floatArrayOf(oldVerts[base+6], oldVerts[base+7], oldVerts[base+8])
        val v3 = floatArrayOf(oldVerts[base+15], oldVerts[base+16], oldVerts[base+17])

        val m01 = floatArrayOf((v0[0]+v1[0])/2f, (v0[1]+v1[1])/2f, (v0[2]+v1[2])/2f)
        val m12 = floatArrayOf((v1[0]+v2[0])/2f, (v1[1]+v2[1])/2f, (v1[2]+v2[2])/2f)
        val m23 = floatArrayOf((v2[0]+v3[0])/2f, (v2[1]+v3[1])/2f, (v2[2]+v3[2])/2f)
        val m30 = floatArrayOf((v3[0]+v0[0])/2f, (v3[1]+v0[1])/2f, (v3[2]+v0[2])/2f)
        val cx = (v0[0]+v1[0]+v2[0]+v3[0])/4f
        val cy = (v0[1]+v1[1]+v2[1]+v3[1])/4f
        val cz = (v0[2]+v1[2]+v2[2]+v3[2])/4f
        val c = floatArrayOf(cx, cy, cz)

        val newFace = mutableListOf<Float>()
        newFace.addAll(listOf(v0[0],v0[1],v0[2], m01[0],m01[1],m01[2], c[0],c[1],c[2]))
        newFace.addAll(listOf(v0[0],v0[1],v0[2], c[0],c[1],c[2], m30[0],m30[1],m30[2]))
        newFace.addAll(listOf(m01[0],m01[1],m01[2], v1[0],v1[1],v1[2], m12[0],m12[1],m12[2]))
        newFace.addAll(listOf(m01[0],m01[1],m01[2], m12[0],m12[1],m12[2], c[0],c[1],c[2]))
        newFace.addAll(listOf(c[0],c[1],c[2], m12[0],m12[1],m12[2], v2[0],v2[1],v2[2]))
        newFace.addAll(listOf(c[0],c[1],c[2], v2[0],v2[1],v2[2], m23[0],m23[1],m23[2]))
        newFace.addAll(listOf(m30[0],m30[1],m30[2], c[0],c[1],c[2], m23[0],m23[1],m23[2]))
        newFace.addAll(listOf(m30[0],m30[1],m30[2], m23[0],m23[1],m23[2], v3[0],v3[1],v3[2]))

        val vertList = oldVerts.toMutableList()
        val rIdx = triStart * 9
        repeat(tpf * 9) { vertList.removeAt(rIdx) }
        vertList.addAll(rIdx, newFace)

        val idx = objects.indexOf(obj)
        val newObj = obj.copy(
            vertices = vertList.toFloatArray(),
            vertexCount = vertList.size / 3,
            type = "subdivided"
        )
        objects[idx] = newObj
        selectedObjectForHighlight = newObj
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }

    /** Merge selected coplanar surfaces on the active mesh object. */
    fun mergeSelectedSurfaces(): Boolean {
        val obj = selectedObjectForHighlight ?: return false
        if (selectMode != SelectMode.SURFACE || selectedFaceIndices.size < 2) return false
        val mesh = obj.mesh ?: return false
        val facesToMerge = selectedFaceIndices.flatMap { mesh.faceGroupMembers(it).asList() }.distinct()
        if (facesToMerge.size < 2) return false
        pushUndo()
        if (!mesh.mergeFaces(facesToMerge)) {
            undo()
            return false
        }
        obj.syncFromMesh()
        selectedFaceIndices.clear()
        selectedFaceIndices.add(facesToMerge.first().coerceAtMost(mesh.faceCount - 1))
        selectedFaceIndex = selectedFaceIndices.first()
        gizmoWorldPos = getGizmoTargetWorldPos()
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
        return true
    }
    val selectedObjectIndices: List<Int>
        get() {
            val indices = mutableListOf<Int>()
            for (i in objects.indices) {
                if (objects[i].selected) indices.add(i)
            }
            return indices
        }

    /** Group selected objects into a single group object */
    fun groupObjects() {
        val indices = selectedObjectIndices
        if (indices.size < 2) return
        pushUndo()

        // Preserve geometry, topology, appearance and world matrices at every depth.
        val selectedObjs = indices.map { copySceneObjectDeep(objects[it]) }
        fun clearChildSelection(obj: SceneObject) {
            obj.selected = false
            obj.children?.forEach { clearChildSelection(it) }
        }
        selectedObjs.forEach { clearChildSelection(it) }

        // Include loaded/nested groups so their names are never reused.
        fun updateGroupCounter(obj: SceneObject) {
            if (obj.type == "group") {
                val number = obj.name.removePrefix("Group ").toIntOrNull() ?: 0
                groupCounter = maxOf(groupCounter, number)
            }
            obj.children?.forEach { updateGroupCounter(it) }
        }
        objects.forEach { updateGroupCounter(it) }
        groupCounter++
        val groupName = "Group $groupCounter"

        val groupObj = SceneObject(
            name = groupName,
            type = "group",
            vertices = floatArrayOf(),
            vertexCount = 0,
            // Group is a tree container; children retain their original world matrices.
            modelMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) },
            color = floatArrayOf(0.3f, 0.5f, 0.9f),  // Blue color for group
            children = selectedObjs
        )

        // Hapus object asli dari level atas; salinannya hidup sebagai child Group
        // sehingga tidak lagi berdiri sendiri, tapi tetap tampil di render.
        for (i in indices.sortedDescending()) {
            objects.removeAt(i)
        }
        objects.add(groupObj)
        for (obj in objects) obj.selected = false
        groupObj.selected = true
        selectedObjectIndex = objects.size - 1
        selectedObjectForHighlight = groupObj
        selectMode = SelectMode.OBJECT
        gizmoVisible = false
        isTransformGrabbed = false
        selectedNodeIndex = -1
        selectedEdgeIndex = -1
        selectedFaceIndex = -1
        selectedNodeIndices.clear()
        selectedEdgeIndices.clear()
        selectedFaceIndices.clear()
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }

    /**
     * Ungroup: bongkar group yang sedang terpilih. Anggota group dinaikkan ke level
     * atas pada posisi group di daftar, mempertahankan matrix dunianya (group
     * ber-matrix identitas, jadi anggota tidak berpindah). Mendukung undo/redo.
     * @return true jika group berhasil dibongkar.
     */
    fun ungroupSelectedObject(): Boolean {
        val idx = objects.indexOfFirst { it.selected && it.type == "group" && !it.children.isNullOrEmpty() }
        if (idx < 0) return false
        pushUndo()

        val group = objects.removeAt(idx)
        val members = group.children!!.map { copySceneObjectDeep(it) }
        members.forEach { it.selected = false }
        objects.addAll(idx, members)

        // Reset seleksi: tidak ada object terpilih setelah ungroup.
        for (obj in objects) obj.selected = false
        selectedObjectIndex = -1
        selectedObjectForHighlight = null
        selectMode = SelectMode.OBJECT
        gizmoVisible = false
        isTransformGrabbed = false
        selectedNodeIndex = -1
        selectedEdgeIndex = -1
        selectedFaceIndex = -1
        selectedNodeIndices.clear()
        selectedEdgeIndices.clear()
        selectedFaceIndices.clear()
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
        return true
    }

    /**
     * Insert Face: pada face yang dipilih, bagi menjadi 1 rectangle di tengah + 4 trapesium di sekeliling.
     * Rectangle tengah di-inset 30% dari tepi face.
     */
        fun insertFace() {
        val obj = selectedObjectForHighlight ?: return
        if (selectMode != SelectMode.SURFACE || selectedFaceIndex < 0) return
        pushUndo()

        obj.mesh?.let { pm ->
            if (selectedFaceIndex >= pm.faceCount) return
            // 0.3 = tebal cincin trapesium, face dalam jadi 70% ukuran face asal.
            val inner = pm.insetFace(selectedFaceIndex, 0.3f)
            if (inner < 0) return
            obj.syncFromMesh()
            selectedFaceIndex = inner
            selectedObjectForHighlight = obj
            gizmoWorldPos = getGizmoTargetWorldPos()
            onObjectsChanged?.invoke(objects.toList())
            glSurfaceView.requestRender()
            return
        }

        val tpf = trianglesPerFace(obj.type)
        val triStart = selectedFaceIndex * tpf
        val oldVerts = obj.vertices.copyOf()
        val newVerts = mutableListOf<Float>()
        val inset = 0.3f

        val origFaceVerts = mutableListOf<Float>()
        val eps = 0.001f
        for (ti in 0 until tpf) {
            val base = (triStart + ti) * 9
            for (v in 0 until 3) {
                val b = base + v * 3
                val vx = oldVerts[b]; val vy = oldVerts[b+1]; val vz = oldVerts[b+2]
                var found = false
                for (fi in 0 until origFaceVerts.size / 3) {
                    val d = (vx - origFaceVerts[fi*3]) + (vy - origFaceVerts[fi*3+1]) + (vz - origFaceVerts[fi*3+2])
                    if (d < eps && d > -eps) { found = true; break }
                }
                if (!found) origFaceVerts.addAll(listOf(vx, vy, vz))
            }
        }
        val origFaceCount = origFaceVerts.size / 3
        val edgeMids = mutableListOf<FloatArray>()

        if (tpf == 2 && (obj.type == "box" || obj.type == "rectangle" || obj.type == "quad" || obj.type == "quadmesh" || obj.type == "subdivided")) {
            val base = triStart * 9
            val v0 = floatArrayOf(oldVerts[base], oldVerts[base+1], oldVerts[base+2])
            val v1 = floatArrayOf(oldVerts[base+3], oldVerts[base+4], oldVerts[base+5])
            val v2 = floatArrayOf(oldVerts[base+6], oldVerts[base+7], oldVerts[base+8])
            val v3 = floatArrayOf(oldVerts[base+15], oldVerts[base+16], oldVerts[base+17])

            val cx = (v0[0]+v1[0]+v2[0]+v3[0])/4f
            val cy = (v0[1]+v1[1]+v2[1]+v3[1])/4f
            val cz = (v0[2]+v1[2]+v2[2]+v3[2])/4f

            val i0 = floatArrayOf(v0[0]+(cx-v0[0])*inset, v0[1]+(cx-v0[1])*inset, v0[2]+(cx-v0[2])*inset)
            val i1 = floatArrayOf(v1[0]+(cx-v1[0])*inset, v1[1]+(cx-v1[1])*inset, v1[2]+(cx-v1[2])*inset)
            val i2 = floatArrayOf(v2[0]+(cx-v2[0])*inset, v2[1]+(cx-v2[1])*inset, v2[2]+(cx-v2[2])*inset)
            val i3 = floatArrayOf(v3[0]+(cx-v3[0])*inset, v3[1]+(cx-v3[1])*inset, v3[2]+(cx-v3[2])*inset)

            val e0 = floatArrayOf((v0[0]+i0[0])/2f, (v0[1]+i0[1])/2f, (v0[2]+i0[2])/2f)
            val e1 = floatArrayOf((v1[0]+i1[0])/2f, (v1[1]+i1[1])/2f, (v1[2]+i1[2])/2f)
            val e2 = floatArrayOf((v2[0]+i2[0])/2f, (v2[1]+i2[1])/2f, (v2[2]+i2[2])/2f)
            val e3 = floatArrayOf((v3[0]+i3[0])/2f, (v3[1]+i3[1])/2f, (v3[2]+i3[2])/2f)

            edgeMids.addAll(listOf(e0, e1, e2, e3))

            newVerts.addAll(listOf(i0[0],i0[1],i0[2], i1[0],i1[1],i1[2], i2[0],i2[1],i2[2]))
            newVerts.addAll(listOf(i0[0],i0[1],i0[2], i2[0],i2[1],i2[2], i3[0],i3[1],i3[2]))
            newVerts.addAll(listOf(v0[0],v0[1],v0[2], v1[0],v1[1],v1[2], i1[0],i1[1],i1[2]))
            newVerts.addAll(listOf(v0[0],v0[1],v0[2], i1[0],i1[1],i1[2], i0[0],i0[1],i0[2]))
            newVerts.addAll(listOf(v1[0],v1[1],v1[2], v2[0],v2[1],v2[2], i2[0],i2[1],i2[2]))
            newVerts.addAll(listOf(v1[0],v1[1],v1[2], i2[0],i2[1],i2[2], i1[0],i1[1],i1[2]))
            newVerts.addAll(listOf(v2[0],v2[1],v2[2], v3[0],v3[1],v3[2], i3[0],i3[1],i3[2]))
            newVerts.addAll(listOf(v2[0],v2[1],v2[2], i3[0],i3[1],i3[2], i2[0],i2[1],i2[2]))
            newVerts.addAll(listOf(v3[0],v3[1],v3[2], v0[0],v0[1],v0[2], i0[0],i0[1],i0[2]))
            newVerts.addAll(listOf(v3[0],v3[1],v3[2], i0[0],i0[1],i0[2], i3[0],i3[1],i3[2]))
        } else {
            for (ti in 0 until tpf) {
                val base = (triStart + ti) * 9
                val v0 = floatArrayOf(oldVerts[base], oldVerts[base+1], oldVerts[base+2])
                val v1 = floatArrayOf(oldVerts[base+3], oldVerts[base+4], oldVerts[base+5])
                val v2 = floatArrayOf(oldVerts[base+6], oldVerts[base+7], oldVerts[base+8])
                val m0 = floatArrayOf((v0[0]+v1[0])/2f, (v0[1]+v1[1])/2f, (v0[2]+v1[2])/2f)
                val m1 = floatArrayOf((v1[0]+v2[0])/2f, (v1[1]+v2[1])/2f, (v1[2]+v2[2])/2f)
                val m2 = floatArrayOf((v2[0]+v0[0])/2f, (v2[1]+v0[1])/2f, (v2[2]+v0[2])/2f)
                newVerts.addAll(listOf(v0[0],v0[1],v0[2], m0[0],m0[1],m0[2], m2[0],m2[1],m2[2]))
                newVerts.addAll(listOf(m0[0],m0[1],m0[2], v1[0],v1[1],v1[2], m1[0],m1[1],m1[2]))
                newVerts.addAll(listOf(m2[0],m2[1],m2[2], m1[0],m1[1],m1[2], v2[0],v2[1],v2[2]))
                newVerts.addAll(listOf(m0[0],m0[1],m0[2], m1[0],m1[1],m1[2], m2[0],m2[1],m2[2]))
                if (ti == 0) edgeMids.addAll(listOf(m0, m1, m2))
            }
        }

        val vertList = oldVerts.toMutableList()
        val replaceIdx = triStart * 9
        for (i in 0 until tpf * 9) vertList[replaceIdx + i] = 0f
        vertList.addAll(replaceIdx, newVerts)

        // -- Hubungkan face tetangga: tambah vertex mid ke face tetangga, triangulasi ulang sebagai pentagon/ngon --
        for (i in 0 until origFaceCount) {
            val next = (i + 1) % origFaceCount
            val ax = origFaceVerts[i*3]; val ay = origFaceVerts[i*3+1]; val az = origFaceVerts[i*3+2]
            val bx = origFaceVerts[next*3]; val by = origFaceVerts[next*3+1]; val bz = origFaceVerts[next*3+2]

            var foundMid: FloatArray? = null
            var bestDist2 = 1e-4f
            val halfx = (ax+bx)/2f; val halfy = (ay+by)/2f; val halfz = (az+bz)/2f
            for (em in edgeMids) {
                val d2 = (em[0]-halfx)*(em[0]-halfx)+(em[1]-halfy)*(em[1]-halfy)+(em[2]-halfz)*(em[2]-halfz)
                if (d2 < bestDist2) { foundMid = em; bestDist2 = d2 }
            }
            if (foundMid == null) continue

            val neighborTriBases = mutableListOf<Int>()
            val totalVerts = vertList.size / 3
            var ti = 0
            while (ti * 3 + 2 < totalVerts) {
                val base = ti * 9
                if (vertList[base] == 0f && vertList[base+1] == 0f && vertList[base+2] == 0f) { ti++; continue }
                if (ti >= replaceIdx/9 && ti < replaceIdx/9 + newVerts.size/9) { ti++; continue }

                val tv0x = vertList[base]; val tv0y = vertList[base+1]; val tv0z = vertList[base+2]
                val tv1x = vertList[base+3]; val tv1y = vertList[base+4]; val tv1z = vertList[base+5]
                val tv2x = vertList[base+6]; val tv2y = vertList[base+7]; val tv2z = vertList[base+8]

                val d0a2 = (tv0x-ax)*(tv0x-ax)+(tv0y-ay)*(tv0y-ay)+(tv0z-az)*(tv0z-az)
                val d1a2 = (tv1x-ax)*(tv1x-ax)+(tv1y-ay)*(tv1y-ay)+(tv1z-az)*(tv1z-az)
                val d2a2 = (tv2x-ax)*(tv2x-ax)+(tv2y-ay)*(tv2y-ay)+(tv2z-az)*(tv2z-az)
                val d0b2 = (tv0x-bx)*(tv0x-bx)+(tv0y-by)*(tv0y-by)+(tv0z-bz)*(tv0z-bz)
                val d1b2 = (tv1x-bx)*(tv1x-bx)+(tv1y-by)*(tv1y-by)+(tv1z-bz)*(tv1z-bz)
                val d2b2 = (tv2x-bx)*(tv2x-bx)+(tv2y-by)*(tv2y-by)+(tv2z-bz)*(tv2z-bz)

                val thr = 0.001f
                val hasA = d0a2 < thr || d1a2 < thr || d2a2 < thr
                val hasB = d0b2 < thr || d1b2 < thr || d2b2 < thr
                if (hasA && hasB) {
                    neighborTriBases.add(base)
                }
                ti++
            }

            if (neighborTriBases.size == 0) continue

            val neighborVerts = mutableListOf<Float>()
            val thr = 0.001f
            for (nb in neighborTriBases) {
                for (v in 0 until 3) {
                    val vx = vertList[nb + v*3]; val vy = vertList[nb + v*3+1]; val vz = vertList[nb + v*3+2]
                    var found = false
                    for (fi in 0 until neighborVerts.size / 3) {
                        val dx = vx - neighborVerts[fi*3]
                        val dy = vy - neighborVerts[fi*3+1]
                        val dz = vz - neighborVerts[fi*3+2]
                        if (dx*dx+dy*dy+dz*dz < thr) { found = true; break }
                    }
                    if (!found) neighborVerts.addAll(listOf(vx, vy, vz))
                }
            }

            // Sisipkan midpoint sisi yang dibagi supaya face tetangga ikut terbuka
            // menjadi n-gon/pentagon dan tetap menempel pada face hasil subdivide.
            val midX = foundMid[0]
            val midY = foundMid[1]
            val midZ = foundMid[2]
            var hasMid = false
            for (fi in 0 until neighborVerts.size / 3) {
                val dx = neighborVerts[fi*3] - midX
                val dy = neighborVerts[fi*3+1] - midY
                val dz = neighborVerts[fi*3+2] - midZ
                if (dx*dx + dy*dy + dz*dz < thr) {
                    hasMid = true
                    break
                }
            }
            if (!hasMid) neighborVerts.addAll(listOf(midX, midY, midZ))

            for (nb in neighborTriBases) {
                for (k in 0 until 9) vertList[nb + k] = 0f
            }

            val nvCount = neighborVerts.size / 3
            var cx = 0f; var cy = 0f; var cz = 0f
            for (fi in 0 until nvCount) {
                cx += neighborVerts[fi*3]; cy += neighborVerts[fi*3+1]; cz += neighborVerts[fi*3+2]
            }
            cx /= nvCount; cy /= nvCount; cz /= nvCount

            val angles = mutableListOf<Pair<Float, Int>>()
            for (fi in 0 until nvCount) {
                val dx = neighborVerts[fi*3] - cx
                val dy = neighborVerts[fi*3+1] - cy
                val dz = neighborVerts[fi*3+2] - cz
                val angle = Math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
                angles.add(Pair(angle, fi))
            }
            angles.sortBy { it.first }

            // Triangulate as a minimal fan from the first boundary vertex.
            // A quad with one split edge becomes a pentagon (5 vertices) and is filled by 3 triangles.
            if (angles.size >= 3) {
                val anchor = angles[0].second
                for (ai in 1 until angles.size - 1) {
                    val cur = angles[ai].second
                    val nxt = angles[ai + 1].second
                    vertList.addAll(listOf(
                        neighborVerts[anchor * 3], neighborVerts[anchor * 3 + 1], neighborVerts[anchor * 3 + 2],
                        neighborVerts[cur * 3], neighborVerts[cur * 3 + 1], neighborVerts[cur * 3 + 2],
                        neighborVerts[nxt * 3], neighborVerts[nxt * 3 + 1], neighborVerts[nxt * 3 + 2]
                    ))
                }
            }
        }
        val idx = objects.indexOf(obj)
        val newObj = obj.copy(
            vertices = vertList.toFloatArray(),
            vertexCount = vertList.size / 3
        )
        objects[idx] = newObj
        selectedObjectForHighlight = newObj
        selectedFaceIndex = -1
        selectedFaceIndices.clear()
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }

    /** Combine selected objects into one mesh while preserving their world transforms. */
    fun combineObjects() {
        val selected = objects.filter { it.selected }
        if (selected.size < 2) return
        pushUndo()

        fun worldPoint(obj: SceneObject, x: Float, y: Float, z: Float): V3 {
            val world = FloatArray(4)
            Matrix.multiplyMV(world, 0, obj.modelMatrix, 0, floatArrayOf(x, y, z, 1f), 0)
            return V3(
                (world[0] / world[3]).toDouble(),
                (world[1] / world[3]).toDouble(),
                (world[2] / world[3]).toDouble()
            )
        }

        fun trianglesFor(obj: SceneObject): List<Tri> {
            val out = mutableListOf<Tri>()
            val mesh = obj.mesh
            if (mesh != null) {
                for (face in mesh.faces) {
                    if (face.size < 3) continue
                    val points = face.map { v -> worldPoint(obj, mesh.vx(v), mesh.vy(v), mesh.vz(v)) }
                    for (i in 1 until points.size - 1) out.add(Tri(points[0], points[i], points[i + 1]))
                }
            } else {
                for (base in obj.vertices.indices step 9) {
                    if (base + 8 >= obj.vertices.size) break
                    out.add(
                        Tri(
                            worldPoint(obj, obj.vertices[base], obj.vertices[base + 1], obj.vertices[base + 2]),
                            worldPoint(obj, obj.vertices[base + 3], obj.vertices[base + 4], obj.vertices[base + 5]),
                            worldPoint(obj, obj.vertices[base + 6], obj.vertices[base + 7], obj.vertices[base + 8])
                        )
                    )
                }
            }
            return out
        }

        val parts = selected.map(::trianglesFor)
        var unioned = emptyList<Tri>()
        var unionSucceeded = true
        for (part in parts) {
            if (part.isEmpty()) continue
            unioned = if (unioned.isEmpty()) {
                part
            } else {
                val result = Csg.union(unioned, part)
                if (result.isEmpty()) {
                    unionSucceeded = false
                    break
                }
                result
            }
        }

        val mergedMesh = PolyMesh()
        mergedMesh.mergeCoplanarSelection = true
        val resultTriangles = if (unionSucceeded && unioned.isNotEmpty()) unioned else parts.flatten()
        for (tri in resultTriangles) {
            val a = mergedMesh.addVertex(tri.a.x.toFloat(), tri.a.y.toFloat(), tri.a.z.toFloat())
            val b = mergedMesh.addVertex(tri.b.x.toFloat(), tri.b.y.toFloat(), tri.b.z.toFloat())
            val c = mergedMesh.addVertex(tri.c.x.toFloat(), tri.c.y.toFloat(), tri.c.z.toFloat())
            mergedMesh.addFace(a, b, c)
        }
        mergedMesh.removeDuplicateFaces()

        var r = 0f; var g = 0f; var b = 0f
        for (obj in selected) {
            r += obj.color[0]; g += obj.color[1]; b += obj.color[2]
        }
        val avg = selected.size.toFloat()
        val avgColor = floatArrayOf(r/avg, g/avg, b/avg)

        val merged = SceneObject(
            name = "combine_${objects.count { it.name.startsWith("combine_") } + 1}",
            type = "combined",
            vertices = mergedMesh.soup,
            vertexCount = mergedMesh.soup.size / 3,
            color = avgColor,
            mesh = mergedMesh
        )
        merged.selected = true

        val indices = objects.indices.filter { objects[it].selected }.sortedDescending()
        for (i in indices) {
            objects.removeAt(i)
        }
        objects.add(merged)
        for (obj in objects) obj.selected = false
        merged.selected = true
        selectedObjectIndex = objects.size - 1
        selectedObjectForHighlight = merged
        selectMode = SelectMode.OBJECT
        gizmoVisible = false
        isTransformGrabbed = false
        selectedNodeIndex = -1
        selectedEdgeIndex = -1
        selectedFaceIndex = -1
        selectedNodeIndices.clear()
        selectedEdgeIndices.clear()
        selectedFaceIndices.clear()
        onObjectsChanged?.invoke(objects.toList())
        glSurfaceView.requestRender()
    }
    fun cleanup() {
        if (objectVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(objectVao), 0)
        if (objectVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(objectVbo), 0)
        if (highlightVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(highlightVao), 0)
        if (highlightVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(highlightVbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(gridVao), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(axisVao), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(boxVao), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(gridVbo), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(axisVbo), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(boxVbo), 0)
        intArrayOf(gridProgram,axisProgram,boxProgram).forEach { if(it!=0) GLES30.glDeleteProgram(it) }
    }
}







