package com.panopticon.phoneapp.calibration

import kotlinx.serialization.Serializable

/**
 * Wire types for the device-wide calibration API (the `/api/calibration`
 * routes in docs/design/http-api.md). Field names match the doc's
 * example bodies exactly so the controller can decode them without a mapping
 * layer.
 *
 * The sweep is a **real empirical probe** now: for every camera, at every
 * `StreamConfigurationMap` output size, it applies a range of zoom
 * requests (via `CONTROL_ZOOM_RATIO` on API 30+ and `SCALER_CROP_REGION` on
 * every API) and records what the HAL actually did - the effective crop rect,
 * whether the requested ratio/position was honored, which physical camera was
 * active (optical vs. digital zoom), and a frame-sharpness score. See
 * `CalibrationRunner` + `ZoomMath`.
 */

@Serializable
data class CalibrationStartResponse(
    val runId: String,
    val status: String,
    val startedAtMs: Long,
    val cameraIds: List<String>,
    /** The partial run this one picks up from (see [resumableFrom]); null for a fresh sweep. */
    val resumedFromRunId: String? = null,
)

@Serializable
data class ProgressWithinStep(val index: Int, val total: Int)

/** Poll target for `GET /api/calibration/status`. */
@Serializable
data class CalibrationStatus(
    val runId: String,
    val status: String, // running | completed | partial | error
    val currentCameraId: String? = null,
    val camerasCompleted: Int,
    val camerasTotal: Int,
    val currentStep: String? = null, // the resolution being swept, e.g. "1920x1080"
    val stepsCompleted: Int,
    val stepsTotal: Int,
    val progressWithinStep: ProgressWithinStep,
    val startedAtMs: Long,
)

@Serializable
data class CalibrationCancelled(val cancelled: Boolean)

@Serializable
data class CalibrationErased(val erased: Boolean)

// ---- Result ----

@Serializable
data class RectNorm(val l: Float, val t: Float, val r: Float, val b: Float)

@Serializable
data class FloatRange2(val lo: Float, val hi: Float)

/** One zoom request and what the HAL actually did with it, at one resolution. */
@Serializable
data class ZoomSample(
    val requestedRatio: Float,
    val reportedRatio: Float? = null,
    val ratioHonored: Boolean,
    /** Requested crop as a fraction of the sensor active array. */
    val requestedCropNorm: RectNorm,
    /** What the HAL reported back (SCALER_CROP_REGION), normalised. This is the
     *  "effective viewport" for this zoom request. */
    val effectiveCropNorm: RectNorm,
    /** Off-centre probe: did an intentionally off-centre crop keep its offset? */
    val positionRequestedNorm: RectNorm? = null,
    val positionReportedNorm: RectNorm? = null,
    /** Did the reported SCALER_CROP_REGION *metadata* echo the off-centre request? */
    val positionMetadataMatch: Boolean? = null,
    /** Did the *pixels* actually shift vs. the centred frame at this zoom? (the truth) */
    val positionFrameShifted: Boolean? = null,
    /** = positionFrameShifted: whether the crop position was really honoured. */
    val positionHonored: Boolean? = null,
    /** Which physical camera answered (logical multi-cam only, API 29+). */
    val activePhysicalId: String? = null,
    val lensFocalLengthMm: Float? = null,
    /** Variance of Laplacian over a centred Y-plane patch. */
    val sharpness: Double = 0.0,
    /** sharpness / (sharpness at ratio 1.0). < ~0.6 => visible quality collapse. */
    val sharpnessRelToBaseline: Double = 1.0,
)

@Serializable
data class ResolutionZoomMap(
    val width: Int,
    val height: Int,
    val samples: List<ZoomSample>,
)

@Serializable
data class CameraDeviceIdentity(
    val cameraId: String,
    val facing: String,
    val focalLengthsMm: List<Float> = emptyList(),
    val isLogicalMultiCam: Boolean = false,
    val physicalIds: List<String> = emptyList(),
    val activeArrayWidth: Int = 0,
    val activeArrayHeight: Int = 0,
    val croppingType: String = "unknown",
    val maxDigitalZoom: Float? = null,
    val zoomRatioRange: FloatRange2? = null,
)

/** Cheap summary of one step's outcome - keeps the controller's "N/M checks" readout working. */
@Serializable
data class CalibrationStep(
    val checksTotal: Int,
    val checksPassed: Int,
)

@Serializable
data class CameraCalibration(
    val deviceIdentity: CameraDeviceIdentity,
    /** Zoom ratios served by an actual optical element / native FOV. `[1,1]` if
     *  this camera has a single physical sensor. */
    val opticalRange: FloatRange2,
    /** Zoom ratios served by cropping (digital zoom). */
    val digitalRange: FloatRange2,
    val crossoverRatio: Float? = null,
    val crossoverMethod: String = "none", // active-physical-id | focal-length | single-camera | none
    val positionHonored: Boolean = false,
    val positionFailRatios: List<Float> = emptyList(),
    /** Ratios where the metadata echoed the off-centre request but the pixels
     *  did NOT shift - i.e. the device reported a crop position it didn't apply
     *  (the old prototype's "the device lies about it"). */
    val positionMetadataLiedRatios: List<Float> = emptyList(),
    /** First requested ratio at which sharpness fell below ~60% of the ratio-1.0 baseline. */
    val qualityCollapseRatio: Float? = null,
    val perResolution: Map<String, ResolutionZoomMap> = emptyMap(),
    val steps: Map<String, CalibrationStep> = emptyMap(),
    /** The run that completed this camera, and when. Cameras in one result can come from
     *  different runs (see [withCameraPromoted]); null on results persisted before that, where
     *  the result's own `runId` / `runAtMs` apply. */
    val runId: String? = null,
    val runAtMs: Long? = null,
)

@Serializable
data class ResultDeviceIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val appVersionName: String,
)

/**
 * Body of `GET /api/calibration/result`. Persisted to disk by
 * [CalibrationStore] so a phone that has calibrated once can serve this on
 * every request - including after an app restart - without re-running the
 * sweep, which is what lets the controller ingest it opportunistically right
 * after pairing (see docs/design/decisions/0009-calibration-model.md).
 */
@Serializable
data class CalibrationResult(
    val runId: String,
    val runAtMs: Long,
    val deviceIdentity: ResultDeviceIdentity,
    val cameras: Map<String, CameraCalibration>,
    /**
     * False for an in-progress sweep (see [CalibrationStore]): it is persisted after every
     * (camera, resolution) step, so a cancel, a thermal stop or a crash leaves exactly the steps
     * that finished, and anything not yet measured is simply absent. True for the completed
     * result, which holds only cameras whose sweep finished. Defaults true because a result
     * persisted before this field existed was only ever written on completion.
     */
    val complete: Boolean = true,
)

/** What `GET /api/calibration/status` calls a persisted result once no sweep is running. */
fun CalibrationResult.runStatus(): String = if (complete) "completed" else "partial"

/**
 * [completed] with [camera] as [cameraId]'s calibration, stamped with the run that finished it,
 * or null when [completed] already holds exactly that data (a resumed run re-finishing a camera
 * it had promoted before being stopped). Every other camera keeps the run that completed it.
 */
fun withCameraPromoted(
    completed: CalibrationResult?,
    runId: String,
    runAtMs: Long,
    deviceIdentity: ResultDeviceIdentity,
    cameraId: String,
    camera: CameraCalibration,
): CalibrationResult? {
    val existing = completed?.cameras?.get(cameraId)
    if (existing != null && existing.perResolution == camera.perResolution) return null
    // Cameras written before per-camera stamps existed carry the result's own.
    val stamped = completed?.cameras.orEmpty().mapValues { (_, c) ->
        if (c.runId != null) c else c.copy(runId = completed?.runId, runAtMs = completed?.runAtMs)
    }
    return CalibrationResult(
        runId = runId,
        runAtMs = runAtMs,
        deviceIdentity = deviceIdentity,
        cameras = stamped + (cameraId to camera.copy(runId = runId, runAtMs = runAtMs)),
        complete = true,
    )
}

/**
 * The calibration the camera pipelines zoom by: each camera's completed calibration, and for a
 * camera that has never completed one, whatever steps the [inProgress] sweep has finished for it -
 * partial data still gives those resolutions real hardware zoom. A camera with completed data
 * keeps using it until its new sweep finishes.
 */
fun zoomView(completed: CalibrationResult?, inProgress: CalibrationResult?): CalibrationResult? {
    if (inProgress == null) return completed
    if (completed == null) return inProgress
    return completed.copy(cameras = inProgress.cameras + completed.cameras)
}

/**
 * The persisted result a new sweep picks up from, skipping every step it already holds, or null
 * to start fresh. Only a partial result written by this same app version resumes: starting over a
 * complete one is a deliberate re-run, and samples from another version's sweep may not be
 * comparable with this one's.
 */
fun resumableFrom(persisted: CalibrationResult?, appVersionName: String): CalibrationResult? =
    persisted?.takeIf { !it.complete && it.deviceIdentity.appVersionName == appVersionName }
