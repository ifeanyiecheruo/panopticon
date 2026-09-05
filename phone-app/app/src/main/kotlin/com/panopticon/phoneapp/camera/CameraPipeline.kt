package com.panopticon.phoneapp.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.panopticon.phoneapp.motion.MotionDetector
import com.panopticon.phoneapp.motion.RecordingPhaseController
import com.panopticon.phoneapp.state.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraPipeline"

/** How often the phase loop wakes to check for an ARMED<->RECORDING transition. */
private const val PHASE_POLL_MS = 100L

/**
 * Camera2 + MediaRecorder pipeline for the back camera, now **motion-gated**:
 * an always-on analysis stream (a small YUV `ImageReader`) feeds [MotionDetector],
 * whose verdict drives [RecordingPhaseController]. While ARMED the session
 * carries only the analysis surface and nothing is written; the first frame
 * with motion flips to RECORDING, which reconfigures the session to add a
 * `MediaRecorder` surface and starts rotating ~10s H.264 clips. Recording is
 * held for a trailer window after motion last stopped, then it disarms.
 *
 * Simplifications still in force for this slice (documented, not accidental):
 *  - **Frame-difference motion only** - no background model / CV library. See
 *    [MotionDetector]; thresholds are un-tuned starting points.
 *  - **No pre-roll.** A clip starts at motion-detection time - `MediaRecorder`
 *    can't back-date a buffer. Pre-roll is tied to the future
 *    `MediaCodec`+`MediaMuxer` switch.
 *  - **Full `CameraCaptureSession` teardown+recreate** on every ARMED<->RECORDING
 *    transition and every clip rotation, rather than an in-place surface swap.
 *    Simpler to reason about; doubles as a session-reconfigure stress test.
 *  - **No audio track** - video only, avoids `RECORD_AUDIO` entirely.
 */
class CameraPipeline(
    private val context: Context,
    private val clipsDir: File,
    private val appConfig: AppConfig,
    private val rotationIntervalMs: Long = 10_000L,
    private val trailerMs: Long = 5_000L,
    private val onClipFinished: (file: File, createdAtMs: Long, durationMs: Long, width: Int, height: Int) -> Unit,
    private val onHealthChanged: (Boolean) -> Unit,
    private val onPhaseChanged: (recording: Boolean) -> Unit = {},
    private val onMotionChanged: (motion: Boolean) -> Unit = {},
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val callbackThread = HandlerThread("PanopticonCameraCallback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private val workExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PanopticonCameraWork") }
    private val scope = CoroutineScope(SupervisorJob() + workExecutor.asCoroutineDispatcher())

    private val phaseController = RecordingPhaseController(trailerMs)
    @Volatile private var detector = MotionDetector(appConfig.get().motionSensitivity)
    @Volatile private var lastMotionReported = false

    private var runLoopJob: Job? = null
    private var running = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var analysisReader: ImageReader? = null
    private var recordingSize: Size = Size(1280, 720)
    private var currentFile: File? = null
    private var currentStartedAtMs: Long = 0

    fun start() {
        if (running) return
        running = true
        runLoopJob = scope.launch { runLoop() }
    }

    fun stop() {
        running = false
        runLoopJob?.cancel()
        phaseController.disarm()
        scope.launch {
            finishCurrentSegment(deleteIfEmpty = true)
            closeSessionAndDevice()
        }
    }

    fun release() {
        stop()
        callbackThread.quitSafely()
        workExecutor.shutdown()
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (running) {
            try {
                openCameraIfNeeded()
                recordingSize = pickRecordingSize()
                ensureAnalysisReader()
                onHealthChanged(true)
                attempt = 0

                // Phase loop: stay in whichever phase the controller reports,
                // reconfiguring the session on each transition.
                while (running) {
                    when (phaseController.phase) {
                        RecordingPhaseController.Phase.ARMED -> runArmedPhase()
                        RecordingPhaseController.Phase.RECORDING -> runRecordingPhase()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "camera loop error (attempt ${attempt + 1})", e)
                onHealthChanged(false)
                runCatching { finishCurrentSegment(deleteIfEmpty = true) }
                runCatching { closeSessionAndDevice() }
                detector.reset()
                attempt++
                delay(minOf(500L * attempt, 5000L))
            }
        }
    }

    // ---- ARMED: analysis-only session, nothing recorded ----

    private suspend fun runArmedPhase() {
        onPhaseChanged(false)
        // Re-read sensitivity each time we arm, so a POST /api/config change
        // takes effect on the next idle period without restarting the service.
        detector = MotionDetector(appConfig.get().motionSensitivity)
        detector.reset()

        val analysis = analysisReader ?: throw IllegalStateException("analysis reader missing")
        val device = cameraDevice ?: throw IllegalStateException("camera closed")
        val failed = AtomicBoolean(false)
        val session = createCaptureSession(device, listOf(analysis.surface), failed)
        delay(300)
        if (failed.get()) {
            session.close()
            throw IllegalStateException("armed session failed asynchronously")
        }
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(analysis.surface)
        }.build()
        session.setRepeatingRequest(request, null, callbackHandler)
        captureSession = session

        try {
            while (running && phaseController.phase == RecordingPhaseController.Phase.ARMED) {
                delay(PHASE_POLL_MS)
            }
        } finally {
            runCatching { session.close() }
            captureSession = null
        }
    }

    // ---- RECORDING: analysis + recorder session, rotating clips ----

    private suspend fun runRecordingPhase() {
        onPhaseChanged(true)
        while (running && phaseController.phase == RecordingPhaseController.Phase.RECORDING) {
            val started = beginSegment()
            if (!started) throw IllegalStateException("could not start a recording segment after retries")

            val segmentDeadline = System.currentTimeMillis() + rotationIntervalMs
            while (running &&
                phaseController.phase == RecordingPhaseController.Phase.RECORDING &&
                System.currentTimeMillis() < segmentDeadline
            ) {
                delay(PHASE_POLL_MS)
            }
            finishCurrentSegment(deleteIfEmpty = false)
        }
    }

    // ---- Analysis stream ----

    private fun ensureAnalysisReader() {
        if (analysisReader != null) return
        val size = pickAnalysisSize()
        val reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val result = detector.accept(bytes, image.width, image.height, plane.rowStride)
                if (result.motion != lastMotionReported) {
                    lastMotionReported = result.motion
                    onMotionChanged(result.motion)
                }
                phaseController.onFrame(result.motion, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "frame analysis failed", e)
            } finally {
                image.close()
            }
        }, callbackHandler)
        analysisReader = reader
    }

    private fun pickAnalysisSize(): Size {
        val id = backCameraId() ?: return Size(320, 240)
        val map = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList().orEmpty()
        // Smallest size at least ~QVGA - enough detail for grid differencing,
        // cheap to shuttle every frame.
        return sizes.filter { it.width >= 240 && it.height >= 180 }
            .minByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(320, 240)
    }

    // ---- Camera / session plumbing ----

    private suspend fun openCameraIfNeeded() {
        if (cameraDevice != null) return
        val id = backCameraId() ?: throw IllegalStateException("no back-facing camera reported")
        cameraDevice = openCameraDevice(id)
    }

    private fun backCameraId(): String? =
        cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

    private suspend fun openCameraDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (cont.isActive) cont.resume(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                    if (cont.isActive) cont.resumeWithException(RuntimeException("camera open error code=$error"))
                }
            }, callbackHandler)
        } catch (e: CameraAccessException) {
            // Reconfirmed on Pixel 6: openCamera can throw synchronously - see QUIRKS.md.
            cont.resumeWithException(e)
        } catch (e: SecurityException) {
            cont.resumeWithException(e)
        }
    }

    /**
     * Builds a fresh MediaRecorder + capture session ([analysisReader] +
     * recorder surfaces) for the next clip file. Keeps the old prototype's
     * confirmed-quirk workaround: configure -> wait 500ms -> check for an async
     * failure before trusting the session and calling `recorder.start()`.
     * Retries up to 3x with a fresh recorder+session each attempt.
     */
    private suspend fun beginSegment(): Boolean {
        var attempt = 0
        while (attempt < 3 && running) {
            attempt++
            val device = cameraDevice ?: return false
            val analysis = analysisReader ?: return false
            val recorder = buildRecorder()
            val recorderSurface = recorder.surface
            val sessionFailed = AtomicBoolean(false)

            val session = try {
                createCaptureSession(device, listOf(analysis.surface, recorderSurface), sessionFailed)
            } catch (e: Exception) {
                Log.e(TAG, "createCaptureSession threw (attempt $attempt)", e)
                recorder.release()
                delay(300)
                continue
            }

            delay(500)
            if (sessionFailed.get() || !running) {
                Log.w(TAG, "session failed asynchronously after configure (attempt $attempt)")
                session.close()
                recorder.release()
                delay(300)
                continue
            }

            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recorderSurface)
                    addTarget(analysis.surface)
                }.build()
                session.setRepeatingRequest(request, null, callbackHandler)
                recorder.start()
            } catch (e: Exception) {
                Log.e(TAG, "setRepeatingRequest/start failed (attempt $attempt)", e)
                session.close()
                recorder.release()
                delay(300)
                continue
            }

            captureSession = session
            mediaRecorder = recorder
            currentStartedAtMs = System.currentTimeMillis()
            return true
        }
        return false
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        sessionFailed: AtomicBoolean,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionFailed.set(true)
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("session configure failed"))
                    }
                    override fun onClosed(session: CameraCaptureSession) {
                        sessionFailed.set(true)
                    }
                },
                callbackHandler,
            )
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        }
    }

    private fun buildRecorder(): MediaRecorder {
        val file = File(clipsDir, clipFileName())
        currentFile = file
        val size = recordingSize
        @Suppress("DEPRECATION")
        val recorder = MediaRecorder()
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(file.absolutePath)
        recorder.setVideoEncodingBitRate(4_000_000)
        recorder.setVideoFrameRate(30)
        recorder.setVideoSize(size.width, size.height)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        // MediaRecorder exposes no keyframe/I-frame-interval control (MediaCodec-only);
        // we can only measure what the device's default encoder produces. See
        // logKeyframeCadence() and QUIRKS.md.
        recorder.prepare()
        return recorder
    }

    private fun clipFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val suffix = (Math.random() * 0xffff).toInt().toString(16).padStart(4, '0')
        return "clip_${ts}_$suffix.mp4"
    }

    private fun finishCurrentSegment(deleteIfEmpty: Boolean) {
        val recorder = mediaRecorder ?: return
        val file = currentFile
        val startedAt = currentStartedAtMs
        mediaRecorder = null
        currentFile = null
        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.w(TAG, "recorder.stop() threw (short/empty segment?)", e)
        }
        try {
            recorder.release()
        } catch (e: Exception) {
            Log.w(TAG, "recorder.release() threw", e)
        }
        captureSession?.let {
            try { it.close() } catch (e: Exception) { /* already torn down */ }
        }
        captureSession = null

        if (file == null) return
        if (!file.exists() || file.length() == 0L) {
            if (deleteIfEmpty) file.delete()
            return
        }
        val durationMs = System.currentTimeMillis() - startedAt
        logKeyframeCadence(file)
        onClipFinished(file, startedAt, durationMs, recordingSize.width, recordingSize.height)
    }

    private fun closeSessionAndDevice() {
        captureSession?.let { runCatching { it.close() } }
        captureSession = null
        analysisReader?.let { runCatching { it.close() } }
        analysisReader = null
        cameraDevice?.let { runCatching { it.close() } }
        cameraDevice = null
    }

    /**
     * Camera-declared sizes intersected with what the AVC encoder actually
     * supports (`isSizeSupported`) - defends against the old prototype's
     * "unsupported encoder size silently black-frames" quirk. Falls back to
     * 1280x720, else the largest agreed size.
     */
    private fun pickRecordingSize(): Size {
        val id = backCameraId() ?: return Size(1280, 720)
        val chars = cameraManager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val cameraSizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val avcInfo = codecList.codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }
        val videoCaps = avcInfo?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities

        val supported = cameraSizes.filter { size ->
            videoCaps == null || videoCaps.isSizeSupported(size.width, size.height)
        }

        return supported.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: supported.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }

    /**
     * Diagnostic-only (logcat): reads back actual keyframe timestamps to check
     * the old prototype's "KEY_I_FRAME_INTERVAL isn't honored reliably"
     * finding against this device. Doesn't change behavior.
     */
    private fun logKeyframeCadence(file: File) {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i
                    break
                }
            }
            if (videoTrack < 0) return
            extractor.selectTrack(videoTrack)
            val keyframeTimesUs = mutableListOf<Long>()
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break
                val isKeyFrame = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                if (isKeyFrame) keyframeTimesUs.add(sampleTime)
                if (!extractor.advance()) break
            }
            extractor.release()
            if (keyframeTimesUs.size > 1) {
                val deltasMs = keyframeTimesUs.zipWithNext { a, b -> (b - a) / 1000 }
                Log.i(TAG, "keyframe cadence for ${file.name}: ${deltasMs.size} intervals, deltas(ms)=$deltasMs")
            } else {
                Log.i(TAG, "keyframe cadence for ${file.name}: only ${keyframeTimesUs.size} keyframe(s) in this clip")
            }
        } catch (e: Exception) {
            Log.w(TAG, "keyframe cadence probe failed for ${file.name}", e)
        }
    }
}
