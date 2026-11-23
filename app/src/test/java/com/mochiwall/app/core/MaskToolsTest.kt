package com.mochiwall.app.core

import com.mochiwall.app.core.mask.MaskBrushRasterizer
import com.mochiwall.app.core.mask.MaskDownsampler
import com.mochiwall.app.core.mask.MaskHistoryBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskToolsTest {

    @Test
    fun brushAndEraser_paintFeatherAndClampDirtyRect() {
        val mask = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)

        val dirty = MaskBrushRasterizer.paintStrokeSegment(
            mask = mask,
            x0 = 72.5f,
            y0 = 160.5f,
            x1 = 72.5f,
            y1 = 160.5f,
            radiusPx = 10f,
            feather = 0.6f,
            isEraser = false
        )
        assertNotNull(dirty)
        dirty!!
        assertTrue(dirty.left in 0..143 && dirty.right in dirty.left..143)
        assertTrue(dirty.top in 0..319 && dirty.bottom in dirty.top..319)

        val centerVal = mask[160 * 144 + 72].toInt() and 0xFF
        val midVal = mask[160 * 144 + 78].toInt() and 0xFF
        val outsideVal = mask[160 * 144 + 85].toInt() and 0xFF

        assertEquals(255, centerVal)
        assertTrue("Feathered ring should be strictly between 0 and 255", midVal in 1..254)
        assertEquals(0, outsideVal)

        // Erase the center back down to 0
        val eraseDirty = MaskBrushRasterizer.paintStrokeSegment(
            mask = mask,
            x0 = 72.5f,
            y0 = 160.5f,
            x1 = 72.5f,
            y1 = 160.5f,
            radiusPx = 6f,
            feather = 0f,
            isEraser = true
        )
        assertNotNull(eraseDirty)
        assertEquals(0, mask[160 * 144 + 72].toInt() and 0xFF)
    }

    @Test
    fun brushOutOfBounds_clampsSafelyOrReturnsNull() {
        val mask = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)

        // Completely out of bounds returns null
        val none = MaskBrushRasterizer.paintStrokeSegment(
            mask = mask,
            x0 = -50f,
            y0 = -50f,
            x1 = -20f,
            y1 = -20f,
            radiusPx = 5f,
            feather = 0.5f,
            isEraser = false
        )
        assertNull(none)

        // Partially overlapping top-left corner clamps dirty rect to [0..143] x [0..319]
        val corner = MaskBrushRasterizer.paintStrokeSegment(
            mask = mask,
            x0 = -2f,
            y0 = -2f,
            x1 = 3f,
            y1 = 3f,
            radiusPx = 6f,
            feather = 0.2f,
            isEraser = false
        )
        assertNotNull(corner)
        assertEquals(0, corner!!.left)
        assertEquals(0, corner.top)
        assertTrue(corner.right in 0..143)
        assertTrue(corner.bottom in 0..319)
    }

    @Test
    fun historyBuffer_enforcesExact15UndoStepsAndTruncatesRedo() {
        val history = MaskHistoryBuffer()
        val working = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)
        val out = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)

        // Initial state = 0
        history.initialize(working)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)

        // Commit 20 distinct strokes (values 1..20)
        for (step in 1..20) {
            working[0] = step.toByte()
            history.commit(working)
        }

        // Exact 15 undo steps (from state 20 down to state 5)
        var undoCount = 0
        while (history.undo(out)) {
            undoCount++
            assertEquals(20 - undoCount, out[0].toInt() and 0xFF)
        }
        assertEquals(15, undoCount)
        assertEquals(5, out[0].toInt() and 0xFF)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        // Redo 3 steps (5 -> 8)
        for (expected in 6..8) {
            assertTrue(history.redo(out))
            assertEquals(expected, out[0].toInt() and 0xFF)
        }

        // New commit truncates redo branch
        working[0] = 99.toByte()
        history.commit(working)
        assertFalse(history.canRedo)
        assertTrue(history.canUndo)

        // Undo returns to 8
        assertTrue(history.undo(out))
        assertEquals(8, out[0].toInt() and 0xFF)

        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun downsampler_enforcesZeroBleedRimPinningFaintNoiseRejectionTinyFallbackAndPerimeterPin() {
        // 1. Empty mask -> hasActiveVertices = false
        val emptyMask = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)
        val emptyResult = MaskDownsampler.downsample(emptyMask)
        assertFalse(emptyResult.hasActiveVertices)
        assertTrue(emptyResult.weights.all { it == 0f })

        // 2. Full mask (255 everywhere) -> perimeter strictly 0, deep interior strictly 1.0, edge faded
        val fullMask = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT) { 0xFF.toByte() }
        val fullResult = MaskDownsampler.downsample(fullMask)
        assertTrue(fullResult.hasActiveVertices)

        for (j in 0 until MaskDownsampler.MESH_NY) {
            for (i in 0 until MaskDownsampler.MESH_NX) {
                val w = fullResult.weights[j * MaskDownsampler.MESH_NX + i]
                if (i == 0 || i == MaskDownsampler.MESH_NX - 1 || j == 0 || j == MaskDownsampler.MESH_NY - 1) {
                    assertEquals("Perimeter ($i,$j) must be pinned to 0", 0f, w, 0f)
                }
            }
        }
        assertEquals(1f, fullResult.weights[40 * MaskDownsampler.MESH_NX + 18], 1e-6f)
        val nearEdge = fullResult.weights[40 * MaskDownsampler.MESH_NX + 1]
        assertTrue("Near-edge weight ($nearEdge) should be faded between 0 and 1", nearEdge > 0f && nearEdge < 1f)

        // 3. Solid interior block -> exterior & rim vertices touching unpainted neighbors are 0f;
        //    deep interior retains > 0.9f and hasActiveVertices == true
        val blockMask = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)
        val minI = 10
        val maxI = 20
        val minJ = 25
        val maxJ = 45
        for (j in minJ..maxJ) {
            for (i in minI..maxI) {
                for (dy in 0 until 4) {
                    for (dx in 0 until 4) {
                        val idx = (j * 4 + dy) * MaskBrushRasterizer.MASK_WIDTH + (i * 4 + dx)
                        blockMask[idx] = 0xFF.toByte()
                    }
                }
            }
        }
        val blockResult = MaskDownsampler.downsample(blockMask)
        assertTrue(blockResult.hasActiveVertices)

        for (j in 0 until MaskDownsampler.MESH_NY) {
            for (i in 0 until MaskDownsampler.MESH_NX) {
                val w = blockResult.weights[j * MaskDownsampler.MESH_NX + i]
                val strictlyInsideBlock = i in (minI + 1)..(maxI - 1) && j in (minJ + 1)..(maxJ - 1)
                if (strictlyInsideBlock) {
                    assertTrue("Deep interior ($i,$j) should retain high weight > 0.9f, got $w", w > 0.9f)
                } else {
                    assertEquals("Exterior and rim ($i,$j) must be pinned to 0f for zero bleed", 0f, w, 0f)
                }
            }
        }

        // 4. Faint feather noise (< 50% alpha, e.g. 120/255 ≈ 0.47) is rejected to 0f
        val faintMask = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT) { 120.toByte() }
        val faintResult = MaskDownsampler.downsample(faintMask)
        assertFalse(faintResult.hasActiveVertices)
        assertTrue(faintResult.weights.all { it == 0f })

        // 5. Tiny localized dot (single 4x4 cell) falls back cleanly without dying
        val tinyDotMask = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)
        val dotI = 18
        val dotJ = 40
        for (dy in 0 until 4) {
            for (dx in 0 until 4) {
                val idx = (dotJ * 4 + dy) * MaskBrushRasterizer.MASK_WIDTH + (dotI * 4 + dx)
                tinyDotMask[idx] = 0xFF.toByte()
            }
        }
        val tinyDotResult = MaskDownsampler.downsample(tinyDotMask)
        assertTrue("Tiny dot should activate fallback", tinyDotResult.hasActiveVertices)
        assertTrue("Tiny dot center should be active", tinyDotResult.weights[dotJ * MaskDownsampler.MESH_NX + dotI] > 0.9f)
        assertEquals(0f, tinyDotResult.weights[dotJ * MaskDownsampler.MESH_NX + (dotI + 1)], 0f)
    }
}
