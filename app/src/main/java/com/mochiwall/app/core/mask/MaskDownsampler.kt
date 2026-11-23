package com.mochiwall.app.core.mask

data class DownsampledMask(
    val weights: FloatArray,
    val hasActiveVertices: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownsampledMask) return false
        return hasActiveVertices == other.hasActiveVertices && weights.contentEquals(other.weights)
    }

    override fun hashCode(): Int {
        var result = weights.contentHashCode()
        result = 31 * result + hasActiveVertices.hashCode()
        return result
    }
}

object MaskDownsampler {

    const val MESH_NX = 36
    const val MESH_NY = 80
    const val MESH_VERTEX_COUNT = MESH_NX * MESH_NY
    const val EDGE_FADE_WIDTH = 0.05f
    const val WEIGHT_CUTOFF = 0.02f

    /**
     * Performs exact 4x4 box downsampling from the 144x320 unsigned-byte mask
     * onto the 36x80 physics mesh, applying a smooth 5% edge fade, hard perimeter pinning,
     * and the 0.02 quantization cutoff.
     */
    fun downsample(maskBytes: ByteArray): DownsampledMask {
        require(maskBytes.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
            "Mask must be exactly ${MaskBrushRasterizer.MASK_BYTE_COUNT} bytes"
        }

        val weights = FloatArray(MESH_VERTEX_COUNT)
        var hasActiveVertices = false
        val invMaxSum = 1f / (16f * 255f)
        val invNxMinus1 = 1f / (MESH_NX - 1).toFloat()
        val invNyMinus1 = 1f / (MESH_NY - 1).toFloat()

        for (j in 0 until MESH_NY) {
            val isBorderY = (j == 0 || j == MESH_NY - 1)
            val v = j * invNyMinus1
            val vEdge = if (v < 1f - v) v else 1f - v
            val baseY = j * 4

            for (i in 0 until MESH_NX) {
                val idx = j * MESH_NX + i
                if (isBorderY || i == 0 || i == MESH_NX - 1) {
                    weights[idx] = 0f
                    continue
                }

                val baseX = i * 4
                var sum = 0
                for (dy in 0 until 4) {
                    val rowOffset = (baseY + dy) * MaskBrushRasterizer.MASK_WIDTH + baseX
                    sum += (maskBytes[rowOffset].toInt() and 0xFF) +
                        (maskBytes[rowOffset + 1].toInt() and 0xFF) +
                        (maskBytes[rowOffset + 2].toInt() and 0xFF) +
                        (maskBytes[rowOffset + 3].toInt() and 0xFF)
                }

                val wRaw = sum * invMaxSum
                val u = i * invNxMinus1
                val uEdge = if (u < 1f - u) u else 1f - u
                val dEdge = if (uEdge < vEdge) uEdge else vEdge

                val edgeFade = smoothstep(0f, EDGE_FADE_WIDTH, dEdge)
                var w = wRaw * edgeFade
                if (w < WEIGHT_CUTOFF) {
                    w = 0f
                } else {
                    hasActiveVertices = true
                }
                weights[idx] = w
            }
        }

        return DownsampledMask(weights = weights, hasActiveVertices = hasActiveVertices)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
