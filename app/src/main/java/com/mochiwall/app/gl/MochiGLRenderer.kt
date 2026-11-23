package com.mochiwall.app.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.mochiwall.app.core.geometry.CoordinateMapper
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.physics.MochiPhysicsSolver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hosting-agnostic OpenGL ES 3.0 renderer shared identically by Stage 3's `MochiPreviewView`
 * (`GLSurfaceView`) and `MochiWallpaperService` (`SimpleEglRenderThread`).
 *
 * Implements Blueprint Sections 5.1 & 5.2:
 * - Zero per-frame heap allocations (pre-allocated heightField, staging array, direct FloatBuffer)
 * - Diagonal TL -> BR triangulation matching `MochiPhysicsSolver`'s 6-triangle area guard 1:1
 * - Virtual displacement-height gradient directional sheen (top-left light source)
 * - Direct sRGB texture upload via `GLUtils.texImage2D` with strict `bitmap.recycle()` in `finally`
 *
 * All GL lifecycle methods must be invoked exclusively on the active GL thread.
 */
class MochiGLRenderer {

    private val heightField = FloatArray(VERTEX_COUNT)
    private val dynamicVertexStaging = FloatArray(DYNAMIC_FLOAT_COUNT)
    private val dynamicVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(DYNAMIC_BUFFER_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val scratchIds = IntArray(1)

    private var shaderProgram: ShaderProgram? = null
    private var vaoId: Int = 0
    private var uvVboId: Int = 0
    private var dynamicVboId: Int = 0
    private var indexIboId: Int = 0
    private var textureId: Int = 0

    var isInitialized: Boolean = false
        private set

    var hasTexture: Boolean = false
        private set

    /**
     * Initializes OpenGL ES 3.0 state, shader program, VAO, static UV VBO, dynamic mesh VBO,
     * and static element IBO for a newly created (or recreated) GL context.
     *
     * Never calls `glDelete*` on stale handles from a previously lost context.
     */
    fun onGlContextCreated() {
        shaderProgram = null
        vaoId = 0
        uvVboId = 0
        dynamicVboId = 0
        indexIboId = 0
        textureId = 0
        isInitialized = false
        hasTexture = false

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(CLEAR_R, CLEAR_G, CLEAR_B, 1.0f)

        shaderProgram = ShaderProgram()

        // Create and bind VAO
        GLES30.glGenVertexArrays(1, scratchIds, 0)
        vaoId = scratchIds[0]
        GLES30.glBindVertexArray(vaoId)

        // Dynamic Mesh VBO: (ndcX, ndcY, sheenIntensity)
        GLES30.glGenBuffers(1, scratchIds, 0)
        dynamicVboId = scratchIds[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            DYNAMIC_BUFFER_BYTES,
            null,
            GLES30.GL_STREAM_DRAW
        )
        GLES30.glEnableVertexAttribArray(ShaderProgram.ATTR_POS_SHEEN_LOC)
        GLES30.glVertexAttribPointer(
            ShaderProgram.ATTR_POS_SHEEN_LOC,
            DYNAMIC_FLOATS_PER_VERTEX,
            GLES30.GL_FLOAT,
            false,
            DYNAMIC_STRIDE_BYTES,
            0
        )

        // Static UV VBO: (u, v) where v = 0 is top of bitmap and v = 1 is bottom
        val uvBuffer = buildStaticUvBuffer()
        GLES30.glGenBuffers(1, scratchIds, 0)
        uvVboId = scratchIds[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            UV_BUFFER_BYTES,
            uvBuffer,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(ShaderProgram.ATTR_UV_LOC)
        GLES30.glVertexAttribPointer(
            ShaderProgram.ATTR_UV_LOC,
            UV_FLOATS_PER_VERTEX,
            GLES30.GL_FLOAT,
            false,
            UV_STRIDE_BYTES,
            0
        )

        // Static Element IBO: diagonal TL -> BR triangles (tl, tr, br) and (tl, br, bl)
        val indexBuffer = buildStaticIndexBuffer()
        GLES30.glGenBuffers(1, scratchIds, 0)
        indexIboId = scratchIds[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexIboId)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            INDEX_BUFFER_BYTES,
            indexBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // Unbind VAO first so its EBO binding remains intact, then unbind GL_ARRAY_BUFFER
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        isInitialized = true
    }

    fun onViewportChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            GLES30.glViewport(0, 0, width, height)
        }
    }

    /**
     * Uploads [bitmap] to OpenGL via `GLUtils.texImage2D` (exact sRGB gallery colors).
     * Always recycles [bitmap] inside `finally`, even if GL is uninitialized or upload fails.
     */
    fun uploadTexture(bitmap: Bitmap) {
        try {
            if (!isInitialized || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return
            }

            if (textureId != 0) {
                scratchIds[0] = textureId
                GLES30.glDeleteTextures(1, scratchIds, 0)
                textureId = 0
                hasTexture = false
            }

            GLES30.glGenTextures(1, scratchIds, 0)
            val newTextureId = scratchIds[0]
            if (newTextureId == 0) {
                return
            }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, newTextureId)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )

            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

            textureId = newTextureId
            hasTexture = true
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    fun clearTexture() {
        if (textureId != 0) {
            scratchIds[0] = textureId
            GLES30.glDeleteTextures(1, scratchIds, 0)
            textureId = 0
        }
        hasTexture = false
    }

    /**
     * Renders one frame using the solver's interpolated displacements, [mapper], and [config].
     * Performs zero per-frame heap allocations.
     */
    fun renderFrame(
        solver: MochiPhysicsSolver,
        mapper: CoordinateMapper,
        config: PhysicsConfig
    ) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val program = shaderProgram
        if (!isInitialized || program == null || !hasTexture || textureId == 0) {
            return
        }

        val p0x = solver.p0x
        val p0y = solver.p0y
        val weights = solver.weights
        val computeSheen = config.sheenEnabled && !solver.isIdle && solver.hasActiveVertices

        if (computeSheen) {
            // Pass A: Compute smooth virtual surface height from interpolated displacement
            for (idx in 0 until VERTEX_COUNT) {
                val ux = solver.interpolatedUx(idx)
                val uy = solver.interpolatedUy(idx)
                heightField[idx] = sqrt(ux * ux + uy * uy + HEIGHT_EPSILON)
            }

            // Pass B: Central-difference gradient for top-left directional sheen + NDC projection
            val invTwoHx = 1f / (2f * solver.hx)
            val invTwoHy = 1f / (2f * solver.hy)

            for (j in 0 until NY) {
                val isInteriorY = j > 0 && j < NY - 1
                val rowOffset = j * NX
                for (i in 0 until NX) {
                    val idx = rowOffset + i
                    val ux = solver.interpolatedUx(idx)
                    val uy = solver.interpolatedUy(idx)

                    val ndcX = mapper.physicsToNdcX(p0x[idx] + ux)
                    val ndcY = mapper.physicsToNdcY(p0y[idx] + uy)

                    val sheen = if (isInteriorY && i > 0 && i < NX - 1) {
                        val w = weights[idx]
                        if (w > 0f) {
                            val dhdx = (heightField[idx + 1] - heightField[idx - 1]) * invTwoHx
                            val dhdy = (heightField[idx + NX] - heightField[idx - NX]) * invTwoHy
                            (max(0f, dhdx + dhdy) / SHEEN_SLOPE_NORM).coerceIn(0f, 1f) * w
                        } else {
                            0f
                        }
                    } else {
                        0f
                    }

                    val outOffset = idx * DYNAMIC_FLOATS_PER_VERTEX
                    dynamicVertexStaging[outOffset] = ndcX
                    dynamicVertexStaging[outOffset + 1] = ndcY
                    dynamicVertexStaging[outOffset + 2] = sheen
                }
            }
        } else {
            // Single-pass rest / sheen-OFF path (skips sqrt and gradient loop)
            for (idx in 0 until VERTEX_COUNT) {
                val ux = solver.interpolatedUx(idx)
                val uy = solver.interpolatedUy(idx)
                val outOffset = idx * DYNAMIC_FLOATS_PER_VERTEX
                dynamicVertexStaging[outOffset] = mapper.physicsToNdcX(p0x[idx] + ux)
                dynamicVertexStaging[outOffset + 1] = mapper.physicsToNdcY(p0y[idx] + uy)
                dynamicVertexStaging[outOffset + 2] = 0f
            }
        }

        // Upload dynamic mesh buffer
        dynamicVertexBuffer.clear()
        dynamicVertexBuffer.put(dynamicVertexStaging)
        dynamicVertexBuffer.flip()

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVboId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            DYNAMIC_BUFFER_BYTES,
            dynamicVertexBuffer
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // Draw mesh
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        program.setTextureUnit(0)
        program.setSheenStrength(
            if (config.sheenEnabled) ShaderProgram.SHEEN_STRENGTH_ON else ShaderProgram.SHEEN_STRENGTH_OFF
        )

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES,
            INDEX_COUNT,
            GLES30.GL_UNSIGNED_SHORT,
            0
        )
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Releases all GL resources on the active GL thread.
     */
    fun release() {
        clearTexture()

        if (dynamicVboId != 0) {
            scratchIds[0] = dynamicVboId
            GLES30.glDeleteBuffers(1, scratchIds, 0)
            dynamicVboId = 0
        }
        if (uvVboId != 0) {
            scratchIds[0] = uvVboId
            GLES30.glDeleteBuffers(1, scratchIds, 0)
            uvVboId = 0
        }
        if (indexIboId != 0) {
            scratchIds[0] = indexIboId
            GLES30.glDeleteBuffers(1, scratchIds, 0)
            indexIboId = 0
        }
        if (vaoId != 0) {
            scratchIds[0] = vaoId
            GLES30.glDeleteVertexArrays(1, scratchIds, 0)
            vaoId = 0
        }

        shaderProgram?.release()
        shaderProgram = null
        isInitialized = false
        hasTexture = false
    }

    private fun buildStaticUvBuffer(): FloatBuffer {
        val uvArray = FloatArray(VERTEX_COUNT * UV_FLOATS_PER_VERTEX)
        val invNxMinus1 = 1f / (NX - 1).toFloat()
        val invNyMinus1 = 1f / (NY - 1).toFloat()

        var offset = 0
        for (j in 0 until NY) {
            val v = j * invNyMinus1
            for (i in 0 until NX) {
                uvArray[offset++] = i * invNxMinus1
                uvArray[offset++] = v
            }
        }

        return ByteBuffer
            .allocateDirect(UV_BUFFER_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(uvArray)
                flip()
            }
    }

    private fun buildStaticIndexBuffer(): java.nio.ShortBuffer {
        val indices = ShortArray(INDEX_COUNT)
        var offset = 0
        for (j in 0 until NY - 1) {
            val rowTop = j * NX
            val rowBottom = (j + 1) * NX
            for (i in 0 until NX - 1) {
                val tl = rowTop + i
                val tr = tl + 1
                val bl = rowBottom + i
                val br = bl + 1

                // Split strictly along diagonal TL -> BR to match MochiPhysicsSolver's area guard
                indices[offset++] = tl.toShort()
                indices[offset++] = tr.toShort()
                indices[offset++] = br.toShort()

                indices[offset++] = tl.toShort()
                indices[offset++] = br.toShort()
                indices[offset++] = bl.toShort()
            }
        }

        return ByteBuffer
            .allocateDirect(INDEX_BUFFER_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(indices)
                flip()
            }
    }

    companion object {
        const val NX: Int = MochiPhysicsSolver.NX
        const val NY: Int = MochiPhysicsSolver.NY
        const val VERTEX_COUNT: Int = MochiPhysicsSolver.VERTEX_COUNT
        const val QUAD_COUNT: Int = (NX - 1) * (NY - 1)
        const val TRIANGLE_COUNT: Int = QUAD_COUNT * 2
        const val INDEX_COUNT: Int = TRIANGLE_COUNT * 3

        private const val DYNAMIC_FLOATS_PER_VERTEX: Int = 3
        private const val DYNAMIC_FLOAT_COUNT: Int = VERTEX_COUNT * DYNAMIC_FLOATS_PER_VERTEX
        private const val DYNAMIC_STRIDE_BYTES: Int = DYNAMIC_FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val DYNAMIC_BUFFER_BYTES: Int = DYNAMIC_FLOAT_COUNT * Float.SIZE_BYTES

        private const val UV_FLOATS_PER_VERTEX: Int = 2
        private const val UV_STRIDE_BYTES: Int = UV_FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val UV_BUFFER_BYTES: Int =
            VERTEX_COUNT * UV_FLOATS_PER_VERTEX * Float.SIZE_BYTES

        private const val INDEX_BUFFER_BYTES: Int = INDEX_COUNT * Short.SIZE_BYTES

        private const val HEIGHT_EPSILON: Float = 1e-6f
        private const val SHEEN_SLOPE_NORM: Float = 0.85f

        private const val CLEAR_R: Float = 0x11 / 255f
        private const val CLEAR_G: Float = 0x12 / 255f
        private const val CLEAR_B: Float = 0x15 / 255f
    }
}
