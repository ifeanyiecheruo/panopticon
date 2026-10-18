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
        uniform vec4 uTexRect;
        attribute vec4 aPos;
        attribute vec4 aTex;
        varying vec2 vTex;
        void main() {
            gl_Position = aPos;
            // uSTMatrix maps *quad/display* coords to the buffer coords to sample, so aTex
            // is already in display space: crop it here, BEFORE the matrix, and uTexRect is
            // in "fraction of the displayed width/height" terms whatever the matrix then
            // does (some cameras' matrices swap the axes outright rather than just flipping
            // them - see CameraFraming.axesSwapped, which is what uTexRect is computed
            // against).
            //
            // uTexRect is (left, top, width, height) - an arbitrary sub-rect, not just a
            // centred crop, because it carries two jobs at once: the fixed output-aspect trim
            // (which is the whole of it when there is no zoom - see ZoomGeometry.split) and
            // whatever zoom the camera hardware would not do off-centre. 0,0,1,1 samples the
            // whole frame.
            vec2 c = uTexRect.xy + aTex.xy * uTexRect.zw;
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

    /**
     * Motion analysis input: the same view as [VERTEX_SHADER] samples, as luma, packed four
     * pixels per RGBA texel. Drawn into a (width/4) x height target, a plain RGBA `glReadPixels`
     * of it is exactly a width x height one-byte-per-pixel luma image - a quarter of the bytes
     * an RGBA readback moves, and no per-pixel conversion on the CPU.
     *
     * Each analysis pixel averages a 3x3 grid of bilinear taps across its footprint, rather than
     * the single tap a plain downscaling blit takes. A 160-wide image of a 4K buffer has ~24
     * source pixels per analysis pixel, and one tap aliases fine texture into shimmer, which a
     * structure-matching detector would read as structure that moved.
     *
     * `uStep` is one analysis pixel in display-space texcoords (1/width, 1/height); the vertex
     * stage carries it through the crop and the transform matrix, so the taps follow whatever
     * rotation or axis swap the camera's matrix applies.
     */
    private const val LUMA_VERTEX_SHADER = """
        uniform mat4 uSTMatrix;
        uniform vec4 uTexRect;
        uniform vec2 uStep;
        attribute vec4 aPos;
        attribute vec4 aTex;
        varying vec2 vTex;
        varying vec2 vStepX;
        varying vec2 vStepY;
        void main() {
            gl_Position = aPos;
            vec2 c = uTexRect.xy + aTex.xy * uTexRect.zw;
            vTex = (uSTMatrix * vec4(c, aTex.zw)).xy;
            vStepX = (uSTMatrix * vec4(uTexRect.z * uStep.x, 0.0, 0.0, 0.0)).xy;
            vStepY = (uSTMatrix * vec4(0.0, uTexRect.w * uStep.y, 0.0, 0.0)).xy;
        }
    """
    private const val LUMA_FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 vTex;
        varying vec2 vStepX;
        varying vec2 vStepY;
        uniform samplerExternalOES sTex;
        const vec3 W = vec3(0.299, 0.587, 0.114);
        float lumaAt(vec2 c) {
            float s = 0.0;
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    s += dot(texture2D(sTex, c + (float(a) * vStepX + float(b) * vStepY) / 3.0).rgb, W);
                }
            }
            return s / 9.0;
        }
        void main() {
            // This fragment's centre sits between its 2nd and 3rd analysis pixels.
            gl_FragColor = vec4(
                lumaAt(vTex - 1.5 * vStepX), lumaAt(vTex - 0.5 * vStepX),
                lumaAt(vTex + 0.5 * vStepX), lumaAt(vTex + 1.5 * vStepX));
        }
    """

    fun buildLumaProgram(): Int = link(LUMA_VERTEX_SHADER, LUMA_FRAGMENT_SHADER)

    fun buildProgram(): Int = link(VERTEX_SHADER, FRAGMENT_SHADER)

    private fun link(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
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
