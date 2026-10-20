package com.panopticon.phoneapp.camera

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * On-demand JPEG stills of exactly what RECORD is encoding: the stand-in for a live view while
 * recording, which can't afford a second encoder (docs/design/decisions/0015-recording-stills.md).
 *
 * Pull-only. Nothing is rendered or read back until [request] is called, so a phone nobody is
 * looking at pays nothing. A request is served from the next camera frame: the GL thread draws
 * that frame once more, with the encoder's own view rect and rotated quad, into a small FBO,
 * reads it back, and hands the pixels to a thread of its own for the JPEG encode.
 *
 * Requests coalesce. Everyone asking while a grab is in flight shares it, and a still younger
 * than the caller's `maxAgeMs` is handed back as is, so several viewers polling one phone cost
 * one grab per window between them rather than one each.
 *
 * The readback is synchronous - `glReadPixels` into client memory, which waits for the GPU and
 * holds the GL thread off `updateTexImage` meanwhile (the stall [MotionReadback] exists to avoid
 * at frame rate). At most one frame every [maxAgeMs][request] that is a few milliseconds, which
 * the camera's buffer queue absorbs.
 *
 * GL methods ([captureIfRequested], [releaseGl]) run on the GL thread; [request] on any thread.
 */
internal class SnapshotReadback(
    /** The longer edge of a still, in pixels. A still is never larger than the encoded frame. */
    private val maxEdge: Int = DEFAULT_MAX_EDGE,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    class Snapshot(val jpeg: ByteArray, val width: Int, val height: Int, val capturedAtMs: Long, private val elapsedMs: Long) {
        fun ageMs(): Long = SystemClock.elapsedRealtime() - elapsedMs
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "PanopticonSnapshot") }
    private val lock = Any()
    private var latest: Snapshot? = null
    /** The grab in flight, shared by everyone who asks before it completes. */
    private var pending: CompletableDeferred<Snapshot>? = null

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
    private var pixels: ByteBuffer? = null

    /** Stills produced, for `GET /api/camera/health`. */
    val produced = AtomicLong()

    /**
     * A still no older than [maxAgeMs]: the last one if it is fresh enough, otherwise the one the
     * next camera frame will produce. The caller bounds the wait - if frames have stopped, nothing
     * completes it until [failPending].
     */
    fun request(maxAgeMs: Long): CompletableDeferred<Snapshot> = synchronized(lock) {
        latest?.takeIf { it.ageMs() <= maxAgeMs }?.let { return CompletableDeferred(it) }
        pending ?: CompletableDeferred<Snapshot>().also { pending = it }
    }

    /**
     * GL thread, once per camera frame. Cheap when nobody has asked - one field read under a lock.
     * [quad] is the encoder's rotated quad and [outW] x [outH] the encoded size, so the still is
     * the recorded frame, scaled down. Leaves the default framebuffer bound.
     */
    fun captureIfRequested(
        oesTex: Int,
        stMatrix: FloatArray,
        texRect: FloatArray,
        quad: FloatBuffer,
        outW: Int,
        outH: Int,
        capturedAtMs: Long,
    ) {
        val target = synchronized(lock) { pending?.also { pending = null } } ?: return
        try {
            val (w, h) = stillSize(outW, outH, maxEdge)
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
            val buf = pixels!!
            buf.position(0)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            val rgba = ByteArray(w * h * 4)
            buf.position(0)
            buf.get(rgba)
            val elapsed = SystemClock.elapsedRealtime()
            executor.execute {
                runCatching { encode(rgba, w, h, jpegQuality) }
                    .onSuccess { jpeg ->
                        val snap = Snapshot(jpeg, w, h, capturedAtMs, elapsed)
                        synchronized(lock) { latest = snap }
                        produced.incrementAndGet()
                        target.complete(snap)
                    }
                    .onFailure { target.completeExceptionally(it) }
            }
        } catch (e: RejectedExecutionException) {
            target.completeExceptionally(e) // shut down mid-grab
        } catch (e: Exception) {
            Log.w(TAG, "snapshot grab failed", e)
            target.completeExceptionally(e)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    /** A pipeline teardown: the frame a waiter is waiting for is never coming. */
    fun failPending(reason: String) {
        val p = synchronized(lock) { pending.also { pending = null } } ?: return
        p.completeExceptionally(IllegalStateException(reason))
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
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "snapshot FBO incomplete" }
        fboW = w
        fboH = h
        pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    }

    private fun deleteTarget() {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (fboTex != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
        fbo = 0; fboTex = 0; fboW = 0; fboH = 0
        pixels = null
    }

    /** GL thread, context still current. The next pipeline run builds its own on first request. */
    fun releaseGl() {
        runCatching {
            deleteTarget()
            if (program != 0) GLES20.glDeleteProgram(program)
        }
        program = 0
        failPending("pipeline stopped")
    }

    fun shutdown() {
        failPending("pipeline released")
        executor.shutdown()
    }

    companion object {
        private const val TAG = "SnapshotReadback"
        const val DEFAULT_MAX_EDGE = 960
        const val DEFAULT_JPEG_QUALITY = 75

        /** [outW] x [outH] scaled so its longer edge is at most [maxEdge], keeping the aspect
         *  ratio; never enlarged. Even dimensions, which every JPEG decoder is happy with. */
        fun stillSize(outW: Int, outH: Int, maxEdge: Int): Pair<Int, Int> {
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(outW, outH))
            fun even(n: Double) = maxOf(2, (n.toInt() / 2) * 2)
            return even(outW * scale) to even(outH * scale)
        }

        /**
         * `glReadPixels` rows run bottom-up; a bitmap's run top-down. Flipped here, on the encode
         * thread, so the GL thread only pays for the read.
         */
        private fun encode(rgba: ByteArray, w: Int, h: Int, quality: Int): ByteArray {
            val stride = w * 4
            val flipped = ByteArray(rgba.size)
            for (row in 0 until h) {
                System.arraycopy(rgba, row * stride, flipped, (h - 1 - row) * stride, stride)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                // ARGB_8888 is stored as R,G,B,A bytes, which is exactly glReadPixels' RGBA.
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(flipped))
                val out = ByteArrayOutputStream(w * h / 4)
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                return out.toByteArray()
            } finally {
                bmp.recycle()
            }
        }
    }
}
