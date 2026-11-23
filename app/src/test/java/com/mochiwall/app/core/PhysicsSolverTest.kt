package com.mochiwall.app.core

import com.mochiwall.app.core.mask.MaskBrushRasterizer
import com.mochiwall.app.core.mask.MaskDownsampler
import com.mochiwall.app.core.model.MochiPreset
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.physics.MochiPhysicsSolver
import com.mochiwall.app.core.physics.TouchAction
import com.mochiwall.app.core.physics.TouchPhase
import com.mochiwall.app.core.physics.TouchStateMachine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsSolverTest {

    private fun createFullInteriorMask(): FloatArray {
        val raw = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT) { 0xFF.toByte() }
        return MaskDownsampler.downsample(raw).weights
    }

    @Test
    fun tenThousandSubsteps_worstCaseStiffness_zeroNaNAndZeroInvertedTriangles() {
        val solver = MochiPhysicsSolver(2808f / 1256f)
        solver.setMask(createFullInteriorMask(), hasActive = true)

        val worstCaseConfig = PhysicsConfig(
            preset = MochiPreset.FIRM_RUBBER,
            softness = 0.0f,
            wobble = 1.0f,
            pullRadius = 1.0f,
            shakeForce = 1.0f
        )

        solver.updateTetherWeights(0.5f, 0.5f * solver.aspectRatio, worstCaseConfig.deriveParams().sigmaRadius)

        for (step in 0 until 10_000) {
            if (step % 100 == 0) {
                val tapX = 0.25f + 0.5f * ((step / 100) % 3) * 0.5f
                val tapY = (0.3f + 0.4f * ((step / 200) % 2)) * solver.aspectRatio
                solver.applyPoke(tapX, tapY, worstCaseConfig)
            }

            val dragging = (step % 250) < 120
            val dragDx = if (dragging) 0.35f * if ((step / 250) % 2 == 0) 1f else -1f else 0f
            val dragDy = if (dragging) 0.20f * if ((step / 125) % 2 == 0) 1f else -1f else 0f
            val sensorFx = if (step % 40 < 20) 1.5f else -1.5f
            val sensorFy = if (step % 60 < 30) 1.5f else -1.5f

            solver.stepSubstep(
                config = worstCaseConfig,
                touchPhase = if (dragging) TouchPhase.DRAGGING else TouchPhase.NONE,
                dragDx = dragDx,
                dragDy = dragDy,
                betaTether = if (dragging) 1.0f else 0.0f,
                sensorFx = sensorFx,
                sensorFy = sensorFy
            )

            // Periodically verify all 5,530 triangles during the stress test as well
            if (step % 500 == 499) {
                assertAllTrianglesPositiveAndFinite(solver, step)
            }
        }

        assertAllTrianglesPositiveAndFinite(solver, 10_000)
    }

    @Test
    fun renderInterpolation_lerpsBetweenPrevUAndCurrU() {
        val solver = MochiPhysicsSolver(2808f / 1256f)
        solver.setMask(createFullInteriorMask(), hasActive = true)
        val config = MochiPreset.MOCHI.toDefaultConfig()
        val touch = TouchStateMachine()

        solver.applyPoke(0.5f, 0.5f * solver.aspectRatio, config)

        // Frame 0 wakes with accumulator = FIXED_DT and renderAlpha = 1.0
        val t0 = 1_000_000_000L
        assertTrue(solver.stepFrame(t0, config, touch, 0f, 0f))

        // Frame 1 advances by 8.333 ms (120 Hz): accumulator = 1.5 * FIXED_DT -> 1 substep, renderAlpha ≈ 0.5
        val t1 = t0 + 8_333_333L
        assertTrue(solver.stepFrame(t1, config, touch, 0f, 0f))
        assertTrue("Expected renderAlpha strictly in (0, 1), got ${solver.renderAlpha}", solver.renderAlpha in 0.4f..0.6f)

        val centerIdx = 40 * MochiPhysicsSolver.NX + 18
        val expectedUx = solver.prevUx[centerIdx] + solver.renderAlpha * (solver.ux[centerIdx] - solver.prevUx[centerIdx])
        val expectedUy = solver.prevUy[centerIdx] + solver.renderAlpha * (solver.uy[centerIdx] - solver.prevUy[centerIdx])

        assertEquals(expectedUx, solver.interpolatedUx(centerIdx), 1e-6f)
        assertEquals(expectedUy, solver.interpolatedUy(centerIdx), 1e-6f)
    }

    @Test
    fun wakeFromIdle_120HzAnd90Hz_progressesMonotonicallyWithoutSnapBack() {
        val config = MochiPreset.MOCHI.toDefaultConfig()
        val mask = createFullInteriorMask()
        val centerIdx = 40 * MochiPhysicsSolver.NX + 18
        val belowCenterIdx = 41 * MochiPhysicsSolver.NX + 18

        val frameIntervalsNs = listOf(
            8_333_333L,  // 120 Hz
            11_111_111L  // 90 Hz
        )

        for (dtNs in frameIntervalsNs) {
            // Case A: applyWakeWobble from IDLE
            val wobbleSolver = MochiPhysicsSolver(2808f / 1256f)
            wobbleSolver.setMask(mask, hasActive = true)
            val touchA = TouchStateMachine()

            wobbleSolver.applyWakeWobble(config)
            val t0 = 1_000_000_000L
            assertTrue(wobbleSolver.stepFrame(t0, config, touchA, 0f, 0f))
            val y0 = wobbleSolver.interpolatedUy(centerIdx)

            assertTrue(wobbleSolver.stepFrame(t0 + dtNs, config, touchA, 0f, 0f))
            val y1 = wobbleSolver.interpolatedUy(centerIdx)

            assertTrue(wobbleSolver.stepFrame(t0 + 2L * dtNs, config, touchA, 0f, 0f))
            val y2 = wobbleSolver.interpolatedUy(centerIdx)

            assertTrue("WakeWobble (dt=$dtNs): Frame 0 y0=$y0 must be > 0", y0 > 0f)
            assertTrue("WakeWobble (dt=$dtNs): Frame 1 y1=$y1 must advance past y0=$y0", y1 > y0)
            assertTrue("WakeWobble (dt=$dtNs): Frame 2 y2=$y2 must advance past y1=$y1", y2 > y1)

            // Case B: applyPoke from IDLE
            val pokeSolver = MochiPhysicsSolver(2808f / 1256f)
            pokeSolver.setMask(mask, hasActive = true)
            val touchB = TouchStateMachine()

            pokeSolver.applyPoke(0.5f, 0.5f * pokeSolver.aspectRatio, config)
            assertTrue(pokeSolver.stepFrame(t0, config, touchB, 0f, 0f))
            val py0 = pokeSolver.interpolatedUy(belowCenterIdx)

            assertTrue(pokeSolver.stepFrame(t0 + dtNs, config, touchB, 0f, 0f))
            val py1 = pokeSolver.interpolatedUy(belowCenterIdx)

            assertTrue(pokeSolver.stepFrame(t0 + 2L * dtNs, config, touchB, 0f, 0f))
            val py2 = pokeSolver.interpolatedUy(belowCenterIdx)

            assertTrue("Poke (dt=$dtNs): Frame 0 py0=$py0 must be > 0", py0 > 0f)
            assertTrue("Poke (dt=$dtNs): Frame 1 py1=$py1 must advance past py0=$py0", py1 > py0)
            assertTrue("Poke (dt=$dtNs): Frame 2 py2=$py2 must advance past py1=$py1", py2 > py1)
        }
    }

    @Test
    fun duplicateOrNonMonotonicTimestamp_doesNotAdvancePhysicsOrMutateState() {
        val solver = MochiPhysicsSolver(2808f / 1256f)
        solver.setMask(createFullInteriorMask(), hasActive = true)
        val config = MochiPreset.MOCHI.toDefaultConfig()
        val touch = TouchStateMachine()

        solver.applyWakeWobble(config)
        val t0 = 2_000_000_000L
        val t1 = t0 + 8_333_333L
        assertTrue(solver.stepFrame(t0, config, touch, 0f, 0f))
        assertTrue(solver.stepFrame(t1, config, touch, 0f, 0f))

        val savedAlpha = solver.renderAlpha
        val savedUx = solver.ux.copyOf()
        val savedUy = solver.uy.copyOf()
        val savedPrevUx = solver.prevUx.copyOf()
        val savedPrevUy = solver.prevUy.copyOf()
        val savedVx = solver.vx.copyOf()
        val savedVy = solver.vy.copyOf()

        // Duplicate timestamp
        assertTrue(solver.stepFrame(t1, config, touch, 0f, 0f))
        assertEquals(savedAlpha, solver.renderAlpha, 0f)
        assertArrayEquals(savedUx, solver.ux, 0f)
        assertArrayEquals(savedUy, solver.uy, 0f)
        assertArrayEquals(savedPrevUx, solver.prevUx, 0f)
        assertArrayEquals(savedPrevUy, solver.prevUy, 0f)
        assertArrayEquals(savedVx, solver.vx, 0f)
        assertArrayEquals(savedVy, solver.vy, 0f)

        // Backwards timestamp
        assertTrue(solver.stepFrame(t1 - 5_000_000L, config, touch, 0f, 0f))
        assertEquals(savedAlpha, solver.renderAlpha, 0f)
        assertArrayEquals(savedUx, solver.ux, 0f)
        assertArrayEquals(savedUy, solver.uy, 0f)
        assertArrayEquals(savedPrevUx, solver.prevUx, 0f)
        assertArrayEquals(savedPrevUy, solver.prevUy, 0f)
        assertArrayEquals(savedVx, solver.vx, 0f)
        assertArrayEquals(savedVy, solver.vy, 0f)
    }

    @Test
    fun idleSleepReset_snapsAllBuffersToZeroAndSetsRenderAlphaOne() {
        val solver = MochiPhysicsSolver(2808f / 1256f)
        solver.setMask(createFullInteriorMask(), hasActive = true)
        val dampedConfig = PhysicsConfig(
            preset = MochiPreset.FIRM_RUBBER,
            softness = 0.05f,
            wobble = 0.0f // High damping so it settles quickly
        )
        val touch = TouchStateMachine()

        // Inject small poke via touch machine
        touch.onTouchEvent(TouchAction.DOWN, 0, 500f, 1000f, 0.5f, 0.5f * solver.aspectRatio, 100L, 24f)
        touch.onTouchEvent(TouchAction.UP, 0, 500f, 1000f, 0.5f, 0.5f * solver.aspectRatio, 150L, 24f)

        var timeNs = 1_000_000_000L
        var settled = false
        for (frame in 0 until 600) {
            val active = solver.stepFrame(timeNs, dampedConfig, touch, 0f, 0f)
            timeNs += 16_666_667L
            if (!active) {
                settled = true
                break
            }
        }

        assertTrue("Solver should settle into IDLE within 10 seconds", settled)
        assertTrue(solver.isIdle)
        assertEquals(1f, solver.renderAlpha, 0f)

        for (i in 0 until MochiPhysicsSolver.VERTEX_COUNT) {
            assertEquals(0f, solver.ux[i], 0f)
            assertEquals(0f, solver.uy[i], 0f)
            assertEquals(0f, solver.prevUx[i], 0f)
            assertEquals(0f, solver.prevUy[i], 0f)
            assertEquals(0f, solver.vx[i], 0f)
            assertEquals(0f, solver.vy[i], 0f)
        }
    }

    @Test
    fun applyWakeWobble_injectsDownwardDiagonalVelocityAndKeepsPerimeterPinned() {
        val solver = MochiPhysicsSolver(2808f / 1256f)
        solver.setMask(createFullInteriorMask(), hasActive = true)
        val config = MochiPreset.MOCHI.toDefaultConfig()

        solver.applyWakeWobble(config)
        assertFalse(solver.isIdle)

        val centerIdx = 40 * MochiPhysicsSolver.NX + 18
        assertTrue("Expected positive +X velocity", solver.vx[centerIdx] > 0f)
        assertTrue("Expected positive +Y velocity", solver.vy[centerIdx] > 0f)
        assertTrue("Expected downward component larger than horizontal", solver.vy[centerIdx] > solver.vx[centerIdx])

        // Perimeter vertices must remain strictly 0
        for (j in 0 until MochiPhysicsSolver.NY) {
            for (i in 0 until MochiPhysicsSolver.NX) {
                if (i == 0 || i == MochiPhysicsSolver.NX - 1 || j == 0 || j == MochiPhysicsSolver.NY - 1) {
                    val idx = j * MochiPhysicsSolver.NX + i
                    assertEquals(0f, solver.vx[idx], 0f)
                    assertEquals(0f, solver.vy[idx], 0f)
                }
            }
        }
    }

    private fun assertAllTrianglesPositiveAndFinite(solver: MochiPhysicsSolver, step: Int) {
        for (i in 0 until MochiPhysicsSolver.VERTEX_COUNT) {
            assertTrue("ux[$i] non-finite at step $step", solver.ux[i].isFinite())
            assertTrue("uy[$i] non-finite at step $step", solver.uy[i].isFinite())
            assertTrue("prevUx[$i] non-finite at step $step", solver.prevUx[i].isFinite())
            assertTrue("prevUy[$i] non-finite at step $step", solver.prevUy[i].isFinite())
            assertTrue("vx[$i] non-finite at step $step", solver.vx[i].isFinite())
            assertTrue("vy[$i] non-finite at step $step", solver.vy[i].isFinite())
        }

        var checkedTriangles = 0
        for (j in 0 until MochiPhysicsSolver.NY - 1) {
            for (i in 0 until MochiPhysicsSolver.NX - 1) {
                val tl = j * MochiPhysicsSolver.NX + i
                val tr = tl + 1
                val bl = tl + MochiPhysicsSolver.NX
                val br = bl + 1

                val area1 = signedTwiceArea(solver, tl, tr, br)
                val area2 = signedTwiceArea(solver, tl, br, bl)

                assertTrue("Triangle 1 inverted at quad ($i,$j) step $step: 2A=$area1", area1 > 0f)
                assertTrue("Triangle 2 inverted at quad ($i,$j) step $step: 2A=$area2", area2 > 0f)
                checkedTriangles += 2
            }
        }
        assertEquals(MochiPhysicsSolver.TRIANGLE_COUNT, checkedTriangles)
    }

    private fun signedTwiceArea(solver: MochiPhysicsSolver, a: Int, b: Int, c: Int): Float {
        val ax = solver.p0x[a] + solver.ux[a]
        val ay = solver.p0y[a] + solver.uy[a]
        val bx = solver.p0x[b] + solver.ux[b]
        val by = solver.p0y[b] + solver.uy[b]
        val cx = solver.p0x[c] + solver.ux[c]
        val cy = solver.p0y[c] + solver.uy[c]
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    }
}
