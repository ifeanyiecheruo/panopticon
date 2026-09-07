package com.panopticon.phoneapp.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.panopticon.phoneapp.motion.MotionDetector
import com.panopticon.phoneapp.state.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraGlPipeline"
private const val EGL_RECORDABLE_ANDROID = 0x3142
private const val ENCODER_BIT_RATE = 4_000_000
private const val I_FRAME_INTERVAL_SEC = 1
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val FIRST_OUTPUT_TIMEOUT_MS = 5_000L
private const val OUTPUT_STALL_TIMEOUT_MS = 4_000L
private const val SUPERVISE_POLL_MS = 200L
private const val DETECTOR_REFRESH_MS = 5_000L
private const val READBACK_W = 160
private const val READBACK_H = 120

/**
 * Camera2 + **GPU texture fan-out** recording pipeline. Motion-gated, gapless, with pre-roll.
 *
 * One camera stream feeds a `SurfaceTexture`; a GL thread samples that external-OES texture once
 * per frame and does two things: renders a small downscaled copy to an FBO and `glReadPixels` it
 * for frame-difference motion detection ([MotionDetector]), and renders the full frame to an EGL
 * window surface on a `MediaCodec` H.264 encoder's input surface. The **encoder runs
 * continuously** while the pipeline is up - it is never stopped.
 *
 * The `MediaMuxer` is what gates on motion. A **pre-roll ring** holds the last few seconds of
 * encoded access units in memory. When motion is seen, on the next keyframe we open a muxer,
 * prime it from the ring back to ~[preRollMs] before the motion, and write live from there;
 * `setMaxFileSize`-free rotation (request a sync frame at the interval, swap the muxer on the
 * next keyframe) keeps consecutive segments contiguous. [trailerMs] after motion last stops the
 * muxer is finalised and we go back to ring-only.
 *
 * Single camera stream = no `[analysis + video]` two-stream HAL dependency (which the BLU G5's
 * Unisoc SC9863A rejects). Verified sustained on both the Pixel 6 and the BLU G5.
 */
class CameraGlPipeline(
    private val context: Context,
    private val segmentsDir: File,
    private val appConfig: AppConfig,
    private val cameraId: String? = null,
    initialControls: CameraControlSpec = CameraControlSpec(),
    private val rotationIntervalMs: Long = 10_000L,
    private val trailerMs: Long = 5_000L,
    private val preRollMs: Long = 3_000L,
    private val onSegmentFinished: (file: File, createdAtMs: Long, durationMs: Long, width: Int, height: Int) -> Unit,
    private val onHealthChanged: (Boolean) -> Unit,
    private val onPhaseChanged: (recording: Boolean) -> Unit = {},
    private val onMotionChanged: (motion: Boolean) -> Unit = {},
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Manual-control state (see CameraControlApply). Re-applied to the repeating request on
    // applyControls() without a session rebuild; caps are read once the camera id is resolved.
    @Volatile private var controls: CameraControlSpec = initialControls
    @Volatile private var caps: CameraCapabilities? = null
    @Volatile private var resolvedCameraId: String? = null

    /** Set when [cameraId] is a "<logical>:<physical>" target: the session's outputs are pinned
     *  to this physical sub-camera via [PhysicalCameraApi28]. */
    @Volatile private var physicalCameraId: String? = null

    private val callbackThread = HandlerThread("PanopticonCameraCb").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val workExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PanopticonCameraWork") }
    private val scope = CoroutineScope(SupervisorJob() + workExecutor.asCoroutineDispatcher())

    @Volatile private var running = false
    private var runLoopJob: Job? = null

    private var recordingSize = Size(1280, 720)

    // camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    // GL thread (owns EGL + all GL objects)
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
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
    private val quad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }
    private val stMatrix = FloatArray(16)
    private val readback = ByteBuffer.allocateDirect(READBACK_W * READBACK_H * 4).order(ByteOrder.nativeOrder())
    private val lumaPlane = ByteArray(READBACK_W * READBACK_H)
    @Volatile private var detector = MotionDetector(appConfig.get().motionSensitivity)
    private var detectorRefreshedAt = 0L
    @Volatile private var lastMotionReported = false

    // encoder
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    @Volatile private var trackFormat: MediaFormat? = null

    // drain thread + mux/ring state (touched only on the drain thread)
    private var drainThread: Thread? = null
    @Volatile private var drainRunning = false
    private val ring = ArrayDeque<Frame>()
    private val ringDepthUs = (preRollMs + 2_000L) * 1000
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var writing = false
    private var segFile: File? = null
    private var segAnchorPtsUs = -1L
    private var segStartPtsUs = -1L
    private var segLastPtsUs = -1L
    private var pendingRoll = false
    private var recStartPtsUs = -1L
    private var recStartElapsedMs = 0L // SystemClock.elapsedRealtime() at the first encoded frame
    private var recStartEpochMs = 0L   // System.currentTimeMillis() at the same instant
    private val frameDurationMs = 1000L / 30

    // cross-thread coordination
    private val lastMotionMs = AtomicLong(0)
    private val motionEver = AtomicBoolean(false)
    private val fatal = AtomicReference<String?>(null)
    private val lastOutputMs = AtomicLong(0)

    private class Frame(val bytes: ByteArray, val ptsUs: Long, val flags: Int, val key: Boolean)

    fun start() {
        if (running) return
        running = true
        runLoopJob = scope.launch { runLoop() }
    }

    fun stop() {
        running = false
        runLoopJob?.cancel()
        scope.launch { teardown() }
    }

    fun release() {
        stop()
        callbackThread.quitSafely()
        workExecutor.shutdown()
    }

    // ---- supervisor ----

    private suspend fun runLoop() {
        var attempt = 0
        while (running) {
            try {
                fatal.set(null)
                lastOutputMs.set(0)
                openCameraIfNeeded()
                recordingSize = pickRecordingSize()
                setupGlAndEncoder()
                startDrain()
                openSessionAndRequest()
                onHealthChanged(true)
                attempt = 0
                superviseUntilError()
            } catch (e: Exception) {
                Log.e(TAG, "pipeline error (attempt ${attempt + 1})", e)
                onHealthChanged(false)
                runCatching { teardown() }
                detector.reset()
                attempt++
                delay(minOf(500L * attempt, 5000L))
            }
        }
    }

    private suspend fun superviseUntilError() {
        val upAt = SystemClock.elapsedRealtime()
        while (running) {
            fatal.get()?.let { throw IllegalStateException(it) }
            val out = lastOutputMs.get()
            val now = SystemClock.elapsedRealtime()
            if (out == 0L && now - upAt > FIRST_OUTPUT_TIMEOUT_MS) {
                throw IllegalStateException("encoder produced no output ${FIRST_OUTPUT_TIMEOUT_MS}ms after start")
            }
            if (out != 0L && now - out > OUTPUT_STALL_TIMEOUT_MS) {
                throw IllegalStateException("encoder output stalled for ${OUTPUT_STALL_TIMEOUT_MS}ms")
            }
            delay(SUPERVISE_POLL_MS)
        }
    }

    // ---- GL + encoder setup (blocking, driven from the supervisor coroutine) ----

    private fun setupGlAndEncoder() {
        val t = HandlerThread("PanopticonCameraGl").apply { start() }
        glThread = t
        glHandler = Handler(t.looper)
        runOnGl {
            createEncoder()
            setupEgl()
            setupGlObjects()
            val st = SurfaceTexture(oesTexId)
            st.setDefaultBufferSize(recordingSize.width, recordingSize.height)
            st.setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)
            surfaceTexture = st
            cameraSurface = Surface(st)
        }
        Log.i(TAG, "GL + encoder up (${recordingSize.width}x${recordingSize.height})")
    }

    private fun createEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, recordingSize.width, recordingSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, ENCODER_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig failed" }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        encoderWindowSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, encoderInputSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(encoderWindowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, encoderWindowSurface, encoderWindowSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun setupGlObjects() {
        program = buildProgram()
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTex")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); oesTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fb = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); fbo = fb[0]
        val ft = IntArray(1); GLES20.glGenTextures(1, ft, 0); fboTex = ft[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, READBACK_W, READBACK_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ---- per-frame (GL thread) ----

    private fun onFrameAvailable() {
        if (!running) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            fatal.compareAndSet(null, "updateTexImage: ${e.message}"); return
        }
        st.getTransformMatrix(stMatrix)
        val tsNanos = st.timestamp

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        quad.position(0); GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        quad.position(2); GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexLoc)

        // analysis: downscale -> FBO -> readback -> MotionDetector
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, READBACK_W, READBACK_H)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        readback.position(0)
        GLES20.glReadPixels(0, 0, READBACK_W, READBACK_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
        detectMotion()

        // record: full frame -> encoder input surface
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, recordingSize.width, recordingSize.height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderWindowSurface, tsNanos)
        EGL14.eglSwapBuffers(eglDisplay, encoderWindowSurface)
    }

    private fun detectMotion() {
        val now = SystemClock.elapsedRealtime()
        if (now - detectorRefreshedAt > DETECTOR_REFRESH_MS) {
            detector = MotionDetector(appConfig.get().motionSensitivity)
            detectorRefreshedAt = now
        }
        var i = 1 // green channel as a luma proxy for frame-differencing
        for (p in lumaPlane.indices) { lumaPlane[p] = readback.get(i); i += 4 }
        val motion = detector.accept(lumaPlane, READBACK_W, READBACK_H, READBACK_W).motion
        if (motion) {
            lastMotionMs.set(now)
            motionEver.set(true)
        }
        if (motion != lastMotionReported) {
            lastMotionReported = motion
            onMotionChanged(motion)
        }
    }

    // ---- encoder drain / muxer / pre-roll ring (drain thread) ----

    private fun startDrain() {
        drainRunning = true
        val t = Thread({ drainLoop() }, "PanopticonCameraDrain")
        drainThread = t
        t.start()
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (drainRunning && running && fatal.get() == null) {
            val enc = encoder ?: break
            val idx = try {
                enc.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (e: Exception) {
                fatal.compareAndSet(null, "dequeueOutputBuffer: ${e.message}"); break
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> trackFormat = enc.outputFormat
                idx >= 0 -> {
                    lastOutputMs.set(SystemClock.elapsedRealtime())
                    handleEncoded(enc, idx, info)
                    runCatching { enc.releaseOutputBuffer(idx, false) }
                }
            }
        }
        // publish whatever's in flight on the way out
        runCatching { if (writing) finalizeMuxer(publish = true) }
    }

    private fun handleEncoded(enc: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (isConfig || info.size <= 0 || trackFormat == null) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val src = enc.getOutputBuffer(idx) ?: return
        src.position(info.offset); src.limit(info.offset + info.size)
        val bytes = ByteArray(info.size).also { src.get(it) }
        val f = Frame(bytes, info.presentationTimeUs, info.flags, key)

        if (recStartPtsUs < 0) {
            recStartPtsUs = f.ptsUs
            recStartElapsedMs = SystemClock.elapsedRealtime()
            recStartEpochMs = System.currentTimeMillis()
        }
        ring.addLast(f)
        // Retain at least ringDepthUs of history (pre-roll + GOP + start latency).
        while (ring.size >= 2 && f.ptsUs - ring.peekFirst().ptsUs > ringDepthUs) ring.removeFirst()

        val now = SystemClock.elapsedRealtime()
        val shouldWrite = motionEver.get() && now - lastMotionMs.get() < trailerMs

        if (writing) {
            if (shouldWrite) {
                writeFrame(f)
            } else {
                finalizeMuxer(publish = true)
                writing = false
                onPhaseChanged(false)
            }
        } else if (shouldWrite && key) {
            openSegmentFromRing(now) // sets writing = true, fires onPhaseChanged(true)
        }
    }

    private fun openSegmentFromRing(nowMs: Long) {
        val fmt = trackFormat ?: return
        segFile = File(segmentsDir, segmentFileName())
        val mx = MediaMuxer(segFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerTrack = mx.addTrack(fmt)
        mx.start()
        muxer = mx

        // start from the last keyframe at/before (now - preRollMs), else the oldest keyframe held.
        val targetPtsUs = recStartPtsUs + (nowMs - preRollMs - recStartElapsedMs) * 1000
        val frames: Array<Frame> = ring.toTypedArray()
        var startIdx = -1
        for (i in frames.indices) {
            if (frames[i].key && frames[i].ptsUs <= targetPtsUs) startIdx = i
        }
        if (startIdx < 0) startIdx = frames.indexOfFirst { it.key }
        if (startIdx < 0) startIdx = 0

        segAnchorPtsUs = frames[startIdx].ptsUs
        segStartPtsUs = segAnchorPtsUs
        segLastPtsUs = segAnchorPtsUs
        for (i in startIdx until frames.size) writeSample(frames[i])
        writing = true
        pendingRoll = false
        onPhaseChanged(true)
        Log.i(TAG, "segment ${segFile?.name} opened (pre-roll ${(frames.last().ptsUs - segAnchorPtsUs) / 1000}ms)")
    }

    private fun writeFrame(f: Frame) {
        if (pendingRoll && f.key) rollMuxer(f.ptsUs)
        writeSample(f)
        if (!pendingRoll && f.ptsUs - segStartPtsUs >= rotationIntervalMs * 1000) {
            runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            pendingRoll = true
        }
    }

    private fun writeSample(f: Frame) {
        val mx = muxer ?: return
        val buf = ByteBuffer.wrap(f.bytes)
        val bi = MediaCodec.BufferInfo().apply { set(0, f.bytes.size, f.ptsUs - segAnchorPtsUs, f.flags) }
        runCatching { mx.writeSampleData(muxerTrack, buf, bi) }
        segLastPtsUs = f.ptsUs
    }

    private fun rollMuxer(newAnchorPtsUs: Long) {
        finalizeMuxer(publish = true)
        val fmt = trackFormat ?: return
        segFile = File(segmentsDir, segmentFileName())
        val mx = MediaMuxer(segFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerTrack = mx.addTrack(fmt)
        mx.start()
        muxer = mx
        segAnchorPtsUs = newAnchorPtsUs
        segStartPtsUs = newAnchorPtsUs
        segLastPtsUs = newAnchorPtsUs
        pendingRoll = false
    }

    private fun finalizeMuxer(publish: Boolean) {
        val mx = muxer ?: return
        val file = segFile
        val anchor = segAnchorPtsUs
        val last = segLastPtsUs
        muxer = null
        muxerTrack = -1
        val stopped = runCatching { mx.stop() }.onFailure { Log.w(TAG, "muxer.stop threw", it) }.isSuccess
        runCatching { mx.release() }
        if (file == null) return
        if (publish && stopped && anchor >= 0 && last > anchor && file.exists() && file.length() > 0L) {
            val createdAtMs = recStartEpochMs + (anchor - recStartPtsUs) / 1000
            val durationMs = (last - anchor) / 1000 + frameDurationMs
            publishSegment(file, createdAtMs, durationMs)
        } else if (file.exists()) {
            runCatching { file.delete() }
        }
    }

    private fun publishSegment(file: File, startedAtMs: Long, durationMs: Long) {
        if (!file.exists() || file.length() == 0L) { runCatching { file.delete() }; return }
        Log.i(TAG, "segment ${file.name}: start=$startedAtMs dur=${durationMs}ms size=${file.length()}B")
        logKeyframeCadence(file)
        onSegmentFinished(file, startedAtMs, durationMs.coerceAtLeast(0L), recordingSize.width, recordingSize.height)
    }

    // ---- camera ----

    private suspend fun openCameraIfNeeded() {
        if (cameraDevice != null) return
        val id = cameraId?.takeIf { it.isNotBlank() } ?: backCameraId()
            ?: throw IllegalStateException("no back-facing camera")
        resolvedCameraId = id
        // "<logical>:<physical>" - open the logical device; pin the session's outputs to the
        // physical sub-camera in createCaptureSession.
        val (logicalId, physId) = CameraCapabilitiesReader.splitTarget(id)
        physicalCameraId = physId?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P }
        caps = runCatching { CameraCapabilitiesReader.read(context, id) }.getOrNull()
        cameraDevice = openCameraDevice(logicalId)
    }

    private suspend fun openSessionAndRequest() {
        val device = cameraDevice ?: throw IllegalStateException("camera closed")
        val surface = cameraSurface ?: throw IllegalStateException("no camera surface")
        val failed = AtomicBoolean(false)
        val session = createCaptureSession(device, listOf(surface), failed)
        delay(500)
        if (failed.get() || !running) { session.close(); throw IllegalStateException("session failed asynchronously") }
        session.setRepeatingRequest(buildRecordRequest(device, surface), null, callbackHandler)
        captureSession = session
        Log.i(TAG, "capture session up")
    }

    /**
     * Merge a new manual-control state and, if a session is live, re-issue the repeating request
     * so it takes effect without tearing the pipeline down (only a *camera switch* rebuilds).
     * Safe to call before the session is up - the state is kept and applied by
     * [buildRecordRequest] when the session next configures.
     */
    fun applyControls(spec: CameraControlSpec) {
        controls = spec
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = cameraSurface ?: return
        runCatching {
            session.setRepeatingRequest(buildRecordRequest(device, surface), null, callbackHandler)
        }.onFailure { Log.w(TAG, "applyControls: setRepeatingRequest failed", it) }
    }

    private fun buildRecordRequest(device: CameraDevice, surface: Surface): CaptureRequest =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            caps?.let { CameraControlApply.applyTo(this, controls, it) }
        }.build()

    private fun backCameraId(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private suspend fun openCameraDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            @Suppress("MissingPermission")
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { if (cont.isActive) cont.resume(device) }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); if (cameraDevice === device) cameraDevice = null
                    fatal.compareAndSet(null, "camera disconnected")
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); if (cameraDevice === device) cameraDevice = null
                    fatal.compareAndSet(null, "camera error $error")
                    if (cont.isActive) cont.resumeWithException(RuntimeException("camera open error $error"))
                }
            }, callbackHandler)
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        } catch (e: SecurityException) {
            cont.resumeWithException(e)
        }
    }

    private suspend fun createCaptureSession(
        device: CameraDevice, surfaces: List<Surface>, sessionFailed: AtomicBoolean,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) { if (cont.isActive) cont.resume(session) }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                sessionFailed.set(true)
                if (cont.isActive) cont.resumeWithException(IllegalStateException("session configure failed"))
            }
            override fun onClosed(session: CameraCaptureSession) { sessionFailed.set(true) }
        }
        try {
            val physId = physicalCameraId
            if (physId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PhysicalCameraApi28.createSession(
                    device, surfaces, physId, { callbackHandler.post(it) }, cb,
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, cb, callbackHandler)
            }
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        }
    }

    // ---- teardown ----

    private fun teardown() {
        drainRunning = false
        runOnGl {
            runCatching { captureSession?.stopRepeating() }
            runCatching { captureSession?.close() }
            runCatching { cameraDevice?.close() }
        }
        captureSession = null
        cameraDevice = null
        runCatching { drainThread?.join(3000) }
        drainThread = null

        runOnGl {
            runCatching { encoder?.signalEndOfInputStream() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            encoder = null
            runCatching { encoderInputSurface?.release() }
            encoderInputSurface = null
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(eglDisplay, encoderWindowSurface) }
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
            runCatching { surfaceTexture?.release() }
            runCatching { cameraSurface?.release() }
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        encoderWindowSurface = EGL14.EGL_NO_SURFACE
        surfaceTexture = null
        cameraSurface = null
        trackFormat = null
        ring.clear()
        writing = false
        muxer = null
        segFile = null
        segAnchorPtsUs = -1L; segStartPtsUs = -1L; segLastPtsUs = -1L
        recStartPtsUs = -1L; recStartElapsedMs = 0L; recStartEpochMs = 0L
        pendingRoll = false
        lastMotionReported = false

        glHandler = null
        glThread?.quitSafely()
        glThread = null
    }

    private fun runOnGl(block: () -> Unit) {
        val h = glHandler ?: return
        if (Thread.currentThread() === glThread) { block(); return }
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        h.post {
            try { block() } catch (e: Throwable) { err = e } finally { latch.countDown() }
        }
        if (!latch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("GL op timed out")
        err?.let { throw it }
    }

    // ---- helpers ----

    private fun pickRecordingSize(): Size {
        val target = resolvedCameraId ?: cameraId?.takeIf { it.isNotBlank() } ?: backCameraId()
            ?: return Size(1280, 720)
        // For a "<logical>:<physical>" target the physical sensor's own stream map applies.
        val (logicalId, physId) = CameraCapabilitiesReader.splitTarget(target)
        val id = physId ?: logicalId
        val map = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val camSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val avc = codecList.codecInfos.firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        val caps = avc?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        val supported = camSizes.filter { caps == null || caps.isSizeSupported(it.width, it.height) }
        parseVideoSize(appConfig.get().videoResolution)?.let { want ->
            supported.firstOrNull { it.width == want.width && it.height == want.height }?.let { return it }
        }
        return supported.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: supported.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
    }

    private fun segmentFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val suffix = (Math.random() * 0xffff).toInt().toString(16).padStart(4, '0')
        return "clip_${ts}_$suffix.mp4"
    }

    private fun buildProgram(): Int {
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

    private fun logKeyframeCadence(file: File) {
        try {
            val ex = MediaExtractor()
            ex.setDataSource(file.absolutePath)
            var vt = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vt = i; break }
            }
            if (vt < 0) return
            ex.selectTrack(vt)
            val kf = mutableListOf<Long>()
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) kf.add(t)
                if (!ex.advance()) break
            }
            ex.release()
            val deltas = kf.zipWithNext { a, b -> (b - a) / 1000 }
            Log.i(TAG, "keyframe cadence ${file.name}: ${deltas.size} intervals deltas(ms)=$deltas")
        } catch (e: Exception) {
            Log.w(TAG, "keyframe cadence probe failed for ${file.name}", e)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uSTMatrix * aTex).xy; }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
    }
}
