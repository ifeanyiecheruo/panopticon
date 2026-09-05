package com.panopticon.phoneapp.calibration

import kotlinx.serialization.Serializable

/**
 * Wire types for the device-wide calibration API (the `/api/calibration`
 * routes in docs/implementation/phone-http-api.md). Field names match the doc's
 * example bodies exactly so the controller can decode them without a mapping
 * layer.
 *
 * Scope note (a deliberate simplification, in the same spirit as
 * `CameraPipeline`'s "no real motion detection" and `ModeRoutes`' "live is a
 * stub"): a calibration sweep here reads each camera's **declared**
 * `CameraCharacteristics` and records them as the reference values, pacing
 * itself through cameras x steps x checks so the progress API is exercised
 * for real. The empirical half - open a capture session, apply each control,
 * read the value back from the `CaptureResult`, and flag where the HAL lied -
 * is the deferred deepening (needs real-hardware iteration, see
 * `CalibrationRunner`). `measured` currently mirrors `declared` and every
 * check reports `ok = true`; the shapes are settled so that pass is drop-in.
 */

@Serializable
data class CalibrationStartResponse(
    val runId: String,
    val status: String,
    val startedAtMs: Long,
    val cameraIds: List<String>,
)

@Serializable
data class ProgressWithinStep(val index: Int, val total: Int)

/** Poll target for `GET /api/calibration/status`. */
@Serializable
data class CalibrationStatus(
    val runId: String,
    val status: String, // running | completed | cancelled | error
    val currentCameraId: String? = null,
    val camerasCompleted: Int,
    val camerasTotal: Int,
    val currentStep: String? = null,
    val stepsCompleted: Int,
    val stepsTotal: Int,
    val progressWithinStep: ProgressWithinStep,
    val startedAtMs: Long,
)

@Serializable
data class CalibrationCancelled(val cancelled: Boolean)

/** One measured-vs-declared probe point within a step. */
@Serializable
data class CalibrationCheck(
    val name: String,
    val declared: String,
    val measured: String,
    val ok: Boolean,
)

@Serializable
data class CalibrationStep(
    val checksTotal: Int,
    val checksPassed: Int,
    val checks: List<CalibrationCheck>,
)

@Serializable
data class CameraDeviceIdentity(
    val cameraId: String,
    val facing: String,
    val focalLengthMm: Float? = null,
    val sensorActiveArray: String? = null,
)

@Serializable
data class CameraCalibration(
    val deviceIdentity: CameraDeviceIdentity,
    val steps: Map<String, CalibrationStep>,
)

/**
 * Body of `GET /api/calibration/result`. Persisted to disk by
 * [CalibrationStore] so a phone that has calibrated once can serve this on
 * every request - including after an app restart - without re-running the
 * sweep, which is what lets the controller ingest it opportunistically right
 * after pairing (see HANDOFF-controller-ux.md "Calibration data model").
 */
@Serializable
data class CalibrationResult(
    val runId: String,
    val runAtMs: Long,
    val cameras: Map<String, CameraCalibration>,
)

/** The two steps every camera is swept through, in order. */
enum class CalibrationStepId(val wire: String) {
    CROP_REGION("crop-region"),
    ZOOM_QUALITY("zoom-quality"),
}
