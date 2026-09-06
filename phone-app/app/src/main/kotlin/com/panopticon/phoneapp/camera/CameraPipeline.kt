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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraPipeline"

/** How often the phase loop wakes to check for an ARMED<->RECORDING transition. */
private const val PHASE_POLL_MS = 100L

/** SharedPreferences flag: this device failed the gapless-rotation probe, always
 *  use the per-segment-rebuild path. */
private const val PREF_GAPLESS_DISABLED = "gapless_rotation_disabled"

/**
 * Camera2 + MediaRecorder pipeline for the back camera, now **motion-gated**:
 * an always-on analysis stream (a small YUV `ImageReader`) feeds [MotionDetector],
 * whose verdict drives [RecordingPhaseController]. While ARMED the session
 * carries only the analysis surface and nothing is written; the first frame
 * with motion flips to RECORDING, which reconfigures the session to add a
 * `MediaRecorder` surface and records H.264. Recording is held for a trailer
 * window after motion last stopped, then it disarms.
 *
 * **Gapless segment rotation.** One `MediaRecorder` + one `CameraCaptureSession`
 * stay alive for the whole RECORDING phase (one motion event). `setMaxFileSize`
 * (sized to ~one rotation interval of video) drives rotation:
 * `MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING` is our cue to hand over the next
 * file with `setNextOutputFile`, the recorder rolls into it at 100% without
 * stopping the encoder or reconfiguring the session, and
 * `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED` tells us the previous file is
 * finalised. So consecutive segments of one motion event are contiguous (no
 * ~1-2s teardown/rebuild gap). `setMaxDuration` is NOT usable for this - on
 * oriole it stops the encoder instead of rolling.
 *
 * Some HALs can't do this at all (the BLU G5's Spreadtrum encoder errors out on
 * `setMaxFileSize` + `setNextOutputFile` and writes nothing). A fail-fast probe
 * (real bytes within ~2s of `start()`?) plus the mid-recording error/watchdog
 * checks catch that; the first time any fires, the pipeline falls back to
 * `runRecordingPhaseLegacy` (tear down + rebuild per ~10s segment - the ~1-2s
 * rotation gap is back, but it works everywhere) and remembers the decision in
 * SharedPreferences so the probe runs at most once per device.
 *
 * (The ARMED->RECORDING transition between *separate* motion events always tears
 * the session down - only rotation *within* a motion event is ever gapless.)
 *
 * Simplifications still in force for this slice (documented, not accidental):
 *  - **Frame-difference motion only** - no background model / CV library. See
 *    [MotionDetector]; thresholds are un-tuned starting points.
 *  - **No pre-roll.** A segment starts at motion-detection time - `MediaRecorder`
 *    can't back-date a buffer. Pre-roll is tied to the future
 *    `MediaCodec`+`MediaMuxer` switch.
 *  - **Full `CameraCaptureSession` teardown+recreate** on every ARMED<->RECORDING
 *    transition (but NOT between segments any more - see above).
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

    // ---- Continuous-recording state (one MediaRecorder for the whole RECORDING phase) ----
    // Touched only on the camera work thread, except rollEvents/recorderError which the
    // MediaRecorder callback (main Looper) writes and the work thread drains.
    private var mediaRecorder: MediaRecorder? = null
    private val segmentFiles = ArrayList<File>()   // index k -> the k-th output file
    private val segmentStartMs = ArrayList<Long>() // index k -> wall-clock start of segmentFiles[k]
    private var rollsPublished = 0                 // segments 0..rollsPublished-1 already handed off
    private val rollEvents = ConcurrentLinkedQueue<Long>() // wall-clock ms of each NEXT_OUTPUT_FILE_STARTED
    private val armNextRequested = AtomicBoolean(false)    // set by MAX_FILESIZE_APPROACHING
    private val recorderError = AtomicBoolean(false)
    private val encoderBitRate = 4_000_000

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

    // ---- RECORDING ----
    //
    // Preferred path: one recorder + one session for the whole motion event,
    // rolling output files gaplessly with setNextOutputFile. Fallback (for HALs
    // that can't do that - e.g. the BLU G5's Spreadtrum encoder errors out on
    // setMaxFileSize+setNextOutputFile): tear down and rebuild per ~10s segment,
    // the pre-gapless behaviour, which reintroduces the ~1-2s rotation gap but
    // works everywhere. The decision is probed once and remembered on disk, so a
    // weak device pays the ~2s probe cost only on its very first motion event ever.

    private val camPrefs = context.applicationContext.getSharedPreferences("panopticon_camera", Context.MODE_PRIVATE)
    @Volatile private var gaplessRotationDisabled = camPrefs.getBoolean(PREF_GAPLESS_DISABLED, false)

    private fun disableGaplessRotation(reason: String) {
        Log.w(TAG, "gapless segment rotation not supported here; using per-segment rebuild: $reason")
        gaplessRotationDisabled = true
        camPrefs.edit().putBoolean(PREF_GAPLESS_DISABLED, true).apply()
    }

    private suspend fun runRecordingPhase() {
        onPhaseChanged(true)
        if (gaplessRotationDisabled) {
            runRecordingPhaseLegacy()
            return
        }
        try {
            runRecordingPhaseGapless()
        } catch (e: GaplessRotationUnsupported) {
            disableGaplessRotation(e.message ?: "unknown")
            runCatching { finishRecording() }
            if (running && phaseController.phase == RecordingPhaseController.Phase.RECORDING) {
                runRecordingPhaseLegacy()
            }
        }
    }

    private class GaplessRotationUnsupported(message: String) : Exception(message)

    private suspend fun runRecordingPhaseGapless() {
        if (!beginRecording()) throw IllegalStateException("could not start recording after retries")

        // Fail-fast probe: a healthy encoder appends a steady stream (~1MB in a
        // couple of seconds at this bitrate). A HAL that can't do size-based
        // rollover tends to write only the container header (~32B) then stall -
        // catch that here rather than after a full watchdog interval of a dead
        // recording. Two samples: enough bytes AND still growing.
        val probeFile = segmentFiles.firstOrNull()
        delay(1_500)
        val len1 = probeFile?.length() ?: 0L
        if (recorderError.get()) throw GaplessRotationUnsupported("MediaRecorder error right after start")
        delay(1_000)
        val len2 = probeFile?.length() ?: 0L
        if (recorderError.get()) throw GaplessRotationUnsupported("MediaRecorder error right after start")
        if (len2 < 64 * 1024 || len2 <= len1) {
            throw GaplessRotationUnsupported("encoder not producing data (${len1}B -> ${len2}B in 1s)")
        }

        // Rotation is size-driven: setMaxFileSize fires MAX_FILESIZE_APPROACHING
        // (~90%), we hand over the next file, and the recorder rolls into it at
        // 100% without stopping. (setMaxDuration was tried first - on oriole it
        // *stops* the encoder rather than rolling, so it can't be used here.)
        // We only arm when a roll is actually near, so a short motion event never
        // leaves a pending setNextOutputFile for recorder.stop() to choke on.
        var armed = false
        var armedAtMs = 0L
        try {
            while (running && phaseController.phase == RecordingPhaseController.Phase.RECORDING) {
                if (recorderError.get()) throw GaplessRotationUnsupported("MediaRecorder error mid-recording")

                var rolled = false
                while (true) {
                    val rollAt = rollEvents.poll() ?: break
                    handleRoll(rollAt)
                    armed = false
                    rolled = true
                }

                val now = System.currentTimeMillis()
                val activeStart = segmentStartMs.getOrNull(rollsPublished) ?: now
                val armDue = armNextRequested.getAndSet(false) ||
                    now - activeStart > rotationIntervalMs * 3 / 2 // backup if APPROACHING never comes
                if (!armed && armDue) {
                    mediaRecorder?.let { armNextFile(it) }
                    armed = true
                    armedAtMs = now
                }
                // Watchdog: once armed, the 100% roll should land within one more
                // interval. If not, this HAL isn't honouring setNextOutputFile
                // rollover - fall back to the per-segment-rebuild path.
                if (armed && !rolled && now - armedAtMs > rotationIntervalMs * 2) {
                    throw GaplessRotationUnsupported("armed setNextOutputFile but no rollover in ${rotationIntervalMs * 2}ms")
                }
                delay(PHASE_POLL_MS)
            }
        } finally {
            finishRecording()
        }
    }

    /**
     * Pre-gapless rotation: build recorder + session, record one segment for
     * rotationIntervalMs (or until motion stops), tear it all down, repeat. Used
     * only when [runRecordingPhaseGapless] has proven the device can't roll
     * files. Has the ~1-2s teardown gap between segments this whole change set
     * out to remove - but it works on every HAL.
     */
    private suspend fun runRecordingPhaseLegacy() {
        while (running && phaseController.phase == RecordingPhaseController.Phase.RECORDING) {
            if (!beginLegacySegment()) throw IllegalStateException("could not start a recording segment after retries")
            val deadline = System.currentTimeMillis() + rotationIntervalMs
            while (running &&
                phaseController.phase == RecordingPhaseController.Phase.RECORDING &&
                System.currentTimeMillis() < deadline
            ) {
                delay(PHASE_POLL_MS)
            }
            finishLegacySegment()
        }
    }

    /**
     * A `NEXT_OUTPUT_FILE_STARTED` was observed at [atMs]: segment `rollsPublished`
     * is finalised and segment `rollsPublished + 1` is now recording. Publish the
     * finished one.
     */
    private fun handleRoll(atMs: Long) {
        mediaRecorder ?: return
        val completedIdx = rollsPublished
        val activeIdx = completedIdx + 1
        if (activeIdx >= segmentFiles.size) {
            Log.w(TAG, "roll observed but no armed file at idx $activeIdx")
            return
        }
        while (segmentStartMs.size <= activeIdx) segmentStartMs.add(atMs)

        val doneFile = segmentFiles[completedIdx]
        val startMs = segmentStartMs[completedIdx]
        publishSegment(doneFile, startMs, atMs - startMs)
        rollsPublished++
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
     * Builds the one MediaRecorder + one capture session ([analysisReader] +
     * recorder surfaces) that stay alive for the whole RECORDING phase, starts
     * recording, and arms the first rollover file. Keeps the old prototype's
     * confirmed-quirk workaround: configure -> wait 500ms -> check for an async
     * failure before trusting the session and calling `recorder.start()`.
     * Retries up to 3x with a fresh recorder+session each attempt.
     */
    private suspend fun beginRecording(): Boolean {
        var attempt = 0
        while (attempt < 3 && running) {
            attempt++
            val device = cameraDevice ?: return false
            val analysis = analysisReader ?: return false

            resetRotationState()
            val recorder = buildContinuousRecorder()
            if (recorder == null) {
                delay(300)
                continue
            }
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
                segmentStartMs.add(System.currentTimeMillis()) // start of segmentFiles[0]
            } catch (e: Exception) {
                Log.e(TAG, "setRepeatingRequest/start failed (attempt $attempt)", e)
                session.close()
                recorder.release()
                delay(300)
                continue
            }

            captureSession = session
            mediaRecorder = recorder
            return true
        }
        return false
    }

    private fun resetRotationState() {
        // Drop any files a previous failed beginRecording() attempt created but
        // never started writing to.
        for (i in rollsPublished until segmentFiles.size) {
            val f = segmentFiles[i]
            if (!f.exists() || f.length() == 0L) runCatching { f.delete() }
        }
        segmentFiles.clear()
        segmentStartMs.clear()
        rollsPublished = 0
        rollEvents.clear()
        armNextRequested.set(false)
        recorderError.set(false)
    }

    /** Hands the recorder the file to roll into when the current one hits its
     *  size cap. Called once a roll is imminent (see runRecordingPhase). */
    private fun armNextFile(recorder: MediaRecorder) {
        val next = File(segmentsDir, segmentFileName())
        try {
            recorder.setNextOutputFile(next)
            segmentFiles.add(next)
        } catch (e: Exception) {
            Log.w(TAG, "setNextOutputFile failed; rotation may pause until the watchdog rebuilds", e)
            runCatching { next.delete() }
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

    /**
     * The single recorder for a RECORDING phase. Writes segmentFiles[0]; every
     * subsequent file is handed over live via [armNextFile]. `setMaxFileSize`
     * drives rotation - `MAX_FILESIZE_APPROACHING` (~90%) is our "arm the next
     * file now" cue and `NEXT_OUTPUT_FILE_STARTED` the "previous file is done"
     * one. Returns null on setup failure so the caller can retry.
     */
    private fun buildContinuousRecorder(): MediaRecorder? {
        val f0 = File(segmentsDir, segmentFileName())
        val size = recordingSize
        @Suppress("DEPRECATION")
        val recorder = MediaRecorder()
        return try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(f0.absolutePath)
            recorder.setVideoEncodingBitRate(encoderBitRate)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(size.width, size.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            // Rotate by size, ~one rotationIntervalMs of video at the target
            // bitrate. Only setMaxFileSize rolls into setNextOutputFile;
            // setMaxDuration was tried and merely stops the encoder on oriole.
            recorder.setMaxFileSize((encoderBitRate.toLong() / 8L) * (rotationIntervalMs / 1000L))
            recorder.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ->
                        armNextRequested.set(true)
                    MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED ->
                        rollEvents.add(System.currentTimeMillis())
                    // MAX_FILESIZE_REACHED: if a next file was armed the roll is
                    // automatic and *_STARTED follows; if not, the recorder stops
                    // and the runRecordingPhase watchdog rebuilds.
                }
            }
            recorder.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                recorderError.set(true)
            }
            // MediaRecorder exposes no keyframe/I-frame-interval control (MediaCodec-only);
            // we can only measure what the device's default encoder produces. See
            // logKeyframeCadence() and QUIRKS.md.
            recorder.prepare()
            segmentFiles.add(f0)
            recorder
        } catch (e: Exception) {
            Log.e(TAG, "buildContinuousRecorder failed", e)
            runCatching { recorder.release() }
            runCatching { f0.delete() }
            null
        }
    }

    private fun segmentFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val suffix = (Math.random() * 0xffff).toInt().toString(16).padStart(4, '0')
        // On-disk name keeps the historical "clip_" prefix: reconcile() scans by
        // .mp4 extension, not prefix, and there's no reason to churn it.
        return "clip_${ts}_$suffix.mp4"
    }

    /**
     * Tears down the RECORDING phase's recorder + session: drains any rollover
     * events still queued, stops the recorder, publishes the final (partial)
     * segment, and deletes the armed-but-never-started file. Safe to call with
     * nothing recording. Called on motion-stop, on `stop()`, and from the
     * runLoop error path.
     */
    private fun finishRecording() {
        if (legacyFile != null) { // legacy per-segment path is active
            finishLegacySegment()
            return
        }
        val recorder = mediaRecorder ?: return
        mediaRecorder = null

        // Segments that rolled since the last poll are real, fully-written files -
        // publish them, but don't arm anything new.
        while (true) {
            val rollAt = rollEvents.poll() ?: break
            runCatching { handleRoll(rollAt) }
        }

        val activeIdx = rollsPublished
        try { recorder.stop() } catch (e: Exception) { Log.w(TAG, "recorder.stop() threw (short/empty segment?)", e) }
        try { recorder.release() } catch (e: Exception) { Log.w(TAG, "recorder.release() threw", e) }
        captureSession?.let { runCatching { it.close() } }
        captureSession = null

        // The active file at teardown: publish it if it has content.
        segmentFiles.getOrNull(activeIdx)?.let { activeFile ->
            val startMs = segmentStartMs.getOrNull(activeIdx) ?: System.currentTimeMillis()
            publishSegment(activeFile, startMs, System.currentTimeMillis() - startMs)
        }
        // Any file armed via setNextOutputFile but never started is a 0-byte stub.
        for (i in (activeIdx + 1) until segmentFiles.size) {
            runCatching { segmentFiles[i].delete() }
        }
        resetRotationState()
    }

    // ---- Legacy per-segment rotation (fallback for HALs that can't roll files) ----

    private var legacyFile: File? = null
    private var legacyStartMs = 0L

    private suspend fun beginLegacySegment(): Boolean {
        var attempt = 0
        while (attempt < 3 && running) {
            attempt++
            val device = cameraDevice ?: return false
            val analysis = analysisReader ?: return false

            val recorder = buildLegacyRecorder()
            if (recorder == null) {
                delay(300)
                continue
            }
            val recorderSurface = recorder.surface
            val sessionFailed = AtomicBoolean(false)
            val session = try {
                createCaptureSession(device, listOf(analysis.surface, recorderSurface), sessionFailed)
            } catch (e: Exception) {
                Log.e(TAG, "legacy createCaptureSession threw (attempt $attempt)", e)
                recorder.release(); legacyFile = null; delay(300); continue
            }
            delay(500)
            if (sessionFailed.get() || !running) {
                session.close(); recorder.release(); legacyFile = null; delay(300); continue
            }
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recorderSurface)
                    addTarget(analysis.surface)
                }.build()
                session.setRepeatingRequest(request, null, callbackHandler)
                recorder.start()
            } catch (e: Exception) {
                Log.e(TAG, "legacy setRepeatingRequest/start failed (attempt $attempt)", e)
                session.close(); recorder.release(); legacyFile = null; delay(300); continue
            }
            captureSession = session
            mediaRecorder = recorder
            legacyStartMs = System.currentTimeMillis()
            return true
        }
        return false
    }

    private fun buildLegacyRecorder(): MediaRecorder? {
        val file = File(segmentsDir, segmentFileName())
        val size = recordingSize
        @Suppress("DEPRECATION")
        val recorder = MediaRecorder()
        return try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.absolutePath)
            recorder.setVideoEncodingBitRate(encoderBitRate)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(size.width, size.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.prepare()
            legacyFile = file
            recorder
        } catch (e: Exception) {
            Log.e(TAG, "buildLegacyRecorder failed", e)
            runCatching { recorder.release() }
            runCatching { file.delete() }
            null
        }
    }

    private fun finishLegacySegment() {
        val recorder = mediaRecorder ?: return
        mediaRecorder = null
        val file = legacyFile
        legacyFile = null
        val startMs = legacyStartMs
        try { recorder.stop() } catch (e: Exception) { Log.w(TAG, "legacy recorder.stop() threw (short segment?)", e) }
        try { recorder.release() } catch (e: Exception) { Log.w(TAG, "legacy recorder.release() threw", e) }
        captureSession?.let { runCatching { it.close() } }
        captureSession = null
        if (file != null) publishSegment(file, startMs, System.currentTimeMillis() - startMs)
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
                Log.i(TAG, "keyframe cadence for ${file.name}: only ${keyframeTimesUs.size} keyframe(s) in this segment")
            }
        } catch (e: Exception) {
            Log.w(TAG, "keyframe cadence probe failed for ${file.name}", e)
        }
    }
}
