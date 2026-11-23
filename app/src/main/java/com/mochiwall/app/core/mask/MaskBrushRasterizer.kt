package com.mochiwall.app.core.mask

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class DirtyRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

object MaskBrushRasterizer {

    const val MASK_WIDTH = 144
    const val MASK_HEIGHT = 320
    const val MASK_BYTE_COUNT = MASK_WIDTH * MASK_HEIGHT

    /**
     * Rasterizes a continuous brush or eraser stroke segment onto a 144x320 unsigned-byte mask.
     * Returns the inclusive clamped [DirtyRect] touched by the stroke, or null if nothing in bounds
     * was affected.
     */
    fun paintStrokeSegment(
        mask: ByteArray,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radiusPx: Float,
        feather: Float,
        isEraser: Boolean
    ): DirtyRect? {
        require(mask.size >= MASK_BYTE_COUNT) {
            "Mask buffer must be at least $MASK_BYTE_COUNT bytes (got ${mask.size})"
        }
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return null
        if (!radiusPx.isFinite() || radiusPx <= 0f) return null

        val safeFeather = if (feather.isFinite()) feather.coerceIn(0f, 1f) else 0f
        val rInner = radiusPx * (1f - safeFeather)
        val featherBand = radiusPx - rInner

        val dx = x1 - x0
        val dy = y1 - y0
        val dist = hypot(dx, dy)
        val stepSize = max(1.0f, 0.25f * radiusPx)
        val steps = if (dist == 0f) 0 else max(1, ceil(dist / stepSize).toInt())

        var dirtyLeft = MASK_WIDTH
        var dirtyTop = MASK_HEIGHT
        var dirtyRight = -1
        var dirtyBottom = -1
        var modifiedAny = false

        for (s in 0..steps) {
            val t = if (steps == 0) 0f else s.toFloat() / steps.toFloat()
            val mx = x0 + dx * t
            val my = y0 + dy * t

            val rawMinX = floor(mx - radiusPx).toInt()
            val rawMaxX = ceil(mx + radiusPx).toInt()
            val rawMinY = floor(my - radiusPx).toInt()
            val rawMaxY = ceil(my + radiusPx).toInt()

            if (rawMaxX < 0 || rawMinX >= MASK_WIDTH || rawMaxY < 0 || rawMinY >= MASK_HEIGHT) {
                continue
            }

            val minX = rawMinX.coerceIn(0, MASK_WIDTH - 1)
            val maxX = rawMaxX.coerceIn(0, MASK_WIDTH - 1)
            val minY = rawMinY.coerceIn(0, MASK_HEIGHT - 1)
            val maxY = rawMaxY.coerceIn(0, MASK_HEIGHT - 1)

            for (py in minY..maxY) {
                val cy = py + 0.5f - my
                val rowOffset = py * MASK_WIDTH
                for (px in minX..maxX) {
                    val cx = px + 0.5f - mx
                    val d = hypot(cx, cy)
                    if (d >= radiusPx) continue

                    val alphaFloat = if (d <= rInner || featherBand <= 1e-6f) {
                        1f
                    } else {
                        val u = ((d - rInner) / featherBand).coerceIn(0f, 1f)
                        1f - (u * u * (3f - 2f * u))
                    }

                    val dabAlpha = (alphaFloat * 255f + 0.5f).toInt().coerceIn(0, 255)
                    if (dabAlpha == 0) continue

                    val idx = rowOffset + px
                    val oldVal = mask[idx].toInt() and 0xFF
                    val newVal = if (isEraser) {
                        min(oldVal, 255 - dabAlpha)
                    } else {
                        max(oldVal, dabAlpha)
                    }
                    mask[idx] = newVal.toByte()

                    if (px < dirtyLeft) dirtyLeft = px
                    if (px > dirtyRight) dirtyRight = px
                    if (py < dirtyTop) dirtyTop = py
                    if (py > dirtyBottom) dirtyBottom = py
                    modifiedAny = true
                }
            }
        }

        return if (modifiedAny) {
            DirtyRect(
                left = dirtyLeft.coerceIn(0, MASK_WIDTH - 1),
                top = dirtyTop.coerceIn(0, MASK_HEIGHT - 1),
                right = dirtyRight.coerceIn(0, MASK_WIDTH - 1),
                bottom = dirtyBottom.coerceIn(0, MASK_HEIGHT - 1)
            )
        } else {
            null
        }
    }
}
