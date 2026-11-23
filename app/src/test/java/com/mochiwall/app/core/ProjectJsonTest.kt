package com.mochiwall.app.core

import com.mochiwall.app.core.model.MochiPreset
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.model.PhysicsStyle
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
    fun roundTrip_preservesAllMetadataAndConfigFieldsIncludingStyle() {
        for (style in listOf(PhysicsStyle.MOCHI, PhysicsStyle.JELLY)) {
            val original = ProjectMetadata(
                contentVersion = 3,
                imageWidth = 1256,
                imageHeight = 2808,
                config = PhysicsConfig(
                    preset = MochiPreset.SOFT_JELLY,
                    style = style,
                    softness = 0.92f,
                    wobble = 0.85f,
                    pullRadius = 0.65f,
                    shakeForce = 0.82f,
                    touchEnabled = true,
                    motionEnabled = false,
                    wakeWobbleEnabled = false,
                    sheenEnabled = true
                )
            )

            val encoded = ProjectJsonCodec.encode(original)
            val json = JSONObject(encoded)

            assertEquals(3, json.getInt("contentVersion"))
            assertEquals(1256, json.getInt("imageWidth"))
            assertEquals(2808, json.getInt("imageHeight"))

            val cfg = json.getJSONObject("config")
            assertEquals("SOFT_JELLY", cfg.getString("preset"))
            assertEquals(style.name, cfg.getString("style"))
            assertEquals(0.92, cfg.getDouble("softness"), 1e-5)
            assertEquals(0.85, cfg.getDouble("wobble"), 1e-5)
            assertEquals(0.65, cfg.getDouble("pullRadius"), 1e-5)
            assertEquals(0.82, cfg.getDouble("shakeForce"), 1e-5)
            assertTrue(cfg.getBoolean("touchEnabled"))
            assertFalse(cfg.getBoolean("motionEnabled"))
            assertFalse(cfg.getBoolean("wakeWobbleEnabled"))
            assertTrue(cfg.getBoolean("sheenEnabled"))

            val decoded = ProjectJsonCodec.decode(encoded)
            assertNotNull(decoded)
            assertEquals(original, decoded)
        }
    }

    @Test
    fun decode_legacyJsonWithoutStyleDefaultsToMochi() {
        val legacyJson = """
            {
              "contentVersion": 5,
              "imageWidth": 1256,
              "imageHeight": 2808,
              "config": {
                "preset": "MOCHI",
                "softness": 0.55,
                "wobble": 0.68,
                "pullRadius": 0.45,
                "shakeForce": 0.65,
                "touchEnabled": true,
                "motionEnabled": true,
                "wakeWobbleEnabled": true,
                "sheenEnabled": true
              }
            }
        """.trimIndent()

        val decoded = ProjectJsonCodec.decode(legacyJson)
        assertNotNull(decoded)
        assertEquals(PhysicsStyle.MOCHI, decoded!!.config.style)
    }

    @Test
    fun decode_handlesCorruptInvalidAndOutOfRangeInputsGracefully() {
        assertNull(ProjectJsonCodec.decode(""))
        assertNull(ProjectJsonCodec.decode("{not valid json"))
        assertNull(ProjectJsonCodec.decode("""{"contentVersion": 0, "imageWidth": 100, "imageHeight": 200}"""))
        assertNull(ProjectJsonCodec.decode("""{"contentVersion": -2, "imageWidth": 100, "imageHeight": 200}"""))
        assertNull(ProjectJsonCodec.decode("""{"contentVersion": 1, "imageWidth": 0, "imageHeight": 200}"""))
        assertNull(ProjectJsonCodec.decode("""{"contentVersion": 1, "imageWidth": 100, "imageHeight": -5}"""))

        // Missing optional fields, unknown preset/style, and out-of-range sliders clamp cleanly
        val messyJson = """
            {
              "contentVersion": 7,
              "imageWidth": 1080,
              "imageHeight": 2400,
              "config": {
                "preset": "UNKNOWN_PRESET",
                "style": "UNKNOWN_STYLE",
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
        assertEquals(PhysicsStyle.MOCHI, decoded.config.style)
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
    fun derivedParams_matchV52CalibratedCurveAndStyleDifferences() {
        // Verify V5.2 preset defaults
        assertEquals(0.55f, MochiPreset.MOCHI.softness, 1e-6f)
        assertEquals(0.68f, MochiPreset.MOCHI.wobble, 1e-6f)
        assertEquals(0.45f, MochiPreset.MOCHI.pullRadius, 1e-6f)
        assertEquals(0.65f, MochiPreset.MOCHI.shakeForce, 1e-6f)
        assertEquals(0.092f, MochiPreset.MOCHI.pokeDepth, 1e-6f)

        assertEquals(0.92f, MochiPreset.SOFT_JELLY.softness, 1e-6f)
        assertEquals(0.85f, MochiPreset.SOFT_JELLY.wobble, 1e-6f)
        assertEquals(0.65f, MochiPreset.SOFT_JELLY.pullRadius, 1e-6f)
        assertEquals(0.82f, MochiPreset.SOFT_JELLY.shakeForce, 1e-6f)
        assertEquals(0.145f, MochiPreset.SOFT_JELLY.pokeDepth, 1e-6f)

        assertEquals(0.08f, MochiPreset.FIRM_RUBBER.softness, 1e-6f)
        assertEquals(0.42f, MochiPreset.FIRM_RUBBER.wobble, 1e-6f)
        assertEquals(0.22f, MochiPreset.FIRM_RUBBER.pullRadius, 1e-6f)
        assertEquals(0.45f, MochiPreset.FIRM_RUBBER.shakeForce, 1e-6f)
        assertEquals(0.048f, MochiPreset.FIRM_RUBBER.pokeDepth, 1e-6f)

        // MOCHI preset in MOCHI style (default)
        val mochiSmooth = MochiPreset.MOCHI.toDefaultConfig().deriveParams()
        assertEquals(PhysicsStyle.MOCHI, mochiSmooth.style)
        assertEquals(191.1578f, mochiSmooth.kStiff, 0.05f)
        assertEquals(48.0132f, mochiSmooth.kCouple, 0.05f)
        assertEquals(4.5f, mochiSmooth.cShear, 1e-6f)
        assertEquals(7.1231f, mochiSmooth.cDamp, 0.02f)
        assertEquals(786.6142f, mochiSmooth.kGrab, 0.1f)
        assertEquals(48.0f, mochiSmooth.cGrab, 1e-4f)
        assertEquals(1.6770f, mochiSmooth.alphaShake, 0.01f)
        assertEquals(3.9304f, mochiSmooth.kappaGyro, 0.01f)
        assertEquals(0.092f, mochiSmooth.pokeDepth, 1e-5f)

        // MOCHI preset in JELLY style (full coupling, zero shear viscosity)
        val mochiJelly = MochiPreset.MOCHI.toDefaultConfig().copy(style = PhysicsStyle.JELLY).deriveParams()
        assertEquals(PhysicsStyle.JELLY, mochiJelly.style)
        assertEquals(1200.3306f, mochiJelly.kCouple, 0.1f)
        assertEquals(0.0f, mochiJelly.cShear, 1e-6f)

        // SOFT_JELLY preset
        val softJelly = MochiPreset.SOFT_JELLY.toDefaultConfig().deriveParams()
        assertEquals(102.8969f, softJelly.kStiff, 0.05f)
        assertEquals(35.5675f, softJelly.kCouple, 0.05f)
        assertEquals(4.5f, softJelly.cShear, 1e-6f)
        assertEquals(3.7431f, softJelly.cDamp, 0.02f)
        assertEquals(625.2017f, softJelly.kGrab, 0.1f)
        assertEquals(44.8269f, softJelly.cGrab, 0.05f)
        assertEquals(2.3761f, softJelly.alphaShake, 0.01f)
        assertEquals(5.5691f, softJelly.kappaGyro, 0.01f)
        assertEquals(0.145f, softJelly.pokeDepth, 1e-5f)

        // FIRM_RUBBER preset
        val firmRubber = MochiPreset.FIRM_RUBBER.toDefaultConfig().deriveParams()
        assertEquals(419.8376f, firmRubber.kStiff, 0.05f)
        assertEquals(70.2889f, firmRubber.kCouple, 0.05f)
        assertEquals(4.5f, firmRubber.cShear, 1e-6f)
        assertEquals(15.1380f, firmRubber.cDamp, 0.02f)
        assertEquals(681.8163f, firmRubber.kGrab, 0.1f)
        assertEquals(44.6061f, firmRubber.cGrab, 0.05f)
        assertEquals(0.9660f, firmRubber.alphaShake, 0.01f)
        assertEquals(2.2640f, firmRubber.kappaGyro, 0.01f)
        assertEquals(0.048f, firmRubber.pokeDepth, 1e-5f)

        // Endpoints s=0 and s=1 satisfy cGrab * dt <= 0.80
        val firmEdge = PhysicsConfig(softness = 0.0f, wobble = 1.0f).deriveParams()
        assertEquals(480.0f, firmEdge.kStiff, 0.05f)
        assertTrue(firmEdge.cGrab <= 48.0f)

        val ultraSoftEdge = PhysicsConfig(softness = 1.0f, wobble = 1.0f).deriveParams()
        assertEquals(90.0f, ultraSoftEdge.kStiff, 0.05f)
        assertEquals(90.0f * 6.5f, ultraSoftEdge.kGrab, 0.1f)
        assertTrue(ultraSoftEdge.cGrab <= 48.0f)
    }
}
