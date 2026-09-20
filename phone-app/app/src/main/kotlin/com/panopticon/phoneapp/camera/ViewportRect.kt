package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import kotlin.math.max

/**
 * Coordinate transform for rects the controller's drag-a-box picker (zoom / AE-spot / AF-point)
 * draws over the live preview.
 *
 * The picker draws against what's actually ON SCREEN: the sensor's active array as already
 * cropped by the *current* zoom ([CameraControlKeys.cropRegionNorm] or [CameraControlKeys.zoomRatio]),
 * further cropped by GL's *fixed* [CameraFraming.computeTexCrop] (this applies even at zero zoom -
 * it's the same crop that made the very first "preview doesn't match record" bug), and then
 * rotated by `rotationDegrees` (see [GlBlit]) - so a freshly-drawn rect's `[0,1]` coordinates are relative to
 * that fully-cropped on-screen *viewport*, not to the full sensor.
 * [CameraControlKeys.cropRegionNorm] (and `aeRegionNorm`/`afRegionNorm`) are, however, defined
 * against the full sensor active array - [CameraControlApply.denorm] maps them straight onto it.
 * [toSensorSpace] bridges the two: it undoes the current rotation and the current (zoom +
 * texCrop) viewport, so the region the user saw and drew is the region that actually gets
 * captured.
 */
internal object ViewportRect {

    /** The current viewport (full sensor active array in normalized 0..1 terms) implied by the
     *  currently-stored control keys and the fixed GL texCrop: an explicit
     *  [CameraControlKeys.cropRegionNorm] wins, else a centred crop for
     *  [CameraControlKeys.zoomRatio], else the full frame - and in every case that base is then
     *  further shrunk (centred) by [texCropX]/[texCropY], since GL applies that crop regardless of
     *  zoom. Defaults to `1f, 1f` (no shrink) for callers that don't have a source/recording size
     *  to derive a texCrop from. */
    fun currentViewport(keys: CameraControlKeys, texCropX: Float = 1f, texCropY: Float = 1f): RectNorm {
        val base = keys.cropRegionNorm
            ?: keys.zoomRatio?.let { centeredViewportForRatio(it) }
            ?: RectNorm(0f, 0f, 1f, 1f)
        return CameraFraming.growBySameCentre(base, 1f / texCropX, 1f / texCropY)
    }

    /** A centred crop covering `1/ratio` of each axis - the normalized-terms equivalent of
     *  [com.panopticon.phoneapp.calibration.ZoomMath.centeredCropForRatio] (ratio &lt;= 1 is
     *  treated as no zoom). */
    fun centeredViewportForRatio(ratio: Float): RectNorm {
        val r = max(1f, ratio)
        val half = (1f - 1f / r) / 2f
        return RectNorm(half, half, 1f - half, 1f - half)
    }

    /** Maps a point from the rotated on-screen frame back to the pre-rotation frame -
     *  `rotationDegrees` is how much [GlBlit.quad] rotates the pre-rotation frame clockwise to
     *  produce what's on screen, so this applies the inverse. */
    private fun inverseRotatePoint(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (CameraFraming.normalizedRotation(rotationDegrees)) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }

    /** [rect] (in the rotated on-screen frame) mapped back to the pre-rotation frame - see
     *  [inverseRotatePoint]. A 90/270 rotation swaps which axis is width/height, so this maps all
     *  four corners and re-derives `(l,t,r,b)` from their bounds rather than just two. */
    fun inverseRotateRect(rect: RectNorm, rotationDegrees: Int): RectNorm {
        val corners = listOf(
            inverseRotatePoint(rect.l, rect.t, rotationDegrees),
            inverseRotatePoint(rect.r, rect.t, rotationDegrees),
            inverseRotatePoint(rect.l, rect.b, rotationDegrees),
            inverseRotatePoint(rect.r, rect.b, rotationDegrees),
        )
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        return RectNorm(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** [rect], normalized within [viewport], mapped to full-sensor-normalized terms - the
     *  inverse of "what fraction of the current viewport is this". Clamped to `0f..1f` to absorb
     *  float rounding at the edges (CameraControlValidation requires a strict sub-rect of 0..1). */
    fun composeWithViewport(rect: RectNorm, viewport: RectNorm): RectNorm {
        val vw = viewport.r - viewport.l
        val vh = viewport.b - viewport.t
        return RectNorm(
            l = (viewport.l + rect.l * vw).coerceIn(0f, 1f),
            t = (viewport.t + rect.t * vh).coerceIn(0f, 1f),
            r = (viewport.l + rect.r * vw).coerceIn(0f, 1f),
            b = (viewport.t + rect.b * vh).coerceIn(0f, 1f),
        )
    }

    /** [drawn] as seen on screen (post-rotation, within the current zoom + texCrop viewport) ->
     *  full sensor-active-array-normalized terms, ready for [CameraControlApply.denorm]. */
    fun toSensorSpace(
        drawn: RectNorm,
        currentKeys: CameraControlKeys,
        rotationDegrees: Int,
        texCropX: Float = 1f,
        texCropY: Float = 1f,
    ): RectNorm {
        val preRotation = inverseRotateRect(drawn, rotationDegrees)
        return composeWithViewport(preRotation, currentViewport(currentKeys, texCropX, texCropY))
    }
}
