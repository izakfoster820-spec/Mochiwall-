package com.mochiwall.app.core.physics

import com.mochiwall.app.core.geometry.Vec2
import com.mochiwall.app.core.model.DerivedPhysicsParams
import com.mochiwall.app.core.model.PhysicsConfig
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

enum class DisplayRotation {
    ROTATION_0,
    ROTATION_90,
    ROTATION_180,
    ROTATION_270
}

/**
 * Timestamp-driven sensor filtering pipeline converting Android linear acceleration and gyroscope
 * readings (+X right, +Y up) into MochiWall physics forces (+X right, +Y down).
 */
class SensorFilterPipeline {

    private var accelInitialized = false
    private var lastAccelTimeNs = 0L
    private var prevAccelRawX = 0f
    private var prevAccelRawY = 0f
    private var accelHpX = 0f
    private var accelHpY = 0f
    private var accelLpX = 0f
    private var accelLpY = 0f
    private var accelFiltX = 0f
    private var accelFiltY = 0f

    private var gyroInitialized = false
    private var lastGyroTimeNs = 0L
    private var gyroLpX = 0f
    private var gyroLpY = 0f
    private var gyroFiltX = 0f
    private var gyroFiltY = 0f

    private var lastConfig: PhysicsConfig? = null
    private var cachedParams: DerivedPhysicsParams? = null

    private fun resolveParams(config: PhysicsConfig): DerivedPhysicsParams {
        val cached = cachedParams
        if (cached != null && config == lastConfig) {
            return cached
        }
        val derived = config.deriveParams()
        lastConfig = config
        cachedParams = derived
        return derived
    }

    fun onLinearAcceleration(
        sx: Float,
        sy: Float,
        timestampNs: Long,
        rotation: DisplayRotation = DisplayRotation.ROTATION_0
    ) {
        if (!sx.isFinite() || !sy.isFinite()) return

        val rx = remapX(sx, sy, rotation)
        val ry = remapY(sx, sy, rotation)

        if (!accelInitialized) {
            accelInitialized = true
            lastAccelTimeNs = timestampNs
            prevAccelRawX = rx
            prevAccelRawY = ry
            accelHpX = 0f
            accelHpY = 0f
            accelLpX = 0f
            accelLpY = 0f
            accelFiltX = 0f
            accelFiltY = 0f
            return
        }

        val dt = ((timestampNs - lastAccelTimeNs) * 1e-9).toFloat().coerceIn(MIN_DT_SEC, MAX_DT_SEC)
        lastAccelTimeNs = timestampNs

        val alphaHp = TAU_ACCEL_HP / (TAU_ACCEL_HP + dt)
        accelHpX = alphaHp * (accelHpX + rx - prevAccelRawX)
        accelHpY = alphaHp * (accelHpY + ry - prevAccelRawY)
        prevAccelRawX = rx
        prevAccelRawY = ry

        val alphaLp = dt / (TAU_ACCEL_LP + dt)
        accelLpX += alphaLp * (accelHpX - accelLpX)
        accelLpY += alphaLp * (accelHpY - accelLpY)

        accelFiltX = softDeadzoneAndTanh(accelLpX, ACCEL_DEADZONE, ACCEL_MAX)
        accelFiltY = softDeadzoneAndTanh(accelLpY, ACCEL_DEADZONE, ACCEL_MAX)
    }

    fun onGyroscope(
        wx: Float,
        wy: Float,
        timestampNs: Long,
        rotation: DisplayRotation = DisplayRotation.ROTATION_0
    ) {
        if (!wx.isFinite() || !wy.isFinite()) return

        val rwx = remapX(wx, wy, rotation)
        val rwy = remapY(wx, wy, rotation)

        if (!gyroInitialized) {
            gyroInitialized = true
            lastGyroTimeNs = timestampNs
            gyroLpX = 0f
            gyroLpY = 0f
            gyroFiltX = 0f
            gyroFiltY = 0f
            return
        }

        val dt = ((timestampNs - lastGyroTimeNs) * 1e-9).toFloat().coerceIn(MIN_DT_SEC, MAX_DT_SEC)
        lastGyroTimeNs = timestampNs

        val alphaG = dt / (TAU_GYRO_LP + dt)
        gyroLpX += alphaG * (rwx - gyroLpX)
        gyroLpY += alphaG * (rwy - gyroLpY)

        gyroFiltX = softDeadzoneAndTanh(gyroLpX, OMEGA_DEADZONE, GYRO_MAX)
        gyroFiltY = softDeadzoneAndTanh(gyroLpY, OMEGA_DEADZONE, GYRO_MAX)
    }

    fun computeForceX(config: PhysicsConfig): Float {
        if (!config.motionEnabled) return 0f
        val params = resolveParams(config)
        val fAccelX = -params.alphaShake * accelFiltX
        val fGyroX = GYRO_SIGN_X * params.kappaGyro * gyroFiltY
        return fAccelX + fGyroX
    }

    fun computeForceY(config: PhysicsConfig): Float {
        if (!config.motionEnabled) return 0f
        val params = resolveParams(config)
        val fAccelY = params.alphaShake * accelFiltY
        val fGyroY = GYRO_SIGN_Y * params.kappaGyro * gyroFiltX
        return fAccelY + fGyroY
    }

    fun computeForce(config: PhysicsConfig): Vec2 {
        return Vec2(computeForceX(config), computeForceY(config))
    }

    fun hasActiveForce(config: PhysicsConfig): Boolean {
        if (!config.motionEnabled) return false
        val fx = computeForceX(config)
        val fy = computeForceY(config)
        return (fx * fx + fy * fy) > 0f
    }

    fun reset() {
        accelInitialized = false
        lastAccelTimeNs = 0L
        prevAccelRawX = 0f
        prevAccelRawY = 0f
        accelHpX = 0f
        accelHpY = 0f
        accelLpX = 0f
        accelLpY = 0f
        accelFiltX = 0f
        accelFiltY = 0f

        gyroInitialized = false
        lastGyroTimeNs = 0L
        gyroLpX = 0f
        gyroLpY = 0f
        gyroFiltX = 0f
        gyroFiltY = 0f
    }

    private fun remapX(x: Float, y: Float, rotation: DisplayRotation): Float {
        return when (rotation) {
            DisplayRotation.ROTATION_0 -> x
            DisplayRotation.ROTATION_90 -> -y
            DisplayRotation.ROTATION_180 -> -x
            DisplayRotation.ROTATION_270 -> y
        }
    }

    private fun remapY(x: Float, y: Float, rotation: DisplayRotation): Float {
        return when (rotation) {
            DisplayRotation.ROTATION_0 -> y
            DisplayRotation.ROTATION_90 -> x
            DisplayRotation.ROTATION_180 -> -y
            DisplayRotation.ROTATION_270 -> -x
        }
    }

    private fun softDeadzoneAndTanh(value: Float, deadzone: Float, maxValue: Float): Float {
        val mag = abs(value)
        if (mag <= deadzone) return 0f
        val shifted = sign(value) * (mag - deadzone)
        return (maxValue * tanh((shifted / maxValue).toDouble())).toFloat()
    }

    companion object {
        const val GYRO_SIGN_X = 1f
        const val GYRO_SIGN_Y = 1f

        const val MIN_DT_SEC = 0.001f
        const val MAX_DT_SEC = 0.100f

        const val TAU_ACCEL_HP = 0.32f
        const val TAU_ACCEL_LP = 0.010f
        const val ACCEL_DEADZONE = 0.25f
        const val ACCEL_MAX = 18.0f

        const val TAU_GYRO_LP = 0.045f
        const val OMEGA_DEADZONE = 0.08f
        const val GYRO_DEADZONE = OMEGA_DEADZONE
        const val GYRO_MAX = 6.0f
    }
}
