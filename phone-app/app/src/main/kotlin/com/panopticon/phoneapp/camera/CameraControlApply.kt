package com.panopticon.phoneapp.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import com.panopticon.phoneapp.calibration.RectNorm
import com.panopticon.phoneapp.calibration.ZoomRatioApi30
import kotlin.math.roundToInt

/**
 * The one place manual-control keys become `CaptureRequest` mutations. Both
 * pipelines call [applyTo] at the tail of building their repeating request, so
 * a control change is a request rebuild - never a session/pipeline rebuild
 * (only a *camera switch* rebuilds).
 *
 * Zoom does not travel with the other keys through [applyTo]. It is not a value that can be set
 * and forgotten: the view the user asked for is split between a hardware zoom and a GL crop by
 * [ZoomGeometry.split], against this phone's own calibration, and only the pipelines know the
 * aspect trim that split depends on. So the pipelines compute the split, hand the hardware half
 * here via [applyZoom], and render the other half themselves.
 *
 * `caps`/`active` are threaded through for the AE/AF region denormalization below.
 */
object CameraControlApply {

    fun applyTo(builder: CaptureRequest.Builder, spec: CameraControlSpec, caps: CameraCapabilities) {
        if (!spec.manualControlEnabled) return
        val k = spec.keys
        val active = Rect(0, 0, caps.activeArrayWidth, caps.activeArrayHeight)
        val haveActive = active.width() > 0 && active.height() > 0

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

    /**
     * Ask the camera for a **centred** zoom of [hwRatio] (`1.0` = ask for nothing).
     *
     * Called by the pipelines with [ZoomGeometry.Split.hwRatio], i.e. only ever a magnification
     * the requested view can absorb - the hardware never crops away part of what the user chose;
     * GL trims whatever is left (see [ZoomGeometry.split]).
     *
     * Two paths, and they are never mixed in one request: `CONTROL_ZOOM_RATIO` where the camera
     * declares it, else a centred `SCALER_CROP_REGION`. Interleaving the two corrupts the readback
     * on the Pixel 6 front camera (docs/quirks/calibration-zoom.md). The API-30 key is reached
     * only through [ZoomRatioApi30], never named here, because ART resolves every field a method
     * mentions when it verifies it - a runtime `SDK_INT` guard around the *call* is not enough and
     * throws `NoSuchFieldError` on API 28 (same quirks file).
     */
    fun applyZoom(builder: CaptureRequest.Builder, hwRatio: Float, caps: CameraCapabilities) {
        if (!hwRatio.isFinite() || hwRatio <= 1.001f) return
        val ratio = hwRatio.coerceIn(
            maxOf(1f, caps.zoomRatioRange.lo),
            maxOf(1f, caps.zoomRatioRange.hi),
        )
        if (ratio <= 1.001f) return
        if (caps.zoomViaRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ZoomRatioApi30.setRequest(builder, ratio)
            return
        }
        val w = caps.activeArrayWidth
        val h = caps.activeArrayHeight
        if (w <= 0 || h <= 0) return
        val cw = (w / ratio).roundToInt().coerceIn(1, w)
        val ch = (h / ratio).roundToInt().coerceIn(1, h)
        val l = (w - cw) / 2
        val t = (h - ch) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + cw, t + ch))
    }

    /** 3x3 identity as `ColorSpaceTransform` rationals (num/den pairs, row-major). */
    private val IDENTITY_TRANSFORM = ColorSpaceTransform(
        intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1),
    )

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
