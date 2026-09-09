package com.panopticon.phoneapp.camera

/**
 * Validate a requested [CameraControlKeys] against one camera's declared
 * [CameraCapabilities], the "validate-then-apply" half of `POST /api/camera/state`
 * (phone-http-api.md): the route returns `400 {"error": ..., "key": ...}` for
 * the first offending key and applies nothing.
 *
 * Framework-free - unit-tested directly. Only non-null fields are checked; a
 * null field means "leave on auto" and is always fine.
 *
 * Position is deliberately *not* rejected here: whether a HAL honours an
 * off-centre `SCALER_CROP_REGION` is exactly what calibration measures
 * empirically, and some devices that declare `CENTER_ONLY` honour it anyway
 * (see docs/quirks/calibration-zoom.md). We only reject a crop rect that isn't a sane sub-rect
 * of the frame.
 */
object CameraControlValidation {

    data class Error(val key: String, val reason: String)

    fun validate(keys: CameraControlKeys, caps: CameraCapabilities): Error? {
        keys.zoomRatio?.let { z ->
            val r = caps.zoomRatioRange
            if (z < r.lo || z > r.hi) {
                return Error("zoomRatio", "must be in ${r.lo}..${r.hi}, got $z")
            }
        }

        keys.cropRegionNorm?.let { c ->
            val sane = c.l in 0f..1f && c.t in 0f..1f && c.r in 0f..1f && c.b in 0f..1f &&
                c.r > c.l && c.b > c.t
            if (!sane) {
                return Error("cropRegionNorm", "must be a sub-rect of 0..1 with r>l and b>t, got $c")
            }
        }

        keys.aeRegionNorm?.let { c ->
            if (caps.maxAeRegions <= 0) return Error("aeRegionNorm", "this camera has no AE metering regions")
            val sane = c.l in 0f..1f && c.t in 0f..1f && c.r in 0f..1f && c.b in 0f..1f &&
                c.r > c.l && c.b > c.t
            if (!sane) return Error("aeRegionNorm", "must be a sub-rect of 0..1 with r>l and b>t, got $c")
        }

        keys.afRegionNorm?.let { c ->
            if (caps.maxAfRegions <= 0) return Error("afRegionNorm", "this camera has no AF metering regions")
            val sane = c.l in 0f..1f && c.t in 0f..1f && c.r in 0f..1f && c.b in 0f..1f &&
                c.r > c.l && c.b > c.t
            if (!sane) return Error("afRegionNorm", "must be a sub-rect of 0..1 with r>l and b>t, got $c")
        }

        keys.aeExposureCompensation?.let { ec ->
            val r = caps.aeCompensationRange
            if (ec < r.lo || ec > r.hi) {
                return Error("aeExposureCompensation", "must be in ${r.lo}..${r.hi}, got $ec")
            }
        }

        if (keys.manualExposure == true) {
            if (!caps.hasManualSensor) {
                return Error("manualExposure", "this camera has no MANUAL_SENSOR capability")
            }
            keys.sensorExposureTimeNs?.let { t ->
                val r = caps.exposureTimeRangeNs
                    ?: return Error("sensorExposureTimeNs", "camera reports no exposure-time range")
                if (t < r.lo || t > r.hi) {
                    return Error("sensorExposureTimeNs", "must be in ${r.lo}..${r.hi} ns, got $t")
                }
            }
            keys.sensorSensitivityIso?.let { iso ->
                val r = caps.sensitivityRange
                    ?: return Error("sensorSensitivityIso", "camera reports no sensitivity range")
                if (iso < r.lo || iso > r.hi) {
                    return Error("sensorSensitivityIso", "must be in ${r.lo}..${r.hi}, got $iso")
                }
            }
        }

        if (keys.manualFocus == true) {
            if (!caps.hasManualFocus || caps.minFocusDistanceDiopters <= 0f) {
                return Error("manualFocus", "this camera has no manual-focus support")
            }
            keys.lensFocusDistanceDiopters?.let { d ->
                if (d < 0f || d > caps.minFocusDistanceDiopters) {
                    return Error(
                        "lensFocusDistanceDiopters",
                        "must be in 0..${caps.minFocusDistanceDiopters} (0 = infinity), got $d",
                    )
                }
            }
        }

        keys.awbMode?.let { m ->
            if (caps.awbModes.isNotEmpty() && m !in caps.awbModes) {
                return Error("awbMode", "must be one of ${caps.awbModes}, got $m")
            }
        }

        if (keys.manualWhiteBalance == true) {
            if (!caps.hasManualWhiteBalance) {
                return Error("manualWhiteBalance", "this camera has no MANUAL_POST_PROCESSING capability")
            }
            val r = caps.wbGainRange
            for ((key, g) in listOf(
                "wbRedGain" to keys.wbRedGain,
                "wbGreenGain" to keys.wbGreenGain,
                "wbBlueGain" to keys.wbBlueGain,
            )) {
                if (g != null && (g < r.lo || g > r.hi)) {
                    return Error(key, "must be in ${r.lo}..${r.hi}, got $g")
                }
            }
        }

        keys.videoStabilizationMode?.let { m ->
            if (caps.videoStabilizationModes.isNotEmpty() && m !in caps.videoStabilizationModes) {
                return Error(
                    "videoStabilizationMode",
                    "must be one of ${caps.videoStabilizationModes}, got $m",
                )
            }
        }

        keys.opticalStabilizationMode?.let { m ->
            if (m !in caps.opticalStabilizationModes) {
                return Error(
                    "opticalStabilizationMode",
                    "must be one of ${caps.opticalStabilizationModes}, got $m",
                )
            }
        }

        return null
    }
}
