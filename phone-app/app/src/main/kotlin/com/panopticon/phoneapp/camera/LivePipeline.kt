package com.panopticon.phoneapp.camera

import android.content.Context
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "LivePipeline"
private const val DEQUEUE_TIMEOUT_US = 10_000L

/**
 * LIVE-mode camera pipeline: a single camera stream rendered **straight into a `MediaCodec` H.264
 * encoder's input surface** (no GL, no `SurfaceTexture` - live preview needs no motion analysis,
 * per phone-http-api.md's "motion detection stays out of live preview"), drained to a
 * [LiveHlsRelay] that produces a rolling plain-HLS playlist.
 *
 * RECORD and LIVE are mutually exclusive (see [com.panopticon.phoneapp.state.AppMode]); this only
 * exists while the phone is in LIVE mode. Two sub-states:
 *  - **armed-idle** ([start] done, [startBroadcasting] not called): camera open, capture session
 *    configured with the encoder surface, but the repeating request doesn't target it - no frames
 *    flow, nothing encodes. Costs a warm camera, not battery for encoding nobody's watching.
 *  - **broadcasting** ([startBroadcasting]): the repeating request targets the encoder surface and
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
    private val bitRate: Int = 2_000_000,
    private val frameRate: Int = 24,
    private val segmentDurationUs: Long = LiveHlsRelay.DEFAULT_SEGMENT_DURATION_US,
    private val inactivityTimeoutMs: Long = 15_000L,
    private val onHealthChanged: (Boolean) -> Unit = {},
    private val onBroadcastingChanged: (Boolean) -> Unit = {},
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Manual-control state (see CameraControlApply). Re-applied to the repeating request on
    // applyControls(); the caps are read once the camera id is resolved in arm().
    @Volatile private var controls: CameraControlSpec = initialControls
    @Volatile private var caps: CameraCapabilities? = null
    @Volatile private var openCameraId: String? = null
    /** Set when [cameraId] is "<logical>:<physical>": session outputs pinned via [PhysicalCameraApi28]. */
    @Volatile private var physicalCameraId: String? = null

    private val callbackThread = HandlerThread("PanopticonLiveCb").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val workExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PanopticonLiveWork") }
    private val scope = CoroutineScope(SupervisorJob() + workExecutor.asCoroutineDispatcher())
    private val mutex = Mutex()

    private var recordingSize = Size(1280, 720)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
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

    /** Idempotent. Begins broadcasting (repeating request targets the encoder, relay runs).
     * Returns the viewer count (1), or 0 if the camera never armed. */
    suspend fun startBroadcasting(): Int = mutex.withLock {
        lastAccessMs.set(SystemClock.elapsedRealtime())
        if (broadcasting.get()) return@withLock 1
        val ok = withTimeoutOrNull(4_000L) { armed.await() } ?: false
        if (!ok) {
            Log.w(TAG, "startBroadcasting: camera not armed")
            return@withLock 0
        }
        val device = cameraDevice ?: return@withLock 0
        val session = captureSession ?: return@withLock 0
        val surface = encoderInputSurface ?: return@withLock 0

        relay = LiveHlsRelay(liveDir, segmentDurationUs)
        startDrain()
        session.setRepeatingRequest(buildLiveRequest(device, surface), null, callbackHandler)
        broadcasting.set(true)
        startWatchdog()
        startSyncFrameLoop()
        onBroadcastingChanged(true)
        Log.i(TAG, "live broadcasting started (${recordingSize.width}x${recordingSize.height} @ ${bitRate / 1000}kbps)")
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
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = encoderInputSurface ?: return
        if (!broadcasting.get()) return
        runCatching {
            session.setRepeatingRequest(buildLiveRequest(device, surface), null, callbackHandler)
        }.onFailure { Log.w(TAG, "applyControls: setRepeatingRequest failed", it) }
    }

    private fun buildLiveRequest(device: CameraDevice, surface: Surface): CaptureRequest =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            // Pin the capture rate so the encoder gets a steady frame cadence - a HAL that drops
            // to 15fps for exposure makes hls.js's buffer maths lumpy.
            stableFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            caps?.let { CameraControlApply.applyTo(this, controls, it) }
        }.build()

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
        recordingSize = pickRecordingSize(physId ?: logicalId)
        cameraDevice = openCameraDevice(logicalId)
        createEncoder()
        val surface = encoderInputSurface ?: throw IllegalStateException("encoder has no input surface")
        val device = cameraDevice ?: throw IllegalStateException("camera closed")
        val failed = AtomicBoolean(false)
        val session = createCaptureSession(device, listOf(surface), failed)
        delay(300)
        if (failed.get() || released) {
            session.close()
            throw IllegalStateException("live session failed to configure")
        }
        captureSession = session
        Log.i(TAG, "live camera armed-idle (${recordingSize.width}x${recordingSize.height})")
    }

    private fun createEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, recordingSize.width, recordingSize.height).apply {
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
        runCatching { drainThread?.join(2000) }
        drainThread = null
        runCatching { relay?.stop() }
        relay = null
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { encoderInputSurface?.release() }
        captureSession = null
        cameraDevice = null
        encoder = null
        encoderInputSurface = null
        trackFormat = null
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

    private fun pickRecordingSize(id: String): Size {
        val map = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val camSizes = map?.getOutputSizes(MediaCodec::class.java)?.toList() ?: emptyList()
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val avc = codecList.codecInfos.firstOrNull {
            it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        val caps = avc?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        val supported = camSizes.filter { caps == null || caps.isSizeSupported(it.width, it.height) }
        return supported.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: supported.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
    }
}
