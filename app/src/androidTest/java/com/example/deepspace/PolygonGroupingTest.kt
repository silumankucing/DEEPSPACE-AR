package com.example.deepspace

import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolygonGroupingTest {
    private fun withEngine(test: (PolygonEngine) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val surface = object : GLSurfaceView(context) {
                override fun requestRender() = Unit
            }
            test(PolygonEngine(context, surface))
        }
    }

    // Inspect the actual render input without requiring a live OpenGL surface.
    @Suppress("UNCHECKED_CAST")
    private fun renderObjects(engine: PolygonEngine): List<PolygonEngine.SceneObject> {
        val method = PolygonEngine::class.java.getDeclaredMethod("flattenForRender")
        method.isAccessible = true
        return method.invoke(engine) as List<PolygonEngine.SceneObject>
    }

    @Test
    fun groupingKeepsModelsAndWireframesAtTheirOriginalPositions() = withEngine { engine ->
        val box = engine.createShape("box")!!
        val sphere = engine.createShape("sphere")!!
        val untouched = engine.createShape("box")!!
        Matrix.translateM(box.modelMatrix, 0, 25f, 3f, -7f)
        box.color = floatArrayOf(1f, 0f, 0f)
        box.faceColors = mutableMapOf(0 to floatArrayOf(0f, 1f, 0f))
        box.selected = true
        sphere.selected = true
        val expected = listOf(box, sphere)

        engine.groupObjects()

        val roots = engine.getObjects()
        assertEquals(2, roots.size)
        assertSame(untouched, roots[0])
        val group = roots[1]
        assertEquals("Group 1", group.name)
        assertEquals("group", group.type)
        assertEquals(0, group.vertexCount)
        assertTrue(group.selected)
        val children = group.children!!
        assertEquals(expected.map { it.name }, children.map { it.name })
        for ((original, child) in expected.zip(children)) {
            assertArrayEquals(original.modelMatrix, child.modelMatrix, 0f)
            assertArrayEquals(original.vertices, child.vertices, 0f)
            assertArrayEquals(original.color, child.color, 0f)
            assertEquals(original.mesh!!.faceCount, child.mesh!!.faceCount)
            assertNotSame(original.mesh, child.mesh)
            assertFalse(child.selected)
            assertTrue(child.visible)
        }
        assertArrayEquals(box.faceColors!![0]!!, children[0].faceColors!![0]!!, 0f)
        assertEquals(setOf(box.name, sphere.name, untouched.name), renderObjects(engine).map { it.name }.toSet())
        group.visible = false
        assertEquals(listOf(untouched), renderObjects(engine))
        group.visible = true
        assertEquals(3, renderObjects(engine).size)
    }

    @Test
    fun nestedGroupsStayVisibleAndUndoRedoRestoresHierarchy() = withEngine { engine ->
        engine.createShape("box")!!.selected = true
        engine.createShape("sphere")!!.selected = true
        engine.groupObjects()
        engine.createShape("box")!!.selected = true
        engine.groupObjects()
        assertEquals("Group 2", engine.getObjects().single().name)
        assertEquals("Group 1", engine.getObjects().single().children!![0].name)
        assertEquals(3, renderObjects(engine).size)

        assertTrue(engine.undo())
        assertEquals(2, engine.getObjects().size)
        assertEquals("Group 1", engine.getObjects()[0].name)
        assertEquals(3, renderObjects(engine).size)
        assertTrue(engine.redo())
        assertEquals("Group 2", engine.getObjects().single().name)
        assertEquals(3, renderObjects(engine).size)
    }

    @Test
    fun loadedGroupNumbersAreNotReused() = withEngine { engine ->
        val model = engine.createShape("box")!!
        engine.clearObjects()
        engine.addExistingObject(PolygonEngine.SceneObject(
            name = "Group 7",
            type = "group",
            vertices = floatArrayOf(),
            vertexCount = 0,
            children = listOf(model),
            selected = true
        ))
        engine.createShape("sphere")!!.selected = true
        engine.groupObjects()
        assertEquals("Group 8", engine.getObjects().single().name)
        assertEquals(2, renderObjects(engine).size)
    }
}
