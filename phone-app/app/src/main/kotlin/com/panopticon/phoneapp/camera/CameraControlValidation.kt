package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm

/**
 * Validate a requested [CameraControlKeys] against one camera's declared
 * [CameraCapabilities], the "validate-then-apply" half of `POST /api/camera/state`
 * (docs/design/http-api.md): the route returns `400 {"error": ..., "key": ...}` for
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

    /**
     * Check the **request-only, viewer-space** keys of an incoming patch, before
     * `resolveSelections` consumes them. This runs first so a bad value is reported under the
     * field name the caller actually sent (`zoomSelectNorm`), not the absolute field it would have
     * turned into.
     *
     * Note what is deliberately *not* checked: how far a selection zooms. A selection is a region,
     * and the resulting magnification is whatever that region implies - clamped by geometry, not
     * rejected. Only the explicit [CameraControlKeys.zoomRatio] slider has a range to be outside of.
     */
    fun validateSelections(keys: CameraControlKeys, caps: CameraCapabilities): Error? {
        sanity(keys.zoomSelectNorm, "zoomSelectNorm")?.let { return it }
        sanity(keys.aeSelectNorm, "aeSelectNorm")?.let { return it }
        sanity(keys.afSelectNorm, "afSelectNorm")?.let { return it }

        keys.zoomRatio?.let { z ->
            // Deliberately no upper bound. A zoom *rect* can imply an arbitrarily large ratio, and
            // the phone reports that derived value back in `keys`; the controller echoes the whole
            // key set on every later patch, so any ceiling here would eventually reject a value
            // this device itself produced - and reject the whole patch with it, leaving the user
            // unable to change any control, including zooming back out. Magnitude is already
            // bounded geometrically: ZoomGeometry.viewForRatio clamps the view it produces, so a
            // silly ratio is harmless rather than dangerous. Only a value that isn't a
            // magnification at all is worth refusing.
            if (!z.isFinite() || z < 1f) {
                return Error("zoomRatio", "must be a magnification of at least 1.0, got $z")
            }
        }
        return null
    }

    fun validate(keys: CameraControlKeys, caps: CameraCapabilities): Error? {
        // zoomViewNorm is the resolved, absolute view. It is produced by ZoomGeometry, which
        // clamps, so this only guards a value sent outright by a caller.
        sanity(keys.zoomViewNorm, "zoomViewNorm")?.let { return it }

        keys.aeRegionNorm?.let { c ->
            if (caps.maxAeRegions <= 0) return Error("aeRegionNorm", "this camera has no AE metering regions")
            sanity(c, "aeRegionNorm")?.let { return it }
        }

        keys.afRegionNorm?.let { c ->
            if (caps.maxAfRegions <= 0) return Error("afRegionNorm", "this camera has no AF metering regions")
            sanity(c, "afRegionNorm")?.let { return it }
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

    /** Every rect on this API is a sub-rect of 0..1 with a positive extent, whichever space it is
     *  expressed in. Null is always fine - it means "leave this control alone". */
    private fun sanity(c: RectNorm?, key: String): Error? {
        if (c == null) return null
        val sane = c.l in 0f..1f && c.t in 0f..1f && c.r in 0f..1f && c.b in 0f..1f &&
            c.r > c.l && c.b > c.t
        return if (sane) null else Error(key, "must be a sub-rect of 0..1 with r>l and b>t, got $c")
    }
}
