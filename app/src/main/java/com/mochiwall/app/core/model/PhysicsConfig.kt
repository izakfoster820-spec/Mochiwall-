package com.mochiwall.app.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class DerivedPhysicsParams(
    val kStiff: Float,
    val kCouple: Float,
    val zeta: Float,
    val cDamp: Float,
    val sigmaRadius: Float,
    val sigmaTap: Float,
    val alphaShake: Float,
    val kappaGyro: Float,
    val pokeDepth: Float,
    val iPoke: Float,
    val kGrab: Float,
    val cGrab: Float
)

data class PhysicsConfig(
    val preset: MochiPreset = MochiPreset.MOCHI,
    val softness: Float = MochiPreset.MOCHI.softness,
    val wobble: Float = MochiPreset.MOCHI.wobble,
    val pullRadius: Float = MochiPreset.MOCHI.pullRadius,
    val shakeForce: Float = MochiPreset.MOCHI.shakeForce,
    val touchEnabled: Boolean = true,
    val motionEnabled: Boolean = true,
    val wakeWobbleEnabled: Boolean = true,
    val sheenEnabled: Boolean = true
) {
    fun sanitized(): PhysicsConfig {
        return copy(
            softness = sanitizeSlider(softness, MochiPreset.MOCHI.softness),
            wobble = sanitizeSlider(wobble, MochiPreset.MOCHI.wobble),
            pullRadius = sanitizeSlider(pullRadius, MochiPreset.MOCHI.pullRadius),
            shakeForce = sanitizeSlider(shakeForce, MochiPreset.MOCHI.shakeForce)
        )
    }

    fun deriveParams(): DerivedPhysicsParams {
        val clean = sanitized()
        val s = clean.softness.toDouble()
        val b = clean.wobble.toDouble()
        val r = clean.pullRadius.toDouble()
        val f = clean.shakeForce.toDouble()

        val kStiff = 340.0 * (95.0 / 340.0).pow(s)
        val kCouple = 1875.0 * (4.0 / 9.0).pow(s)
        val zeta = 0.55 - 0.43 * b
        val sqrtKStiff = sqrt(kStiff)
        val cDamp = 2.0 * zeta * sqrtKStiff

        val sigmaRadius = 0.08 + 0.20 * r
        val sigmaTap = 0.65 * sigmaRadius

        val fPow = f.pow(1.5)
        val alphaShake = 0.045 * fPow
        val kappaGyro = 0.04 * fPow

        val pokeDepth = computePokeDepth(clean)
        val iPoke = pokeDepth * sqrtKStiff
        val kGrab = 2.5 * kStiff
        val cGrab = max(0.0, 1.80 * sqrt(kStiff + kGrab) - cDamp)

        return DerivedPhysicsParams(
            kStiff = kStiff.toFloat(),
            kCouple = kCouple.toFloat(),
            zeta = zeta.toFloat(),
            cDamp = cDamp.toFloat(),
            sigmaRadius = sigmaRadius.toFloat(),
            sigmaTap = sigmaTap.toFloat(),
            alphaShake = alphaShake.toFloat(),
            kappaGyro = kappaGyro.toFloat(),
            pokeDepth = pokeDepth.toFloat(),
            iPoke = iPoke.toFloat(),
            kGrab = kGrab.toFloat(),
            cGrab = cGrab.toFloat()
        )
    }

    companion object {
        private const val PRESET_EPS = 1e-5f

        private fun sanitizeSlider(value: Float, fallback: Float): Float {
            return if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
        }

        /**
         * Returns exact preset pokeDepth when sliders match the active preset,
         * and piecewise-linearly interpolates through the 3 Blueprint anchor points
         * (0.05 -> 0.048, 0.50 -> 0.086, 0.85 -> 0.135) for custom slider values.
         */
        private fun computePokeDepth(config: PhysicsConfig): Double {
            val p = config.preset
            if (abs(config.softness - p.softness) < PRESET_EPS &&
                abs(config.wobble - p.wobble) < PRESET_EPS &&
                abs(config.pullRadius - p.pullRadius) < PRESET_EPS &&
                abs(config.shakeForce - p.shakeForce) < PRESET_EPS
            ) {
                return p.pokeDepth.toDouble()
            }

            val s = config.softness.toDouble()
            return if (s <= 0.50) {
                val slopeLow = (0.086 - 0.048) / (0.50 - 0.05)
                (0.048 + slopeLow * (s - 0.05)).coerceAtLeast(0.03)
            } else {
                val slopeHigh = (0.135 - 0.086) / (0.85 - 0.50)
                (0.086 + slopeHigh * (s - 0.50)).coerceAtMost(0.18)
            }
        }
    }
}
