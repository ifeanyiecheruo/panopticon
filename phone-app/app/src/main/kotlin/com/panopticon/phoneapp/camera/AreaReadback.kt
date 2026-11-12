package com.panopticon.phoneapp.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.panopticon.phoneapp.calibration.RectNorm
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The luma of one region of the recorded frame, for [AutoAreaTuner] to measure - the same draw
 * as the encoder's (view rect, rotated quad), rendered at up to [AreaReadback.request]'s
 * `maxEdge` and read back only over the region asked for.
 *
 * Pull-only, one request at a time: nothing is drawn until the tuner asks. A request names the
 * earliest frame it will take, by sensor timestamp (the `SurfaceTexture` timestamp is the
 * `CaptureResult`'s `SENSOR_TIMESTAMP`), so a measurement can never come from a frame captured
 * before the setting it is measuring took effect.
 *
 * The read is synchronous (`glReadPixels`), which holds the GL thread for a few milliseconds per
 * request; requests come at most a few times a second, and only while tuning or checking for
 * drift. GL methods run on the GL thread; [request] on any.
 */
internal class AreaReadback {

    private class Pending(
        val region: RectNorm,
        val maxEdge: Int,
        val minTimestampNs: Long,
        val result: CompletableDeferred<AreaTuning.LumaPlane>,
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "PanopticonAreaReadback") }
    private val lock = Any()
    private var pending: Pending? = null

    // GL thread only.
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uStMatrix = 0
    private var uTexRect = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0

    /**
     * The luma of [region] (fractions of the output frame, top down) from the first frame
     * captured at or after [minTimestampNs], drawn with the longer edge at most [maxEdge]. A
     * request still waiting is failed in favour of this one.
     */
    fun request(region: RectNorm, maxEdge: Int, minTimestampNs: Long): CompletableDeferred<AreaTuning.LumaPlane> {
        val p = Pending(region, maxEdge, minTimestampNs, CompletableDeferred())
        val old = synchronized(lock) { pending.also { pending = p } }
        old?.result?.completeExceptionally(IllegalStateException("superseded"))
        return p.result
    }

    /** GL thread, once per camera frame. Cheap when nobody has asked. Leaves the default
     *  framebuffer bound. */
    fun captureIfRequested(
        oesTex: Int,
        stMatrix: FloatArray,
        texRect: FloatArray,
        quad: FloatBuffer,
        outW: Int,
        outH: Int,
        frameTimestampNs: Long,
    ) {
        val p = synchronized(lock) {
            pending?.takeIf { frameTimestampNs >= it.minTimestampNs }?.also { pending = null }
        } ?: return
        try {
            val (w, h) = SnapshotReadback.stillSize(outW, outH, p.maxEdge)
            ensureTarget(w, h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniform4f(uTexRect, texRect[0], texRect[1], texRect[2], texRect[3])
            quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPos)
            quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Only the region, in GL's bottom-up rows.
            val x0 = floor(p.region.l * w).toInt().coerceIn(0, w)
            val x1 = ceil(p.region.r * w).toInt().coerceIn(x0, w)
            val top = floor(p.region.t * h).toInt().coerceIn(0, h)
            val bottom = ceil(p.region.b * h).toInt().coerceIn(top, h)
            val rw = x1 - x0
            val rh = bottom - top
            if (rw <= 0 || rh <= 0) {
                p.result.completeExceptionally(IllegalStateException("region covers no pixel"))
                return
            }
            val buf = ByteBuffer.allocateDirect(rw * rh * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(x0, h - bottom, rw, rh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            executor.execute {
                runCatching { toLuma(buf, rw, rh) }
                    .onSuccess { p.result.complete(it) }
                    .onFailure { p.result.completeExceptionally(it) }
            }
        } catch (e: RejectedExecutionException) {
            p.result.completeExceptionally(e)
        } catch (e: Exception) {
            Log.w(TAG, "area readback failed", e)
            p.result.completeExceptionally(e)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    private fun ensureTarget(w: Int, h: Int) {
        if (program == 0) {
            program = GlBlit.buildProgram()
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            uStMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uTexRect = GLES20.glGetUniformLocation(program, "uTexRect")
        }
        if (fbo != 0 && w == fboW && h == fboH) return
        deleteTarget()
        val fb = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); fbo = fb[0]
        val ft = IntArray(1); GLES20.glGenTextures(1, ft, 0); fboTex = ft[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "area FBO incomplete" }
        fboW = w
        fboH = h
    }

    private fun deleteTarget() {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (fboTex != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
        fbo = 0; fboTex = 0; fboW = 0; fboH = 0
    }

    /** GL thread, context still current. */
    fun releaseGl() {
        runCatching {
            deleteTarget()
            if (program != 0) GLES20.glDeleteProgram(program)
        }
        program = 0
        failPending("pipeline stopped")
    }

    fun failPending(reason: String) {
        val p = synchronized(lock) { pending.also { pending = null } } ?: return
        p.result.completeExceptionally(IllegalStateException(reason))
    }

    fun shutdown() {
        failPending("pipeline released")
        executor.shutdown()
    }

    companion object {
        private const val TAG = "AreaReadback"

        /** RGBA rows bottom-up -> BT.709 luma rows top-down (the weights the player measures with). */
        internal fun toLuma(rgba: ByteBuffer, w: Int, h: Int): AreaTuning.LumaPlane {
            val out = ByteArray(w * h)
            for (row in 0 until h) {
                val src = row * w * 4
                val dst = (h - 1 - row) * w
                for (x in 0 until w) {
                    val o = src + x * 4
                    val r = rgba.get(o).toInt() and 0xFF
                    val g = rgba.get(o + 1).toInt() and 0xFF
                    val b = rgba.get(o + 2).toInt() and 0xFF
                    out[dst + x] = ((54 * r + 183 * g + 19 * b) shr 8).toByte()
                }
            }
            return AreaTuning.LumaPlane(w, h, out)
        }
    }
}
