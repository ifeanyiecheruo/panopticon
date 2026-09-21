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
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.panopticon.phoneapp.calibration.CalibrationStore
import com.panopticon.phoneapp.calibration.RectNorm

private const val TAG = "LivePipeline"
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val EGL_RECORDABLE_ANDROID = 0x3142

/**
 * LIVE-mode camera pipeline: a single camera stream rendered through a small GL blit (centre-crop
 * to the target aspect ratio, then rotate per [rotationDegrees] - see [GlBlit] and
 * [CameraFraming], shared with [CameraGlPipeline]'s RECORD path so the two apply both identically)
 * into a `MediaCodec` H.264 encoder's input surface, drained to a [LiveHlsRelay] that produces a
 * rolling plain-HLS playlist. No motion detection here (per docs/design/http-api.md's "motion
 * detection stays out of live preview") - just the crop/rotate step RECORD also needs.
 *
 * RECORD and LIVE are mutually exclusive (see [com.panopticon.phoneapp.state.AppMode]); this only
 * exists while the phone is in LIVE mode. Two sub-states:
 *  - **armed-idle** ([start] done, [startBroadcasting] not called): camera open, capture session
 *    configured with the SurfaceTexture-backed camera surface, but the repeating request doesn't
 *    target it - no frames flow, nothing encodes. Costs a warm camera, not battery for encoding
 *    nobody's watching.
 *  - **broadcasting** ([startBroadcasting]): the repeating request targets the camera surface and
 *    the relay runs. A [inactivityTimeoutMs] no-request watchdog drops back to armed-idle.
 *
 * Keyframe cadence: `KEY_I_FRAME_INTERVAL = 1s` **and** an explicit `REQUEST_SYNC_FRAME` timer
 * (see [startSyncFrameLoop]) at half the segment target - belt and braces, so [LiveHlsRelay] can
 * rotate on a real keyframe on cadence regardless of how a given device honours the format hint.
 * The capture rate is also pinned via `CONTROL_AE_TARGET_FPS_RANGE` so the encoder gets a steady
 * frame cadence for hls.js's buffer maths.
 */
class LivePipeline(
    private val context: Context,
    private val liveDir: File,
    private val cameraId: String? = null,
    initialControls: CameraControlSpec = CameraControlSpec(),
    /** Requested record/broadcast size "<w>x<h>"; "" = pick 720p-ish. */
    private val videoResolution: String = "",
    /** Mounting-orientation correction (0/90/180/270) - see [CameraFraming.normalizedRotation].
     *  A change requires recreating the pipeline (the encoder's dimensions may swap for 90/270),
     *  same as a resolution/camera change - there's no light re-apply path for this. */
    rotationDegreesConfig: Int = 0,
    private val bitRate: Int = 2_000_000,
    private val frameRate: Int = 24,
    private val segmentDurationUs: Long = LiveHlsRelay.DEFAULT_SEGMENT_DURATION_US,
    private val inactivityTimeoutMs: Long = 15_000L,
    private val onHealthChanged: (Boolean) -> Unit = {},
    private val onBroadcastingChanged: (Boolean) -> Unit = {},
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val rotationDegrees = CameraFraming.normalizedRotation(rotationDegreesConfig)

    // Manual-control state (see CameraControlApply). Re-applied to the repeating request on
    // applyControls(); the caps are read once the camera id is resolved in arm().
    @Volatile private var controls: CameraControlSpec = initialControls
    @Volatile private var caps: CameraCapabilities? = null

    // Zoom is split between a centred hardware magnification and a GL crop (ZoomGeometry.split),
    // against this phone's own calibration sweep. The split depends on the aspect trim, which only
    // exists once frames flow, so it is recomputed on the first frame as well as on every control
    // change. Reading a stale split for a frame or two after a change is harmless: the hardware
    // takes a few frames to act on a new zoom anyway.
    @Volatile private var zoomLut: ZoomCalibrationLut.Lut = ZoomCalibrationLut.Lut.NONE
    @Volatile private var zoomSplit: ZoomGeometry.Split? = null
    @Volatile private var openCameraId: String? = null
    /** Set when [cameraId] is "<logical>:<physical>": session outputs pinned via [PhysicalCameraApi28]. */
    @Volatile private var physicalCameraId: String? = null

    private val callbackThread = HandlerThread("PanopticonLiveCb").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val workExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PanopticonLiveWork") }
    private val scope = CoroutineScope(SupervisorJob() + workExecutor.asCoroutineDispatcher())
    private val mutex = Mutex()

    // Sizing: recordingSize is the pre-rotation content-aspect target (matched to RECORD mode's
    // choice, see pickRecordingSize); sourceSize is the camera -> SurfaceTexture buffer (sensor
    // aspect, cropped to recordingSize's aspect via texCropX/Y); outputSize is recordingSize with
    // width/height swapped for a 90/270 rotation - the actual encoder dimensions.
    private var recordingSize = Size(1280, 720)
    private var sourceSize = Size(1280, 720)
    private var texCropX = 1f
    private var texCropY = 1f
    private var outputSize = Size(1280, 720)
    private var outputQuad: java.nio.FloatBuffer? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // GL thread (owns EGL + all GL objects) - mirrors CameraGlPipeline's, minus the
    // motion-analysis FBO pass, which LIVE doesn't need.
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
    private var uTexRectLoc = 0
    private var oesTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    // The real crop needs the camera's transform matrix, which doesn't exist until frames flow, so
    // it's computed on the first frame (before that first draw) rather than at setup.
    @Volatile private var texCropFinalized = false

    /** The crop actually being rendered with, or null before the first frame has been drawn.
     *  `POST /api/camera/state` needs it to map a rect drawn on the preview back to the sensor -
     *  see `CameraRoutes.adjustIncomingRects`. */
    fun currentTexCrop(): Pair<Float, Float>? =
        if (texCropFinalized) texCropX to texCropY else null

    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null

    @Volatile private var relay: LiveHlsRelay? = null
    @Volatile private var trackFormat: MediaFormat? = null
    private var drainThread: Thread? = null
    @Volatile private var drainRunning = false
    private var watchdogJob: Job? = null
    private var syncFrameJob: Job? = null

    private val armed = CompletableDeferred<Boolean>()
    private val broadcasting = AtomicBoolean(false)
    private val lastAccessMs = AtomicLong(0)
    @Volatile private var released = false

    val isBroadcasting: Boolean get() = broadcasting.get()

    fun currentPlaylist(): String? = relay?.currentPlaylist()

    fun readSegment(seq: Int): ByteArray? = relay?.readSegment(seq)

    // ---- lifecycle ----

    /** Enter armed-idle: open the camera and configure the session, no frames flowing yet. */
    fun start() {
        scope.launch {
            try {
                arm()
                onHealthChanged(true)
                if (!armed.isCompleted) armed.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "live arm failed", e)
                onHealthChanged(false)
                if (!armed.isCompleted) armed.complete(false)
            }
        }
    }

    /** Leave LIVE mode entirely - stop broadcasting, tear the camera down, kill the threads. */
    fun release() {
        released = true
        scope.launch {
            mutex.withLock { stopBroadcastingLocked() }
            teardown()
        }.invokeOnCompletion {
            callbackThread.quitSafely()
            workExecutor.shutdown()
        }
    }

    // ---- viewer-triggered (called from LiveRoutes, which is a suspend context) ----

    /** Idempotent. Begins broadcasting (repeating request targets the camera surface, relay runs).
     * Returns the viewer count (1); 0 if the camera failed to arm (hard failure);
     * [STILL_ARMING] (-1) if arming is still in progress and the caller should retry. */
    suspend fun startBroadcasting(): Int = mutex.withLock {
        lastAccessMs.set(SystemClock.elapsedRealtime())
        if (broadcasting.get()) return@withLock 1
        // Arming (open camera + configure the capture session) can take several
        // seconds on a cold front-facing camera. Distinguish "not done yet"
        // (tell the caller to retry) from "arm() reported failure" (give up).
        when (withTimeoutOrNull(10_000L) { armed.await() }) {
            null -> {
                Log.w(TAG, "startBroadcasting: camera still arming, ask caller to retry")
                return@withLock STILL_ARMING
            }
            false -> {
                Log.w(TAG, "startBroadcasting: camera failed to arm")
                return@withLock 0
            }
            else -> {} // armed OK — fall through
        }
        val device = cameraDevice ?: return@withLock STILL_ARMING
        val session = captureSession ?: return@withLock STILL_ARMING
        val surface = cameraSurface ?: return@withLock STILL_ARMING

        relay = LiveHlsRelay(liveDir, segmentDurationUs)
        startDrain()
        session.setRepeatingRequest(buildLiveRequest(device, surface), null, callbackHandler)
        broadcasting.set(true)
        startWatchdog()
        startSyncFrameLoop()
        onBroadcastingChanged(true)
        Log.i(TAG, "live broadcasting started (${outputSize.width}x${outputSize.height} @ ${bitRate / 1000}kbps)")
        1
    }

    /** Explicit stop. The watchdog calls the same path after [inactivityTimeoutMs] of no GETs. */
    suspend fun stopBroadcasting() = mutex.withLock { stopBroadcastingLocked() }

    /** Called on every playlist/segment GET so the inactivity watchdog knows someone's watching. */
    fun touch() = lastAccessMs.set(SystemClock.elapsedRealtime())

    /**
     * Merge a new manual-control state and, if currently broadcasting, re-issue the repeating
     * request so it takes effect without a session rebuild. A no-op if the camera isn't up yet;
     * the state is kept and applied by [buildLiveRequest] when broadcasting next starts.
     */
    fun applyControls(spec: CameraControlSpec) {
        controls = spec
        // A zoom change moves work between the camera and GL, so the split is redone before the
        // request is rebuilt below. The shader picks the new crop up on its next frame.
        recomputeZoomSplit()
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = cameraSurface ?: return
        if (!broadcasting.get()) return
        runCatching {
            session.setRepeatingRequest(buildLiveRequest(device, surface), null, callbackHandler)
        }.onFailure { Log.w(TAG, "applyControls: setRepeatingRequest failed", it) }
    }

    /** The region of the full field of view the buffer currently holds - the whole frame until a
     *  hardware zoom is in effect. The shader places the requested view inside it. */
    private fun deliveredCrop(): RectNorm = zoomSplit?.deliveredCrop ?: ZoomGeometry.FULL

    /** The view the user asked for, or null when running full auto. */
    private fun zoomView(): RectNorm? =
        controls.keys.zoomViewNorm.takeIf { controls.manualControlEnabled }

    /** Recompute how much of the zoom the camera should do. Returns true when the hardware half
     *  changed, meaning the repeating request has to be re-issued to take effect. */
    private fun recomputeZoomSplit(): Boolean {
        val previous = zoomSplit?.hwRatio ?: 1f
        val split = ZoomGeometry.split(
            viewSensor = ZoomGeometry.viewToSensor(zoomView() ?: ZoomGeometry.FULL, texCropX, texCropY),
            lut = zoomLut.entries,
            texCropX = texCropX,
            texCropY = texCropY,
        )
        zoomSplit = split
        return kotlin.math.abs(split.hwRatio - previous) > 0.01f
    }

    private fun buildLiveRequest(device: CameraDevice, surface: Surface): CaptureRequest =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            // Pin the capture rate so the encoder gets a steady frame cadence - a HAL that drops
            // to 15fps for exposure makes hls.js's buffer maths lumpy.
            stableFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            caps?.let {
                CameraControlApply.applyTo(this, controls, it)
                CameraControlApply.applyZoom(this, zoomSplit?.hwRatio ?: 1f, it)
            }
        }.build()

    /** Re-issue the repeating request so a changed hardware zoom takes effect, without rebuilding
     *  the session. Safe to call from the GL thread. */
    private fun reissueRepeatingRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = cameraSurface ?: return
        if (!broadcasting.get()) return
        runCatching {
            session.setRepeatingRequest(buildLiveRequest(device, surface), null, callbackHandler)
        }.onFailure { Log.w(TAG, "reissueRepeatingRequest failed", it) }
    }

    private fun stopBroadcastingLocked() {
        watchdogJob?.cancel()
        watchdogJob = null
        syncFrameJob?.cancel()
        syncFrameJob = null
        if (!broadcasting.getAndSet(false)) return
        runCatching { captureSession?.stopRepeating() }
        drainRunning = false
        runCatching { drainThread?.join(2000) }
        drainThread = null
        relay?.stop()
        relay = null
        trackFormat = null
        onBroadcastingChanged(false)
        Log.i(TAG, "live broadcasting stopped (back to armed-idle)")
    }

    // ---- internals ----

    private suspend fun arm() {
        val id = cameraId?.takeIf { it.isNotBlank() } ?: backCameraId()
            ?: throw IllegalStateException("no back-facing camera")
        openCameraId = id
        val (logicalId, physId) = CameraCapabilitiesReader.splitTarget(id)
        physicalCameraId = physId?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P }
        caps = runCatching { CameraCapabilitiesReader.read(context, id) }.getOrNull()
        val sizingId = physId ?: logicalId
        recordingSize = pickRecordingSize(sizingId)
        // The zoom map is per camera and per output size, so it is loaded here rather than once
        // at construction. An uncalibrated phone yields an empty table, which simply means the
        // camera is asked for nothing and GL does all the zooming.
        zoomLut = ZoomCalibrationLut.build(
            CalibrationStore(context).load(), id, recordingSize.width, recordingSize.height,
        )
        zoomSplit = null
        if (!zoomLut.isEmpty) {
            Log.i(TAG, "zoom map: camera ${zoomLut.sourceCameraId} @ ${zoomLut.sourceResolution}, ${zoomLut.entries.size} samples")
        }
        sourceSize = CameraFraming.pickSourceSize(cameraManager, sizingId, recordingSize)
        outputSize = CameraFraming.rotatedOutputSize(recordingSize, rotationDegrees)
        cameraDevice = openCameraDevice(logicalId)
        setupGlAndEncoder()
        val surface = cameraSurface ?: throw IllegalStateException("no camera surface")
        val device = cameraDevice ?: throw IllegalStateException("camera closed")
        val failed = AtomicBoolean(false)
        val session = createCaptureSession(device, listOf(surface), failed)
        delay(300)
        if (failed.get() || released) {
            session.close()
            throw IllegalStateException("live session failed to configure")
        }
        captureSession = session
        Log.i(
            TAG,
            "live camera armed-idle (out ${outputSize.width}x${outputSize.height}, rotation $rotationDegrees, " +
                "camera source ${sourceSize.width}x${sourceSize.height})",
        )
    }

    // ---- GL + encoder setup (blocking, driven from arm()'s coroutine) ----

    private fun setupGlAndEncoder() {
        val t = HandlerThread("PanopticonLiveGl").apply { start() }
        glThread = t
        glHandler = Handler(t.looper)
        runOnGl {
            createEncoder()
            setupEgl()
            setupGlObjects()
            val st = SurfaceTexture(oesTexId)
            st.setDefaultBufferSize(sourceSize.width, sourceSize.height)
            st.setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)
            surfaceTexture = st
            cameraSurface = Surface(st)
        }
    }

    private fun createEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputSize.width, outputSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
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
        program = GlBlit.buildProgram()
        outputQuad = GlBlit.quad(rotationDegrees)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTex")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uTexRectLoc = GLES20.glGetUniformLocation(program, "uTexRect")
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); oesTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    // ---- per-frame (GL thread) ----

    private fun onFrameAvailable() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        st.getTransformMatrix(stMatrix)
        if (!texCropFinalized) {
            val (cropX, cropY) = CameraFraming.correctedTexCrop(sourceSize, recordingSize, stMatrix)
            texCropX = cropX; texCropY = cropY
            texCropFinalized = true
            Log.i(TAG, "texCrop for this camera's transform: $cropX,$cropY")
            // The split could not be computed before this point, so re-issue the request if the
            // camera now turns out to be able to do part of the zoom itself.
            if (recomputeZoomSplit()) reissueRepeatingRequest()
        }
        val tsNanos = st.timestamp
        val quad = outputQuad ?: return

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        // One rect does both jobs: the fixed output-aspect trim, and whatever zoom the camera
        // hardware isn't doing off-centre. Un-zoomed it is exactly the centred trim this drew
        // before zoom existed.
        // manualControlEnabled is the master switch: full auto means no zoom either, same as it
        // means no manual exposure (see CameraControlApply's early return).
        // texRectUniform, not shaderRect: the uniform's Y axis runs bottom-up while every rect
        // here is viewer-oriented (top-down), so it has to be flipped on the way in. deliveredCrop
        // is what the camera is already zoomed to, so the view is placed inside it.
        val u = ZoomGeometry.texRectUniform(zoomView(), texCropX, texCropY, deliveredCrop())
        GLES20.glUniform4f(uTexRectLoc, u[0], u[1], u[2], u[3])
        quad.position(0); GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        quad.position(2); GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexLoc)

        GLES20.glViewport(0, 0, outputSize.width, outputSize.height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderWindowSurface, tsNanos)
        EGL14.eglSwapBuffers(eglDisplay, encoderWindowSurface)
    }

    private fun startDrain() {
        drainRunning = true
        val t = Thread({ drainLoop() }, "PanopticonLiveDrain")
        drainThread = t
        t.start()
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (drainRunning && !released) {
            val enc = encoder ?: break
            val idx = try {
                enc.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "dequeueOutputBuffer failed", e)
                onHealthChanged(false)
                break
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> trackFormat = enc.outputFormat
                idx >= 0 -> {
                    val fmt = trackFormat
                    if (fmt != null && info.size > 0) {
                        val src = enc.getOutputBuffer(idx)
                        if (src != null) {
                            src.position(info.offset)
                            src.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also { src.get(it) }
                            relay?.feed(bytes, info.presentationTimeUs, info.flags, fmt)
                        }
                    }
                    runCatching { enc.releaseOutputBuffer(idx, false) }
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (broadcasting.get()) {
                delay(5_000)
                if (SystemClock.elapsedRealtime() - lastAccessMs.get() > inactivityTimeoutMs) {
                    Log.i(TAG, "no live-view activity for ${inactivityTimeoutMs}ms, returning to armed-idle")
                    mutex.withLock { stopBroadcastingLocked() }
                    return@launch
                }
            }
        }
    }

    /** Ask the encoder for a keyframe on the segment cadence, so [LiveHlsRelay] can rotate on a
     * real keyframe roughly every [segmentDurationUs] no matter how a given device honours
     * `KEY_I_FRAME_INTERVAL`. Requested twice per target so at least one lands near the boundary
     * (some HALs skip roughly every other request - see the prototype's QUIRKS.md). */
    private fun startSyncFrameLoop() {
        syncFrameJob?.cancel()
        val intervalMs = (segmentDurationUs / 1000 / 2).coerceAtLeast(250)
        syncFrameJob = scope.launch {
            while (broadcasting.get()) {
                delay(intervalMs)
                runCatching {
                    encoder?.setParameters(android.os.Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    })
                }
            }
        }
    }

    private fun stableFpsRange(): Range<Int>? {
        val id = cameraDevice?.id ?: return null
        val ranges = runCatching {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }.getOrNull() ?: return null
        // Prefer a fixed range at the target rate (lower == upper == frameRate); otherwise the
        // fixed range with the highest rate <= frameRate; otherwise the widest range topping out
        // at frameRate.
        return ranges.firstOrNull { it.lower == frameRate && it.upper == frameRate }
            ?: ranges.filter { it.lower == it.upper && it.upper <= frameRate }.maxByOrNull { it.upper }
            ?: ranges.filter { it.upper <= frameRate }.maxByOrNull { it.lower }
            ?: ranges.minByOrNull { it.upper }
    }

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

    // ---- camera helpers (same shape as CameraGlPipeline's, kept local) ----

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
                    onHealthChanged(false)
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); if (cameraDevice === device) cameraDevice = null
                    onHealthChanged(false)
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

    companion object {
        /** [startBroadcasting] return value: the camera is still coming up; retry shortly. */
        const val STILL_ARMING = -1
    }

    /** Match RECORD mode's aspect ratio (indeed, since both now enumerate sizes the same way via
     *  [RecordingSizeSelection.recordModeSize], the exact same size) so the live preview's
     *  viewport is the same field of view actually captured when recording. */
    private fun pickRecordingSize(id: String): Size =
        RecordingSizeSelection.recordModeSize(cameraManager, id, videoResolution) ?: Size(1280, 720)
}

/** "1920x1080" -> Size(1920, 1080); anything unparseable -> null. */
internal fun parseVideoSize(s: String): Size? {
    val parts = s.split('x', 'X')
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    return if (w > 0 && h > 0) Size(w, h) else null
}
