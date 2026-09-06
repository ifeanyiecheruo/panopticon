package com.panopticon.phoneapp.calibration

import kotlinx.serialization.Serializable

/**
 * Wire types for the device-wide calibration API (the `/api/calibration`
 * routes in docs/implementation/phone-http-api.md). Field names match the doc's
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
    val currentStep: String? = null, // the resolution being swept, e.g. "1920x1080"
    val stepsCompleted: Int,
    val stepsTotal: Int,
    val progressWithinStep: ProgressWithinStep,
    val startedAtMs: Long,
)

@Serializable
data class CalibrationCancelled(val cancelled: Boolean)

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
 * after pairing (see HANDOFF-controller-ux.md "Calibration data model").
 */
@Serializable
data class CalibrationResult(
    val runId: String,
    val runAtMs: Long,
    val deviceIdentity: ResultDeviceIdentity,
    val cameras: Map<String, CameraCalibration>,
)
