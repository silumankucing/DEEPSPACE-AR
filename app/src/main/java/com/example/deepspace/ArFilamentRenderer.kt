package com.example.deepspace

import android.opengl.Matrix
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tan

/**
 * Transparent native Filament renderer for the AR page.
 *
 * Renders a glTF/GLB model over the live camera preview: the SurfaceView is
 * non-opaque, the swap chain uses CONFIG_TRANSPARENT, the View blends
 * TRANSLUCENT and the Scene has no skybox — so everything the model does not
 * cover shows the camera preview behind it.
 *
 * There is NO ARCore here. Placement is driven by the OpenCV ORB anchor
 * (a normalized 2D box) via [setModelAnchor], unprojected onto a fixed depth
 * plane in front of a plain perspective camera.
 */
class ArFilamentRenderer(private val surfaceView: SurfaceView) {

    data class SavedLight(
        val direction: FloatArray,
        val color: FloatArray,
        val intensity: Float,
        val castShadows: Boolean
    )

    companion object {
        private const val TAG = "ArFilamentRenderer"
        private const val NEAR = 0.1
        private const val FAR = 100.0
        private const val FOV_DEG = 45.0          // vertical FOV of the AR camera
        private const val ANCHOR_DEPTH = 2.0f     // meters in front of the camera
        private const val CONFIG_TRANSPARENT = 0x1L  // SwapChainFlags.CONFIG_TRANSPARENT
        init {
            // Must load native libs before touching Engine/AssetLoader.
            Filament.init()
            Gltfio.init()
        }
    }

    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity: Int = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)

    private val assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)

    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    // Multi-model support: list of all loaded assets and their root names
    private val assets: MutableList<FilamentAsset> = mutableListOf()
    private val modelNames: MutableList<String> = mutableListOf()
    // Status hidden per model (paralel dengan `assets`).
    private val hiddenFlags: MutableList<Boolean> = mutableListOf()
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    private var viewportW = 1
    private var viewportH = 1

    // Nama root node dari GLB
    private var currentModelName: String = "Assembly"

    // Latest anchor from OpenCV (normalized center + size); null = keep default centered.
    private var anchorX = 0.5f
    private var anchorY = 0.5f
    private var anchorW = 0.4f
    private var anchorVisible = true

    // Interactive transform (zoom / rotate / pan applied on top of anchor)
    private var userScale = 1.0f
    private var userRotX = 0.0f   // rotation around X axis (degrees)
    private var userRotY = 0.0f   // rotation around Y axis (degrees)
    private var userPanX = 0.0f   // horizontal pan offset (normalized)
    private var userPanY = 0.0f   // vertical pan offset (normalized)
    private val savedCameraMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val savedLights = listOf(
        SavedLight(floatArrayOf(0.5f, -1.0f, -0.8f), floatArrayOf(1.0f, 0.98f, 0.95f), 90_000.0f, false),
        SavedLight(floatArrayOf(-0.3f, 1.0f, 0.4f), floatArrayOf(0.6f, 0.65f, 0.8f), 35_000.0f, false)
    )
    // Entity cahaya yang sudah dibuat + skala intensitas saat ini (untuk slider Lighting).
    private val lightEntities: MutableList<Int> = mutableListOf()
    private var currentIntensityScale = 1f

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            val sc = swapChain ?: return
            if (renderer.beginFrame(sc, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
            }
        }
    }

    init {
        // Transparent background: no skybox, blend the whole view, clear alpha 0.
        scene.skybox = null
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.scene = scene
        view.camera = camera
        view.isPostProcessingEnabled = false

        val clear = renderer.clearOptions
        clear.clear = true
        clear.clearColor = floatArrayOf(0f, 0f, 0f, 0f)
        renderer.clearOptions = clear

        // Camera sits at origin looking down -Z.
        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        camera.setModelMatrix(identity)

        addLights()

        uiHelper.isOpaque = false
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                // CONFIG_TRANSPARENT (== 0x1) — lets the camera preview show through
                // wherever the model does not draw. The named constant lives in the
                // package-private SwapChainFlags, so the literal is used directly.
                swapChain = engine.createSwapChain(surface, CONFIG_TRANSPARENT)
            }

            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it); swapChain = null }
            }

            override fun onResized(width: Int, height: Int) {
                viewportW = width.coerceAtLeast(1)
                viewportH = height.coerceAtLeast(1)
                view.viewport = Viewport(0, 0, viewportW, viewportH)
                val aspect = viewportW.toDouble() / viewportH.toDouble()
                camera.setProjection(FOV_DEG, aspect, NEAR, FAR, Camera.Fov.VERTICAL)
            }
        }
        uiHelper.attachTo(surfaceView)
    }

    private fun addLights() {
        val em = EntityManager.get()
        for (light in savedLights) {
            val entity = em.create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .direction(light.direction[0], light.direction[1], light.direction[2])
                .color(light.color[0], light.color[1], light.color[2])
                .intensity(light.intensity)
                .castShadows(light.castShadows)
                .build(engine, entity)
            scene.addEntity(entity)
            lightEntities.add(entity)
        }
    }

    /**
     * Sembunyikan / tampilkan model pada index tertentu.
     * Return status hidden terbaru (true = disembunyikan).
     */
    fun isModelHidden(index: Int): Boolean =
        index >= 0 && index < hiddenFlags.size && hiddenFlags[index]

    fun toggleVisibilityAt(index: Int): Boolean {
        if (index < 0 || index >= assets.size) return false
        val a = assets[index]
        val newHidden = !(hiddenFlags.getOrNull(index) ?: false)
        hiddenFlags[index] = newHidden
        if (newHidden || !anchorVisible) scene.removeEntities(a.entities)
        if (!newHidden && anchorVisible) scene.addEntities(a.entities)
        return newHidden
    }

    /** Set arah cahaya (dinormalisasi) untuk semua directional light. */
    fun setLightDirection(x: Float, y: Float, z: Float) {
        val len = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        rebuildLights(floatArrayOf(x / len, y / len, z / len), currentIntensityScale)
    }

    /** Set skala intensitas (1f = default) untuk semua directional light. */
    fun setLightIntensityScale(scale: Float) {
        currentIntensityScale = scale
        rebuildLights(null, scale)
    }

    /** Buat ulang semua directional light dengan arah/skala intensitas terbaru. */
    private fun rebuildLights(directionOverride: FloatArray?, intensityScale: Float) {
        val lm = engine.lightManager
        val em = EntityManager.get()
        for (e in lightEntities) {
            scene.remove(e)
            em.destroy(e)
        }
        lightEntities.clear()
        for (light in savedLights) {
            val dir = directionOverride ?: light.direction
            val entity = em.create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .direction(dir[0], dir[1], dir[2])
                .color(light.color[0], light.color[1], light.color[2])
                .intensity(light.intensity * intensityScale)
                .castShadows(light.castShadows)
                .build(engine, entity)
            scene.addEntity(entity)
            lightEntities.add(entity)
        }
    }

    /** Load a GLB/glTF from raw bytes, replacing any current model. */
    fun loadGlb(bytes: ByteArray) {
        clearAllAssets()
        addGlb(bytes)
    }

    /** Add another GLB/glTF model without removing existing models. */
    fun addGlb(bytes: ByteArray) {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes); rewind()
        }
        val newAsset = assetLoader.createAsset(buffer) ?: return
        resourceLoader.loadResources(newAsset)
        assets.add(newAsset)
        hiddenFlags.add(false)
        asset = newAsset
        if (anchorVisible) scene.addEntities(newAsset.entities)
        applyAnchorTransform()

        // Parse root node name dari GLB JSON
        val name = parseGlbRootName(bytes)
        modelNames.add(name)
        currentModelName = name
    }

    /**
     * Parse low level root name dari binary GLB.
     * Mencari node pertama di scene graph yang punya mesh atau children.
     */
    private fun parseGlbRootName(glbBytes: ByteArray): String {
        try {
            var offset = 12
            if (glbBytes.size < 20) return "Assembly"
            val jsonLen = ((glbBytes[offset].toInt() and 0xFF) shl 0) or
                          ((glbBytes[offset + 1].toInt() and 0xFF) shl 8) or
                          ((glbBytes[offset + 2].toInt() and 0xFF) shl 16) or
                          ((glbBytes[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            val chunkType = ((glbBytes[offset].toInt() and 0xFF) shl 0) or
                            ((glbBytes[offset + 1].toInt() and 0xFF) shl 8) or
                            ((glbBytes[offset + 2].toInt() and 0xFF) shl 16) or
                            ((glbBytes[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            if (chunkType != 0x4E4F534A) return "Assembly"
            val jsonStr = String(glbBytes, offset, jsonLen, Charsets.UTF_8)
            val root = org.json.JSONObject(jsonStr)
            val nodes = root.optJSONArray("nodes") ?: return "Assembly"

            // Ambil scene root indices
            val scene = root.optInt("scene", 0)
            val scenes = root.optJSONArray("scenes")
            val sceneRootIndices = mutableListOf<Int>()
            if (scenes != null && scene < scenes.length()) {
                val sceneObj = scenes.getJSONObject(scene)
                val nodeIds = sceneObj.optJSONArray("nodes")
                if (nodeIds != null) {
                    for (i in 0 until nodeIds.length()) {
                        sceneRootIndices.add(nodeIds.getInt(i))
                    }
                }
            }
            if (sceneRootIndices.isEmpty()) {
                for (i in 0 until nodes.length()) sceneRootIndices.add(i)
            }

            // Cari low level root: node pertama di scene roots yang punya mesh
            // Jika tidak, ambil node pertama yang namanya tidak kosong
            for (idx in sceneRootIndices) {
                val nodeObj = nodes.getJSONObject(idx)
                val name = nodeObj.optString("name", "")
                if (nodeObj.has("mesh") && name.isNotEmpty()) {
                    return name
                }
            }
            // Fallback: node pertama dengan nama
            for (idx in sceneRootIndices) {
                val nodeObj = nodes.getJSONObject(idx)
                val name = nodeObj.optString("name", "")
                if (name.isNotEmpty()) return name
            }
        } catch (_: Exception) { }
        return "Assembly"
    }

    /**
     * Ambil daftar nama node dari asset untuk ditampilkan di tree.
     * Hanya root node (assembly) yang ditampilkan — semua child part
     * dijadikan satu kesatuan object. Nama diambil dari root node asset.
     */
    fun getNodeNames(): List<Pair<String, Int>> {
        if (assets.isEmpty()) return emptyList()
        return modelNames.mapIndexed { index, name ->
            // Jika cuma 1 model, depth=1; jika multi, depth berdasarkan urutan
            name to 1
        }
    }

    /** Clear all loaded objects (assets) from scene. */
    fun clearObjects() {
        clearAllAssets()
    }

    /**
     * Hapus model pada index tertentu dari daftar assets.
     * Jika index tidak valid, tidak ada yang dihapus.
     */
    fun removeModelAt(index: Int) {
        if (index < 0 || index >= assets.size) return
        val a = assets.removeAt(index)
        modelNames.removeAt(index)
        if (index < hiddenFlags.size) hiddenFlags.removeAt(index)
        scene.removeEntities(a.entities)
        assetLoader.destroyAsset(a)
        // Update currentModelName
        currentModelName = modelNames.lastOrNull() ?: "Assembly"
    }

    private fun clearAllAssets() {
        assets.forEach { a ->
            scene.removeEntities(a.entities)
            assetLoader.destroyAsset(a)
        }
        assets.clear()
        modelNames.clear()
        hiddenFlags.clear()
        asset = null
    }

    /**
     * Perbesar/kecil model oleh faktor multiplier (relatif).
     * Misal: scaleBy(1.1) = zoom in, scaleBy(0.9) = zoom out.
     */
    fun scaleBy(factor: Float) {
        userScale = (userScale * factor).coerceIn(0.1f, 10.0f)
        applyAnchorTransform()
    }

    /**
     * Rotasi model di sumbu X (pitch) dan Y (yaw), dalam derajat.
     */
    fun rotateBy(deltaX: Float, deltaY: Float) {
        userRotX = (userRotX + deltaX) % 360f
        userRotY = (userRotY + deltaY) % 360f
        applyAnchorTransform()
    }

    /**
     * Geser/pan model di bidang horizontal dan vertikal.
     * deltaX/deltaY dalam pixel pada view.
     */
    fun panBy(deltaX: Float, deltaY: Float) {
        userPanX += deltaX * 0.001f
        userPanY -= deltaY * 0.001f   // negatif agar pan ke atas = object ke atas
        applyAnchorTransform()
    }

    /**
     * Reset semua interaksi (scale, rotation, pan) ke default.
     */
    fun resetTransform() {
        userScale = 1.0f
        userRotX = 0.0f
        userRotY = 0.0f
        userPanX = 0.0f
        userPanY = 0.0f
        applyAnchorTransform()
    }

    fun setModelAnchor(x: Float, y: Float, w: Float) {
        anchorX = x; anchorY = y; anchorW = w.coerceAtLeast(0.02f)
        applyAnchorTransform()
    }

    fun getSavedCameraMatrix(): FloatArray = savedCameraMatrix.copyOf()

    fun getSavedLights(): List<SavedLight> = savedLights.map {
        SavedLight(it.direction.copyOf(), it.color.copyOf(), it.intensity, it.castShadows)
    }

    fun showAnchor() {
        if (anchorVisible) return
        anchorVisible = true
        assets.forEachIndexed { i, a ->
            // Model yang disembunyikan lewat Hide/Show tidak dimunculkan kembali.
            if (!(hiddenFlags.getOrNull(i) ?: false)) scene.addEntities(a.entities)
        }
    }

    fun hideAnchor() {
        if (!anchorVisible) return
        anchorVisible = false
        assets.forEach { scene.removeEntities(it.entities) }
    }

    private fun applyAnchorTransform() {
        if (assets.isEmpty()) return

        // Unproject the normalized anchor center onto the depth plane.
        val ndcX = anchorX * 2f - 1f
        val ndcY = 1f - anchorY * 2f
        val aspect = viewportW.toFloat() / viewportH.toFloat()
        val halfH = tan(Math.toRadians(FOV_DEG / 2.0)).toFloat() * ANCHOR_DEPTH
        val halfW = halfH * aspect
        val worldX = ndcX * halfW
        val worldY = ndcY * halfH
        val worldZ = -ANCHOR_DEPTH

        val tm = engine.transformManager
        val targetSize = (anchorW * halfW * 2f).coerceIn(0.15f, 3.0f)

        // Apply transform to each asset with staggered offset
        for ((i, a) in assets.withIndex()) {
            val box = a.boundingBox
            val ext = box.halfExtent
            val maxDim = maxOf(ext[0], ext[1], ext[2]).coerceAtLeast(1e-4f) * 2f
            val s = targetSize / maxDim
            val center = box.center

            val inst = tm.getInstance(a.root)
            val m = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            // Stagger each additional model slightly to the right
            val offsetX = i * targetSize * 1.2f
            Matrix.translateM(m, 0, worldX + userPanX + offsetX, worldY + userPanY, worldZ)
            Matrix.rotateM(m, 0, userRotY, 0f, 1f, 0f)
            Matrix.rotateM(m, 0, userRotX, 1f, 0f, 0f)
            Matrix.scaleM(m, 0, s * userScale, s * userScale, s * userScale)
            Matrix.translateM(m, 0, -center[0], -center[1], -center[2])
            tm.setTransform(inst, m)
        }
    }

    fun resume() {
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        choreographer.removeFrameCallback(frameCallback)
    }

    fun destroy() {
        choreographer.removeFrameCallback(frameCallback)
        uiHelper.detach()
        clearAllAssets()
        resourceLoader.destroy()
        assetLoader.destroy()
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroy()
    }
}
