package com.panopticon.phoneapp.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
import com.panopticon.phoneapp.motion.CameraDisturbance
import com.panopticon.phoneapp.motion.MotionAnalyzer
import com.panopticon.phoneapp.motion.MotionGate
import com.panopticon.phoneapp.motion.MotionTrace
import com.panopticon.phoneapp.state.AppConfig
import com.panopticon.phoneapp.state.ThermalReader
import com.panopticon.phoneapp.state.ThermalStatus
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
import com.panopticon.phoneapp.calibration.CalibrationStore
import com.panopticon.phoneapp.calibration.RectNorm

private const val TAG = "CameraGlPipeline"
private const val EGL_RECORDABLE_ANDROID = 0x3142
private const val ENCODER_BIT_RATE = 4_000_000
private const val I_FRAME_INTERVAL_SEC = 1
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val FIRST_OUTPUT_TIMEOUT_MS = 5_000L
private const val OUTPUT_STALL_TIMEOUT_MS = 4_000L
private const val SUPERVISE_POLL_MS = 200L
private const val READBACK_W = 160
private const val READBACK_H = 120
private const val ROLE = "record"

/** Capture results a fresh pipeline must deliver before its start-up disturbance is over - about
 *  two thirds of a second at 30fps, which is enough for auto-exposure and white balance to settle
 *  on both test devices. */
private const val PIPELINE_SETTLE_RESULTS = 20L

/** How often to sample thermal state across a run. Slow on purpose: the effect being chased
 *  builds over minutes, and PowerManager rate-limits the headroom read to once a second. */
private const val THERMAL_SAMPLE_MS = 10_000L

/**
 * Analyse one frame in every N for motion.
 *
 * `1` is the original behaviour: every frame, and a synchronous `glReadPixels` flush on every
 * frame with it. `0` disables motion detection altogether - **diagnostic only**, since nothing
 * will ever record; it exists so the readback can be removed from the frame loop to prove
 * whether it, rather than `eglSwapBuffers` backpressure from the encoder, is what starves the
 * camera's buffer queue.
 *
 * At 30fps, 3 gives motion detection a 100ms cadence, which is far inside what a fixed camera
 * needs, and cuts the number of pipeline flushes by two thirds.
 */
private const val ANALYSIS_FRAME_INTERVAL = 1

/** Below this much free space a new segment isn't even attempted (see CameraGlPipeline.newMuxer):
 *  one 10s segment at 4K is ~40MB, so this is a few segments' worth. */
private const val MIN_FREE_TO_OPEN_BYTES = 200L * 1024 * 1024

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
    private val healthRegistry: CameraHealthRegistry = CameraHealthRegistry(),
    /** See [ANALYSIS_FRAME_INTERVAL]. A parameter so an experiment can vary it without touching
     *  the frame loop. */
    private val analysisFrameInterval: Int = ANALYSIS_FRAME_INTERVAL,
    private val onSegmentFinished: (file: File, createdAtMs: Long, durationMs: Long, width: Int, height: Int) -> Unit,
    private val onHealthChanged: (Boolean) -> Unit,
    private val onPhaseChanged: (recording: Boolean) -> Unit = {},
    private val onMotionChanged: (motion: Boolean) -> Unit = {},
    /** Asked to free space when a segment can't be opened for lack of it - see [newMuxer]. */
    private val onStorageLow: () -> Unit = {},
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Manual-control state (see CameraControlApply). Re-applied to the repeating request on
    // applyControls() without a session rebuild; caps are read once the camera id is resolved.
    @Volatile private var controls: CameraControlSpec = initialControls
    @Volatile private var caps: CameraCapabilities? = null

    // Zoom is split between a centred hardware magnification and a GL crop (ZoomGeometry.split),
    // against this phone's own calibration sweep. The split depends on the aspect trim, which only
    // exists once frames flow, so it is recomputed on the first frame as well as on every control
    // change.
    //
    // Requested and delivered are deliberately separate. A CONTROL_ZOOM_RATIO change takes ~10
    // frames to reach the buffer (on the Pixel 6 via a reconfigure that drops a frame or two),
    // and the shader used to adopt the requested split immediately - normalising the view against
    // a crop the buffer did not hold yet, which renders the wrong region, magnified further and
    // soft, until the hardware lands and the picture snaps. That snap is a whole-frame change, so
    // motion detection recorded it: it accounted for the majority of all clips (see
    // docs/status/camera-stall-investigation.md). So the shader now renders against what
    // CaptureResults say the camera is actually delivering ([zoomFeedback]), which makes every
    // intermediate frame correct - the view holds still and merely sharpens.
    @Volatile private var zoomLut: ZoomCalibrationLut.Lut = ZoomCalibrationLut.Lut.NONE
    @Volatile private var requestedSplit: ZoomGeometry.Split? = null
    private val zoomFeedback = ZoomFeedback()
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
    // The camera → SurfaceTexture buffer size. When the sensor's native aspect
    // ratio differs from [recordingSize]'s, this is a sensor-aspect size (so the
    // HAL fills it without anamorphic squash) and the GL blit centre-crops it to
    // [recordingSize]'s aspect via [texCropX]/[texCropY]. Equal to [recordingSize]
    // when no correction is needed.
    private var sourceSize = Size(1280, 720)
    private var texCropX = 1f
    private var texCropY = 1f
    // User-configured mounting-orientation correction (0/90/180/270), re-read on every pipeline
    // (re)start in runLoop(). outputSize is recordingSize with width/height swapped for a 90/270
    // rotation - the actual encoder/segment dimensions once GlBlit's rotated quad is applied.
    private var rotationDegrees = 0
    private var outputSize = Size(1280, 720)
    private var outputQuad: java.nio.FloatBuffer? = null

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
    private var uTexRectLoc = 0
    private var oesTexId = 0
    private var fbo = 0
    private var fboTex = 0
    private val quad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }
    private val stMatrix = FloatArray(16)
    // See LivePipeline's identical field - texCrop is computed before we've seen a real
    // transform matrix, then corrected once we have one in case this camera swaps axes.
    @Volatile private var texCropFinalized = false
    private val readback = ByteBuffer.allocateDirect(READBACK_W * READBACK_H * 4).order(ByteOrder.nativeOrder())
    private val rgbaPlane = ByteArray(READBACK_W * READBACK_H * 4)

    // Motion detection, and the one channel the camera half of this class uses to talk to it.
    // Everything below that reconfigures, re-requests or restarts the camera brackets itself in
    // beginDisturbance/endDisturbance, so a change *we* made is never read as something moving in
    // the scene. Declared as the interface, not the implementation, so that stays true by
    // construction: the camera side cannot reach past it into the detector.
    private val analyzer = MotionAnalyzer(sensitivity = { appConfig.get().motionSensitivity })
    private val motionGate: MotionGate = analyzer
    @Volatile private var lastMotionReported = false

    /** Re-pointed at the real camera's record once [openCameraIfNeeded] resolves an id; until
     *  then a placeholder, so nothing on a failure path has to null-check a counter. */
    @Volatile private var health: CameraHealth =
        healthRegistry.forCamera(cameraId?.takeIf { it.isNotBlank() } ?: "(unresolved)", ROLE)

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

    // Three independent liveness witnesses, one per stage, so a stall can be attributed rather
    // than merely detected: the camera handing us a buffer (GL thread), the camera telling us it
    // captured something (callback thread), and the encoder emitting bytes (drain thread). Only
    // the last of these drives the supervisor's timeout; the other two exist to say which half
    // died first. See recordStallForensics().
    private val lastFrameMs = AtomicLong(0)
    private val lastCaptureResultMs = AtomicLong(0)
    private val framesThisRun = AtomicLong(0)
    private val captureResultsThisRun = AtomicLong(0)
    private val encodedThisRun = AtomicLong(0)
    private val runFirstFrameMs = AtomicLong(0)
    /** Buffers the camera could not deliver during this run - the counter that tracks the
     *  starvation directly rather than only its end state. */
    private val bufferLostThisRun = AtomicLong(0)
    /** Frames seen by the GL thread, for [analysisFrameInterval]. GL thread only. */
    private var analysisFrameCounter = 0L
    /** Frames handed to the encoder's input surface, and the ones eglSwapBuffers refused.
     *  `lastFrameMs` only says the GL thread is still turning - it is stamped before the draw.
     *  A swap that fails leaves it fresh while the encoder receives nothing, which is
     *  indistinguishable from an encoder that has wedged unless the return value is read. It
     *  never was: both calls discarded it. GL thread only. */
    private val swapsThisRun = AtomicLong(0)
    private val swapFailuresThisRun = AtomicLong(0)
    private var lastSwapError = 0

    // Thermal, sampled by the supervisor - see sampleThermal(). Only ever touched from there and
    // from the failure path, both on the supervisor coroutine.
    private var peakThermalSeverity = -1
    private var lastThermalSeverity = Int.MIN_VALUE

    /** Last AE target FPS range the HAL reported, so the gauge is written on change rather than
     *  on every capture result. Touched only from the camera callback thread. */
    @Volatile private var lastAeFpsRange: android.util.Range<Int>? = null

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
                zoomFeedback.clear()
                // Everything from here to the first settled frame is ours: the camera opens, the
                // session configures, exposure and white balance converge from nothing, the
                // aspect trim is measured on the first frame and the zoom is requested off the
                // back of it. None of it is the scene moving.
                motionGate.beginDisturbance(CameraDisturbance.PIPELINE_START)
                openCameraIfNeeded()
                health.runStarted()
                peakThermalSeverity = -1
                recordingSize = pickRecordingSize()
                sourceSize = pickSourceSize(recordingSize)
                texCropFinalized = false
                rotationDegrees = CameraFraming.normalizedRotation(appConfig.get().rotationDegrees)
                outputSize = CameraFraming.rotatedOutputSize(recordingSize, rotationDegrees)
                health.gauge("recordingSize", "${recordingSize.width}x${recordingSize.height}")
                health.gauge("sourceSize", "${sourceSize.width}x${sourceSize.height}")
                health.gauge("outputSize", "${outputSize.width}x${outputSize.height}")
                health.gauge("rotationDegrees", rotationDegrees)
                health.gauge("analysisFrameInterval", analysisFrameInterval)
                setupGlAndEncoder()
                startDrain()
                openSessionAndRequest()
                onHealthChanged(true)
                attempt = 0
                superviseUntilError()
            } catch (e: Exception) {
                val reason = classifyFailure(e)
                Log.e(TAG, "pipeline error (attempt ${attempt + 1}, reason=$reason)", e)
                val forensics = recordStallForensics(reason)
                health.runEnded(reason, "${e.message} | $forensics")
                onHealthChanged(false)
                runCatching { teardown() }
                analyzer.reset()
                attempt++
                health.gauge("restartAttempt", attempt)
                delay(minOf(500L * attempt, 5000L))
            }
        }
    }

    /**
     * A stable short name for what ended a run, so `GET /api/camera/health` can report a mean
     * uptime *per cause* rather than one number that averages a stall together with a mode
     * switch. The message text still goes along as the event's detail.
     */
    private fun classifyFailure(e: Exception): String {
        // A cancelled supervisor job is us calling stop() (a mode switch), not a fault - it must
        // not land in the same bucket as a stall or the mean uptime per cause stops meaning
        // anything. See docs/quirks/manual-camera-controls.md.
        if (e is kotlinx.coroutines.CancellationException) return "stopped"
        val m = e.message ?: return "unknown"
        return when {
            m.startsWith("encoder output stalled") -> "stall"
            m.startsWith("encoder produced no output") -> "noFirstOutput"
            m.startsWith("camera disconnected") -> "cameraDisconnected"
            m.startsWith("camera error") -> "cameraError"
            m.startsWith("camera open error") -> "cameraOpenError"
            m.startsWith("session") -> "sessionFailed"
            m.startsWith("updateTexImage") -> "updateTexImage"
            m.startsWith("dequeueOutputBuffer") -> "dequeueOutputBuffer"
            else -> "unknown"
        }
    }

    /**
     * The state of both halves of the pipeline at the moment it was declared dead.
     *
     * This is the measurement the stall investigation turns on. The supervisor only knows that
     * *encoded output* stopped, which two very different faults produce: the camera stopped
     * handing us frames (a HAL or buffer problem, nothing we can fix in the encoder), or frames
     * kept arriving and the encoder or GL thread stopped consuming them (ours). Capture results
     * are the third, independent witness - if they keep coming while frames do not, the camera
     * thinks it is still capturing and the buffers are going somewhere else.
     *
     * Recorded as ages rather than absolute times because that is what is comparable across the
     * dozens of restarts an hour this is meant to characterise.
     */
    /**
     * Returns a one-line summary of the same forensics, for the *event* record.
     *
     * The gauges below are a last-known-value store, so a burst of failures leaves only the last
     * one's numbers - and a burst is exactly when they matter. That cost us the first clean stall
     * of the evening: it was overwritten by an `updateTexImage` failure 18 seconds later, before
     * the sampler next looked. The returned string goes into the `runEnded` event, which lives in
     * a ring, so every failure keeps its own numbers regardless of how closely they land.
     */
    private fun recordStallForensics(reason: String): String {
        val now = SystemClock.elapsedRealtime()
        fun age(t: Long) = if (t == 0L) -1L else now - t
        health.gauge("lastFailure.reason", reason)
        health.gauge("lastFailure.encodedOutputAgeMs", age(lastOutputMs.get()))
        health.gauge("lastFailure.cameraFrameAgeMs", age(lastFrameMs.get()))
        health.gauge("lastFailure.captureResultAgeMs", age(lastCaptureResultMs.get()))
        health.gauge("lastFailure.framesThisRun", framesThisRun.get())
        health.gauge("lastFailure.captureResultsThisRun", captureResultsThisRun.get())
        health.gauge("lastFailure.encodedFramesThisRun", encodedThisRun.get())
        health.gauge("lastFailure.swapsThisRun", swapsThisRun.get())
        health.gauge("lastFailure.swapFailuresThisRun", swapFailuresThisRun.get())
        health.gauge("lastFailure.measuredFps", "%.2f".format(measuredFps()))
        health.gauge("lastFailure.activeDisturbances", analyzer.activeDisturbances().toString())
        val t = sampleThermal()
        health.gauge("lastFailure.thermalLevel", t.level)
        health.gauge("lastFailure.thermalSeverity", t.severity)
        health.gauge("lastFailure.thermalHeadroom", t.headroom?.let { "%.3f".format(it) } ?: "n/a")
        health.gauge("lastFailure.thermalPeakThisRun", peakThermalSeverity)
        publishMotionGauges()
        // Ages, not absolute times: which of the three stages went quiet first is the whole
        // diagnostic, and only the differences between them carry that.
        return "frameAge=${age(lastFrameMs.get())} resultAge=${age(lastCaptureResultMs.get())}" +
            " encAge=${age(lastOutputMs.get())} frames=${framesThisRun.get()}" +
            " results=${captureResultsThisRun.get()} enc=${encodedThisRun.get()}" +
            " fps=${"%.2f".format(measuredFps())} thermal=${t.level}/${t.headroom ?: "n/a"}" +
            " bufLost=${bufferLostThisRun.get()} swaps=${swapsThisRun.get()}" +
            " swapFail=${swapFailuresThisRun.get()}"
    }

    /**
     * Motion-gate and zoom-readback totals, sampled at the few moments worth paying for rather
     * than per frame.
     *
     * `motion.disturbancesExpired` above zero is the one to watch: it means a disturbance was
     * dropped by its timeout instead of being ended by whoever began it, which is a pairing bug
     * in this file, not a camera fault. `zoom.unreadable` dominating `zoom.readable` means this
     * device tells us nothing about where its zoom actually is, and the crop is back to trusting
     * the request.
     */
    /**
     * Read the device's thermal state into the health record, noting a change as an event.
     *
     * The *peak* is kept per run alongside the current value because the two answer different
     * questions: whether the device was hot when it died, and whether it had been hot at all
     * during the run leading up to it. A device that throttles, sheds load and cools back down
     * before the stall would show a benign current reading and a damning peak.
     */
    private fun sampleThermal(): ThermalStatus {
        val t = ThermalReader.read(context)
        if (!t.supported) {
            health.gauge("thermal.supported", "false")
            return t
        }
        health.gauge("thermal.severity", t.severity)
        health.gauge("thermal.level", t.level)
        // Stamped because these are only written while a pipeline is running. In STANDBY they
        // freeze at their last value, and a frozen "critical" reads exactly like a live one -
        // which is how a cooled phone got reported as still hot. /api/status reads thermal live;
        // this is the record of when *these* numbers were true.
        health.gauge("thermal.sampledAtMs", System.currentTimeMillis())
        t.headroom?.let { health.gauge("thermal.headroom", "%.3f".format(it)) }
        if (t.severity > peakThermalSeverity) {
            peakThermalSeverity = t.severity
            health.gauge("thermal.peakSeverityThisRun", t.severity)
        }
        if (t.severity != lastThermalSeverity) {
            // Into the recent ring, so the *timing* of a climb relative to the stall is visible
            // and not just the final value.
            health.event("thermalChanged", "${t.level} (${t.severity}) headroom=${t.headroom ?: "n/a"}")
            lastThermalSeverity = t.severity
        }
        return t
    }

    private fun publishMotionGauges() {
        health.gauge("motion.framesAnalysed", analyzer.framesAnalysed)
        health.gauge("motion.framesSuppressed", analyzer.framesSuppressed)
        health.gauge("motion.disturbances", analyzer.disturbances)
        health.gauge("motion.disturbancesExpired", analyzer.disturbancesExpired)
        health.gauge("zoom.readable", zoomFeedback.readable)
        health.gauge("zoom.unreadable", zoomFeedback.unreadable)
        health.gauge("zoom.requestedRatio", requestedSplit?.hwRatio ?: 1f)
    }

    private suspend fun superviseUntilError() {
        val upAt = SystemClock.elapsedRealtime()
        var nextThermalSampleMs = 0L
        while (running) {
            fatal.get()?.let { throw IllegalStateException(it) }
            val out = lastOutputMs.get()
            val now = SystemClock.elapsedRealtime()
            // A slow thermal time series across the whole run, recording or not. This is what
            // turns "the pipeline dies more often at 4K" into either "because it is cooking" or
            // "and the device is stone cold, so look elsewhere" - and it has to cover the quiet
            // stretches too, since most of a run writes nothing to disk.
            if (now >= nextThermalSampleMs) {
                nextThermalSampleMs = now + THERMAL_SAMPLE_MS
                sampleThermal()
                // The motion-gate and zoom-feedback totals ride along on the same tick. They used
                // to be published only on a stall or a finished segment, which meant the counters
                // that say whether the false-clip guards are working at all were invisible for as
                // long as they were working - readable only once something had gone wrong. A
                // guard you cannot watch while it holds is a guard you cannot evaluate.
                publishMotionGauges()
                health.gauge("run.framesThisRun", framesThisRun.get())
                health.gauge("run.captureResultsThisRun", captureResultsThisRun.get())
                health.gauge("run.measuredFps", "%.2f".format(measuredFps()))
                health.gauge("run.swaps", swapsThisRun.get())
                health.gauge("run.swapFailures", swapFailuresThisRun.get())
            }
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
            st.setDefaultBufferSize(sourceSize.width, sourceSize.height)
            st.setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)
            surfaceTexture = st
            cameraSurface = Surface(st)
        }
        Log.i(
            TAG,
            "GL + encoder up (out ${outputSize.width}x${outputSize.height}, rotation $rotationDegrees, " +
                "camera source ${sourceSize.width}x${sourceSize.height})",
        )
    }

    private fun createEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputSize.width, outputSize.height).apply {
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
        if (!texCropFinalized) {
            val (cropX, cropY) = CameraFraming.correctedTexCrop(sourceSize, recordingSize, stMatrix)
            texCropX = cropX; texCropY = cropY
            texCropFinalized = true
            Log.i(TAG, "texCrop for this camera's transform: $cropX,$cropY")
            health.gauge("texCrop", "$cropX,$cropY")
            // The split could not be computed before this point, so re-issue the request if the
            // camera now turns out to be able to do part of the zoom itself.
            if (recomputeZoomSplit()) reissueRepeatingRequest()
        }
        val tsNanos = st.timestamp
        val now = SystemClock.elapsedRealtime()
        lastFrameMs.set(now)
        framesThisRun.incrementAndGet()
        runFirstFrameMs.compareAndSet(0L, now)

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
        // is what the camera is already zoomed to *for this frame* - looked up by the frame's own
        // sensor timestamp, not assumed from the last request - so the view is placed inside what
        // the buffer actually holds.
        val u = ZoomGeometry.texRectUniform(zoomView(), texCropX, texCropY, deliveredCrop(tsNanos))
        GLES20.glUniform4f(uTexRectLoc, u[0], u[1], u[2], u[3])
        quad.position(0); GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        quad.position(2); GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexLoc)

        // analysis: downscale -> FBO -> readback -> MotionDetector.
        //
        // Skipped entirely on frames we are not analysing, because the readback is the expensive
        // half and the FBO draw exists only to feed it. `glReadPixels` is synchronous: it does not
        // return until the GPU has drained everything queued, which at 4K includes the previous
        // frame's full-size draw. The 76KB it moves is nothing; the flush is the cost. While the
        // GL thread sits in it, nothing calls updateTexImage, and the camera's buffer queue is
        // what runs dry - see docs/status/camera-stall-investigation.md.
        //
        // Analysing every Nth frame cuts how often that happens, in proportion. It does not
        // remove the stall (an async PBO readback would); it trades detection latency, which a
        // fixed security camera has to spare, against a starvation window it does not.
        if (analysisFrameInterval > 0 && analysisFrameCounter++ % analysisFrameInterval == 0L) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, READBACK_W, READBACK_H)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            readback.position(0)
            GLES20.glReadPixels(0, 0, READBACK_W, READBACK_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
            detectMotion(now)
        }

        // record: full frame, rotated per rotationDegrees -> encoder input surface
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val rotQuad = outputQuad
        if (rotQuad != null) {
            rotQuad.position(0); GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, rotQuad)
            rotQuad.position(2); GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, rotQuad)
        }
        GLES20.glViewport(0, 0, outputSize.width, outputSize.height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderWindowSurface, tsNanos)
        if (EGL14.eglSwapBuffers(eglDisplay, encoderWindowSurface)) {
            swapsThisRun.incrementAndGet()
        } else {
            val err = EGL14.eglGetError()
            // Log the first of a run and then every 30th: a surface that has gone bad stays bad,
            // and at 30fps an unthrottled log would be the loudest thing in the buffer.
            val n = swapFailuresThisRun.incrementAndGet()
            if (n == 1L || n % 30L == 0L) {
                Log.w(TAG, "eglSwapBuffers failed (0x${Integer.toHexString(err)}) x$n")
            }
            if (err != lastSwapError) {
                lastSwapError = err
                health.event("swapFailed", "0x${Integer.toHexString(err)}")
            }
        }
    }

    private fun detectMotion(now: Long) {
        // All three colour channels: a change of colour with no change of brightness is still
        // something happening in the scene - see MotionDetector.
        readback.position(0)
        readback.get(rgbaPlane)
        // The analyzer, not the detector: it drops the frames our own reconfigures produced, and
        // re-bases the reference frame across them, before any of this asks "did something move?"
        val verdict = analyzer.accept(rgbaPlane, READBACK_W, READBACK_H, READBACK_W * 4, now, pixelStride = 4)
        MotionTrace.sink?.onFrame(rgbaPlane, READBACK_W, READBACK_H, now, verdict)
        val motion = verdict.motion
        if (motion) {
            lastMotionMs.set(now)
            motionEver.set(true)
        }
        if (motion != lastMotionReported) {
            lastMotionReported = motion
            onMotionChanged(motion)
        }
    }

    /** Frames per second measured over this run, not the rate we asked the encoder for - a
     *  drooping capture rate is one of the things that could precede a stall. */
    private fun measuredFps(): Double {
        val first = runFirstFrameMs.get()
        val last = lastFrameMs.get()
        val n = framesThisRun.get()
        if (first == 0L || last <= first || n < 2) return 0.0
        return (n - 1) * 1000.0 / (last - first)
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
                    encodedThisRun.incrementAndGet()
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

    /**
     * A started muxer on a new segment file, or null if there's no room for one. Returning null
     * rather than throwing is the point: this runs on the drain thread, and an exception here
     * (2026-09-29: `ENOSPC`, the disk full) killed the whole app, which restarted, tried again and
     * died again. Instead the segment is skipped, eviction is asked to make room, and the next
     * keyframe retries - motion keeps being detected, only the footage is lost meanwhile.
     */
    private fun newMuxer(fmt: MediaFormat): MediaMuxer? {
        val dir = segmentsDir
        if (dir.usableSpace < MIN_FREE_TO_OPEN_BYTES) {
            noteStorageLow("usable=${dir.usableSpace}B")
            return null
        }
        val file = File(dir, segmentFileName())
        return try {
            val mx = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerTrack = mx.addTrack(fmt)
            mx.start()
            segFile = file
            mx
        } catch (e: Exception) {
            noteStorageLow(e.message ?: e.javaClass.simpleName)
            runCatching { file.delete() }
            null
        }
    }

    private var storageLowLoggedMs = 0L

    private fun noteStorageLow(detail: String) {
        onStorageLow()
        val now = SystemClock.elapsedRealtime()
        // At most one line a minute: the next keyframe retries every second or so.
        if (now - storageLowLoggedMs >= 60_000L) {
            storageLowLoggedMs = now
            Log.w(TAG, "segment not opened, skipping footage: $detail")
            health.event("segmentOpenFailed", detail)
        }
    }

    private fun openSegmentFromRing(nowMs: Long) {
        val fmt = trackFormat ?: return
        val mx = newMuxer(fmt) ?: return
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
        val mx = newMuxer(fmt)
        if (mx == null) {
            // Drop out of writing; handleEncoded reopens (with pre-roll) at a later keyframe.
            writing = false
            pendingRoll = false
            onPhaseChanged(false)
            return
        }
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
        health.event("segmentPublished", "dur=${durationMs}ms bytes=${file.length()}")
        health.gauge("measuredFps", "%.2f".format(measuredFps()))
        publishMotionGauges()
        logKeyframeCadence(file)
        onSegmentFinished(file, startedAtMs, durationMs.coerceAtLeast(0L), outputSize.width, outputSize.height)
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
        // The zoom map is per camera and per output size, so it is loaded here rather than once
        // at construction. An uncalibrated phone yields an empty table, which simply means the
        // camera is asked for nothing and GL does all the zooming.
        health = healthRegistry.forCamera(id, ROLE)
        zoomLut = ZoomCalibrationLut.build(
            CalibrationStore(context).load(), id, recordingSize.width, recordingSize.height,
        )
        requestedSplit = null
        health.gauge("zoomLutSamples", zoomLut.entries.size)
        health.gauge("zoomViaRatioApi", (caps?.zoomViaRatioApi ?: false).toString())
        if (!zoomLut.isEmpty) {
            Log.i(TAG, "zoom map: camera ${zoomLut.sourceCameraId} @ ${zoomLut.sourceResolution}, ${zoomLut.entries.size} samples")
        }
        cameraDevice = openCameraDevice(logicalId)
    }

    private suspend fun openSessionAndRequest() {
        val device = cameraDevice ?: throw IllegalStateException("camera closed")
        val surface = cameraSurface ?: throw IllegalStateException("no camera surface")
        val failed = AtomicBoolean(false)
        val session = createCaptureSession(device, listOf(surface), failed)
        delay(500)
        if (failed.get() || !running) { session.close(); throw IllegalStateException("session failed asynchronously") }
        session.setRepeatingRequest(buildRecordRequest(device, surface), captureMonitor, callbackHandler)
        captureSession = session
        Log.i(TAG, "capture session up")
        health.event("sessionUp")
    }

    /**
     * Merge a new manual-control state and, if a session is live, re-issue the repeating request
     * so it takes effect without tearing the pipeline down (only a *camera switch* rebuilds).
     * Safe to call before the session is up - the state is kept and applied by
     * [buildRecordRequest] when the session next configures.
     */
    fun applyControls(spec: CameraControlSpec) {
        controls = spec
        // Exposure, focus and white balance all visibly change the picture, and a metering-region
        // change makes the HAL re-converge - all of it indistinguishable from motion. Ended when
        // the first result built from the new request comes back.
        motionGate.beginDisturbance(CameraDisturbance.CONTROLS)
        health.event("applyControls")
        // A zoom change moves work between the camera and GL, so the split is redone before the
        // request is rebuilt below. The shader does *not* pick the new crop up on its next frame:
        // it follows the capture results, so it only moves as the hardware actually does.
        if (recomputeZoomSplit()) beginZoomTransition()
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = cameraSurface ?: return
        runCatching {
            session.setRepeatingRequest(buildRecordRequest(device, surface), captureMonitor, callbackHandler)
        }.onFailure {
            Log.w(TAG, "applyControls: setRepeatingRequest failed", it)
            health.event("setRepeatingRequestFailed", it.message)
            motionGate.endDisturbance(CameraDisturbance.CONTROLS)
        }
    }

    /**
     * The region of the full field of view the buffer holds **for the frame timestamped
     * [frameTimestampNs]** - not the region the pending request will eventually produce.
     *
     * `SurfaceTexture.getTimestamp()` and `CaptureResult`'s `SENSOR_TIMESTAMP` are the same value
     * from the same clock, so this is an exact per-frame answer rather than a guess about how far
     * behind the hardware currently is. The shader places the requested view inside whatever comes
     * back, which stays correct at every point of a zoom transition instead of only at the ends.
     */
    private fun deliveredCrop(frameTimestampNs: Long): RectNorm {
        val requested = requestedSplit?.hwRatio ?: 1f
        val applied = zoomFeedback.appliedRatio(frameTimestampNs, requested)
        return ZoomGeometry.splitFor(
            viewSensor = ZoomGeometry.viewToSensor(zoomView() ?: ZoomGeometry.FULL, texCropX, texCropY),
            lut = zoomLut.entries,
            hwRatio = applied,
            texCropX = texCropX,
            texCropY = texCropY,
        ).deliveredCrop
    }

    /** The view the user asked for, or null when running full auto. */
    private fun zoomView(): RectNorm? =
        controls.keys.zoomViewNorm.takeIf { controls.manualControlEnabled }

    /** Recompute how much of the zoom the camera should do. Returns true when the hardware half
     *  changed, meaning the repeating request has to be re-issued to take effect. */
    private fun recomputeZoomSplit(): Boolean {
        val previous = requestedSplit?.hwRatio ?: 1f
        val split = ZoomGeometry.split(
            viewSensor = ZoomGeometry.viewToSensor(zoomView() ?: ZoomGeometry.FULL, texCropX, texCropY),
            lut = zoomLut.entries,
            texCropX = texCropX,
            texCropY = texCropY,
        )
        requestedSplit = split
        return kotlin.math.abs(split.hwRatio - previous) > 0.01f
    }

    /** A hardware zoom is now in flight; hold motion off until a result says it has landed. */
    private fun beginZoomTransition() {
        motionGate.beginDisturbance(CameraDisturbance.ZOOM)
        health.event("zoomRequested", "ratio=${requestedSplit?.hwRatio}")
    }

    private fun buildRecordRequest(device: CameraDevice, surface: Surface): CaptureRequest =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            caps?.let {
                CameraControlApply.applyTo(this, controls, it)
                CameraControlApply.applyZoom(this, requestedSplit?.hwRatio ?: 1f, it)
            }
        }.build()

    /**
     * The repeating request's result listener - previously `null`, which cost us both halves of
     * this file's problem.
     *
     * It is what makes the zoom observable instead of assumed ([deliveredCrop]), and it is the
     * only place the camera can tell us a capture went wrong at all: with no callback attached, a
     * run of `onCaptureFailed`s or a dropped buffer is completely silent, and the first thing
     * anyone learns is that encoded output stopped four seconds ago. Every branch here is cheap -
     * it runs on the camera callback thread at frame rate.
     */
    private val captureMonitor = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            lastCaptureResultMs.set(SystemClock.elapsedRealtime())
            val n = captureResultsThisRun.incrementAndGet()
            // Controls are applied by the request this result came from, so by definition they
            // have taken effect by now.
            motionGate.endDisturbance(CameraDisturbance.CONTROLS)
            // Start-up is over once frames have been flowing long enough for auto-exposure and
            // white balance to have converged - counted in frames rather than wall time because
            // that is what convergence actually depends on.
            if (n >= PIPELINE_SETTLE_RESULTS) motionGate.endDisturbance(CameraDisturbance.PIPELINE_START)

            // The capture rate the HAL has settled on, recorded only when it changes. This is the
            // evidence the CONTROL_AE_TARGET_FPS_RANGE question turns on: the record path leaves
            // it unpinned (LivePipeline pins it), and pinning it is both a plausible fix for the
            // stalls and a plausible regression, because it stops the HAL lengthening exposure at
            // night. Rather than guess, watch whether the rate actually droops before a stall.
            result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { range ->
                if (range != lastAeFpsRange) {
                    lastAeFpsRange = range
                    health.gauge("aeTargetFpsRange", range.toString())
                    health.event("aeTargetFpsChanged", range.toString())
                }
            }

            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val applied = AppliedZoomReader.read(result, caps)
            zoomFeedback.record(ts, applied)
            val want = requestedSplit?.hwRatio ?: 1f
            // Landed once the report is within a percent of the request - or immediately if this
            // device reports nothing we can use, where waiting would mean never re-opening the
            // gate and the settling window is all the cover there is.
            val landed = applied?.let { kotlin.math.abs(it - want) <= maxOf(0.02f, want * 0.01f) }
                ?: (zoomFeedback.unreadable >= ZoomFeedback.UNREADABLE_BEFORE_TRUSTING_REQUEST)
            if (landed) motionGate.endDisturbance(CameraDisturbance.ZOOM)
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            health.event("captureFailed", "reason=${failure.reason} frame=${failure.frameNumber}")
        }

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long,
        ) {
            bufferLostThisRun.incrementAndGet()
            health.event("captureBufferLost", "frame=$frameNumber")
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            health.event("captureSequenceAborted", "sequence=$sequenceId")
        }
    }

    /** Re-issue the repeating request so a changed hardware zoom takes effect, without rebuilding
     *  the session. Safe to call from the GL thread. */
    private fun reissueRepeatingRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = cameraSurface ?: return
        runCatching {
            session.setRepeatingRequest(buildRecordRequest(device, surface), captureMonitor, callbackHandler)
        }.onFailure {
            Log.w(TAG, "reissueRepeatingRequest failed", it)
            health.event("setRepeatingRequestFailed", it.message)
            motionGate.endDisturbance(CameraDisturbance.ZOOM)
        }
    }

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
                    health.event("cameraDisconnected")
                    fatal.compareAndSet(null, "camera disconnected")
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); if (cameraDevice === device) cameraDevice = null
                    health.event("cameraDeviceError", "error=$error")
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
        // Re-arm rather than resume. These two are what the drain thread gates writing on, and
        // they used to survive a teardown - so a pipeline that stalled within [trailerMs] of the
        // last motion came back up, found `motionEver` still true and `lastMotionMs` still
        // recent, and opened a segment on its very first keyframe without analysing a single
        // frame. That path never consults the motion gate at all, which is why the guards could
        // hold perfectly and a restart still produced a clip (confirmed on a 0.7s clip whose only
        // event was the zoom snap itself). Motion seen by the *previous* camera session says
        // nothing about this one: different session, seconds later, exposure and zoom re-settling.
        motionEver.set(false)
        lastMotionMs.set(0)
        // Per-run witnesses, so the next run's forensics describe the next run.
        lastFrameMs.set(0); lastCaptureResultMs.set(0); runFirstFrameMs.set(0)
        framesThisRun.set(0); captureResultsThisRun.set(0); encodedThisRun.set(0)
        bufferLostThisRun.set(0)
        swapsThisRun.set(0); swapFailuresThisRun.set(0); lastSwapError = 0

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

    /** Plain logical/physical id whose stream map + sensor geometry apply to this
     *  session (the physical sub-camera's own, for a "<logical>:<physical>" target). */
    private fun sizingCameraId(): String? {
        val target = resolvedCameraId ?: cameraId?.takeIf { it.isNotBlank() } ?: backCameraId() ?: return null
        val (logicalId, physId) = CameraCapabilitiesReader.splitTarget(target)
        return physId ?: logicalId
    }

    /** The size to capture and encode at: the viewing size, as LIVE uses - see
     *  [RecordingSizeSelection.recordingSize] for why it is no longer reduced for zoom. */
    private fun pickRecordingSize(): Size {
        val id = sizingCameraId() ?: return Size(1280, 720)
        val size = RecordingSizeSelection.recordingSize(cameraManager, id, appConfig.get().videoResolution)
        health.gauge("viewingSize", "${size.width}x${size.height}")
        return size
    }

    /** The camera → SurfaceTexture buffer size for [target]'s aspect ratio - see
     *  [CameraFraming.pickSourceSize]. */
    private fun pickSourceSize(target: Size): Size {
        val id = sizingCameraId() ?: return target
        return CameraFraming.pickSourceSize(cameraManager, id, target)
    }

    private fun segmentFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val suffix = (Math.random() * 0xffff).toInt().toString(16).padStart(4, '0')
        return "clip_${ts}_$suffix.mp4"
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

}
