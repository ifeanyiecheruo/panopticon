package com.panopticon.phoneapp.camera

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shared, stateless GL building blocks for the "blit the camera's OES texture into a target
 * surface, centre-cropped to an aspect ratio and rotated by a multiple of 90 degrees" step used
 * by both [CameraGlPipeline] (RECORD) and [LivePipeline] (LIVE), so `rotationDegrees` is applied
 * identically by both and the live preview always shows what actually gets recorded. Each
 * pipeline still owns its own EGL context, textures and program object - only the shader source
 * and the rotated-quad vertex math are shared.
 */
internal object GlBlit {
    private const val VERTEX_SHADER = """
        uniform mat4 uSTMatrix;
        uniform vec2 uTexCrop;
        attribute vec4 aPos;
        attribute vec4 aTex;
        varying vec2 vTex;
        void main() {
            gl_Position = aPos;
            // uSTMatrix maps *quad/display* coords to the buffer coords to sample, so aTex
            // is already in display space: crop it here, BEFORE the matrix, and uTexCrop's
            // x/y mean "fraction of the displayed width/height" whatever the matrix then
            // does (some cameras' matrices swap the axes outright rather than just flipping
            // them - see CameraFraming.axesSwapped, which is what uTexCrop is computed
            // against). uTexCrop is 1,1 when no crop is needed.
            vec2 c = vec2(0.5) + (aTex.xy - vec2(0.5)) * uTexCrop;
            vTex = (uSTMatrix * vec4(c, aTex.zw)).xy;
        }
    """
    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex;
        uniform samplerExternalOES sTex;
        void main() { gl_FragColor = texture2D(sTex, vTex); }
    """

    fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        val st = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        check(st[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val st = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        check(st[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    /**
     * The (position, texcoord) vertex data for a `GL_TRIANGLE_STRIP` full-screen quad, with the
     * position corners rotated [degrees] clockwise (as viewed in the final video) from the
     * identity orientation (`degrees = 0`); [degrees] must be a multiple of 90. Texcoords are
     * left at their identity corners - rotating the polygon while keeping each corner's texcoord
     * fixed is geometrically equivalent to physically rotating the sampled image, and works
     * regardless of the crop/aspect ratio in effect.
     */
    fun quad(degrees: Int): FloatBuffer {
        val pos = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f))
        val tex = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 1f))
        val steps = ((degrees / 90) % 4 + 4) % 4
        val buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until 4) {
            var x = pos[i][0]
            var y = pos[i][1]
            // Clip space is Y-up; rotating (x, y) -> (y, -x) per 90-degree step here is intended
            // to produce a clockwise rotation of the rendered image (screen/video space is
            // Y-down). Flip the sign here if an on-device check shows it's backwards.
            repeat(steps) {
                val nx = y
                val ny = -x
                x = nx
                y = ny
            }
            buf.put(x); buf.put(y); buf.put(tex[i][0]); buf.put(tex[i][1])
        }
        buf.position(0)
        return buf
    }
}
