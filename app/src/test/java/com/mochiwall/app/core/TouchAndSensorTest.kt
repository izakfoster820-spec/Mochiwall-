package com.mochiwall.app.core

import com.mochiwall.app.core.model.MochiPreset
import com.mochiwall.app.core.physics.DisplayRotation
import com.mochiwall.app.core.physics.SensorFilterPipeline
import com.mochiwall.app.core.physics.TouchAction
import com.mochiwall.app.core.physics.TouchPhase
import com.mochiwall.app.core.physics.TouchStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class TouchAndSensorTest {

    @Test
    fun touchDown_doesNotFirePoke_fastUpFiresSingleConsumptionPoke() {
        val touch = TouchStateMachine()

        touch.onTouchEvent(TouchAction.DOWN, 0, 100f, 200f, 0.4f, 0.9f, 1_000L, 24f)
        assertEquals(TouchPhase.ARMED_TAP, touch.phase)
        assertEquals(0f, touch.betaTether, 0f)
        assertNull("ACTION_DOWN must not fire poke", touch.consumePendingPoke())

        // Fast UP within 300 ms inside touch slop fires poke at physDown
        touch.onTouchEvent(TouchAction.UP, 0, 105f, 203f, 0.41f, 0.91f, 1_120L, 24f)
        assertEquals(TouchPhase.NONE, touch.phase)

        val poke = touch.consumePendingPoke()
        assertNotNull(poke)
        assertEquals(0.4f, poke!!.physX, 1e-6f)
        assertEquals(0.9f, poke.physY, 1e-6f)
        assertNull("PendingPoke must be single-consumption", touch.consumePendingPoke())
    }

    @Test
    fun touchMovePastSlop_entersDraggingWithZeroSnapAndCancelResetsTether() {
        val touch = TouchStateMachine()

        touch.onTouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0.5f, 0.5f, 2_000L, 20f)
        // Secondary pointer must be ignored
        touch.onTouchEvent(TouchAction.DOWN, 2, 300f, 300f, 0.8f, 0.8f, 2_010L, 20f)
        assertEquals(TouchPhase.ARMED_TAP, touch.phase)

        // Move past slop -> DRAGGING with anchor locked at slop-exit (zero snap)
        touch.onTouchEvent(TouchAction.MOVE, 1, 130f, 100f, 0.55f, 0.50f, 2_030L, 20f)
        assertEquals(TouchPhase.DRAGGING, touch.phase)
        assertTrue(touch.consumeAnchorChanged())
        assertEquals(0.55f, touch.anchorPhysX, 1e-6f)
        assertEquals(0.50f, touch.anchorPhysY, 1e-6f)
        assertEquals(0f, touch.dragDx, 1e-6f)
        assertEquals(0f, touch.dragDy, 1e-6f)

        // Ramp betaTether over 40 ms
        touch.advanceRamp(0.020f)
        assertEquals(0.5f, touch.betaTether, 1e-5f)
        touch.advanceRamp(0.020f)
        assertEquals(1.0f, touch.betaTether, 1e-5f)

        // Drag vector clamped to D_max = 0.35 gu
        touch.onTouchEvent(TouchAction.MOVE, 1, 800f, 800f, 1.55f, 1.50f, 2_060L, 20f)
        assertEquals(TouchStateMachine.MAX_DRAG_DISTANCE_GU, hypot(touch.dragDx, touch.dragDy), 1e-5f)

        // CANCEL immediately clears betaTether for elastic rebound
        touch.onTouchEvent(TouchAction.CANCEL, 1, 800f, 800f, 1.55f, 1.50f, 2_100L, 20f)
        assertEquals(TouchPhase.NONE, touch.phase)
        assertEquals(0f, touch.betaTether, 0f)
    }

    @Test
    fun touchCancelWithDifferentPointerId_cancelsActiveDragAndResetsBetaTether() {
        val touch = TouchStateMachine()

        touch.onTouchEvent(TouchAction.DOWN, 1, 100f, 100f, 0.5f, 0.5f, 3_000L, 20f)
        touch.onTouchEvent(TouchAction.MOVE, 1, 150f, 100f, 0.6f, 0.5f, 3_020L, 20f)
        touch.advanceRamp(0.050f)
        assertEquals(TouchPhase.DRAGGING, touch.phase)
        assertEquals(1.0f, touch.betaTether, 1e-6f)

        // OS/launcher ACTION_CANCEL delivered with a different pointerId must still cancel
        touch.onTouchEvent(TouchAction.CANCEL, 99, 0f, 0f, 0f, 0f, 3_050L, 20f)
        assertEquals(TouchPhase.NONE, touch.phase)
        assertEquals(0f, touch.betaTether, 0f)
        assertEquals(0f, touch.dragDx, 0f)
        assertEquals(0f, touch.dragDy, 0f)
    }

    @Test
    fun commandTap_firesWhenStandaloneAndDeduplicatesWithin250Ms() {
        val touch = TouchStateMachine()

        // Standalone COMMAND_TAP succeeds
        assertTrue(touch.enqueueCommandTap(0.5f, 1.0f, 5_000L))
        val standalone = touch.consumePendingPoke()
        assertNotNull(standalone)
        assertEquals(0.5f, standalone!!.physX, 1e-6f)

        // Touch poke at t = 6000 ms deduplicates COMMAND_TAP within 250 ms
        touch.onTouchEvent(TouchAction.DOWN, 0, 100f, 100f, 0.3f, 0.3f, 5_950L, 24f)
        touch.onTouchEvent(TouchAction.UP, 0, 100f, 100f, 0.3f, 0.3f, 6_000L, 24f)
        assertNotNull(touch.consumePendingPoke())

        assertFalse(touch.enqueueCommandTap(0.3f, 0.3f, 6_200L))
        assertNull(touch.consumePendingPoke())

        // After 250 ms cooldown, COMMAND_TAP works again
        assertTrue(touch.enqueueCommandTap(0.3f, 0.3f, 6_250L))
        assertNotNull(touch.consumePendingPoke())

        // Drag end at t = 7100 ms deduplicates COMMAND_TAP within 250 ms
        touch.onTouchEvent(TouchAction.DOWN, 0, 100f, 100f, 0.5f, 0.5f, 7_000L, 20f)
        touch.onTouchEvent(TouchAction.MOVE, 0, 140f, 100f, 0.6f, 0.5f, 7_050L, 20f)
        assertFalse("COMMAND_TAP ignored during active drag", touch.enqueueCommandTap(0.6f, 0.5f, 7_060L))
        touch.onTouchEvent(TouchAction.UP, 0, 140f, 100f, 0.6f, 0.5f, 7_100L, 20f)

        assertFalse(touch.enqueueCommandTap(0.6f, 0.5f, 7_300L))
        assertTrue(touch.enqueueCommandTap(0.6f, 0.5f, 7_350L))
    }

    @Test
    fun sensorPipeline_firstSampleZeroSpikeAndDeadzoneSuppression() {
        val pipeline = SensorFilterPipeline()
        val config = MochiPreset.MOCHI.toDefaultConfig()

        // First sample must initialize state with zero spike even on huge input
        pipeline.onLinearAcceleration(15f, 15f, 1_000_000_000L, DisplayRotation.ROTATION_0)
        pipeline.onGyroscope(5f, 5f, 1_000_000_000L, DisplayRotation.ROTATION_0)
        val initialForce = pipeline.computeForce(config)
        assertEquals(0f, initialForce.x, 0f)
        assertEquals(0f, initialForce.y, 0f)
        assertFalse(pipeline.hasActiveForce(config))

        // Tiny noise below deadzone stays strictly 0
        pipeline.reset()
        pipeline.onLinearAcceleration(0f, 0f, 1_000_000_000L, DisplayRotation.ROTATION_0)
        pipeline.onLinearAcceleration(0.20f, -0.20f, 1_020_000_000L, DisplayRotation.ROTATION_0)
        pipeline.onGyroscope(0f, 0f, 1_000_000_000L, DisplayRotation.ROTATION_0)
        pipeline.onGyroscope(0.10f, -0.10f, 1_020_000_000L, DisplayRotation.ROTATION_0)

        val quietForce = pipeline.computeForce(config)
        assertEquals(0f, quietForce.x, 0f)
        assertEquals(0f, quietForce.y, 0f)
    }

    @Test
    fun sensorPipeline_verifiesExplicitInertialAndGyroSignsAndRotations() {
        val pipeline = SensorFilterPipeline()
        val config = MochiPreset.MOCHI.toDefaultConfig()

        // +a_x' -> -F_x, +a_y' -> +F_y (in physics +Y down)
        pipeline.onLinearAcceleration(0f, 0f, 1_000_000_000L, DisplayRotation.ROTATION_0)
        pipeline.onLinearAcceleration(6f, 6f, 1_020_000_000L, DisplayRotation.ROTATION_0)
        val accelForce = pipeline.computeForce(config)
        assertTrue("Expected +a_x to produce -F_x, got ${accelForce.x}", accelForce.x < 0f)
        assertTrue("Expected +a_y to produce +F_y, got ${accelForce.y}", accelForce.y > 0f)

        // +omega_y' -> +F_x, +omega_x' -> +F_y
        pipeline.reset()
        pipeline.onGyroscope(0f, 0f, 1_000_000_000L, DisplayRotation.ROTATION_0)
        pipeline.onGyroscope(2f, 3f, 1_050_000_000L, DisplayRotation.ROTATION_0)
        val gyroForce = pipeline.computeForce(config)
        assertTrue("Expected +omega_y to produce +F_x, got ${gyroForce.x}", gyroForce.x > 0f)
        assertTrue("Expected +omega_x to produce +F_y, got ${gyroForce.y}", gyroForce.y > 0f)

        // Verify all 4 DisplayRotation remappings on +sx = 8, sy = 0
        // ROTATION_90:  (sx', sy') = (-sy, +sx) = (0, +8) -> F_x = 0, F_y > 0
        pipeline.reset()
        pipeline.onLinearAcceleration(0f, 0f, 1_000_000_000L, DisplayRotation.ROTATION_90)
        pipeline.onLinearAcceleration(8f, 0f, 1_020_000_000L, DisplayRotation.ROTATION_90)
        val f90 = pipeline.computeForce(config)
        assertEquals(0f, f90.x, 1e-6f)
        assertTrue(f90.y > 0f)

        // ROTATION_180: (sx', sy') = (-sx, -sy) = (-8, 0) -> F_x > 0, F_y = 0
        pipeline.reset()
        pipeline.onLinearAcceleration(0f, 0f, 1_000_000_000L, DisplayRotation.ROTATION_180)
        pipeline.onLinearAcceleration(8f, 0f, 1_020_000_000L, DisplayRotation.ROTATION_180)
        val f180 = pipeline.computeForce(config)
        assertTrue(f180.x > 0f)
        assertEquals(0f, f180.y, 1e-6f)

        // ROTATION_270: (sx', sy') = (+sy, -sx) = (0, -8) -> F_x = 0, F_y < 0
        pipeline.reset()
        pipeline.onLinearAcceleration(0f, 0f, 1_000_000_000L, DisplayRotation.ROTATION_270)
        pipeline.onLinearAcceleration(8f, 0f, 1_020_000_000L, DisplayRotation.ROTATION_270)
        val f270 = pipeline.computeForce(config)
        assertEquals(0f, f270.x, 1e-6f)
        assertTrue(f270.y < 0f)

        // motionEnabled = false forces (0, 0)
        val disabledConfig = config.copy(motionEnabled = false)
        val zeroForce = pipeline.computeForce(disabledConfig)
        assertEquals(0f, zeroForce.x, 0f)
        assertEquals(0f, zeroForce.y, 0f)
        assertFalse(pipeline.hasActiveForce(disabledConfig))
    }
}
