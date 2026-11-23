package com.mochiwall.app.core

import com.mochiwall.app.core.geometry.AspectFillViewport
import com.mochiwall.app.core.geometry.CoordinateMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun matchingAspectRatio_hasFullUnitViewport() {
        val mapper = CoordinateMapper.fromDimensions(1256, 2808, 1256, 2808)
        val vp = mapper.viewport

        assertEquals(0f, vp.uMin, 1e-6f)
        assertEquals(1f, vp.uMax, 1e-6f)
        assertEquals(0f, vp.vMin, 1e-6f)
        assertEquals(1f, vp.vMax, 1e-6f)
        assertEquals(2808f / 1256f, vp.aspectRatio, 1e-6f)
    }

    @Test
    fun widerSurface_appliesVerticalCenterCrop() {
        val mapper = CoordinateMapper.fromDimensions(1256, 2808, 1600, 2000)
        val vp = mapper.viewport

        assertEquals(0f, vp.uMin, 1e-6f)
        assertEquals(1f, vp.uMax, 1e-6f)
        assertTrue(vp.vMin > 0f)
        assertTrue(vp.vMax < 1f)
        assertEquals(1f, vp.vMin + vp.vMax, 1e-6f)

        val expectedSpanV = (1256f / 2808f) / (1600f / 2000f)
        assertEquals(expectedSpanV, vp.vMax - vp.vMin, 1e-5f)
    }

    @Test
    fun tallerSurface_appliesHorizontalCenterCrop() {
        val mapper = CoordinateMapper.fromDimensions(1256, 2808, 1000, 3000)
        val vp = mapper.viewport

        assertEquals(0f, vp.vMin, 1e-6f)
        assertEquals(1f, vp.vMax, 1e-6f)
        assertTrue(vp.uMin > 0f)
        assertTrue(vp.uMax < 1f)
        assertEquals(1f, vp.uMin + vp.uMax, 1e-6f)

        val expectedSpanU = (1000f / 3000f) / (1256f / 2808f)
        assertEquals(expectedSpanU, vp.uMax - vp.uMin, 1e-5f)
    }

    @Test
    fun fourCornersAndCenter_roundTripToNdcExactly() {
        val cases = listOf(
            CoordinateMapper.fromDimensions(1256, 2808, 1256, 2808),
            CoordinateMapper.fromDimensions(1256, 2808, 1600, 2000),
            CoordinateMapper.fromDimensions(1256, 2808, 1000, 3000)
        )

        for (mapper in cases) {
            val w = mapper.viewport.surfaceWidth.toFloat()
            val h = mapper.viewport.surfaceHeight.toFloat()

            val topLeft = mapper.surfaceToPhysics(0f, 0f)
            val topRight = mapper.surfaceToPhysics(w, 0f)
            val bottomLeft = mapper.surfaceToPhysics(0f, h)
            val bottomRight = mapper.surfaceToPhysics(w, h)
            val center = mapper.surfaceToPhysics(w * 0.5f, h * 0.5f)

            val ndcTL = mapper.physicsToNdc(topLeft.x, topLeft.y)
            val ndcTR = mapper.physicsToNdc(topRight.x, topRight.y)
            val ndcBL = mapper.physicsToNdc(bottomLeft.x, bottomLeft.y)
            val ndcBR = mapper.physicsToNdc(bottomRight.x, bottomRight.y)
            val ndcCenter = mapper.physicsToNdc(center.x, center.y)

            assertEquals(-1f, ndcTL.x, 1e-5f)
            assertEquals(1f, ndcTL.y, 1e-5f)
            assertEquals(1f, ndcTR.x, 1e-5f)
            assertEquals(1f, ndcTR.y, 1e-5f)
            assertEquals(-1f, ndcBL.x, 1e-5f)
            assertEquals(-1f, ndcBL.y, 1e-5f)
            assertEquals(1f, ndcBR.x, 1e-5f)
            assertEquals(-1f, ndcBR.y, 1e-5f)
            assertEquals(0f, ndcCenter.x, 1e-5f)
            assertEquals(0f, ndcCenter.y, 1e-5f)
        }
    }

    @Test
    fun zoomedSurfaceToMaskPixel_clampsBoundsAndInvertsZoomPan() {
        val mapper = CoordinateMapper.fromDimensions(1256, 2808, 1256, 2808)

        // Center stays at center regardless of zoom when pan is 0
        val centerPixel = mapper.zoomedSurfaceToMaskPixel(628f, 1404f, zoom = 3.0f, panX = 0f, panY = 0f)
        assertEquals(72, centerPixel.first)
        assertEquals(160, centerPixel.second)

        // Panning view by (+100, -200) shifts where surface center sits in view coordinates
        val pannedCenter = mapper.zoomedSurfaceToMaskPixel(
            viewX = 628f + 100f,
            viewY = 1404f - 200f,
            zoom = 2.5f,
            panX = 100f,
            panY = -200f
        )
        assertEquals(72, pannedCenter.first)
        assertEquals(160, pannedCenter.second)

        // Extreme out-of-bounds inputs clamp safely to [0..143] x [0..319]
        val minClamp = mapper.zoomedSurfaceToMaskPixel(-1e6f, -1e6f, zoom = 3.0f, panX = 500f, panY = 500f)
        assertEquals(0, minClamp.first)
        assertEquals(0, minClamp.second)

        val maxClamp = mapper.zoomedSurfaceToMaskPixel(1e6f, 1e6f, zoom = 3.0f, panX = -500f, panY = -500f)
        assertEquals(143, maxClamp.first)
        assertEquals(319, maxClamp.second)
    }

    @Test
    fun invalidAndNonFiniteInputs_fallBackSafelyWithoutNaN() {
        val zeroMapper = CoordinateMapper.fromDimensions(0, -10, 0, -5)
        val uv = zeroMapper.surfaceToMasterUv(Float.NaN, Float.POSITIVE_INFINITY)
        assertTrue(uv.x.isFinite() && uv.y.isFinite())

        val phys = zeroMapper.surfaceToPhysics(Float.NEGATIVE_INFINITY, Float.NaN)
        assertTrue(phys.x.isFinite() && phys.y.isFinite())

        val ndc = zeroMapper.physicsToNdc(Float.NaN, Float.POSITIVE_INFINITY)
        assertTrue(ndc.x.isFinite() && ndc.y.isFinite())

        val pixel = zeroMapper.zoomedSurfaceToMaskPixel(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            zoom = 0f,
            panX = Float.NaN,
            panY = Float.NEGATIVE_INFINITY
        )
        assertTrue(pixel.first in 0..143)
        assertTrue(pixel.second in 0..319)

        val corruptVpMapper = CoordinateMapper(
            AspectFillViewport(
                uMin = Float.NaN,
                uMax = Float.NaN,
                vMin = 1f,
                vMax = 0f,
                imageWidth = 0,
                imageHeight = 0,
                surfaceWidth = 0,
                surfaceHeight = 0,
                aspectRatio = -1f
            )
        )
        val safeNdc = corruptVpMapper.physicsToNdc(0.5f, 0.5f)
        assertTrue(safeNdc.x.isFinite() && safeNdc.y.isFinite())
    }
}
