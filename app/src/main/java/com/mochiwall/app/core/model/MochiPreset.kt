package com.mochiwall.app.core.model

enum class MochiPreset(
    val softness: Float,
    val wobble: Float,
    val pullRadius: Float,
    val shakeForce: Float,
    val pokeDepth: Float
) {
    MOCHI(
        softness = 0.55f,
        wobble = 0.68f,
        pullRadius = 0.45f,
        shakeForce = 0.65f,
        pokeDepth = 0.092f
    ),
    SOFT_JELLY(
        softness = 0.92f,
        wobble = 0.85f,
        pullRadius = 0.65f,
        shakeForce = 0.82f,
        pokeDepth = 0.145f
    ),
    FIRM_RUBBER(
        softness = 0.08f,
        wobble = 0.42f,
        pullRadius = 0.22f,
        shakeForce = 0.45f,
        pokeDepth = 0.048f
    );

    fun toDefaultConfig(): PhysicsConfig {
        return PhysicsConfig(
            preset = this,
            style = PhysicsStyle.MOCHI,
            softness = softness,
            wobble = wobble,
            pullRadius = pullRadius,
            shakeForce = shakeForce,
            touchEnabled = true,
            motionEnabled = true,
            wakeWobbleEnabled = true,
            sheenEnabled = true
        )
    }

    companion object {
        fun fromName(name: String?): MochiPreset {
            val normalized = name?.trim() ?: return MOCHI
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: MOCHI
        }
    }
}
