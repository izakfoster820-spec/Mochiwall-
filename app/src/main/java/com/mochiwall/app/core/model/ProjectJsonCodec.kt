package com.mochiwall.app.core.model

import org.json.JSONObject

object ProjectJsonCodec {

    private const val KEY_CONTENT_VERSION = "contentVersion"
    private const val KEY_IMAGE_WIDTH = "imageWidth"
    private const val KEY_IMAGE_HEIGHT = "imageHeight"
    private const val KEY_CONFIG = "config"

    private const val KEY_PRESET = "preset"
    private const val KEY_SOFTNESS = "softness"
    private const val KEY_WOBBLE = "wobble"
    private const val KEY_PULL_RADIUS = "pullRadius"
    private const val KEY_SHAKE_FORCE = "shakeForce"
    private const val KEY_TOUCH_ENABLED = "touchEnabled"
    private const val KEY_MOTION_ENABLED = "motionEnabled"
    private const val KEY_WAKE_WOBBLE_ENABLED = "wakeWobbleEnabled"
    private const val KEY_SHEEN_ENABLED = "sheenEnabled"

    fun encode(metadata: ProjectMetadata): String {
        val cleanConfig = metadata.config.sanitized()
        val configObj = JSONObject().apply {
            put(KEY_PRESET, cleanConfig.preset.name)
            put(KEY_SOFTNESS, cleanConfig.softness.toDouble())
            put(KEY_WOBBLE, cleanConfig.wobble.toDouble())
            put(KEY_PULL_RADIUS, cleanConfig.pullRadius.toDouble())
            put(KEY_SHAKE_FORCE, cleanConfig.shakeForce.toDouble())
            put(KEY_TOUCH_ENABLED, cleanConfig.touchEnabled)
            put(KEY_MOTION_ENABLED, cleanConfig.motionEnabled)
            put(KEY_WAKE_WOBBLE_ENABLED, cleanConfig.wakeWobbleEnabled)
            put(KEY_SHEEN_ENABLED, cleanConfig.sheenEnabled)
        }

        val root = JSONObject().apply {
            put(KEY_CONTENT_VERSION, metadata.contentVersion)
            put(KEY_IMAGE_WIDTH, metadata.imageWidth)
            put(KEY_IMAGE_HEIGHT, metadata.imageHeight)
            put(KEY_CONFIG, configObj)
        }

        return root.toString(2)
    }

    fun decode(jsonString: String): ProjectMetadata? {
        if (jsonString.isBlank()) return null
        return try {
            val root = JSONObject(jsonString)
            if (!root.has(KEY_CONTENT_VERSION) ||
                !root.has(KEY_IMAGE_WIDTH) ||
                !root.has(KEY_IMAGE_HEIGHT)
            ) {
                return null
            }

            val contentVersion = root.optInt(KEY_CONTENT_VERSION, -1)
            val imageWidth = root.optInt(KEY_IMAGE_WIDTH, -1)
            val imageHeight = root.optInt(KEY_IMAGE_HEIGHT, -1)
            if (contentVersion <= 0 || imageWidth <= 0 || imageHeight <= 0) {
                return null
            }

            val defaults = PhysicsConfig()
            val configObj = root.optJSONObject(KEY_CONFIG)
            val config = if (configObj == null) {
                defaults
            } else {
                val presetName = if (configObj.has(KEY_PRESET) && !configObj.isNull(KEY_PRESET)) {
                    configObj.optString(KEY_PRESET, defaults.preset.name)
                } else {
                    defaults.preset.name
                }

                PhysicsConfig(
                    preset = MochiPreset.fromName(presetName),
                    softness = optFiniteFloat(configObj, KEY_SOFTNESS, defaults.softness),
                    wobble = optFiniteFloat(configObj, KEY_WOBBLE, defaults.wobble),
                    pullRadius = optFiniteFloat(configObj, KEY_PULL_RADIUS, defaults.pullRadius),
                    shakeForce = optFiniteFloat(configObj, KEY_SHAKE_FORCE, defaults.shakeForce),
                    touchEnabled = configObj.optBoolean(KEY_TOUCH_ENABLED, defaults.touchEnabled),
                    motionEnabled = configObj.optBoolean(KEY_MOTION_ENABLED, defaults.motionEnabled),
                    wakeWobbleEnabled = configObj.optBoolean(KEY_WAKE_WOBBLE_ENABLED, defaults.wakeWobbleEnabled),
                    sheenEnabled = configObj.optBoolean(KEY_SHEEN_ENABLED, defaults.sheenEnabled)
                ).sanitized()
            }

            ProjectMetadata(
                contentVersion = contentVersion,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                config = config
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun optFiniteFloat(obj: JSONObject, key: String, fallback: Float): Float {
        if (!obj.has(key) || obj.isNull(key)) return fallback
        val value = obj.optDouble(key, Double.NaN)
        return if (value.isFinite()) value.toFloat() else fallback
    }
}
