package com.panopticon.phoneapp.calibration

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import com.panopticon.phoneapp.BuildConfig
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.state.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

private const val TAG = "CalibrationRunner"

/** Zoom requests swept per resolution (geometric spacing, dense at the low end). */
private const val ZOOM_STEPS = 14

/** Of those, how many also get an off-centre crop-region probe (position honoured?). */
private const val POSITION_PROBE_STEPS = 6

/** Guardrail against pathological device size lists. */
private const val MAX_RESOLUTIONS_PER_CAMERA = 24

private const val INITIAL_SETTLE_MS = 800L
private const val PER_SAMPLE_SETTLE_MS = 320L

private const val SHARPNESS_PATCH = 256

/**
 * Runs one device-wide **empirical** zoom calibration sweep at a time.
 *
 * For every camera `CameraManager` reports, at every `StreamConfigurationMap`
 * output size, the sweep applies a geometric range of zoom requests and
 * records what the HAL actually did:
 *
 *  - the effective crop rect (`SCALER_CROP_REGION` read back), normalised -
 *    i.e. the true field of view at that zoom;
 *  - whether the requested ratio was honoured (`CONTROL_ZOOM_RATIO` on API 30+,
 *    else area of the reported crop);
 *  - whether an intentionally off-centre crop keeps its offset or is recentred;
 *  - which physical camera answered (`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`)
 *    and the reported lens focal length, to locate the optical->digital
 *    crossover;
 *  - a frame-sharpness score (variance of Laplacian on a centred Y patch), to
 *    catch digital-zoom quality collapse.
 *
 * Needs exclusive camera access, so it only runs from `AppMode.STANDBY` -
 * recording (and live preview) must be explicitly stopped first.
 */
class CalibrationRunner(
    context: Context,
    private val store: CalibrationStore,
    private val appState: AppState,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val callbackThread = HandlerThread("PanopticonCalibration").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    // Whether the API-gated Camera2 zoom keys exist on this device. Read once;
    // all use of them is funnelled through ZoomRatioApi30 / ActivePhysicalIdApi29
    // so the verifier never touches those fields on older devices.
    private val hasZoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private val hasActivePhysicalId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @Volatile
    private var active: ActiveRun? = null

    @Volatile
    private var lastCompletedRunId: String? = store.load()?.runId

    // ---- Public API (called from CalibrationRoutes) ----

    sealed interface StartOutcome {
        data class Started(val response: CalibrationStartResponse) : StartOutcome
        data class AlreadyRunning(val runId: String) : StartOutcome
        data class NoCameras(val message: String) : StartOutcome
        data class CameraBusy(val message: String) : StartOutcome
    }

    fun start(): StartOutcome {
        synchronized(lock) {
            active?.takeIf { it.status == "running" }?.let { return StartOutcome.AlreadyRunning(it.runId) }

            when (appState.mode.value) {
                AppMode.RECORD -> return StartOutcome.CameraBusy(
                    "stop recording on the phone before calibrating (POST /api/mode standby)",
                )
                AppMode.LIVE -> return StartOutcome.CameraBusy("live preview is using the camera")
                AppMode.STANDBY -> Unit
            }

            val cameraIds = try {
                cameraManager.cameraIdList.toList()
            } catch (e: Exception) {
                Log.e(TAG, "cameraIdList threw", e)
                emptyList()
            }
            if (cameraIds.isEmpty()) return StartOutcome.NoCameras("device reports no cameras")

            val run = ActiveRun(
                runId = "cal-" + randomHex(6),
                cameraIds = cameraIds,
                startedAtMs = System.currentTimeMillis(),
            )
            active = run
            run.job = scope.launch { sweep(run) }
            return StartOutcome.Started(
                CalibrationStartResponse(
                    runId = run.runId,
                    status = "running",
                    startedAtMs = run.startedAtMs,
                    cameraIds = cameraIds,
                ),
            )
        }
    }

    fun status(runId: String?): CalibrationStatus? {
        val run = active
        if (runId == null) return run?.snapshot() ?: lastCompletedSnapshot()
        if (run != null && run.runId == runId) return run.snapshot()
        if (runId == lastCompletedRunId) return lastCompletedSnapshot()
        return null
    }

    fun cancel(runId: String): Boolean {
        synchronized(lock) {
            val run = active ?: return false
            if (run.runId != runId || run.status != "running") return false
            run.status = "cancelled"
            run.job?.cancel()
            return true
        }
    }

    sealed interface ResultOutcome {
        data class Completed(val result: CalibrationResult) : ResultOutcome
        object NotCompleted : ResultOutcome
        object Unknown : ResultOutcome
    }

    fun result(runId: String?): ResultOutcome {
        val persisted = store.load()
        if (runId == null) return persisted?.let { ResultOutcome.Completed(it) } ?: ResultOutcome.Unknown
        if (persisted != null && persisted.runId == runId) return ResultOutcome.Completed(persisted)
        val run = active
        if (run != null && run.runId == runId) return ResultOutcome.NotCompleted
        return ResultOutcome.Unknown
    }

    // ---- The sweep ----

    private suspend fun sweep(run: ActiveRun) {
        try {
            // Grace period: if we just came out of RECORD, the motion pipeline's
            // camera teardown is still in flight on its own executor. Opening a
            // camera into that race gets the device disconnected out from under
            // us (seen on the BLU G5). Give it a beat.
            delay(1200)

            for ((camIndex, cameraId) in run.cameraIds.withIndex()) {
                run.currentCameraId = cameraId
                run.camerasCompleted = camIndex
                run.stepsCompleted = 0
                run.stepsTotal = 0

                val cam = try {
                    probeCamera(cameraId, run)
                } catch (e: Exception) {
                    if (run.status == "cancelled") throw e
                    Log.e(TAG, "camera $cameraId probe failed", e)
                    null
                }
                if (cam != null) run.cameras[cameraId] = cam
                run.camerasCompleted = camIndex + 1
            }

            synchronized(lock) {
                val result = CalibrationResult(
                    runId = run.runId,
                    runAtMs = System.currentTimeMillis(),
                    deviceIdentity = ResultDeviceIdentity(
                        manufacturer = Build.MANUFACTURER,
                        model = Build.MODEL,
                        device = Build.DEVICE,
                        appVersionName = BuildConfig.VERSION_NAME,
                    ),
                    cameras = run.cameras.toMap(),
                )
                store.save(result)
                run.status = "completed"
                lastCompletedRunId = run.runId
                Log.i(TAG, "calibration ${run.runId} completed (${result.cameras.size} cameras)")
            }
        } catch (e: Exception) {
            if (run.status == "cancelled") {
                Log.i(TAG, "calibration ${run.runId} cancelled; ${run.cameras.size} camera(s) kept partial results")
            } else {
                run.status = "error"
                Log.e(TAG, "calibration ${run.runId} failed", e)
            }
        }
    }

    private suspend fun probeCamera(cameraId: String, run: ActiveRun): CameraCalibration {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 4032, 3024)
        val activeRect = active.toIntRect()

        val zoomRange: Range<Float>? = if (hasZoomRatio) ZoomRatioApi30.ratioRange(chars) else null
        val maxDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        val lo = zoomRange?.lower ?: 1f
        val hi = zoomRange?.upper ?: (maxDigital ?: 1f).coerceAtLeast(1.0001f)
        val ratios = geometricRatios(lo, hi, ZOOM_STEPS)
        val positionProbeRatios = ratios.filterIndexed { i, _ ->
            i % (ratios.size / POSITION_PROBE_STEPS).coerceAtLeast(1) == 0
        }.toSet()

        val identity = buildIdentity(cameraId, chars, activeRect, maxDigital, zoomRange)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = (map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList())
            .distinctBy { it.width to it.height }
            // Smallest first: on a weak HAL we get real data from the easy
            // sizes before a large one has any chance of tipping the device over.
            .sortedBy { it.width.toLong() * it.height }
            .take(MAX_RESOLUTIONS_PER_CAMERA)
        run.stepsTotal = sizes.size

        // One camera open per resolution. Reusing a single CameraDevice across
        // session teardown+recreate disconnects the device entirely on some
        // HALs (reproduced on the BLU G5, API 28 - see docs/QUIRKS.md); a fresh
        // device per resolution is slower but every resolution actually gets
        // probed.
        val perResolution = LinkedHashMap<String, ResolutionZoomMap>()
        for ((resIndex, size) in sizes.withIndex()) {
            run.currentStep = "${size.width}x${size.height}"
            run.stepsCompleted = resIndex

            val lost = AtomicBoolean(false)
            val device = try {
                openCameraDeviceWithRetry(cameraId, lost)
            } catch (e: Exception) {
                if (run.status == "cancelled") throw e
                Log.w(TAG, "camera $cameraId won't open for ${size.width}x${size.height}", e)
                null
            }
            if (device != null) {
                try {
                    val zoomMap = probeResolution(device, size, activeRect, ratios, positionProbeRatios, lost, run)
                    if (zoomMap.samples.isNotEmpty()) {
                        perResolution["${size.width}x${size.height}"] = zoomMap
                    }
                } catch (e: Exception) {
                    if (run.status == "cancelled") throw e
                    Log.w(TAG, "camera $cameraId resolution ${size.width}x${size.height} failed", e)
                } finally {
                    runCatching { device.close() }
                    delay(600) // let the HAL release before the next open
                }
            }
            run.stepsCompleted = resIndex + 1
        }

        return summariseCamera(identity, perResolution, ratios)
    }

    private suspend fun probeResolution(
        device: CameraDevice,
        size: Size,
        activeRect: ZoomMath.IntRect,
        ratios: List<Float>,
        positionProbeRatios: Set<Float>,
        lost: AtomicBoolean,
        run: ActiveRun,
    ): ResolutionZoomMap {
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        val frameHolder = FrameHolder()
        val frameLock = Any()
        var readerClosing = false
        reader.setOnImageAvailableListener({ r ->
            synchronized(frameLock) {
                if (readerClosing) return@synchronized
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@synchronized
                try {
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    frameHolder.set(bytes, img.width, img.height, plane.rowStride)
                } catch (e: Exception) {
                    // image closed under us mid-read - benign, just drop this frame
                } finally {
                    runCatching { img.close() }
                }
            }
        }, callbackHandler)

        val resultHolder = ResultHolder()
        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                resultHolder.set(result)
            }
        }

        val session = createSession(device, reader.surface, lost)
        val samples = ArrayList<ZoomSample>(ratios.size)
        try {
            var settle = INITIAL_SETTLE_MS
            var lockAe = false
            for (ratio in ratios) {
                if (lost.get()) break
                val requestedCrop = ZoomMath.centeredCropForRatio(activeRect, ratio)

                // Primary probe: the repeating request holds one control the
                // whole time (either CONTROL_ZOOM_RATIO or a centred crop -
                // never both, and the position sub-probe below is a one-shot so
                // it never disturbs this stream; mixing the two paths in the
                // repeating request corrupts the Pixel 6 front camera's
                // readback - see docs/QUIRKS.md).
                fun primaryRequest(): CaptureRequest {
                    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(reader.surface)
                    if (lockAe) {
                        b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                        b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    }
                    if (hasZoomRatio) ZoomRatioApi30.setRequest(b, ratio)
                    else b.set(CaptureRequest.SCALER_CROP_REGION, requestedCrop.toRect())
                    return b.build()
                }

                val primary = try {
                    val req = primaryRequest()
                    session.setRepeatingRequest(req, captureCallback, callbackHandler)
                    delay(settle)
                    // One-shot capture of the same request: its result is
                    // guaranteed to reflect *this* zoom, not a stale one.
                    captureOneShot(session, req) ?: resultHolder.get()
                } catch (e: Exception) {
                    lost.set(true)
                    Log.w(TAG, "request for ${ratio}x failed; abandoning this resolution", e)
                    null
                }
                if (primary == null && lost.get()) break
                settle = PER_SAMPLE_SETTLE_MS
                lockAe = true

                val result = primary
                val frame = frameHolder.get()

                val reportedRatio = if (hasZoomRatio && result != null) ZoomRatioApi30.readResult(result) else null
                val reportedCrop = result?.get(CaptureResult.SCALER_CROP_REGION)?.toIntRect()
                val effectiveCrop = reportedCrop
                    ?: reportedRatio?.let { ZoomMath.centeredCropForRatio(activeRect, it) }
                    ?: requestedCrop
                val effRatioForHonor = reportedRatio ?: reportedCrop?.let { ZoomMath.ratioFromCrop(it, activeRect) }
                val sharpness = frame?.let {
                    ZoomMath.varianceOfLaplacian(it.y, it.width, it.height, it.rowStride, SHARPNESS_PATCH)
                } ?: 0.0
                val activePhysical = if (hasActivePhysicalId && result != null)
                    ActivePhysicalIdApi29.read(result) else null
                val focal = result?.get(CaptureResult.LENS_FOCAL_LENGTH)

                var positionRequestedNorm: RectNorm? = null
                var positionReportedNorm: RectNorm? = null
                var positionHonored: Boolean? = null

                // ---- off-centre crop-region probe (position honoured?): a
                //      one-shot capture so the primary repeating stream is
                //      untouched. Metadata only - no frame needed. ----
                if (ratio in positionProbeRatios && ratio > 1.02f && !lost.get()) {
                    try {
                        val offCrop = ZoomMath.offsetCropForRatio(activeRect, ratio, 0.6f, 0.6f)
                        val pb = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        pb.addTarget(reader.surface)
                        pb.set(CaptureRequest.CONTROL_AE_LOCK, true)
                        pb.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                        pb.set(CaptureRequest.SCALER_CROP_REGION, offCrop.toRect())
                        val pResult = captureOneShot(session, pb.build())
                        val pReported = pResult?.get(CaptureResult.SCALER_CROP_REGION)?.toIntRect()
                        positionRequestedNorm = ZoomMath.normalize(offCrop, activeRect)
                        positionReportedNorm = pReported?.let { ZoomMath.normalize(it, activeRect) }
                        positionHonored = ZoomMath.positionHonored(offCrop, pReported, tolPx = activeRect.width / 50)
                    } catch (e: Exception) {
                        Log.w(TAG, "position probe at ${ratio}x failed", e)
                    }
                }

                samples += ZoomSample(
                    requestedRatio = ratio,
                    reportedRatio = reportedRatio,
                    ratioHonored = ZoomMath.ratioHonored(ratio, effRatioForHonor),
                    requestedCropNorm = ZoomMath.normalize(requestedCrop, activeRect),
                    effectiveCropNorm = ZoomMath.normalize(effectiveCrop, activeRect),
                    positionRequestedNorm = positionRequestedNorm,
                    positionReportedNorm = positionReportedNorm,
                    positionHonored = positionHonored,
                    activePhysicalId = activePhysical,
                    lensFocalLengthMm = focal,
                    sharpness = sharpness,
                    sharpnessRelToBaseline = 1.0, // filled below
                )
                run.stepChecksTotal = ratios.size
                run.stepCheckIndex = samples.size
            }
        } finally {
            // Release order matters: stop new frames, block the listener, wait
            // for any in-flight callback to finish on the callback thread, THEN
            // free the reader - otherwise `close()` unmaps a native buffer a
            // callback is still reading and the process takes a SIGSEGV
            // (reproduced on the Pixel 6). See docs/QUIRKS.md.
            runCatching { session.stopRepeating() }
            synchronized(frameLock) { readerClosing = true }
            runCatching { reader.setOnImageAvailableListener(null, callbackHandler) }
            val drained = java.util.concurrent.CountDownLatch(1)
            callbackHandler.post { drained.countDown() }
            runCatching { drained.await(1, java.util.concurrent.TimeUnit.SECONDS) }
            runCatching { session.close() }
            runCatching { reader.close() }
        }

        val baseline = samples.firstOrNull { it.sharpness > 0.0 }?.sharpness ?: 0.0
        val withRel = if (baseline > 0.0) {
            samples.map { it.copy(sharpnessRelToBaseline = it.sharpness / baseline) }
        } else samples
        return ResolutionZoomMap(size.width, size.height, withRel)
    }

    private fun summariseCamera(
        identity: CameraDeviceIdentity,
        perResolution: Map<String, ResolutionZoomMap>,
        ratios: List<Float>,
    ): CameraCalibration {
        // Optical/digital split + quality collapse don't depend on output size,
        // so derive them from whichever resolution captured the most samples
        // (a resolution that bailed early would give a truncated ratio range).
        val ref = perResolution.values.maxByOrNull { it.samples.size }?.samples ?: emptyList()
        val split = ZoomMath.deriveOpticalDigitalSplit(
            ratios = ref.map { it.requestedRatio },
            activePhysicalIds = ref.map { it.activePhysicalId },
            focalLengths = ref.map { it.lensFocalLengthMm },
        )
        val collapse = ZoomMath.deriveQualityCollapse(
            ref.map { it.requestedRatio },
            ref.map { it.sharpnessRelToBaseline },
        )

        val allSamples = perResolution.values.flatMap { it.samples }
        val posProbes = allSamples.filter { it.positionHonored != null }
        val posHonored = posProbes.isNotEmpty() && posProbes.all { it.positionHonored == true }
        val posFailRatios = posProbes.filter { it.positionHonored == false }
            .map { it.requestedRatio }.distinct().sorted()

        val zoomChecksPassed = allSamples.count { it.ratioHonored && it.positionHonored != false }
        val steps = mapOf(
            "zoom-map" to CalibrationStep(checksTotal = allSamples.size, checksPassed = zoomChecksPassed),
            "crop-region" to CalibrationStep(
                checksTotal = posProbes.size,
                checksPassed = posProbes.count { it.positionHonored == true },
            ),
        )

        return CameraCalibration(
            deviceIdentity = identity,
            opticalRange = split.opticalRange,
            digitalRange = split.digitalRange,
            crossoverRatio = split.crossoverRatio,
            crossoverMethod = split.method,
            positionHonored = posHonored,
            positionFailRatios = posFailRatios,
            qualityCollapseRatio = collapse,
            perResolution = perResolution,
            steps = steps,
        )
    }

    private fun buildIdentity(
        cameraId: String,
        chars: CameraCharacteristics,
        activeRect: ZoomMath.IntRect,
        maxDigital: Float?,
        zoomRange: Range<Float>?,
    ): CameraDeviceIdentity {
        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val onP = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val isLogical = onP && LogicalCameraApi28.isLogicalMultiCam(chars)
        val physicalIds = if (onP) LogicalCameraApi28.physicalIds(chars) else emptyList()
        val croppingType = when (chars.get(CameraCharacteristics.SCALER_CROPPING_TYPE)) {
            CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY -> "CENTER_ONLY"
            CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM -> "FREEFORM"
            else -> "unknown"
        }
        return CameraDeviceIdentity(
            cameraId = cameraId,
            facing = facing,
            focalLengthsMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
            isLogicalMultiCam = isLogical,
            physicalIds = physicalIds,
            activeArrayWidth = activeRect.width,
            activeArrayHeight = activeRect.height,
            croppingType = croppingType,
            maxDigitalZoom = maxDigital,
            zoomRatioRange = zoomRange?.let { FloatRange2(it.lower, it.upper) },
        )
    }

    // ---- Camera2 plumbing ----

    /** Retries a camera open a few times - a just-released camera (from the
     *  motion pipeline, or the previous camera in this sweep) can briefly
     *  report IN_USE / MAX_CAMERAS on slower devices. */
    private suspend fun openCameraDeviceWithRetry(id: String, lost: AtomicBoolean): CameraDevice {
        var lastError: Exception? = null
        repeat(4) { attempt ->
            try {
                return openCameraDevice(id, lost)
            } catch (e: Exception) {
                lastError = e
                lost.set(false) // a failed open isn't a mid-probe loss
                Log.w(TAG, "camera $id open attempt ${attempt + 1} failed: ${e.message}")
                delay(1000L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("camera $id would not open")
    }

    /**
     * Opens [id]. [lost] is flipped true if the HAL later disconnects/errors
     * the device out from under us mid-probe (common on weak devices - this is
     * the old prototype's "session configures then fails async" quirk) so the
     * probe loop can bail gracefully instead of crashing on every subsequent
     * Camera2 call.
     */
    private suspend fun openCameraDevice(id: String, lost: AtomicBoolean): CameraDevice =
        suspendCancellableCoroutine { cont ->
            try {
                cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (cont.isActive) cont.resume(device)
                    }
                    override fun onDisconnected(device: CameraDevice) {
                        lost.set(true)
                        device.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("camera $id disconnected"))
                    }
                    override fun onError(device: CameraDevice, error: Int) {
                        lost.set(true)
                        device.close()
                        if (cont.isActive) cont.resumeWithException(RuntimeException("camera $id open error $error"))
                    }
                }, callbackHandler)
            } catch (e: CameraAccessException) {
                cont.resumeWithException(e)
            } catch (e: SecurityException) {
                cont.resumeWithException(e)
            }
        }

    /**
     * Submits [request] once and returns its `TotalCaptureResult` (or null on
     * failure / timeout). Used so a sample's readback is guaranteed to belong
     * to that sample's request, not whatever the repeating stream last landed.
     */
    private suspend fun captureOneShot(
        session: CameraCaptureSession,
        request: CaptureRequest,
        timeoutMs: Long = 1500,
    ): TotalCaptureResult? {
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
                    session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            s: CameraCaptureSession,
                            req: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            if (cont.isActive) cont.resume(result)
                        }
                        override fun onCaptureFailed(
                            s: CameraCaptureSession,
                            req: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure,
                        ) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }, callbackHandler)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun createSession(
        device: CameraDevice,
        surface: android.view.Surface,
        lost: AtomicBoolean,
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
                        lost.set(true)
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("session configure failed"))
                    }
                    override fun onClosed(session: CameraCaptureSession) {
                        lost.set(true)
                    }
                },
                callbackHandler,
            )
        } catch (e: CameraAccessException) {
            cont.resumeWithException(e)
        }
    }

    // ---- Snapshots / misc ----

    private fun lastCompletedSnapshot(): CalibrationStatus? {
        val result = store.load() ?: return null
        return CalibrationStatus(
            runId = result.runId,
            status = "completed",
            camerasCompleted = result.cameras.size,
            camerasTotal = result.cameras.size,
            stepsCompleted = 0,
            stepsTotal = 0,
            progressWithinStep = ProgressWithinStep(0, 0),
            startedAtMs = result.runAtMs,
        )
    }

    private fun randomHex(nBytes: Int): String {
        val buf = ByteArray(nBytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private class ActiveRun(
        val runId: String,
        val cameraIds: List<String>,
        val startedAtMs: Long,
    ) {
        @Volatile var job: Job? = null
        @Volatile var status: String = "running"
        @Volatile var currentCameraId: String? = null
        @Volatile var currentStep: String? = null
        @Volatile var camerasCompleted: Int = 0
        @Volatile var stepsCompleted: Int = 0
        @Volatile var stepsTotal: Int = 0
        @Volatile var stepChecksTotal: Int = 0
        @Volatile var stepCheckIndex: Int = 0

        val cameras = LinkedHashMap<String, CameraCalibration>()

        fun snapshot() = CalibrationStatus(
            runId = runId,
            status = status,
            currentCameraId = currentCameraId,
            camerasCompleted = camerasCompleted,
            camerasTotal = cameraIds.size,
            currentStep = currentStep,
            stepsCompleted = stepsCompleted,
            stepsTotal = stepsTotal,
            progressWithinStep = ProgressWithinStep(stepCheckIndex, stepChecksTotal),
            startedAtMs = startedAtMs,
        )
    }

    private class FrameHolder {
        class Frame(val y: ByteArray, val width: Int, val height: Int, val rowStride: Int)

        @Volatile private var frame: Frame? = null
        fun set(y: ByteArray, w: Int, h: Int, stride: Int) { frame = Frame(y, w, h, stride) }
        fun get(): Frame? = frame
    }

    private class ResultHolder {
        @Volatile private var result: TotalCaptureResult? = null
        fun set(r: TotalCaptureResult) { result = r }
        fun get(): TotalCaptureResult? = result
    }
}

private fun Rect.toIntRect() = ZoomMath.IntRect(left, top, right, bottom)
private fun ZoomMath.IntRect.toRect() = Rect(left, top, right, bottom)

/** [count] ratios from [lo] to [hi] with geometric spacing (dense at the low end). */
private fun geometricRatios(lo: Float, hi: Float, count: Int): List<Float> {
    if (count <= 1 || hi <= lo) return listOf(lo)
    val out = ArrayList<Float>(count)
    for (i in 0 until count) {
        val t = i.toFloat() / (count - 1)
        out += lo * (hi / lo).toDouble().pow(t.toDouble()).toFloat()
    }
    return out
}
