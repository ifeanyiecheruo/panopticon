package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.CalibrationResult
import com.panopticon.phoneapp.calibration.CameraCalibration
import com.panopticon.phoneapp.calibration.ResolutionZoomMap
import com.panopticon.phoneapp.calibration.ZoomSample
import kotlin.math.abs

/**
 * Turns this phone's own persisted calibration sweep into the lookup table [ZoomGeometry.split]
 * uses to decide how much of a zoom the camera can do.
 *
 * Pure and framework-free so it unit-tests on a plain JVM; the caller loads the
 * [CalibrationResult] from `CalibrationStore` and passes it in.
 *
 * ## Which measurement to believe
 *
 * The sweep records two things per zoom request: the ratio the HAL *reported*
 * ([ZoomSample.reportedRatio]) and the crop rect it reported
 * ([ZoomSample.effectiveCropNorm]). Only one of them is meaningful on any given device, and
 * getting this wrong is not a small error:
 *
 * - On API 30+ hardware driving zoom through `CONTROL_ZOOM_RATIO` (the Pixel 6),
 *   `SCALER_CROP_REGION` stays pinned to the **full active array** at every ratio - measured
 *   `effectiveCropNorm ~= (1.0, 1.0)` from 0.67x through 7x. Believing the crop would mean
 *   concluding that even 7x still shows the whole frame, so the solver would happily ask for
 *   maximum zoom at any zoom level and the preview would fly off to a tiny centre crop.
 * - On legacy hardware with no `CONTROL_ZOOM_RATIO` (the BLU G5, API 28), the reported ratio is
 *   absent and the crop rect is exactly what carries the information.
 *
 * So: prefer the reported ratio, fall back to the crop's area, and fall back again to the
 * requested ratio. Each sample is then reduced to a single delivered magnification, which is all
 * a centred hardware zoom needs.
 */
internal object ZoomCalibrationLut {

    /** What the solver needs to know about one camera at one output size. */
    data class Lut(
        val entries: List<ZoomGeometry.LutEntry>,
        /** The camera entry these came from, for logging - empty when nothing matched. */
        val sourceCameraId: String = "",
        /** The probed output size actually used, which may not be the one being recorded. */
        val sourceResolution: String = "",
    ) {
        val isEmpty get() = entries.isEmpty()

        companion object {
            val NONE = Lut(emptyList())
        }
    }

    /**
     * Build the table for [cameraId] at [width]x[height].
     *
     * Returns [Lut.NONE] when this phone has never been calibrated, which makes
     * [ZoomGeometry.split] fall back to "ask the camera for nothing, let GL do it all" - correct,
     * just soft.
     */
    fun build(result: CalibrationResult?, cameraId: String, width: Int, height: Int): Lut {
        val cameras = result?.cameras ?: return Lut.NONE
        val (camKey, cam) = pickCamera(cameras, cameraId) ?: return Lut.NONE
        val (resKey, zoomMap) = pickResolution(cam.perResolution, width, height) ?: return Lut.NONE

        val entries = zoomMap.samples
            .mapNotNull { s ->
                val magnification = deliveredMagnification(s) ?: return@mapNotNull null
                ZoomGeometry.LutEntry(requestedRatio = s.requestedRatio, deliveredMagnification = magnification)
            }
            .sortedBy { it.requestedRatio }
        return Lut(entries, camKey, resKey)
    }

    /**
     * The magnification [sample] actually produced - see the class comment for why the reported
     * ratio wins over the reported crop. Null when neither signal is usable.
     */
    fun deliveredMagnification(sample: ZoomSample): Float? {
        sample.reportedRatio?.let { if (it.isFinite() && it > 0f) return it }
        val c = sample.effectiveCropNorm
        val w = c.r - c.l
        val h = c.b - c.t
        if (w > 1e-4f && h > 1e-4f) {
            // Area-equivalent magnification, matching ZoomMath.ratioFromCrop.
            val m = 2f / (w + h)
            if (m.isFinite() && m > 0f) return m
        }
        return if (sample.requestedRatio.isFinite() && sample.requestedRatio > 0f) sample.requestedRatio else null
    }

    /**
     * The calibration entry to use for [cameraId].
     *
     * A `"<logical>:<physical>"` id is not swept separately - the probe walks the ids
     * `CameraManager` reports - so a pinned physical sub-camera falls back to its **logical
     * parent**. That is an approximation worth being explicit about: pinning to one sensor removes
     * the logical camera's optical handover, so the parent's samples below the crossover describe
     * a lens this session cannot actually reach. It stays safe because the split only ever asks
     * for a magnification the view can absorb, and because every ratio at or above 1.0 is served
     * by the same sensor either way.
     */
    private fun pickCamera(
        cameras: Map<String, CameraCalibration>,
        cameraId: String,
    ): Pair<String, CameraCalibration>? {
        cameras[cameraId]?.let { return cameraId to it }
        val logical = cameraId.substringBefore(':')
        cameras[logical]?.let { return logical to it }
        return cameras.entries.firstOrNull()?.let { it.key to it.value }
    }

    /**
     * The probed output size closest in area to [width]x[height]. An exact match is preferred, but
     * the sweep only covers sizes `StreamConfigurationMap` offered at probe time and the recording
     * size may not be among them (the Pixel 6 records 3840x2160 while the sweep topped out at
     * 2688x1512). Zoom honouring barely varies across output sizes on the devices measured so far,
     * so the nearest one is a sound stand-in.
     */
    private fun pickResolution(
        perResolution: Map<String, ResolutionZoomMap>,
        width: Int,
        height: Int,
    ): Pair<String, ResolutionZoomMap>? {
        if (perResolution.isEmpty()) return null
        val exact = "${width}x$height"
        perResolution[exact]?.let { if (it.samples.isNotEmpty()) return exact to it }
        val target = width.toLong() * height.toLong()
        return perResolution.entries
            .filter { it.value.samples.isNotEmpty() }
            .minByOrNull { abs(it.value.width.toLong() * it.value.height.toLong() - target) }
            ?.let { it.key to it.value }
    }
}
