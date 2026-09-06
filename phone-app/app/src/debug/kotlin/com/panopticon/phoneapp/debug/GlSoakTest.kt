package com.panopticon.phoneapp.debug

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only soak harness for the planned GPU texture fan-out pipeline. It exercises exactly the
 * API surface that pipeline will depend on, for a sustained run, and logs a PASS/FAIL verdict:
 *
 *   camera (1 stream) -> SurfaceTexture -> GL external-OES texture, per frame:
 *     - updateTexImage / getTransformMatrix / getTimestamp
 *     - render downscaled to an FBO + glReadPixels  (the "inspect frames in memory" path)
 *     - render full-res to an EGL window surface on the MediaCodec input surface
 *       + eglPresentationTimeANDROID + eglSwapBuffers   (the "hand memory to the HW encoder" path)
 *   drain thread: dequeueOutputBuffer -> MediaMuxer, rotating the muxer every ~10s at a keyframe.
 *
 * The point is to find out whether a weak HAL (BLU G5 / Unisoc SC9863A) keeps all of this alive
 * and stable for 10 minutes before we build the real thing on top of it. Not shipped: src/debug.
 */
class GlSoakTest(
    private val context: Context,
    private val durationMs: Long = 10 * 60_000L,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val bitRate: Int = 4_000_000,
    private val rotationIntervalMs: Long = 10_000L,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val glThread = HandlerThread("GlSoakGl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val camThread = HandlerThread("GlSoakCam").apply { start() }
    private val camHandler = Handler(camThread.looper)

    // EGL / GL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var encoderWindowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uStMatrixLoc = 0
    private var oesTexId = 0
    private var fbo = 0
    private var fboTex = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f,  1f, -1f, 1f, 0f,  -1f, 1f, 0f, 1f,  1f, 1f, 1f, 1f)); position(0)
    }
    private val stMatrix = FloatArray(16)
    private val readbackW = 160
    private val readbackH = 120
    private val readback: ByteBuffer = ByteBuffer.allocateDirect(readbackW * readbackH * 4).order(ByteOrder.nativeOrder())

    // camera / codec
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    @Volatile private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var muxerTrack = -1
    @Volatile private var muxerStarted = false
    private var muxerFile: File? = null
    private val muxFiles = ArrayList<File>()

    // counters
    private val framesFromCamera = AtomicInteger()
    private val framesRendered = AtomicInteger()
    private val glErrors = AtomicInteger()
    private val encoderOutBuffers = AtomicInteger()
    private val muxerRotations = AtomicInteger()
    private val bytesWritten = AtomicLong()
    private val hardFailures = AtomicInteger()
    @Volatile private var lastFailure: String? = null
    @Volatile private var lastLumaSum = 0L
    @Volatile private var lastFrameDiff = 0L
    @Volatile private var prevReadbackSum = -1L

    private val running = AtomicBoolean(false)
    private val drainThread = Thread({ drainLoop() }, "GlSoakDrain")

    fun run(onDone: (summary: String) -> Unit) {
        running.set(true)
        glHandler.post {
            try {
                setupEglAndEncoder()
                setupGl()
                startCameraAndSession()
            } catch (e: Exception) {
                fail("setup: ${e.message}")
                Log.e(TAG, "setup failed", e)
                running.set(false)
            }
        }
        drainThread.start()

        val start = SystemClock.elapsedRealtime()
        Thread({
            var nextLog = start + 30_000
            while (running.get() && SystemClock.elapsedRealtime() - start < durationMs) {
                SystemClock.sleep(1_000)
                if (SystemClock.elapsedRealtime() >= nextLog) {
                    logProgress(SystemClock.elapsedRealtime() - start)
                    nextLog += 30_000
                }
            }
            running.set(false)
            teardown()
            onDone(verdict(SystemClock.elapsedRealtime() - start))
        }, "GlSoakMonitor").start()
    }

    // ---- setup ----

    private fun setupEglAndEncoder() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val ver = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        require(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) {
            "eglChooseConfig (recordable) failed"
        }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        require(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        // Encoder + its input surface, then an EGL window surface on it.
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        encoderWindowSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, encoderInputSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        require(encoderWindowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        require(EGL14.eglMakeCurrent(eglDisplay, encoderWindowSurface, encoderWindowSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
        Log.i(TAG, "EGL ready: vendor=${EGL14.eglQueryString(eglDisplay, EGL14.EGL_VENDOR)} " +
            "gl=${GLES20.glGetString(GLES20.GL_RENDERER)}")
    }

    private fun setupGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTex")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Analysis FBO (small; glReadPixels target).
        val fb = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); fbo = fb[0]
        val ft = IntArray(1); GLES20.glGenTextures(1, ft, 0); fboTex = ft[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, readbackW, readbackH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTex, 0)
        val fbStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        require(fbStatus == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: $fbStatus" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        checkGl("setupGl")
    }

    private fun startCameraAndSession() {
        val camId = backCameraId()
        if (camId == null) { fail("no back camera"); return }
        val recSize = pickSurfaceTextureSize(camId)
        Log.i(TAG, "camera $camId, SurfaceTexture size ${recSize.first}x${recSize.second} (encoder ${width}x$height)")

        val st = SurfaceTexture(oesTexId)
        st.setDefaultBufferSize(recSize.first, recSize.second)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)
        surfaceTexture = st
        cameraSurface = Surface(st)

        @Suppress("MissingPermission")
        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                try {
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(cameraSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(cameraSurface!!)
                                }.build()
                                session.setRepeatingRequest(req, null, camHandler)
                                Log.i(TAG, "capture session configured + repeating request set")
                            } catch (e: Exception) { fail("setRepeatingRequest: ${e.message}") }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { fail("session configure failed") }
                    }, camHandler)
                } catch (e: Exception) { fail("createCaptureSession: ${e.message}") }
            }
            override fun onDisconnected(device: CameraDevice) { fail("camera disconnected"); device.close() }
            override fun onError(device: CameraDevice, error: Int) { fail("camera error $error"); device.close() }
        }, camHandler)
    }

    // ---- per-frame (GL thread) ----

    private fun onFrameAvailable() {
        if (!running.get()) return
        framesFromCamera.incrementAndGet()
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            fail("updateTexImage: ${e.message}"); return
        }
        st.getTransformMatrix(stMatrix)
        val tsNanos = st.timestamp

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexLoc)

        // 1) downscale -> FBO -> read back (the in-memory inspect path)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, readbackW, readbackH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        readback.position(0)
        GLES20.glReadPixels(0, 0, readbackW, readbackH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
        analyseReadback()

        // 2) full-res -> encoder window surface
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderWindowSurface, tsNanos)
        EGL14.eglSwapBuffers(eglDisplay, encoderWindowSurface)

        framesRendered.incrementAndGet()
        checkGl("frame")
    }

    private fun analyseReadback() {
        var sum = 0L
        val n = readbackW * readbackH
        var i = 1 // green channel
        repeat(n) { sum += (readback.get(i).toInt() and 0xFF); i += 4 }
        lastLumaSum = sum
        if (prevReadbackSum >= 0) lastFrameDiff = kotlin.math.abs(sum - prevReadbackSum)
        prevReadbackSum = sum
    }

    // ---- encoder drain (own thread) ----

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var segStartUs = -1L
        var pendingRoll = false
        while (running.get()) {
            val enc = encoder
            if (enc == null) { SystemClock.sleep(20); continue }
            val idx = try {
                enc.dequeueOutputBuffer(info, 10_000L)
            } catch (e: Exception) { fail("dequeueOutputBuffer: ${e.message}"); break }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer(enc.outputFormat)
                idx >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        if (pendingRoll && isKey) { rollMuxer(); segStartUs = -1L; pendingRoll = false }
                        if (segStartUs < 0) segStartUs = info.presentationTimeUs
                        val buf = try { enc.getOutputBuffer(idx) } catch (e: Exception) { null }
                        if (buf != null) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val adj = MediaCodec.BufferInfo().apply {
                                set(0, info.size, info.presentationTimeUs - segStartUs, info.flags)
                            }
                            try {
                                muxer?.writeSampleData(muxerTrack, buf, adj)
                                bytesWritten.addAndGet(info.size.toLong())
                            } catch (e: Exception) { fail("writeSampleData: ${e.message}") }
                        }
                        encoderOutBuffers.incrementAndGet()
                        if (!pendingRoll && info.presentationTimeUs - segStartUs >= rotationIntervalMs * 1000) {
                            try {
                                enc.setParameters(android.os.Bundle().apply {
                                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                })
                            } catch (e: Exception) { Log.w(TAG, "request sync frame: ${e.message}") }
                            pendingRoll = true
                        }
                    }
                    try { enc.releaseOutputBuffer(idx, false) } catch (e: Exception) {}
                }
            }
        }
    }

    private fun startMuxer(format: MediaFormat) {
        if (muxerStarted) return
        val f = File(context.cacheDir, "glsoak_${System.currentTimeMillis()}.mp4")
        val mx = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerTrack = mx.addTrack(format)
        mx.start()
        muxer = mx; muxerFile = f; muxerStarted = true; muxFiles.add(f)
        Log.i(TAG, "muxer started -> ${f.name}")
    }

    private fun rollMuxer() {
        try { muxer?.stop() } catch (e: Exception) { fail("muxer.stop on roll: ${e.message}") }
        try { muxer?.release() } catch (e: Exception) {}
        muxer = null; muxerStarted = false
        muxerRotations.incrementAndGet()
        val fmt = encoder?.outputFormat ?: return
        startMuxer(fmt)
    }

    // ---- monitoring / teardown ----

    private fun logProgress(elapsedMs: Long) {
        val heapMb = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }) / (1024 * 1024)
        val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val secs = elapsedMs / 1000.0
        Log.i(TAG, "[+${elapsedMs / 1000}s] camFrames=${framesFromCamera.get()} " +
            "(${"%.1f".format(framesFromCamera.get() / secs)}/s) rendered=${framesRendered.get()} " +
            "encOut=${encoderOutBuffers.get()} (${"%.1f".format(encoderOutBuffers.get() / secs)}/s) " +
            "muxRotations=${muxerRotations.get()} MB=${bytesWritten.get() / (1024 * 1024)} " +
            "glErr=${glErrors.get()} fails=${hardFailures.get()} " +
            "lumaSum=$lastLumaSum diff=$lastFrameDiff heapMB=$heapMb nativeMB=$nativeMb")
    }

    private fun teardown() {
        // Stop the camera + drain thread first, so nothing races the muxer/encoder cleanup.
        val camLatch = CountDownLatch(1)
        glHandler.post {
            runCatching { captureSession?.stopRepeating() }
            runCatching { captureSession?.close() }
            runCatching { cameraDevice?.close() }
            camLatch.countDown()
        }
        runCatching { camLatch.await(3, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { drainThread.join(3000) } // exits on running==false

        val latch = CountDownLatch(1)
        glHandler.post {
            runCatching { encoder?.signalEndOfInputStream() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(eglDisplay, encoderWindowSurface) }
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
            runCatching { surfaceTexture?.release() }
            runCatching { cameraSurface?.release() }
            muxFiles.forEach { runCatching { it.delete() } }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        glThread.quitSafely(); camThread.quitSafely()
    }

    private fun verdict(elapsedMs: Long): String {
        val secs = elapsedMs / 1000.0
        val camFps = framesFromCamera.get() / secs
        val encFps = encoderOutBuffers.get() / secs
        val expectedRotations = elapsedMs / rotationIntervalMs
        val ok = hardFailures.get() == 0 &&
            glErrors.get() == 0 &&
            elapsedMs >= durationMs - 5_000 &&
            camFps > 5.0 &&
            encFps > 5.0 &&
            muxerRotations.get() >= expectedRotations - 3 &&
            lastFrameDiff >= 0 && lastLumaSum in 1_000L..(readbackW.toLong() * readbackH * 255 - 1_000)
        return buildString {
            append("GL SOAK ${if (ok) "PASS" else "FAIL"} after ${elapsedMs / 1000}s:\n")
            append("  camera frames = ${framesFromCamera.get()} (${"%.1f".format(camFps)}/s)\n")
            append("  rendered      = ${framesRendered.get()}\n")
            append("  encoder out   = ${encoderOutBuffers.get()} (${"%.1f".format(encFps)}/s)\n")
            append("  muxer rotations = ${muxerRotations.get()} (expected ~$expectedRotations)\n")
            append("  bytes written = ${bytesWritten.get() / (1024 * 1024)} MB\n")
            append("  GL errors     = ${glErrors.get()}\n")
            append("  hard failures = ${hardFailures.get()}${lastFailure?.let { " (last: $it)" } ?: ""}\n")
            append("  last readback luma-sum = $lastLumaSum, inter-frame diff = $lastFrameDiff\n")
        }
    }

    private fun fail(msg: String) {
        hardFailures.incrementAndGet()
        lastFailure = msg
        Log.e(TAG, "FAIL: $msg")
    }

    private fun checkGl(where: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) {
            glErrors.incrementAndGet()
            Log.e(TAG, "glError 0x${Integer.toHexString(e)} at $where")
        }
    }

    // ---- helpers ----

    private fun backCameraId(): String? = cameraManager.cameraIdList.firstOrNull {
        cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
    }

    private fun pickSurfaceTextureSize(camId: String): Pair<Int, Int> {
        val map = cameraManager.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val exact = sizes.firstOrNull { it.width == width && it.height == height }
        if (exact != null) return width to height
        val best = sizes.filter { it.width <= width && it.height <= height }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minByOrNull { it.width.toLong() * it.height }
        return (best?.width ?: width) to (best?.height ?: height)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        require(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        require(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    companion object {
        private const val TAG = "GlSoak"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vTex;
            void main() {
              gl_Position = aPos;
              vTex = (uSTMatrix * aTex).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
    }
}
