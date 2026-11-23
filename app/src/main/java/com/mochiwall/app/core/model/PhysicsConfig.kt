package com.mochiwall.app.core.model

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class PhysicsStyle {
    MOCHI,
    JELLY;

    companion object {
        fun fromName(name: String?): PhysicsStyle {
            val normalized = name?.trim() ?: return MOCHI
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: MOCHI
        }
    }
}

data class DerivedPhysicsParams(
    val style: PhysicsStyle,
    val kStiff: Float,
    val kCouple: Float,
    val cShear: Float,
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
    val style: PhysicsStyle = PhysicsStyle.MOCHI,
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

        val kStiff = 480.0 * (90.0 / 480.0).pow(s)
        val zeta = 0.55 - 0.43 * b
        val sqrtKStiff = sqrt(kStiff)
        val cDamp = 2.0 * zeta * sqrtKStiff

        val grabRatio = 1.2 + 5.3 * s
        val kGrab = kStiff * grabRatio
        val cGrab = (1.80 * sqrt(kStiff + kGrab) - cDamp).coerceIn(0.0, 48.0)

        val baseKCouple = 1875.0 * (4.0 / 9.0).pow(s)
        val kCouple: Double
        val cShear: Float
        when (clean.style) {
            PhysicsStyle.MOCHI -> {
                kCouple = 0.04 * baseKCouple
                cShear = MOCHI_CSHEAR
            }
            PhysicsStyle.JELLY -> {
                kCouple = baseKCouple
                cShear = 0.0f
            }
        }

        val sigmaRadius = 0.08 + 0.20 * r
        val sigmaTap = 0.65 * sigmaRadius

        val fPow = f.pow(1.5)
        val alphaShake = 3.2 * fPow
        val kappaGyro = 7.5 * fPow

        val pokeDepth = computePokeDepth(clean)
        val iPoke = pokeDepth * sqrtKStiff

        return DerivedPhysicsParams(
            style = clean.style,
            kStiff = kStiff.toFloat(),
            kCouple = kCouple.toFloat(),
            cShear = cShear,
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
        const val MOCHI_CSHEAR = 4.5f

        private fun sanitizeSlider(value: Float, fallback: Float): Float {
            return if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
        }

        /**
         * Returns exact preset pokeDepth when sliders match the active preset,
         * and piecewise-linearly interpolates through the 3 V5.2 anchor points
         * (0.08 -> 0.048, 0.55 -> 0.092, 0.92 -> 0.145) for custom slider values.
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
            return if (s <= 0.55) {
                val slopeLow = (0.092 - 0.048) / (0.55 - 0.08)
                (0.048 + slopeLow * (s - 0.08)).coerceAtLeast(0.03)
            } else {
                val slopeHigh = (0.145 - 0.092) / (0.92 - 0.55)
                (0.092 + slopeHigh * (s - 0.55)).coerceAtMost(0.18)
            }
        }
    }
}
