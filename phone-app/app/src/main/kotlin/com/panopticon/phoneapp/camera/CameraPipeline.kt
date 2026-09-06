package com.panopticon.phoneapp.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
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
import kotlinx.coroutines.yield
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

/** Target encoder bitrate for the recording stream. */
private const val ENCODER_BIT_RATE = 4_000_000

/** Encoder GOP length. Also the coarsest granularity a rotation can land on if a
 *  device ignores our on-demand sync-frame request. */
private const val I_FRAME_INTERVAL_SEC = 1

/** dequeueOutputBuffer timeout (µs). Short so the drain loop still checks the
 *  recording phase promptly. */
private const val DEQUEUE_TIMEOUT_US = 10_000L

/** If the encoder emits no output at all for this long after a recording session
 *  is configured, treat the camera/encoder as unhealthy and let runLoop rebuild. */
private const val FIRST_OUTPUT_TIMEOUT_MS = 4_000L

/**
 * Camera2 + **MediaCodec/MediaMuxer** pipeline for the back camera, **motion-gated**:
 * an always-on analysis stream (a small YUV `ImageReader`) feeds [MotionDetector],
 * whose verdict drives [RecordingPhaseController]. While ARMED the session carries
 * only the analysis surface and nothing is written; the first frame with motion
 * flips to RECORDING, which reconfigures the session to add the encoder's input
 * surface. Recording is held for a trailer window after motion last stopped.
 *
 * **Gapless segment rotation.** One `MediaCodec` H.264 encoder (surface input)
 * runs for the whole RECORDING phase - it is never stopped or reconfigured. The
 * `MediaMuxer` writing its output to the current `.mp4` is what rotates: at each
 * ~[rotationIntervalMs] boundary we ask the encoder for a sync frame
 * (`PARAMETER_KEY_REQUEST_SYNC_FRAME`); on the next `BUFFER_FLAG_KEY_FRAME`
 * output buffer we `stop()`/`release()` the old muxer, open a new one on the next
 * file (re-`addTrack` from the cached output `MediaFormat`, which carries the
 * SPS/PPS), and write that keyframe as sample 0 of the new segment. The encoder
 * never pauses, so consecutive segments of one motion event are contiguous - no
 * ~1-2s session-rebuild gap, on every device. Per-segment sample PTS are
 * rebased to 0; each segment's wall-clock `createdAtMs` is chained from the
 * recording's start PTS so `endMs[k] == createdAtMs[k+1]`.
 *
 * (The ARMED->RECORDING transition between *separate* motion events still tears
 * the session down - only rotation *within* a motion event is gapless. That
 * boundary is a real motion stop and legitimately ends a clip.)
 *
 * Simplifications still in force (documented, not accidental):
 *  - **Frame-difference motion only** - no background model / CV library. See
 *    [MotionDetector]; thresholds are un-tuned starting points.
 *  - **No pre-roll** yet. The MediaCodec pipeline makes it feasible (feed a ring
 *    of pre-motion frames) but it isn't wired up.
 *  - **No audio track** - video only, avoids `RECORD_AUDIO` entirely.
 */
class CameraPipeline(
    private val context: Context,
    private val segmentsDir: File,
    private val appConfig: AppConfig,
    private val rotationIntervalMs: Long = 10_000L,
    private val trailerMs: Long = 5_000L,
    private val onSegmentFinished: (file: File, createdAtMs: Long, durationMs: Long, width: Int, height: Int) -> Unit,
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
    private var analysisReader: ImageReader? = null
    private var recordingSize: Size = Size(1280, 720)

    // ---- Recording state (one encoder for the whole RECORDING phase; the muxer
    // rotates). Touched only on the camera work thread. ----
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var muxerVideoTrack = -1
    private var muxerStarted = false
    private var trackFormat: MediaFormat? = null     // encoder's real output format (carries csd-0/csd-1)
    private var segFile: File? = null                // the segment currently being muxed
    private var segFirstPtsUs = -1L                  // PTS of the first sample written to segFile
    private var segLastPtsUs = 0L                    // PTS of the most recent sample written to segFile
    private var recStartPtsUs = -1L                  // PTS of the first sample of the whole recording
    private var recStartWallMs = 0L                  // wall clock at that first sample
    private var pendingRoll = false                  // rotation is due; waiting for a keyframe to land it
    private val frameDurationMs = 1000L / 30

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
            finishRecording()
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
                runCatching { finishRecording() }
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

    // ---- RECORDING: persistent encoder, rotating muxer ----

    private suspend fun runRecordingPhase() {
        onPhaseChanged(true)
        val device = cameraDevice ?: throw IllegalStateException("camera closed")
        val analysis = analysisReader ?: throw IllegalStateException("analysis reader missing")

        resetRecordingState()
        Log.i(TAG, "recording: ${recordingSize.width}x${recordingSize.height}")
        if (!createEncoder()) throw IllegalStateException("could not create the video encoder")
        val encSurface = encoderInputSurface ?: throw IllegalStateException("encoder has no input surface")

        val failed = AtomicBoolean(false)
        val session = createCaptureSession(device, listOf(analysis.surface, encSurface), failed)
        delay(500)
        if (failed.get() || !running) {
            session.close()
            throw IllegalStateException("recording session failed asynchronously")
        }
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(encSurface)
            addTarget(analysis.surface)
        }.build()
        session.setRepeatingRequest(request, null, callbackHandler)
        captureSession = session

        segFile = File(segmentsDir, segmentFileName()) // first segment; muxer opens on FORMAT_CHANGED

        try {
            drainLoop()
        } finally {
            finishRecording()
        }
    }

    /** Pulls encoded buffers, muxes them, and rotates the muxer at each interval.
     *  Runs until motion stops (phase leaves RECORDING) or the pipeline stops. */
    private suspend fun drainLoop() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        val rotationUs = rotationIntervalMs * 1000
        val startedAt = System.currentTimeMillis()
        var sawOutput = false

        while (running && phaseController.phase == RecordingPhaseController.Phase.RECORDING) {
            yield() // cancellation checkpoint; no-op cost on this single-thread dispatcher
            when (val idx = enc.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!sawOutput && System.currentTimeMillis() - startedAt > FIRST_OUTPUT_TIMEOUT_MS) {
                        throw IllegalStateException("encoder produced no output ${FIRST_OUTPUT_TIMEOUT_MS}ms after start")
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackFormat = enc.outputFormat
                    startMuxer()
                }
                else -> if (idx >= 0) {
                    sawOutput = true
                    handleEncodedBuffer(enc, idx, info, rotationUs, allowRoll = true)
                }
            }
        }
    }

    /**
     * Writes one encoded buffer to the current muxer. When [allowRoll] and a
     * rotation is pending, a keyframe buffer triggers the muxer swap first (so
     * the keyframe becomes sample 0 of the new segment). Returns true on EOS.
     */
    private fun handleEncodedBuffer(
        enc: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        rotationUs: Long,
        allowRoll: Boolean,
    ): Boolean {
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

        if (!isConfig && info.size > 0 && muxerStarted) {
            val buf = enc.getOutputBuffer(index)
            if (buf != null) {
                if (allowRoll && pendingRoll && isKey) rollMuxer()

                if (segFirstPtsUs < 0) {
                    segFirstPtsUs = info.presentationTimeUs
                    if (recStartPtsUs < 0) {
                        recStartPtsUs = info.presentationTimeUs
                        recStartWallMs = System.currentTimeMillis()
                    }
                }

                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val rebased = MediaCodec.BufferInfo().apply {
                    set(0, info.size, info.presentationTimeUs - segFirstPtsUs, info.flags)
                }
                muxer?.writeSampleData(muxerVideoTrack, buf, rebased)
                segLastPtsUs = info.presentationTimeUs

                if (allowRoll && !pendingRoll && info.presentationTimeUs - segFirstPtsUs >= rotationUs) {
                    requestSyncFrame(enc)
                    pendingRoll = true
                }
            }
        }
        enc.releaseOutputBuffer(index, false)
        return isEos
    }

    private fun requestSyncFrame(enc: MediaCodec) {
        runCatching {
            enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }.onFailure { Log.w(TAG, "request sync frame failed", it) }
    }

    private fun createEncoder(): Boolean {
        val size = recordingSize
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, ENCODER_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        }
        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            true
        } catch (e: Exception) {
            Log.e(TAG, "createEncoder failed", e)
            runCatching { encoder?.release() }
            encoder = null
            runCatching { encoderInputSurface?.release() }
            encoderInputSurface = null
            false
        }
    }

    private fun startMuxer() {
        val fmt = trackFormat ?: return
        val file = segFile ?: return
        if (muxerStarted) return
        val mx = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerVideoTrack = mx.addTrack(fmt)
        mx.start()
        muxer = mx
        muxerStarted = true
    }

    /** Finalises the current segment file and opens a fresh muxer on the next
     *  one. Called from the drain loop when a keyframe lands after a rotation
     *  became due - the encoder is untouched, so there is no gap. */
    private fun rollMuxer() {
        finalizeMuxer(publish = true)
        segFile = File(segmentsDir, segmentFileName())
        segFirstPtsUs = -1L
        segLastPtsUs = 0L
        pendingRoll = false
        startMuxer()
    }

    private fun finalizeMuxer(publish: Boolean) {
        val mx = muxer
        val file = segFile
        val firstPts = segFirstPtsUs
        val lastPts = segLastPtsUs
        muxer = null
        muxerStarted = false
        muxerVideoTrack = -1

        // A muxer with zero samples fails to stop and leaves a moov-less, broken
        // .mp4 - only publish on a clean stop with real samples, else delete.
        var stopped = false
        if (mx != null) {
            stopped = runCatching { mx.stop() }.onFailure { Log.w(TAG, "muxer.stop() threw", it) }.isSuccess
            runCatching { mx.release() }
        }
        if (file == null) return
        if (publish && stopped && firstPts >= 0 && file.exists() && file.length() > 0L) {
            val createdAtMs = recStartWallMs + (firstPts - recStartPtsUs) / 1000
            val durationMs = (lastPts - firstPts) / 1000 + frameDurationMs
            publishSegment(file, createdAtMs, durationMs)
        } else if (file.exists()) {
            runCatching { file.delete() }
        }
    }

    private fun publishSegment(file: File, startedAtMs: Long, durationMs: Long) {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "segment ${file.name} empty, dropping")
            runCatching { file.delete() }
            return
        }
        Log.i(TAG, "segment ${file.name}: start=$startedAtMs dur=${durationMs}ms size=${file.length()}B")
        logKeyframeCadence(file)
        onSegmentFinished(file, startedAtMs, durationMs.coerceAtLeast(0L), recordingSize.width, recordingSize.height)
    }

    /**
     * Tears down the RECORDING phase: signals end-of-stream on the encoder,
     * drains and muxes the tail, finalises the last (partial) segment, and
     * releases the encoder + session. Idempotent / safe when nothing is
     * recording. Called on motion-stop, on `stop()`, and from the runLoop error
     * path.
     */
    private fun finishRecording() {
        val enc = encoder ?: return
        encoder = null

        runCatching { captureSession?.stopRepeating() }
        runCatching { enc.signalEndOfInputStream() }
        drainTail(enc)
        finalizeMuxer(publish = true)

        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { enc.stop() }
        runCatching { enc.release() }
        runCatching { encoderInputSurface?.release() }
        encoderInputSurface = null
        resetRecordingState()
    }

    /** Best-effort drain of whatever the encoder still holds after EOS, bounded
     *  so teardown can't hang on a stuck codec. */
    private fun drainTail(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val deadline = System.currentTimeMillis() + 800
        while (System.currentTimeMillis() < deadline) {
            val idx = runCatching { enc.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US) }.getOrNull() ?: return
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Cache the format but don't open a muxer here - if the format
                    // only arrives during teardown there's no useful segment to
                    // write, and starting one just leaves a broken file.
                    trackFormat = enc.outputFormat
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                idx >= 0 -> {
                    val eos = runCatching {
                        handleEncodedBuffer(enc, idx, info, rotationIntervalMs * 1000, allowRoll = false)
                    }.getOrDefault(true)
                    if (eos) return
                }
            }
        }
    }

    private fun resetRecordingState() {
        encoder = null
        encoderInputSurface = null
        muxer = null
        muxerVideoTrack = -1
        muxerStarted = false
        trackFormat = null
        segFile = null
        segFirstPtsUs = -1L
        segLastPtsUs = 0L
        recStartPtsUs = -1L
        recStartWallMs = 0L
        pendingRoll = false
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

    private fun segmentFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val suffix = (Math.random() * 0xffff).toInt().toString(16).padStart(4, '0')
        // On-disk name keeps the historical "clip_" prefix: reconcile() scans by
        // .mp4 extension, not prefix, and there's no reason to churn it.
        return "clip_${ts}_$suffix.mp4"
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
     * Camera-declared recordable sizes intersected with what the AVC encoder
     * actually supports (`isSizeSupported`) - defends against the old prototype's
     * "unsupported encoder size silently black-frames" quirk. Falls back to
     * 1280x720, else the largest agreed size.
     */
    private fun pickRecordingSize(): Size {
        val id = backCameraId() ?: return Size(1280, 720)
        val chars = cameraManager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val cameraSizes = map?.getOutputSizes(MediaCodec::class.java)?.toList() ?: emptyList()

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
     * Diagnostic-only (logcat): reads back actual keyframe timestamps from each
     * finished segment via `MediaExtractor` and logs the deltas - a cheap check
     * on the encoder's real GOP cadence vs. the requested [I_FRAME_INTERVAL_SEC].
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
                Log.i(TAG, "keyframe cadence for ${file.name}: only ${keyframeTimesUs.size} keyframe(s) in this segment")
            }
        } catch (e: Exception) {
            Log.w(TAG, "keyframe cadence probe failed for ${file.name}", e)
        }
    }
}
