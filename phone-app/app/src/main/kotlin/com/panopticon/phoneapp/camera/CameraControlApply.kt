package com.panopticon.phoneapp.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import com.panopticon.phoneapp.calibration.RectNorm
import com.panopticon.phoneapp.calibration.ZoomMath
import com.panopticon.phoneapp.calibration.ZoomRatioApi30
import kotlin.math.roundToInt

/**
 * The one place manual-control keys become `CaptureRequest` mutations. Both
 * pipelines call [applyTo] at the tail of building their repeating request, so
 * a control change is a request rebuild - never a session/pipeline rebuild
 * (only a *camera switch* rebuilds).
 *
 * Two invariants from docs/QUIRKS.md:
 *  - `SCALER_CROP_REGION` and `CONTROL_ZOOM_RATIO` are never set in the same
 *    request - interleaving them across a session corrupts the Pixel 6 front
 *    camera's readback. `cropRegionNorm` (an off-centre rect) takes the
 *    `SCALER_CROP_REGION` path; a bare `zoomRatio` takes the ratio API on
 *    API 30+, else a centred `SCALER_CROP_REGION`.
 *  - the API-30 zoom-ratio field is only ever touched through [ZoomRatioApi30].
 */
object CameraControlApply {

    fun applyTo(builder: CaptureRequest.Builder, spec: CameraControlSpec, caps: CameraCapabilities) {
        if (!spec.manualControlEnabled) return
        val k = spec.keys
        val active = Rect(0, 0, caps.activeArrayWidth, caps.activeArrayHeight)
        val haveActive = active.width() > 0 && active.height() > 0

        val cropNorm = k.cropRegionNorm
        val zoom = k.zoomRatio
        when {
            cropNorm != null && haveActive ->
                builder.set(CaptureRequest.SCALER_CROP_REGION, denorm(cropNorm, active))
            zoom != null && caps.zoomViaRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ZoomRatioApi30.setRequest(builder, zoom)
            zoom != null && haveActive ->
                builder.set(CaptureRequest.SCALER_CROP_REGION, centeredCrop(active, zoom))
        }

        k.aeExposureCompensation?.let {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
        }
        k.aeLock?.let { builder.set(CaptureRequest.CONTROL_AE_LOCK, it) }

        // Manual exposure: within it, either the shutter/ISO dials OR a
        // spot-metering region (the region keeps AE metering ON, biased to that
        // area). The controller only ever sets one of the two.
        if (k.manualExposure == true) {
            val aeRegion = k.aeRegionNorm
            if (aeRegion != null && haveActive && caps.maxAeRegions > 0) {
                builder.set(
                    CaptureRequest.CONTROL_AE_REGIONS,
                    arrayOf(MeteringRectangle(denorm(aeRegion, active), MeteringRectangle.METERING_WEIGHT_MAX)),
                )
            } else if (caps.hasManualSensor) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                k.sensorExposureTimeNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
                k.sensorSensitivityIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            }
        }

        // Manual focus: within it, either the distance dial OR a focus-point
        // region (continuous AF locked to that area). Again exactly one is set.
        if (k.manualFocus == true) {
            val afRegion = k.afRegionNorm
            if (afRegion != null && haveActive && caps.maxAfRegions > 0) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                builder.set(
                    CaptureRequest.CONTROL_AF_REGIONS,
                    arrayOf(MeteringRectangle(denorm(afRegion, active), MeteringRectangle.METERING_WEIGHT_MAX)),
                )
            } else if (caps.hasManualFocus) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                k.lensFocusDistanceDiopters?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
            }
        }

        // White balance: manual RGGB gains win over an AWB preset (both target the same result).
        if (k.manualWhiteBalance == true && caps.hasManualWhiteBalance) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            // TRANSFORM_MATRIX mode requires BOTH gains and the 3x3 transform to be set - a HAL
            // handed only gains produces garbage/black frames (reproduced on the Pixel 6).
            // Identity transform => the GAINS alone do the per-channel white-balance scaling.
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)
            val r = (k.wbRedGain ?: 1f).coerceIn(caps.wbGainRange.lo, caps.wbGainRange.hi)
            val g = (k.wbGreenGain ?: 1f).coerceIn(caps.wbGainRange.lo, caps.wbGainRange.hi)
            val b = (k.wbBlueGain ?: 1f).coerceIn(caps.wbGainRange.lo, caps.wbGainRange.hi)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(r, g, g, b))
        } else {
            k.awbMode?.let { builder.set(CaptureRequest.CONTROL_AWB_MODE, it) }
        }

        k.videoStabilizationMode?.let {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it)
        }
        k.opticalStabilizationMode?.let {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it)
        }
    }

    /** 3x3 identity as `ColorSpaceTransform` rationals (num/den pairs, row-major). */
    private val IDENTITY_TRANSFORM = ColorSpaceTransform(
        intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1),
    )

    private fun centeredCrop(active: Rect, ratio: Float): Rect {
        val ir = ZoomMath.centeredCropForRatio(
            ZoomMath.IntRect(active.left, active.top, active.right, active.bottom),
            ratio,
        )
        return Rect(ir.left, ir.top, ir.right, ir.bottom)
    }

    private fun denorm(n: RectNorm, active: Rect): Rect {
        val w = active.width()
        val h = active.height()
        return Rect(
            (n.l * w).roundToInt().coerceIn(0, w - 1),
            (n.t * h).roundToInt().coerceIn(0, h - 1),
            (n.r * w).roundToInt().coerceIn(1, w),
            (n.b * h).roundToInt().coerceIn(1, h),
        )
    }
}
