package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm

/**
 * Coordinate transform for rects the controller's drag-a-box picker (zoom / AE-spot / AF-point)
 * draws over the live preview.
 *
 * The picker draws against what's actually ON SCREEN: the sensor's active array as already
 * cropped by the *current* zoom ([CameraControlKeys.zoomViewNorm]), further cropped by GL's fixed
 * output-aspect trim [CameraFraming.correctedTexCrop] (this applies even at zero zoom - it's the
 * same crop that made the very first "preview doesn't match record" bug), and then rotated by
 * `rotationDegrees` (see [GlBlit]) - so a freshly-drawn rect's `[0,1]` coordinates are relative to
 * that fully-cropped on-screen *viewport*, not to the full sensor.
 * `aeRegionNorm`/`afRegionNorm` are, however, defined against the full sensor active array -
 * [CameraControlApply.denorm] maps them straight onto it.
 * [toSensorSpace] bridges the two: it undoes the current rotation and the current (zoom +
 * texCrop) viewport, so the region the user saw and drew is the region that actually gets
 * captured.
 */
internal object ViewportRect {

    /** The region of the sensor active array that is currently *on screen*, in normalized 0..1
     *  terms: [CameraControlKeys.zoomViewNorm] (frame space - a fraction of the un-zoomed view)
     *  placed back onto the sensor through the fixed output-aspect trim [texCropX]/[texCropY],
     *  which GL applies regardless of zoom. Un-zoomed, this is just that trim.
     *
     *  This is deliberately the *same* value [ZoomGeometry.shaderRect] renders, so a rect the user
     *  drew over the preview is untransformed through exactly the crop they were looking at.
     *  Defaults to `1f, 1f` (no trim) for callers that don't have a live pipeline to take a real
     *  texCrop from. */
    fun currentViewport(keys: CameraControlKeys, texCropX: Float = 1f, texCropY: Float = 1f): RectNorm =
        ZoomGeometry.viewToSensor(keys.zoomViewNorm ?: ZoomGeometry.FULL, texCropX, texCropY)

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
