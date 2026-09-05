package com.panopticon.phoneapp.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
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

/**
 * Camera2 + MediaRecorder pipeline that continuously records the back camera into rotating
 * H.264 clip files (~10s each). No real motion detection in this slice - deliberately always
 * recording ("always motion" gate). See QUIRKS.md for what was re-verified on the Pixel 6 while
 * building this (session-reconfigure stress from the rotation loop, keyframe-interval honesty).
 *
 * Simplifications called out explicitly (documented, not accidental):
 *  - Always-recording instead of real motion-gated start/stop (`RecordingPhaseController` in the
 *    old prototype). A real detector is out of scope for this slice.
 *  - Full CameraCaptureSession teardown+recreate on every rotation, rather than a lighter
 *    in-place surface swap. Simpler to reason about and doubles as a stress test of Camera2
 *    session-reconfigure robustness (see QUIRKS.md).
 *  - No audio track - video only, avoids RECORD_AUDIO permission entirely for this slice.
 */
class CameraPipeline(
    private val context: Context,
    private val clipsDir: File,
    private val rotationIntervalMs: Long = 10_000L,
    private val onClipFinished: (file: File, createdAtMs: Long, durationMs: Long, width: Int, height: Int) -> Unit,
    private val onHealthChanged: (Boolean) -> Unit,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val callbackThread = HandlerThread("PanopticonCameraCallback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private val workExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PanopticonCameraWork") }
    private val scope = CoroutineScope(SupervisorJob() + workExecutor.asCoroutineDispatcher())

    private var runLoopJob: Job? = null
    private var running = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
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
                onHealthChanged(true)
                attempt = 0

                // Rotate clips on a fixed cadence for as long as the session stays healthy.
                while (running) {
                    val started = beginSegment()
                    if (!started) throw IllegalStateException("could not start a segment after retries")
                    delay(rotationIntervalMs)
                    if (!running) break
                    finishCurrentSegment(deleteIfEmpty = false)
                }
            } catch (e: Exception) {
                // Camera2 calls have been observed to throw synchronously under HAL stress, not
                // just report failure via callbacks - treat any failure here exactly like an
                // unexpected device close: tear everything down and let the loop reopen.
                Log.e(TAG, "camera loop error (attempt ${attempt + 1})", e)
                onHealthChanged(false)
                runCatching { finishCurrentSegment(deleteIfEmpty = true) }
                runCatching { closeSessionAndDevice() }
                attempt++
                delay(minOf(500L * attempt, 5000L))
            }
        }
    }

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
            // Reconfirmed on Pixel 6 during this slice: openCamera can throw synchronously
            // (not just via the async callback) - see QUIRKS.md.
            cont.resumeWithException(e)
        } catch (e: SecurityException) {
            cont.resumeWithException(e)
        }
    }

    /**
     * Builds a fresh MediaRecorder + capture session for the next clip file, then - per the old
     * prototype's confirmed quirk that a session can report configured successfully and fail at
     * the very first capture request - waits briefly and checks whether an async failure landed
     * before trusting it and calling `recorder.start()`. Retries up to 3 times (fresh recorder +
     * session each attempt, since a MediaRecorder that's failed once can't be reused).
     */
    private suspend fun beginSegment(): Boolean {
        var attempt = 0
        while (attempt < 3 && running) {
            attempt++
            val device = cameraDevice ?: return false
            val recorder = buildRecorder()
            val surface = recorder.surface
            val sessionFailed = AtomicBoolean(false)

            val session = try {
                createCaptureSession(device, surface, sessionFailed)
            } catch (e: Exception) {
                Log.e(TAG, "createCaptureSession threw (attempt $attempt)", e)
                recorder.release()
                delay(300)
                continue
            }

            // Post-configure settle window: give any asynchronous HAL failure a chance to
            // surface before we trust this session and start recording into it.
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
                    addTarget(surface)
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
        surface: android.view.Surface,
        sessionFailed: AtomicBoolean,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(surface),
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
        val dir = clipsDir
        val name = clipFileName()
        val file = File(dir, name)
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
        // Note: MediaRecorder has no public API for keyframe/I-frame interval or explicit
        // sync-frame requests at all (those are MediaCodec-only - MediaFormat.KEY_I_FRAME_INTERVAL,
        // PARAMETER_KEY_REQUEST_SYNC_FRAME - and this pipeline deliberately uses MediaRecorder for
        // simplicity, see class doc). We can't set a target cadence, only measure whatever the
        // device's default encoder produces; see logKeyframeCadence() and QUIRKS.md.
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
        cameraDevice?.let { runCatching { it.close() } }
        cameraDevice = null
    }

    /**
     * Cross-references camera-declared sizes against what the AVC encoder actually claims to
     * support (`MediaCodecInfo.VideoCapabilities.isSizeSupported`) - reconfirms the old
     * prototype's "unsupported encoder size silently black-frames" quirk defensively, by simply
     * never selecting a size the encoder doesn't also agree on. Falls back to 1280x720 if that
     * exact size isn't in the intersection, else the largest supported size.
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
     * Reads back the actual keyframe timestamps MediaRecorder produced, to check the old
     * prototype's "KEY_I_FRAME_INTERVAL isn't honored reliably" finding against this device.
     * Purely diagnostic (logcat only) - doesn't change behavior, since a rotating ~10s clip
     * doesn't depend on keyframe cadence the way the old live-HLS segmenter did.
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
                Log.i(TAG, "keyframe cadence for ${file.name}: only ${keyframeTimesUs.size} keyframe(s) in this ~${rotationIntervalMs}ms clip")
            }
        } catch (e: Exception) {
            Log.w(TAG, "keyframe cadence probe failed for ${file.name}", e)
        }
    }
}
