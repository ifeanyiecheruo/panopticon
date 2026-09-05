package com.panopticon.phoneapp.calibration

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Range
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

private const val TAG = "CalibrationRunner"

/** Milliseconds paused between individual probe points - see class doc. */
private const val CHECK_PACING_MS = 120L
private const val ZOOM_QUALITY_SAMPLES = 15

/**
 * Owns the single device-wide calibration sweep at a time. A sweep walks
 * **every** camera `CameraManager` reports (per phone-http-api.md - the whole
 * point of calibration is catching a device lying about its Camera2
 * capabilities, and that risk is per-camera, not just the default one),
 * running a fixed ordered set of steps per camera and reporting camera + step
 * + within-step progress the whole way.
 *
 * Deliberate simplification for this slice (documented, not accidental): each
 * check records the camera's **declared** `CameraCharacteristics` value as
 * both `declared` and `measured` and reports `ok = true`. Wiring the
 * empirical half - open a `CameraCaptureSession`, apply the control, read the
 * effective value back from the `CaptureResult`, and flag the mismatch - is
 * deferred; it needs iteration against real hardware (the old prototype's
 * `QUIRKS.md` has hard-won `SCALER_CROP_REGION`/digital-zoom findings to
 * re-verify against, per HANDOFF-implementation.md). The run/step/progress
 * state machine, persistence, cancellation, and the whole wire contract are
 * real now, so that deepening is a drop-in.
 *
 * Calibration does NOT tear down the recording pipeline: reading
 * `CameraCharacteristics` needs no exclusive camera access, so RECORD keeps
 * running through a sweep. (If/when the empirical half lands it will need to
 * coordinate with the pipeline the way LIVE mode does.)
 */
class CalibrationRunner(
    context: Context,
    private val store: CalibrationStore,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    @Volatile
    private var active: ActiveRun? = null

    /** runId of the last run that reached "completed" (the result itself lives on disk). */
    @Volatile
    private var lastCompletedRunId: String? = store.load()?.runId

    // ---- Public API (called from CalibrationRoutes) ----

    sealed interface StartOutcome {
        data class Started(val response: CalibrationStartResponse) : StartOutcome
        data class AlreadyRunning(val runId: String) : StartOutcome
        data class NoCameras(val message: String) : StartOutcome
    }

    fun start(): StartOutcome {
        synchronized(lock) {
            active?.takeIf { it.status == "running" }?.let { return StartOutcome.AlreadyRunning(it.runId) }

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

    /** null -> respond 404. `runId == null` means "the current or last run". */
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
            run.status = "cancelled" // set before cancel() so sweep()'s catch knows it was deliberate
            run.job?.cancel()
            return true
        }
    }

    sealed interface ResultOutcome {
        data class Completed(val result: CalibrationResult) : ResultOutcome
        object NotCompleted : ResultOutcome // -> 409
        object Unknown : ResultOutcome // -> 404
    }

    fun result(runId: String?): ResultOutcome {
        val persisted = store.load()
        if (runId == null) {
            return persisted?.let { ResultOutcome.Completed(it) } ?: ResultOutcome.Unknown
        }
        if (persisted != null && persisted.runId == runId) return ResultOutcome.Completed(persisted)
        val run = active
        if (run != null && run.runId == runId) return ResultOutcome.NotCompleted
        return ResultOutcome.Unknown
    }

    // ---- The sweep ----

    private suspend fun sweep(run: ActiveRun) {
        try {
            for ((camIndex, cameraId) in run.cameraIds.withIndex()) {
                run.currentCameraId = cameraId
                run.camerasCompleted = camIndex
                run.stepsCompleted = 0

                val chars = try {
                    cameraManager.getCameraCharacteristics(cameraId)
                } catch (e: Exception) {
                    Log.w(TAG, "characteristics for camera $cameraId failed; recording error checks", e)
                    null
                }

                val steps = linkedMapOf<String, CalibrationStep>()
                val stepIds = CalibrationStepId.values()
                for ((stepIndex, stepId) in stepIds.withIndex()) {
                    run.currentStep = stepId.wire
                    run.stepsCompleted = stepIndex
                    // delay() inside runStep() throws CancellationException if
                    // cancel() fired - that propagates out to the catch below,
                    // leaving cameras finished so far with their partial results.
                    steps[stepId.wire] = runStep(run, stepId, chars)
                    run.stepsCompleted = stepIndex + 1
                }

                run.cameras[cameraId] = CameraCalibration(
                    deviceIdentity = deviceIdentity(cameraId, chars),
                    steps = steps,
                )
                run.camerasCompleted = camIndex + 1
            }

            synchronized(lock) {
                val result = CalibrationResult(
                    runId = run.runId,
                    runAtMs = System.currentTimeMillis(),
                    cameras = run.cameras.toMap(),
                )
                store.save(result)
                run.status = "completed"
                lastCompletedRunId = run.runId
                Log.i(TAG, "calibration ${run.runId} completed (${result.cameras.size} cameras)")
            }
        } catch (e: Exception) {
            // cancel() sets status = "cancelled" then cancels the job, which
            // surfaces here as a CancellationException - expected, not an error.
            if (run.status == "cancelled") {
                Log.i(TAG, "calibration ${run.runId} cancelled; ${run.cameras.size} camera(s) kept partial results")
            } else {
                run.status = "error"
                Log.e(TAG, "calibration ${run.runId} failed mid-sweep", e)
            }
        }
    }

    private suspend fun runStep(
        run: ActiveRun,
        stepId: CalibrationStepId,
        chars: CameraCharacteristics?,
    ): CalibrationStep {
        val checks = when (stepId) {
            CalibrationStepId.CROP_REGION -> cropRegionChecks(chars)
            CalibrationStepId.ZOOM_QUALITY -> zoomQualityChecks(chars)
        }
        run.stepChecksTotal = checks.size
        run.stepCheckIndex = 0
        val done = ArrayList<CalibrationCheck>(checks.size)
        for ((i, check) in checks.withIndex()) {
            delay(CHECK_PACING_MS)
            run.stepCheckIndex = i + 1
            done += check
        }
        return CalibrationStep(
            checksTotal = checks.size,
            checksPassed = done.count { it.ok },
            checks = done,
        )
    }

    // ---- Per-step check builders (declared-characteristics snapshot) ----

    private fun cropRegionChecks(chars: CameraCharacteristics?): List<CalibrationCheck> {
        if (chars == null) return listOf(errCheck("characteristics-unavailable"))
        val out = mutableListOf<CalibrationCheck>()

        val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        out += declaredCheck("SCALER_AVAILABLE_MAX_DIGITAL_ZOOM", maxDigitalZoom?.toString() ?: "unset")

        val zoomRange = zoomRatioRange(chars)
        out += declaredCheck(
            "CONTROL_ZOOM_RATIO_RANGE",
            zoomRange?.let { "${it.lower}..${it.upper}" } ?: "n/a (<API 30)",
        )

        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        out += declaredCheck("SENSOR_INFO_ACTIVE_ARRAY_SIZE", activeArray?.toShortString() ?: "unset") // Rect member

        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        out += declaredCheck("SENSOR_INFO_PIXEL_ARRAY_SIZE", pixelArray?.toString() ?: "unset")

        out += declaredCheck(
            "SCALER_CROPPING_TYPE",
            when (chars.get(CameraCharacteristics.SCALER_CROPPING_TYPE)) {
                CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY -> "CENTER_ONLY"
                CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM -> "FREEFORM"
                else -> "unset"
            },
        )

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        out += declaredCheck("REQUEST_AVAILABLE_CAPABILITIES", "${caps?.size ?: 0} capabilities")

        return out
    }

    private fun zoomQualityChecks(chars: CameraCharacteristics?): List<CalibrationCheck> {
        if (chars == null) return listOf(errCheck("characteristics-unavailable"))

        val declaredMax = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoomRange = zoomRatioRange(chars)
        val lo = zoomRange?.lower ?: 1f
        val hi = zoomRange?.upper ?: declaredMax.coerceAtLeast(1f)

        return (0 until ZOOM_QUALITY_SAMPLES).map { i ->
            val t = i.toFloat() / (ZOOM_QUALITY_SAMPLES - 1)
            val ratio = lo + t * (hi - lo)
            val inRange = ratio in lo..hi
            CalibrationCheck(
                name = "zoom-ratio %.2fx".format(ratio),
                declared = "supported in %.2f..%.2f".format(lo, hi),
                measured = if (inRange) "accepted" else "out of declared range", // == declared for this slice
                ok = inRange,
            )
        }
    }

    private fun zoomRatioRange(chars: CameraCharacteristics): Range<Float>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }

    private fun deviceIdentity(cameraId: String, chars: CameraCharacteristics?): CameraDeviceIdentity {
        val facing = when (chars?.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val focal = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        return CameraDeviceIdentity(
            cameraId = cameraId,
            facing = facing,
            focalLengthMm = focal,
            sensorActiveArray = activeArray?.toShortString(), // android.graphics.Rect member: "[l,t][r,b]"
        )
    }

    private fun declaredCheck(name: String, declared: String) =
        CalibrationCheck(name = name, declared = declared, measured = declared, ok = true)

    private fun errCheck(name: String) =
        CalibrationCheck(name = name, declared = "-", measured = "characteristics unavailable", ok = false)

    // ---- Snapshots ----

    private fun lastCompletedSnapshot(): CalibrationStatus? {
        val result = store.load() ?: return null
        val stepsTotal = CalibrationStepId.values().size
        return CalibrationStatus(
            runId = result.runId,
            status = "completed",
            currentCameraId = null,
            camerasCompleted = result.cameras.size,
            camerasTotal = result.cameras.size,
            currentStep = null,
            stepsCompleted = stepsTotal,
            stepsTotal = stepsTotal,
            progressWithinStep = ProgressWithinStep(0, 0),
            startedAtMs = result.runAtMs,
        )
    }

    private fun randomHex(nBytes: Int): String {
        val buf = ByteArray(nBytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    /**
     * Mutable per-run state. Progress fields are `@Volatile` and only ever
     * advanced by the single sweep coroutine, then read (never written) by the
     * status endpoint - no lock needed for those reads.
     */
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
        @Volatile var stepChecksTotal: Int = 0
        @Volatile var stepCheckIndex: Int = 0

        val cameras = linkedMapOf<String, CameraCalibration>()

        fun snapshot() = CalibrationStatus(
            runId = runId,
            status = status,
            currentCameraId = currentCameraId,
            camerasCompleted = camerasCompleted,
            camerasTotal = cameraIds.size,
            currentStep = currentStep,
            stepsCompleted = stepsCompleted,
            stepsTotal = CalibrationStepId.values().size,
            progressWithinStep = ProgressWithinStep(stepCheckIndex, stepChecksTotal),
            startedAtMs = startedAtMs,
        )
    }
}
