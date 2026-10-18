package com.panopticon.phoneapp.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Gets the motion detector's input off the GPU, and the detector off the GL thread.
 *
 * The GL thread used to `glReadPixels` an RGBA frame synchronously and then run the detector
 * itself. `glReadPixels` into client memory does not return until the GPU has drained everything
 * queued, including the previous frame's full-size encoder draw, and while the GL thread waits
 * nothing calls `updateTexImage`, so the camera's buffer queue runs dry
 * (docs/quirks/camera2-recording-pipeline.md). Three changes here:
 *
 * - **Luma, packed** ([GlBlit.buildLumaProgram]): [width]x[height] luma drawn into a
 *   (width/4)x[height] RGBA target. 19KB per frame instead of 76KB, and no conversion on the CPU.
 * - **Asynchronous readback** on a GLES 3 context: `glReadPixels` into one of two pixel-pack
 *   buffers returns at once. The buffer filled on the *previous* analysed frame is mapped and
 *   copied now, by which time its transfer has long finished. Detection runs one analysed frame
 *   (~66ms) late, which the recorder's pre-roll absorbs. On a GLES 2 context it falls back to a
 *   synchronous read.
 * - **A thread of its own** for analysis: frames go through a small pool of buffers, and when
 *   the analysis thread is still busy with an earlier frame the new one is dropped and counted,
 *   never waited for.
 *
 * GL methods ([setup], [capture], [releaseGl]) run on the GL thread; [onFrame] runs on the
 * analysis thread.
 */
internal class MotionReadback(
    val width: Int,
    val height: Int,
    private val onFrame: (luma: ByteArray, width: Int, height: Int, nowMs: Long) -> Unit,
) {
    private val texW = width / 4
    private val bytes = width * height

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "PanopticonMotion") }
    private val pool = ArrayBlockingQueue<ByteArray>(POOL).apply { repeat(POOL) { offer(ByteArray(bytes)) } }

    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uStMatrix = 0
    private var uTexRect = 0
    private var uStep = 0
    private var fbo = 0
    private var fboTex = 0
    private val pbos = IntArray(2)
    private val pending = BooleanArray(2)
    private val pendingMs = LongArray(2)
    private var next = 0
    private var syncBuffer: ByteBuffer? = null
    private val quad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    /** Whether readback is asynchronous (GLES 3 pixel-pack buffers). */
    @Volatile var async = false; private set
    /** Frames dropped because the analysis thread was still busy. */
    val dropped = AtomicLong()
    /** Analysis time per frame, ms: an exponential mean and the worst since the last [takeMaxMs]. */
    @Volatile var meanMs = 0.0; private set
    private val maxMicros = AtomicLong()

    fun takeMaxMs(): Double = maxMicros.getAndSet(0) / 1000.0

    /** GL thread, with the context current. [es3] says whether the context is GLES 3. */
    fun setup(es3: Boolean) {
        program = GlBlit.buildLumaProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uStMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uTexRect = GLES20.glGetUniformLocation(program, "uTexRect")
        uStep = GLES20.glGetUniformLocation(program, "uStep")

        val fb = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); fbo = fb[0]
        val ft = IntArray(1); GLES20.glGenTextures(1, ft, 0); fboTex = ft[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, texW, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "motion FBO incomplete" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        async = es3 && runCatching {
            GLES30.glGenBuffers(2, pbos, 0)
            for (id in pbos) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, id)
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES20.glGetError() == GLES20.GL_NO_ERROR
        }.getOrDefault(false)
        if (!async) syncBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        pending.fill(false)
        next = 0
        Log.i(TAG, "motion readback ${width}x$height luma, ${if (async) "async (PBO)" else "sync"}")
    }

    /**
     * GL thread: render this frame's analysis image and start reading it back; hand the previous
     * one to the analysis thread. [texRect] is the `uTexRect` uniform the encoder draw uses, so
     * motion is analysed over exactly the recorded view. Leaves the default framebuffer bound.
     */
    fun capture(oesTex: Int, stMatrix: FloatArray, texRect: FloatArray, nowMs: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, texW, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
        GLES20.glUniform4f(uTexRect, texRect[0], texRect[1], texRect[2], texRect[3])
        GLES20.glUniform2f(uStep, 1f / width, 1f / height)
        quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (async) {
            val cur = next
            val prev = cur xor 1
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[cur])
            GLES30.glReadPixels(0, 0, texW, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
            pending[cur] = true
            pendingMs[cur] = nowMs
            if (pending[prev]) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[prev])
                val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
                if (mapped != null) {
                    submit(mapped, pendingMs[prev])
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                }
                pending[prev] = false
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            next = prev
        } else {
            val buf = syncBuffer!!
            buf.position(0)
            GLES20.glReadPixels(0, 0, texW, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            submit(buf, nowMs)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun submit(src: ByteBuffer, nowMs: Long) {
        val arr = pool.poll()
        if (arr == null) {
            dropped.incrementAndGet()
            return
        }
        src.position(0)
        src.get(arr, 0, bytes)
        val task = Runnable {
            val t0 = System.nanoTime()
            try {
                onFrame(arr, width, height, nowMs)
            } catch (e: Exception) {
                Log.e(TAG, "motion analysis failed", e)
            } finally {
                pool.offer(arr)
            }
            val micros = (System.nanoTime() - t0) / 1000
            meanMs += 0.05 * (micros / 1000.0 - meanMs)
            maxMicros.accumulateAndGet(micros) { a, b -> maxOf(a, b) }
        }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            pool.offer(arr) // shut down: a frame after release() has nowhere to go
        }
    }

    /** GL thread, context still current: delete GL objects. Analysis already queued still runs. */
    fun releaseGl() {
        runCatching {
            if (async) GLES30.glDeleteBuffers(2, pbos, 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            GLES20.glDeleteProgram(program)
        }
        pending.fill(false)
    }

    fun shutdown() {
        executor.shutdown()
    }

    private companion object {
        const val TAG = "MotionReadback"
        /** In flight at once: one being analysed, one queued, one being filled. */
        const val POOL = 3
    }
}
