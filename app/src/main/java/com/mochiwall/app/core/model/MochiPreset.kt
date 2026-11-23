package com.mochiwall.app.core.model

enum class MochiPreset(
    val softness: Float,
    val wobble: Float,
    val pullRadius: Float,
    val shakeForce: Float,
    val pokeDepth: Float
) {
    MOCHI(
        softness = 0.50f,
        wobble = 0.70f,
        pullRadius = 0.40f,
        shakeForce = 0.62f,
        pokeDepth = 0.086f
    ),
    SOFT_JELLY(
        softness = 0.85f,
        wobble = 0.88f,
        pullRadius = 0.65f,
        shakeForce = 0.80f,
        pokeDepth = 0.135f
    ),
    FIRM_RUBBER(
        softness = 0.05f,
        wobble = 0.45f,
        pullRadius = 0.20f,
        shakeForce = 0.46f,
        pokeDepth = 0.048f
    );

    fun toDefaultConfig(): PhysicsConfig {
        return PhysicsConfig(
            preset = this,
            softness = softness,
            wobble = wobble,
            pullRadius = pullRadius,
            shakeForce = shakeForce
        )
    }

    companion object {
        fun fromName(name: String?): MochiPreset {
            val normalized = name?.trim() ?: return MOCHI
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: MOCHI
        }
    }
}
