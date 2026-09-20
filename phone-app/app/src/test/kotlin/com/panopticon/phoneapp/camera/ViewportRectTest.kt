package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Test

private const val TOL = 1e-4f

class ViewportRectTest {

    private fun assertRectEquals(expected: RectNorm, actual: RectNorm) {
        assertEquals(expected.l, actual.l, TOL)
        assertEquals(expected.t, actual.t, TOL)
        assertEquals(expected.r, actual.r, TOL)
        assertEquals(expected.b, actual.b, TOL)
    }

    @Test
    fun `no rotation, no zoom - drawn rect passes through unchanged`() {
        val drawn = RectNorm(0.2f, 0.3f, 0.6f, 0.7f)
        val result = ViewportRect.toSensorSpace(drawn, CameraControlKeys(), rotationDegrees = 0)
        assertRectEquals(drawn, result)
    }

    @Test
    fun `90-degree rotation is undone before applying`() {
        // Physically rotating a photo 90 degrees clockwise sends its top-left corner to the
        // result's top-right - so on-screen top-right (the drawn rect here) must have come from
        // the pre-rotation frame's top-left.
        val drawn = RectNorm(0.75f, 0f, 1f, 0.25f)
        val result = ViewportRect.toSensorSpace(drawn, CameraControlKeys(), rotationDegrees = 90)
        assertRectEquals(RectNorm(0f, 0f, 0.25f, 0.25f), result)
    }

    @Test
    fun `180-degree rotation flips both axes`() {
        val drawn = RectNorm(0.1f, 0.2f, 0.3f, 0.4f)
        val result = ViewportRect.toSensorSpace(drawn, CameraControlKeys(), rotationDegrees = 180)
        assertRectEquals(RectNorm(0.7f, 0.6f, 0.9f, 0.8f), result)
    }

    @Test
    fun `270-degree rotation`() {
        // Rotating 270 clockwise sends the pre-rotation frame's top-right corner to the result's
        // top-left - so on-screen top-right must have come from the pre-rotation bottom-right.
        val drawn = RectNorm(0.75f, 0f, 1f, 0.25f)
        val result = ViewportRect.toSensorSpace(drawn, CameraControlKeys(), rotationDegrees = 270)
        assertRectEquals(RectNorm(0.75f, 0.75f, 1f, 1f), result)
    }

    @Test
    fun `a rect drawn while already zoomed composes with the current crop`() {
        // Currently zoomed into the centre quarter of the sensor.
        val currentCrop = RectNorm(0.25f, 0.25f, 0.75f, 0.75f)
        // The user draws a box over the left half of what's on screen (which IS that quarter).
        val drawn = RectNorm(0f, 0f, 0.5f, 1f)
        val result = ViewportRect.toSensorSpace(
            drawn, CameraControlKeys(cropRegionNorm = currentCrop), rotationDegrees = 0,
        )
        // That's the left half of the current crop, in full-sensor terms: 0.25..0.5 horizontally.
        assertRectEquals(RectNorm(0.25f, 0.25f, 0.5f, 0.75f), result)
    }

    @Test
    fun `zoomRatio alone implies a centred viewport`() {
        // zoomRatio 2 implies a centred crop covering the middle half of each axis.
        val drawn = RectNorm(0f, 0f, 1f, 1f) // the whole (already-zoomed) screen
        val result = ViewportRect.toSensorSpace(
            drawn, CameraControlKeys(zoomRatio = 2f), rotationDegrees = 0,
        )
        assertRectEquals(RectNorm(0.25f, 0.25f, 0.75f, 0.75f), result)
    }

    @Test
    fun `texCrop shrinks the viewport even at zero zoom`() {
        // Pixel 6-like: texCropY = 0.75, so only the centre 75% of sensor height is actually on
        // screen even with no crop region or zoomRatio set - the visible viewport is
        // (0, 0.125, 1, 0.875), not the full (0, 0, 1, 1).
        val drawn = RectNorm(0f, 0.8f, 0.2f, 1f) // near the bottom edge of the visible preview
        val result = ViewportRect.toSensorSpace(
            drawn, CameraControlKeys(), rotationDegrees = 0, texCropX = 1f, texCropY = 0.75f,
        )
        // 0.8..1.0 of the visible (0.125..0.875) range -> 0.125 + [0.8, 1.0] * 0.75 = [0.725, 0.875].
        assertRectEquals(RectNorm(0f, 0.725f, 0.2f, 0.875f), result)
    }

    @Test
    fun `texCrop composes with an existing zoom viewport`() {
        val currentCrop = RectNorm(0.25f, 0.25f, 0.75f, 0.75f)
        val drawn = RectNorm(0f, 0f, 1f, 1f) // the whole (already-zoomed) screen
        val result = ViewportRect.toSensorSpace(
            drawn, CameraControlKeys(cropRegionNorm = currentCrop), rotationDegrees = 0,
            texCropX = 1f, texCropY = 0.5f,
        )
        // The stored crop (0.25..0.75, half-extent 0.5) is itself shrunk by texCropY=0.5 around
        // its own centre before the (whole-screen) drawn rect is composed against it.
        assertRectEquals(RectNorm(0.25f, 0.375f, 0.75f, 0.625f), result)
    }

    @Test
    fun `rotation and zoom compose together`() {
        val currentCrop = RectNorm(0.25f, 0.25f, 0.75f, 0.75f)
        // Same drawn rect as the 90-degree case above, now while already zoomed in.
        val drawn = RectNorm(0.75f, 0f, 1f, 0.25f)
        val result = ViewportRect.toSensorSpace(
            drawn, CameraControlKeys(cropRegionNorm = currentCrop), rotationDegrees = 90,
        )
        // Un-rotate first -> (0, 0, 0.25, 0.25) within the crop, then map that top-left 25% of
        // the current (centre-quarter) crop into full-sensor terms.
        assertRectEquals(RectNorm(0.25f, 0.25f, 0.375f, 0.375f), result)
    }
}
