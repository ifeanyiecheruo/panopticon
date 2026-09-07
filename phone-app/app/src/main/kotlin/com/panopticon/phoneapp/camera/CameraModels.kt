package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import kotlinx.serialization.Serializable

/**
 * Wire types for the camera-selection + manual-control API
 * (`/api/cameras`, `/api/cameras/active`, `/api/camera/capabilities`,
 * `/api/camera/state` in docs/implementation/phone-http-api.md).
 *
 * `RectNorm` is reused from the calibration models on purpose - the controller
 * already decodes that exact `{l,t,r,b}` shape for `effectiveCropNorm`, and a
 * zoom-rect the user draws over the live preview is the same kind of value.
 *
 * Everything here is framework-free and unit-tested; the Android shims
 * ([CameraCatalog], [CameraCapabilitiesReader], [CameraControlApply]) convert
 * to/from Camera2 types at the edges.
 */

@Serializable
data class IntRange2(val lo: Int, val hi: Int)

@Serializable
data class LongRange2(val lo: Long, val hi: Long)

@Serializable
data class FloatRange2(val lo: Float, val hi: Float)

// ---- /api/cameras ----

@Serializable
data class CameraInfo(
    val cameraId: String,
    val facing: String, // back | front | external | unknown
    val label: String,
    val focalLengthMm: Float? = null,
    val isActive: Boolean = false,
)

@Serializable
data class CamerasResponse(val cameras: List<CameraInfo>)

@Serializable
data class ActiveCameraRequest(val cameraId: String)

@Serializable
data class ActiveCameraResponse(val activeCameraId: String)

// ---- /api/camera/capabilities ----

/**
 * Declared per-key ranges for one physical camera, read straight from
 * `CameraCharacteristics`. This is what the controller's adjuster UI bounds its
 * sliders to and what [CameraControlValidation] checks a requested state
 * against.
 */
@Serializable
data class CameraCapabilities(
    val cameraId: String,
    val zoomRatioRange: FloatRange2,
    /** true when `CONTROL_ZOOM_RATIO` (API 30+) drives zoom; false = legacy `SCALER_CROP_REGION`. */
    val zoomViaRatioApi: Boolean,
    val aeCompensationRange: IntRange2,
    /** AE-compensation step in milli-EV (the `Rational` step * 1000), so it stays an int on the wire. */
    val aeCompensationStepMilliEv: Int,
    val exposureTimeRangeNs: LongRange2? = null,
    val sensitivityRange: IntRange2? = null,
    /** 0 when the lens reports no usable minimum focus distance (fixed focus / unknown). */
    val minFocusDistanceDiopters: Float = 0f,
    val hasManualSensor: Boolean = false,
    val hasManualFocus: Boolean = false,
    /** `REQUEST_AVAILABLE_CAPABILITIES` ∋ `MANUAL_POST_PROCESSING` - enables `AWB_MODE_OFF` + RGGB gains. */
    val hasManualWhiteBalance: Boolean = false,
    /** Nominal per-channel RGGB gain range for manual white balance. Camera2 declares no range for
     *  `COLOR_CORRECTION_GAINS`; this is a fixed sane band the UI bounds its sliders to. */
    val wbGainRange: FloatRange2 = FloatRange2(1.0f, 8.0f),
    /** `CONTROL_AWB_AVAILABLE_MODES` (deduped). 1 == `CONTROL_AWB_MODE_AUTO`; 0 == OFF (manual gains). */
    val awbModes: List<Int> = emptyList(),
    /** `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` (deduped - some HALs list duplicates). 0 == OFF, 1 == ON. */
    val videoStabilizationModes: List<Int> = emptyList(),
    /** `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` (deduped). 0 == OFF, 1 == ON. Empty == no OIS. */
    val opticalStabilizationModes: List<Int> = emptyList(),
    /** Physical sub-camera ids of a logical multi-camera (`chars.physicalCameraIds`), API 28+.
     *  Present only on the logical camera's own capabilities; a `"<logical>:<physical>"` entry
     *  carries the physical camera's ranges directly. */
    val physicalCameraIds: List<String> = emptyList(),
    val croppingType: String = "unknown", // CENTER_ONLY | FREEFORM | unknown
    val activeArrayWidth: Int = 0,
    val activeArrayHeight: Int = 0,
)

// ---- /api/camera/state ----

/**
 * The concrete manual-control key set. A null field means "leave this control
 * on auto / HAL default". `zoomRatio` and `cropRegionNorm` are mutually
 * exclusive at apply time (see [CameraControlApply]) - `cropRegionNorm` wins
 * when both are set.
 */
@Serializable
data class CameraControlKeys(
    val zoomRatio: Float? = null,
    /** Off-centre zoom rect as a fraction of the sensor active array. */
    val cropRegionNorm: RectNorm? = null,
    val aeExposureCompensation: Int? = null,
    /** AE metering freeze - not a manual-exposure dial (see docs/QUIRKS.md). */
    val aeLock: Boolean? = null,
    val manualExposure: Boolean? = null,
    val sensorExposureTimeNs: Long? = null,
    val sensorSensitivityIso: Int? = null,
    val manualFocus: Boolean? = null,
    val lensFocusDistanceDiopters: Float? = null,
    /** `CONTROL_AWB_MODE` (auto / a preset like incandescent / daylight / …). See [CameraCapabilities.awbModes].
     *  Mutually exclusive with [manualWhiteBalance] at apply time - manual gains win. */
    val awbMode: Int? = null,
    /** `AWB_MODE_OFF` + `COLOR_CORRECTION_GAINS` from [wbRedGain]/[wbGreenGain]/[wbBlueGain].
     *  Gated on [CameraCapabilities.hasManualWhiteBalance]. */
    val manualWhiteBalance: Boolean? = null,
    val wbRedGain: Float? = null,
    val wbGreenGain: Float? = null,
    val wbBlueGain: Float? = null,
    /** `CONTROL_VIDEO_STABILIZATION_MODE` (0 off, 1 on). See [CameraCapabilities.videoStabilizationModes]. */
    val videoStabilizationMode: Int? = null,
    /** `LENS_OPTICAL_STABILIZATION_MODE` (0 off, 1 on). See [CameraCapabilities.opticalStabilizationModes]. */
    val opticalStabilizationMode: Int? = null,
)

/**
 * Persisted (in `DeviceConfig`) manual-control intent. `manualControlEnabled`
 * is the master switch: false = every pipeline runs full auto regardless of
 * [keys]. Kept whole across mode switches and app restarts so a state tuned
 * while watching the live preview also governs recording.
 */
@Serializable
data class CameraControlSpec(
    val manualControlEnabled: Boolean = false,
    val keys: CameraControlKeys = CameraControlKeys(),
) {
    /** Apply a POST /api/camera/state patch: `keys` (when present) replaces the set wholesale. */
    fun withPatch(patch: CameraStatePatch): CameraControlSpec = copy(
        manualControlEnabled = patch.manualControlEnabled ?: manualControlEnabled,
        keys = patch.keys ?: keys,
    )
}

@Serializable
data class CameraStateResponse(
    val cameraId: String,
    val rotationDegrees: Int,
    val manualControlEnabled: Boolean,
    val keys: CameraControlKeys,
)

@Serializable
data class CameraStatePatch(
    val manualControlEnabled: Boolean? = null,
    val keys: CameraControlKeys? = null,
)
