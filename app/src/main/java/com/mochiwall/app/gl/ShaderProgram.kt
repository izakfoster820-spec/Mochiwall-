package com.mochiwall.app.gl

import android.opengl.GLES30

/**
 * OpenGL ES 3.0 shader program for MochiWall's displaced wallpaper mesh and
 * directional 3D surface sheen (Blueprint Section 5.2).
 *
 * Must be constructed, used, and released exclusively on the active GL thread.
 */
class ShaderProgram {

    var programId: Int = 0
        private set

    private var uWallpaperTexLoc: Int = -1
    private var uSheenStrengthLoc: Int = -1

    init {
        compileAndLink()
    }

    private fun compileAndLink() {
        var vertexShader = 0
        var fragmentShader = 0
        var program = 0
        try {
            vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE, "vertex")
            fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE, "fragment")

            program = GLES30.glCreateProgram()
            if (program == 0) {
                throw IllegalStateException("glCreateProgram returned 0")
            }

            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val infoLog = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                program = 0
                throw IllegalStateException("Failed to link OpenGL ES 3.0 program: $infoLog")
            }

            uWallpaperTexLoc = GLES30.glGetUniformLocation(program, UNIFORM_WALLPAPER_TEX)
            uSheenStrengthLoc = GLES30.glGetUniformLocation(program, UNIFORM_SHEEN_STRENGTH)
            programId = program
        } finally {
            if (vertexShader != 0) {
                if (program != 0) {
                    GLES30.glDetachShader(program, vertexShader)
                }
                GLES30.glDeleteShader(vertexShader)
            }
            if (fragmentShader != 0) {
                if (program != 0) {
                    GLES30.glDetachShader(program, fragmentShader)
                }
                GLES30.glDeleteShader(fragmentShader)
            }
        }
    }

    private fun compileShader(type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            throw IllegalStateException("glCreateShader($label) returned 0")
        }

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val infoLog = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Failed to compile $label shader: $infoLog")
        }
        return shader
    }

    fun use() {
        if (programId != 0) {
            GLES30.glUseProgram(programId)
        }
    }

    fun setTextureUnit(unit: Int) {
        if (programId != 0 && uWallpaperTexLoc >= 0) {
            GLES30.glUniform1i(uWallpaperTexLoc, unit)
        }
    }

    fun setSheenStrength(strength: Float) {
        if (programId != 0 && uSheenStrengthLoc >= 0) {
            GLES30.glUniform1f(uSheenStrengthLoc, strength)
        }
    }

    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        uWallpaperTexLoc = -1
        uSheenStrengthLoc = -1
    }

    companion object {
        const val ATTR_POS_SHEEN_LOC: Int = 0
        const val ATTR_UV_LOC: Int = 1

        const val SHEEN_STRENGTH_ON: Float = 0.14f
        const val SHEEN_STRENGTH_OFF: Float = 0.0f

        private const val UNIFORM_WALLPAPER_TEX = "uWallpaperTex"
        private const val UNIFORM_SHEEN_STRENGTH = "uSheenStrength"

        private val VERTEX_SHADER_SOURCE = """
            #version 300 es
            layout(location = 0) in vec3 aPosSheen; // xy = NDC position, z = sheenIntensity
            layout(location = 1) in vec2 aUv;

            out vec2 vUv;
            out float vSheen;

            void main() {
                vUv = aUv;
                vSheen = aPosSheen.z;
                gl_Position = vec4(aPosSheen.xy, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_SOURCE = """
            #version 300 es
            precision mediump float;

            in vec2 vUv;
            in float vSheen;
            uniform sampler2D uWallpaperTex;
            uniform float uSheenStrength; // 0.14 when Sheen ON, 0.0 when Sheen OFF
            out vec4 fragColor;

            void main() {
                vec4 tex = texture(uWallpaperTex, vUv);
                float highlight = clamp(vSheen * vSheen * uSheenStrength, 0.0, 0.22);
                vec3 finalRgb = clamp(tex.rgb + vec3(highlight), 0.0, 1.0);
                fragColor = vec4(finalRgb, 1.0);
            }
        """.trimIndent()
    }
}
