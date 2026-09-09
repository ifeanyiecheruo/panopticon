package com.panopticon.phoneapp.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import com.panopticon.phoneapp.calibration.LogicalCameraApi28
import com.panopticon.phoneapp.calibration.ZoomRatioApi30

/**
 * Reads one camera's declared control ranges into [CameraCapabilities] for
 * `GET /api/camera/capabilities`. A pure characteristics read - no camera open,
 * works in any mode.
 *
 * `cameraId` may be a plain logical/physical id, or a `"<logical>:<physical>"`
 * pair (a logical multi-camera constrained to one of its physical sub-cameras -
 * see [CameraCatalog]); the physical camera's own characteristics are read in
 * that case.
 *
 * API-gated keys (`CONTROL_ZOOM_RATIO_RANGE` API 30, `physicalCameraIds` API 28)
 * are funnelled through the `@RequiresApi` objects in `calibration/ZoomApiCompat.kt`
 * so the ART verifier never touches the field on an older device (the
 * `NoSuchFieldError`-behind-a-guard trap - see docs/quirks/calibration-zoom.md).
 */
object CameraCapabilitiesReader {

    /** Split "0:2" -> ("0", "2"); a plain id -> (id, null). */
    fun splitTarget(cameraId: String): Pair<String, String?> {
        val i = cameraId.indexOf(':')
        return if (i < 0) cameraId to null else cameraId.substring(0, i) to cameraId.substring(i + 1)
    }

    fun read(context: Context, cameraId: String): CameraCapabilities {
        val cameraManager =
            context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val (logicalId, physicalId) = splitTarget(cameraId)
        // For a "<logical>:<physical>" target, read the physical sensor's own ranges.
        val charsId = physicalId ?: logicalId
        val chars = cameraManager.getCameraCharacteristics(charsId)
        return read(cameraId, chars)
    }

    fun read(cameraId: String, chars: CameraCharacteristics): CameraCapabilities {
        val hasZoomRatioApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val zoomRange = if (hasZoomRatioApi) ZoomRatioApi30.ratioRange(chars) else null
        val maxDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = FloatRange2(
            lo = zoomRange?.lower ?: 1f,
            hi = (zoomRange?.upper ?: maxDigital).coerceAtLeast(1.0001f),
        )

        val aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeCompStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val aeCompStepMilliEv =
            if (aeCompStep != null && aeCompStep.denominator != 0) {
                (aeCompStep.numerator * 1000.0 / aeCompStep.denominator).toInt()
            } else 0

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val hasManualSensor = caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        )
        val hasManualPostProcessing = caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
        )

        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val minFocusDistance =
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val hasManualFocus = minFocusDistance > 0f &&
            afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_OFF)

        // Deduped: the old prototype's quirk (carried forward) is that some HALs list duplicate
        // stabilization modes; harmless once distinct'd.
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.toList()?.distinct() ?: emptyList()
        val vidStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toList()?.distinct() ?: emptyList()
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.toList()?.distinct() ?: emptyList()

        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !cameraId.contains(':') && LogicalCameraApi28.isLogicalMultiCam(chars)
        ) {
            LogicalCameraApi28.physicalIds(chars)
        } else emptyList()

        val croppingType = when (chars.get(CameraCharacteristics.SCALER_CROPPING_TYPE)) {
            CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY -> "CENTER_ONLY"
            CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM -> "FREEFORM"
            else -> "unknown"
        }
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0

        // Selectable record/broadcast sizes: every ~16:9 output size this camera
        // supports from 480p up to 4K, largest first. Both the recordable-video
        // list and the SurfaceTexture (preview) list are unioned - some HALs
        // populate one but not the other. A very large pick may be down-scaled
        // by the live encoder, but recording honours it.
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = buildList {
            streamMap?.getOutputSizes(MediaRecorder::class.java)?.let { addAll(it) }
            streamMap?.getOutputSizes(SurfaceTexture::class.java)?.let { addAll(it) }
        }
        val outputResolutions = sizes
            .filter {
                it.width in 640..3840 &&
                    kotlin.math.abs(it.width.toDouble() / it.height - 16.0 / 9.0) < 0.06
            }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height }
            .map { "${it.width}x${it.height}" }

        return CameraCapabilities(
            cameraId = cameraId,
            zoomRatioRange = zoom,
            zoomViaRatioApi = hasZoomRatioApi && zoomRange != null,
            aeCompensationRange = IntRange2(aeCompRange?.lower ?: 0, aeCompRange?.upper ?: 0),
            aeCompensationStepMilliEv = aeCompStepMilliEv,
            exposureTimeRangeNs = expRange?.let { LongRange2(it.lower, it.upper) },
            sensitivityRange = isoRange?.let { IntRange2(it.lower, it.upper) },
            minFocusDistanceDiopters = minFocusDistance,
            hasManualSensor = hasManualSensor,
            hasManualFocus = hasManualFocus,
            hasManualWhiteBalance = hasManualPostProcessing,
            awbModes = awbModes,
            videoStabilizationModes = vidStabModes,
            opticalStabilizationModes = oisModes,
            maxAeRegions = maxAeRegions,
            maxAfRegions = maxAfRegions,
            outputResolutions = outputResolutions,
            physicalCameraIds = physicalIds,
            croppingType = croppingType,
            activeArrayWidth = active?.width() ?: 0,
            activeArrayHeight = active?.height() ?: 0,
        )
    }
}
