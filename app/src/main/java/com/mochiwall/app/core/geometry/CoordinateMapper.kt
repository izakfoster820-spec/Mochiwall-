package com.mochiwall.app.core.geometry

import kotlin.math.floor

data class Vec2(
    val x: Float,
    val y: Float
)

data class AspectFillViewport(
    val uMin: Float,
    val uMax: Float,
    val vMin: Float,
    val vMax: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val aspectRatio: Float
)

/**
 * Unified Aspect-Fill coordinate mapper shared by touch input, Stage 2 mask sculpting,
 * physics space (gu), and OpenGL ES NDC rendering.
 *
 * Physics space convention: +X right (width = 1.0 gu), +Y down (height = aspectRatio gu).
 */
class CoordinateMapper(val viewport: AspectFillViewport) {

    private val safeSurfaceWidth: Float
    private val safeSurfaceHeight: Float
    private val safeUMin: Float
    private val safeUSpan: Float
    private val safeVMin: Float
    private val safeVSpan: Float
    private val safeAspectRatio: Float

    init {
        safeSurfaceWidth = if (viewport.surfaceWidth > 0) viewport.surfaceWidth.toFloat() else 1f
        safeSurfaceHeight = if (viewport.surfaceHeight > 0) viewport.surfaceHeight.toFloat() else 1f

        val uMinValid = viewport.uMin.isFinite()
        val uMaxValid = viewport.uMax.isFinite()
        val rawUSpan = if (uMinValid && uMaxValid) viewport.uMax - viewport.uMin else 1f
        if (uMinValid && uMaxValid && rawUSpan > MIN_SPAN) {
            safeUMin = viewport.uMin
            safeUSpan = rawUSpan
        } else {
            safeUMin = 0f
            safeUSpan = 1f
        }

        val vMinValid = viewport.vMin.isFinite()
        val vMaxValid = viewport.vMax.isFinite()
        val rawVSpan = if (vMinValid && vMaxValid) viewport.vMax - viewport.vMin else 1f
        if (vMinValid && vMaxValid && rawVSpan > MIN_SPAN) {
            safeVMin = viewport.vMin
            safeVSpan = rawVSpan
        } else {
            safeVMin = 0f
            safeVSpan = 1f
        }

        safeAspectRatio = if (viewport.aspectRatio.isFinite() && viewport.aspectRatio > MIN_SPAN) {
            viewport.aspectRatio
        } else {
            1f
        }
    }

    constructor(
        imageWidth: Int,
        imageHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int
    ) : this(computeViewport(imageWidth, imageHeight, surfaceWidth, surfaceHeight))

    fun surfaceToMasterUv(x: Float, y: Float): Vec2 {
        val sx = if (x.isFinite()) x else 0f
        val sy = if (y.isFinite()) y else 0f
        val u = safeUMin + (sx / safeSurfaceWidth) * safeUSpan
        val v = safeVMin + (sy / safeSurfaceHeight) * safeVSpan
        return Vec2(u, v)
    }

    fun surfaceToPhysics(x: Float, y: Float): Vec2 {
        val uv = surfaceToMasterUv(x, y)
        return Vec2(uv.x, uv.y * safeAspectRatio)
    }

    /**
     * Maps a Stage 2 zoomed/panned view coordinate into a clamped 144x320 mask pixel.
     * Forward zoom/pan around view center:
     *   viewX = (surfaceX - cx) * zoom + cx + panX
     *   viewY = (surfaceY - cy) * zoom + cy + panY
     */
    fun zoomedSurfaceToMaskPixel(
        viewX: Float,
        viewY: Float,
        zoom: Float,
        panX: Float,
        panY: Float
    ): Pair<Int, Int> {
        val vx = if (viewX.isFinite()) viewX else 0f
        val vy = if (viewY.isFinite()) viewY else 0f
        val safeZoom = if (zoom.isFinite() && zoom > MIN_SPAN) zoom else 1f
        val safePanX = if (panX.isFinite()) panX else 0f
        val safePanY = if (panY.isFinite()) panY else 0f

        val cx = safeSurfaceWidth * 0.5f
        val cy = safeSurfaceHeight * 0.5f

        val unzoomedX = (vx - cx - safePanX) / safeZoom + cx
        val unzoomedY = (vy - cy - safePanY) / safeZoom + cy

        val uv = surfaceToMasterUv(unzoomedX, unzoomedY)
        val uClamped = uv.x.coerceIn(0f, 1f)
        val vClamped = uv.y.coerceIn(0f, 1f)

        val px = floor(uClamped * MASK_WIDTH.toFloat()).toInt().coerceIn(0, MASK_WIDTH - 1)
        val py = floor(vClamped * MASK_HEIGHT.toFloat()).toInt().coerceIn(0, MASK_HEIGHT - 1)
        return Pair(px, py)
    }

    fun physicsToNdcX(px: Float): Float {
        val safePx = if (px.isFinite()) px else 0f
        return 2f * ((safePx - safeUMin) / safeUSpan) - 1f
    }

    fun physicsToNdcY(py: Float): Float {
        val safePy = if (py.isFinite()) py else 0f
        return 1f - 2f * (((safePy / safeAspectRatio) - safeVMin) / safeVSpan)
    }

    fun physicsToNdc(px: Float, py: Float): Vec2 {
        return Vec2(physicsToNdcX(px), physicsToNdcY(py))
    }

    companion object {
        const val MASK_WIDTH = 144
        const val MASK_HEIGHT = 320
        private const val MIN_SPAN = 1e-6f

        fun computeViewport(
            imageWidth: Int,
            imageHeight: Int,
            surfaceWidth: Int,
            surfaceHeight: Int
        ): AspectFillViewport {
            val safeImgW = if (imageWidth > 0) imageWidth else 1
            val safeImgH = if (imageHeight > 0) imageHeight else 1
            val safeSurfW = if (surfaceWidth > 0) surfaceWidth else 1
            val safeSurfH = if (surfaceHeight > 0) surfaceHeight else 1

            val rRef = safeImgW.toFloat() / safeImgH.toFloat()
            val rAspect = safeImgH.toFloat() / safeImgW.toFloat()
            val rSurf = safeSurfW.toFloat() / safeSurfH.toFloat()

            val uMin: Float
            val uMax: Float
            val vMin: Float
            val vMax: Float

            if (rSurf > rRef) {
                uMin = 0f
                uMax = 1f
                val spanV = (rRef / rSurf).coerceIn(MIN_SPAN, 1f)
                vMin = (1f - spanV) * 0.5f
                vMax = 1f - vMin
            } else {
                vMin = 0f
                vMax = 1f
                val spanU = (rSurf / rRef).coerceIn(MIN_SPAN, 1f)
                uMin = (1f - spanU) * 0.5f
                uMax = 1f - uMin
            }

            return AspectFillViewport(
                uMin = uMin,
                uMax = uMax,
                vMin = vMin,
                vMax = vMax,
                imageWidth = safeImgW,
                imageHeight = safeImgH,
                surfaceWidth = safeSurfW,
                surfaceHeight = safeSurfH,
                aspectRatio = rAspect
            )
        }

        fun fromDimensions(
            imageWidth: Int,
            imageHeight: Int,
            surfaceWidth: Int,
            surfaceHeight: Int
        ): CoordinateMapper {
            return CoordinateMapper(
                computeViewport(imageWidth, imageHeight, surfaceWidth, surfaceHeight)
            )
        }
    }
}
