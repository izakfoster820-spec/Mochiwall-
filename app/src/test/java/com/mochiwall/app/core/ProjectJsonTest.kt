package com.mochiwall.app.core

import com.mochiwall.app.core.model.MochiPreset
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.model.ProjectJsonCodec
import com.mochiwall.app.core.model.ProjectMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonTest {

    @Test
    fun roundTrip_preservesAllMetadataAndConfigFieldsAndExactJsonKeys() {
        val original = ProjectMetadata(
            contentVersion = 3,
            imageWidth = 1256,
            imageHeight = 2808,
            config = PhysicsConfig(
                preset = MochiPreset.SOFT_JELLY,
                softness = 0.85f,
                wobble = 0.88f,
                pullRadius = 0.65f,
                shakeForce = 0.80f,
                touchEnabled = true,
                motionEnabled = false,
                wakeWobbleEnabled = false,
                sheenEnabled = true
            )
        )

        val encoded = ProjectJsonCodec.encode(original)
        val json = JSONObject(encoded)

        // Verify literal Blueprint Section 6.1 keys
        assertEquals(3, json.getInt("contentVersion"))
        assertEquals(1256, json.getInt("imageWidth"))
        assertEquals(2808, json.getInt("imageHeight"))

        val cfg = json.getJSONObject("config")
        assertEquals("SOFT_JELLY", cfg.getString("preset"))
        assertEquals(0.85, cfg.getDouble("softness"), 1e-5)
        assertEquals(0.88, cfg.getDouble("wobble"), 1e-5)
        assertEquals(0.65, cfg.getDouble("pullRadius"), 1e-5)
        assertEquals(0.80, cfg.getDouble("shakeForce"), 1e-5)
        assertTrue(cfg.getBoolean("touchEnabled"))
        assertFalse(cfg.getBoolean("motionEnabled"))
        assertFalse(cfg.getBoolean("wakeWobbleEnabled"))
        assertTrue(cfg.getBoolean("sheenEnabled"))

        val decoded = ProjectJsonCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_handlesCorruptInvalidAndOutOfRangeInputsGracefully() {
        assertNull(ProjectJsonCodec.decode(""))
        assertNull(ProjectJsonCodec.decode("{not valid json"))
        assertNull(ProjectJsonCodec.decode("""{"contentVersion": 0, "imageWidth": 100, "imageHeight": 200}"""))
        assertNull(ProjectJsonCodec.decode("""{"contentVersion": -2, "imageWidth": 100, "imageHeight": 200}"""))
        assertNull(ProjectJsonCodec.decode("""{"contentVersion": 1, "imageWidth": 0, "imageHeight": 200}"""))
        assertNull(ProjectJsonCodec.decode("""{"contentVersion": 1, "imageWidth": 100, "imageHeight": -5}"""))

        // Missing optional fields, unknown preset, and out-of-range sliders clamp cleanly
        val messyJson = """
            {
              "contentVersion": 7,
              "imageWidth": 1080,
              "imageHeight": 2400,
              "config": {
                "preset": "UNKNOWN_PRESET",
                "softness": -5.0,
                "wobble": 99.0,
                "touchEnabled": false
              }
            }
        """.trimIndent()

        val decoded = ProjectJsonCodec.decode(messyJson)
        assertNotNull(decoded)
        decoded!!
        assertEquals(7, decoded.contentVersion)
        assertEquals(MochiPreset.MOCHI, decoded.config.preset)
        assertEquals(0f, decoded.config.softness, 0f)
        assertEquals(1f, decoded.config.wobble, 0f)
        assertEquals(MochiPreset.MOCHI.pullRadius, decoded.config.pullRadius, 1e-6f)
        assertEquals(MochiPreset.MOCHI.shakeForce, decoded.config.shakeForce, 1e-6f)
        assertFalse(decoded.config.touchEnabled)
        assertTrue(decoded.config.motionEnabled)
        assertTrue(decoded.config.wakeWobbleEnabled)
        assertTrue(decoded.config.sheenEnabled)
    }

    @Test
    fun derivedParams_matchBlueprintSection46Table() {
        val mochi = MochiPreset.MOCHI.toDefaultConfig().deriveParams()
        assertEquals(179.72f, mochi.kStiff, 0.1f)
        assertEquals(1250.0f, mochi.kCouple, 0.1f)
        assertEquals(6.68f, mochi.cDamp, 0.02f)
        assertEquals(0.086f, mochi.pokeDepth, 1e-5f)

        val jelly = MochiPreset.SOFT_JELLY.toDefaultConfig().deriveParams()
        assertEquals(115.04f, jelly.kStiff, 0.1f)
        assertEquals(941.09f, jelly.kCouple, 0.1f)
        assertEquals(3.68f, jelly.cDamp, 0.02f)
        assertEquals(0.135f, jelly.pokeDepth, 1e-5f)

        val rubber = MochiPreset.FIRM_RUBBER.toDefaultConfig().deriveParams()
        assertEquals(319.01f, rubber.kStiff, 0.1f)
        assertEquals(1800.46f, rubber.kCouple, 0.1f)
        assertEquals(12.74f, rubber.cDamp, 0.02f)
        assertEquals(0.048f, rubber.pokeDepth, 1e-5f)
    }
}
