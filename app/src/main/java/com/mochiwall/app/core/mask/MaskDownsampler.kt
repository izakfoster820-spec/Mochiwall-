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
    const val INSIDE_THRESHOLD = 0.50f
    const val NEIGHBOR_SUPPORT_THRESHOLD = 0.35f

    /**
     * Performs 4x4 box downsampling from the 144x320 unsigned-byte mask onto the 36x80 physics
     * mesh with crisp interior thresholding, strict 8-neighbor rim pinning (preventing triangle
     * spill onto unpainted background), 5% screen-edge fade, hard perimeter pinning, 0.02 cutoff,
     * and a tiny-stroke safety fallback so localized dots remain responsive.
     */
    fun downsample(maskBytes: ByteArray): DownsampledMask {
        require(maskBytes.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
            "Mask must be exactly ${MaskBrushRasterizer.MASK_BYTE_COUNT} bytes"
        }

        val raw = FloatArray(MESH_VERTEX_COUNT)
        val inside = BooleanArray(MESH_VERTEX_COUNT)
        val coreWeight = FloatArray(MESH_VERTEX_COUNT)
        val invMaxSum = 1f / (16f * 255f)
        val invCoreRange = 1f / (1f - NEIGHBOR_SUPPORT_THRESHOLD)

        // 1. Compute 4x4 box averages and crisp interior flags/weights for all 36x80 vertices
        for (j in 0 until MESH_NY) {
            val baseY = j * 4
            val rowOffsetMesh = j * MESH_NX
            for (i in 0 until MESH_NX) {
                val idx = rowOffsetMesh + i
                val baseX = i * 4
                var sum = 0
                for (dy in 0 until 4) {
                    val rowOffset = (baseY + dy) * MaskBrushRasterizer.MASK_WIDTH + baseX
                    sum += (maskBytes[rowOffset].toInt() and 0xFF) +
                        (maskBytes[rowOffset + 1].toInt() and 0xFF) +
                        (maskBytes[rowOffset + 2].toInt() and 0xFF) +
                        (maskBytes[rowOffset + 3].toInt() and 0xFF)
                }

                val r = sum * invMaxSum
                raw[idx] = r
                val isInside = r >= INSIDE_THRESHOLD
                inside[idx] = isInside
                coreWeight[idx] = if (isInside) {
                    ((r - NEIGHBOR_SUPPORT_THRESHOLD) * invCoreRange).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }

        // 2. Strict 8-neighbor rim pinning + 5% screen-edge fade + hard perimeter pinning
        val weights = FloatArray(MESH_VERTEX_COUNT)
        var hasActiveVertices = false

        for (j in 1 until MESH_NY - 1) {
            val rowOffsetMesh = j * MESH_NX
            for (i in 1 until MESH_NX - 1) {
                val idx = rowOffsetMesh + i
                if (!inside[idx]) {
                    weights[idx] = 0f
                    continue
                }

                var allNeighborsSupported = true
                neighborCheck@ for (dj in -1..1) {
                    val nRow = (j + dj) * MESH_NX
                    for (di in -1..1) {
                        val nIdx = nRow + (i + di)
                        if (raw[nIdx] < NEIGHBOR_SUPPORT_THRESHOLD) {
                            allNeighborsSupported = false
                            break@neighborCheck
                        }
                    }
                }

                if (allNeighborsSupported) {
                    var w = coreWeight[idx] * edgeFade(i, j)
                    if (w < WEIGHT_CUTOFF) {
                        w = 0f
                    } else {
                        hasActiveVertices = true
                    }
                    weights[idx] = w
                } else {
                    weights[idx] = 0f
                }
            }
        }

        // 3. Tiny-stroke safety fallback for localized dots smaller than 3x3 mesh cells
        if (!hasActiveVertices) {
            for (j in 1 until MESH_NY - 1) {
                val rowOffsetMesh = j * MESH_NX
                for (i in 1 until MESH_NX - 1) {
                    val idx = rowOffsetMesh + i
                    if (!inside[idx]) continue
                    var w = coreWeight[idx] * edgeFade(i, j)
                    if (w < WEIGHT_CUTOFF) {
                        w = 0f
                    } else {
                        hasActiveVertices = true
                    }
                    weights[idx] = w
                }
            }
        }

        return DownsampledMask(weights = weights, hasActiveVertices = hasActiveVertices)
    }

    private fun edgeFade(i: Int, j: Int): Float {
        val u = i / (MESH_NX - 1).toFloat()
        val v = j / (MESH_NY - 1).toFloat()
        val uEdge = if (u < 1f - u) u else 1f - u
        val vEdge = if (v < 1f - v) v else 1f - v
        val dEdge = if (uEdge < vEdge) uEdge else vEdge
        return smoothstep(0f, EDGE_FADE_WIDTH, dEdge)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
