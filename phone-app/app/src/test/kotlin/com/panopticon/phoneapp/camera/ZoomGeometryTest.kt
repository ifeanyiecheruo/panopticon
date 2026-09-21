package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOL = 1e-4f

class ZoomGeometryTest {

    private fun assertRectEquals(expected: RectNorm, actual: RectNorm) {
        assertEquals(expected.l, actual.l, TOL)
        assertEquals(expected.t, actual.t, TOL)
        assertEquals(expected.r, actual.r, TOL)
        assertEquals(expected.b, actual.b, TOL)
    }

    private fun side(r: RectNorm) = (r.r - r.l) to (r.b - r.t)

    /** A centred crop of the given normalised side length - what a well-behaved HAL delivers. */
    private fun centred(side: Float): RectNorm {
        val half = (1f - side) / 2f
        return RectNorm(half, half, 1f - half, 1f - half)
    }

    /** A well-behaved camera: every requested ratio is delivered exactly. */
    private fun lut(vararg ratios: Float) =
        ratios.map { ZoomGeometry.LutEntry(requestedRatio = it, deliveredMagnification = it) }

    // ---- fitToAspect ----

    @Test
    fun `a wide drag grows vertically to the output aspect, containing the selection`() {
        val drag = RectNorm(0.2f, 0.45f, 0.8f, 0.55f) // 0.6 x 0.1
        val fitted = ZoomGeometry.fitToAspect(drag)
        val (w, h) = side(fitted)
        assertEquals("fitted rect is square in frame space", w, h, TOL)
        assertEquals(0.6f, w, TOL)
        // It must CONTAIN the drag, never trim it.
        assertTrue(fitted.l <= drag.l + TOL && fitted.r >= drag.r - TOL)
        assertTrue(fitted.t <= drag.t + TOL && fitted.b >= drag.b - TOL)
    }

    @Test
    fun `a tall drag grows horizontally`() {
        val drag = RectNorm(0.45f, 0.1f, 0.55f, 0.7f)
        val fitted = ZoomGeometry.fitToAspect(drag)
        val (w, h) = side(fitted)
        assertEquals(w, h, TOL)
        assertEquals(0.6f, w, TOL)
    }

    @Test
    fun `a fitted rect at the edge is shifted inside the frame, not shrunk`() {
        // Drag hugging the left edge: the square that contains it would run off the left.
        val drag = RectNorm(0f, 0.1f, 0.1f, 0.7f)
        val fitted = ZoomGeometry.fitToAspect(drag)
        val (w, h) = side(fitted)
        assertEquals("kept square", w, h, TOL)
        assertEquals("kept its size rather than shrinking", 0.6f, w, TOL)
        assertEquals("shifted flush to the edge", 0f, fitted.l, TOL)
        assertTrue(fitted.r <= 1f + TOL)
    }

    // ---- compose: selections compound ----

    @Test
    fun `drawing the same rect twice zooms twice`() {
        val full = RectNorm(0f, 0f, 1f, 1f)
        val selection = RectNorm(0.25f, 0.25f, 0.75f, 0.75f) // centre half
        val once = ZoomGeometry.compose(full, selection, rotationDegrees = 0)
        assertRectEquals(RectNorm(0.25f, 0.25f, 0.75f, 0.75f), once)

        // The same viewer-space rect again must zoom again - this is exactly what the old
        // "is it different from the stored rect?" heuristic could not express.
        val twice = ZoomGeometry.compose(once, selection, rotationDegrees = 0)
        assertRectEquals(RectNorm(0.375f, 0.375f, 0.625f, 0.625f), twice)
        assertEquals(4f, ZoomGeometry.ratioOf(twice), 1e-3f)
    }

    @Test
    fun `composing stays square through many zooms, so the aspect never drifts`() {
        var view = RectNorm(0f, 0f, 1f, 1f)
        val lopsided = RectNorm(0.1f, 0.3f, 0.9f, 0.5f)
        repeat(5) {
            view = ZoomGeometry.compose(view, lopsided, rotationDegrees = 0)
            val (w, h) = side(view)
            assertEquals("still square after zoom", w, h, TOL)
        }
    }

    @Test
    fun `a selection is composed within the current view, not the whole frame`() {
        // Already zoomed into the bottom-right quarter.
        val view = RectNorm(0.5f, 0.5f, 1f, 1f)
        // Select the top-left quarter OF WHAT IS ON SCREEN.
        val selection = RectNorm(0f, 0f, 0.5f, 0.5f)
        val next = ZoomGeometry.compose(view, selection, rotationDegrees = 0)
        assertRectEquals(RectNorm(0.5f, 0.5f, 0.75f, 0.75f), next)
    }

    @Test
    fun `rotation is undone before composing`() {
        val full = RectNorm(0f, 0f, 1f, 1f)
        // On a 90-degree-rotated view, the top-right quarter came from the pre-rotation top-left.
        val selection = RectNorm(0.5f, 0f, 1f, 0.5f)
        val next = ZoomGeometry.compose(full, selection, rotationDegrees = 90)
        assertRectEquals(RectNorm(0f, 0f, 0.5f, 0.5f), next)
    }

    // ---- slider ----

    @Test
    fun `the slider zooms about the current view centre, preserving off-centre framing`() {
        // Zoomed into a rect whose centre is well off-centre, but far enough from the edges that
        // a 2x view still fits (the edge case has its own test below).
        val view = RectNorm(0.6f, 0.2f, 0.8f, 0.4f) // centre (0.7, 0.3)
        val next = ZoomGeometry.viewForRatio(view, 2f)
        assertEquals("centre x kept", 0.7f, (next.l + next.r) / 2f, TOL)
        assertEquals("centre y kept", 0.3f, (next.t + next.b) / 2f, TOL)
        assertEquals(2f, ZoomGeometry.ratioOf(next), 1e-3f)
    }

    @Test
    fun `sliding back to 1x shows the whole frame again`() {
        val view = RectNorm(0.6f, 0.1f, 0.8f, 0.3f)
        val next = ZoomGeometry.viewForRatio(view, 1f)
        assertRectEquals(RectNorm(0f, 0f, 1f, 1f), next)
    }

    @Test
    fun `a slider zoom near an edge stays inside the frame`() {
        val view = RectNorm(0.9f, 0.9f, 1f, 1f) // centre (0.95, 0.95)
        val next = ZoomGeometry.viewForRatio(view, 2f)
        assertTrue("inside the frame", next.l >= -TOL && next.t >= -TOL)
        assertTrue("inside the frame", next.r <= 1f + TOL && next.b <= 1f + TOL)
        assertEquals("kept its magnification rather than shrinking", 2f, ZoomGeometry.ratioOf(next), 1e-3f)
    }

    // ---- hardware / GL split ----

    @Test
    fun `an uncalibrated phone does all the zoom in GL`() {
        val target = centred(0.5f) // want a 2x centred view
        val s = ZoomGeometry.split(target, lut = emptyList())
        assertEquals("no hardware zoom requested", 1f, s.hwRatio, TOL)
        assertRectEquals(RectNorm(0.25f, 0.25f, 0.75f, 0.75f), s.glRect)
        assertEquals(2f, s.glResidual, 1e-3f)
    }

    @Test
    fun `the hardware takes as much of the zoom as it can without cropping the selection`() {
        val target = centred(0.25f) // want 4x
        val s = ZoomGeometry.split(target, lut(1f, 2f, 4f, 8f))
        assertEquals("picked the 4x sample", 4f, s.hwRatio, TOL)
        assertEquals("nothing left for GL", 1f, s.glResidual, 1e-3f)
        assertRectEquals(RectNorm(0f, 0f, 1f, 1f), s.glRect)
    }

    @Test
    fun `a zoom between calibrated steps is interpolated, not rounded down to a staircase`() {
        val target = centred(1f / 3f) // want 3x, but only 2x and 4x were measured
        val s = ZoomGeometry.split(target, lut(1f, 2f, 4f))
        assertEquals("interpolated straight to 3x", 3f, s.hwRatio, 1e-2f)
        assertEquals("so GL has nothing left to do", 1f, s.glResidual, 1e-2f)
    }

    @Test
    fun `an off-centre selection limits the hardware to a centred crop that still contains it`() {
        // A 2x-sized view tucked into the top-left corner: it touches two edges, so ANY centred
        // zoom would cut it off. GL therefore does the whole job - the deliberate cost of only
        // ever asking the camera for a centred magnification (see ZoomGeometry.split).
        val corner = RectNorm(0f, 0f, 0.5f, 0.5f)
        val s = ZoomGeometry.split(corner, lut(1f, 2f, 4f))
        assertEquals("no hardware zoom - it would crop the corner away", 1f, s.hwRatio, TOL)
        assertEquals("GL does all 2x", 2f, s.glResidual, 1e-3f)
        assertRectEquals(corner, s.glRect)

        // The same size of view, centred, gets the full hardware zoom instead.
        val middle = centred(0.5f)
        val c = ZoomGeometry.split(middle, lut(1f, 2f, 4f))
        assertEquals(2f, c.hwRatio, TOL)
        assertEquals(1f, c.glResidual, 1e-3f)
    }

    @Test
    fun `a partly off-centre selection still gets some hardware help`() {
        // Centre at 0.35, half-extent 0.15 -> furthest edge is 0.5 from centre... no: 0.5-0.2=0.3.
        val view = RectNorm(0.2f, 0.2f, 0.5f, 0.5f)
        val s = ZoomGeometry.split(view, lut(1f, 1.5f, 2f, 3f, 4f))
        assertTrue("some hardware zoom, but not the full 3.3x the size alone implies", s.hwRatio > 1.4f)
        assertTrue(s.hwRatio < 2.1f)
        // Whatever the split, the view must still be exactly reproducible.
        assertRectEquals(view, ViewportRect.composeWithViewport(s.glRect, s.deliveredCrop))
    }

    @Test
    fun `the GL rect always lands back on exactly the requested view`() {
        // Composing the GL rect back onto what the camera delivers must reproduce the target -
        // that is the guarantee that the viewport shows what the user selected, however the work
        // was divided.
        for (target in listOf(
            RectNorm(0.1f, 0.2f, 0.4f, 0.5f),
            centred(0.5f),
            RectNorm(0.3f, 0.3f, 0.7f, 0.7f),
            RectNorm(0f, 0.4f, 0.2f, 0.6f),
        )) {
            val s = ZoomGeometry.split(target, lut(1f, 1.5f, 2f, 3f))
            assertRectEquals(target, ViewportRect.composeWithViewport(s.glRect, s.deliveredCrop))
        }
    }

    @Test
    fun `zoom-out samples below 1x are never used as a crop`() {
        val target = centred(0.5f)
        val wide = listOf(
            ZoomGeometry.LutEntry(0.67f, 0.67f),
            ZoomGeometry.LutEntry(1f, 1f),
            ZoomGeometry.LutEntry(2f, 2f),
        )
        val s = ZoomGeometry.split(target, wide)
        assertEquals(2f, s.hwRatio, TOL)
    }

    @Test
    fun `a non-linear optical handover is followed as measured, not assumed linear`() {
        // The Pixel 6 shape: asking for 1.15x hands over from the ultrawide to the main sensor,
        // and the delivered magnification does NOT track the requested ratio on the way there.
        // A solver assuming "field of view = 1 / ratio" would ask for far too much zoom here.
        val measured = listOf(
            ZoomGeometry.LutEntry(requestedRatio = 1f, deliveredMagnification = 1f),
            ZoomGeometry.LutEntry(requestedRatio = 1.15f, deliveredMagnification = 1.05f),
            ZoomGeometry.LutEntry(requestedRatio = 2f, deliveredMagnification = 1.9f),
            ZoomGeometry.LutEntry(requestedRatio = 4f, deliveredMagnification = 3.8f),
        )
        // Want exactly 1.9x of magnification: the measurement says that takes a 2.0x REQUEST.
        val s = ZoomGeometry.split(centred(1f / 1.9f), measured)
        assertEquals("asked for the ratio the measurement says delivers 1.9x", 2f, s.hwRatio, 1e-2f)
        assertEquals("and it lands, so GL is idle", 1f, s.glResidual, 1e-2f)

        // A linear assumption would have asked for 1.9 and come up short.
        val naive = ZoomGeometry.split(centred(1f / 1.9f), lut(1f, 1.15f, 2f, 4f))
        assertEquals(1.9f, naive.hwRatio, 1e-2f)
        assertTrue("the two models genuinely disagree", kotlin.math.abs(s.hwRatio - naive.hwRatio) > 0.05f)
    }

    @Test
    fun `asking for no zoom predicts no zoom, even when the table starts above 1x`() {
        // The real Pixel 6 back camera: its zoom-IN samples start at the 1.152x optical handover,
        // because everything below that zooms OUT. Clamping an un-zoomed request up to the lowest
        // measured sample would predict a 1.152x magnified buffer that the camera was never asked
        // to produce, and the shader would crop for a zoom that is not there - which showed up on
        // device as a glResidual of 0.93 at zero zoom.
        val pixel6Back = listOf(
            ZoomGeometry.LutEntry(requestedRatio = 1.152f, deliveredMagnification = 1.152f),
            ZoomGeometry.LutEntry(requestedRatio = 1.380f, deliveredMagnification = 1.380f),
            ZoomGeometry.LutEntry(requestedRatio = 7f, deliveredMagnification = 7f),
        )
        val s = ZoomGeometry.split(
            ZoomGeometry.viewToSensor(ZoomGeometry.FULL, 1f, 0.421875f),
            pixel6Back, texCropX = 1f, texCropY = 0.421875f,
        )
        assertEquals("no hardware zoom asked for", 1f, s.hwRatio, TOL)
        assertEquals("and none predicted", 1f, s.deliveredMagnification, TOL)
        assertRectEquals(ZoomGeometry.FULL, s.deliveredCrop)
        assertEquals("so GL is doing nothing but the aspect trim", 1f, s.glResidual, 1e-3f)
    }

    @Test
    fun `the hardware is never asked for more magnification than was measured`() {
        val s = ZoomGeometry.split(centred(0.05f), lut(1f, 2f)) // want 20x, only 2x measured
        assertEquals("capped at the top measured sample", 2f, s.hwRatio, TOL)
        // GL covers the remaining 10x, which the reported residual pins at its ceiling - the
        // rendering is not capped, only this readout is.
        assertEquals(ZoomGeometry.MAX_GL_RESIDUAL, s.glResidual, 1e-3f)
        assertRectEquals(centred(0.05f), ViewportRect.composeWithViewport(s.glRect, s.deliveredCrop))
    }

    @Test
    fun `with no zoom the GL rect is just the aspect trim, as it always was`() {
        // 16:9 output from a 4:3 sensor. The un-zoomed view IS the trim, so the shader rect must
        // come out as the same centred crop the pipeline rendered before zoom existed, and count
        // as no residual zoom at all.
        val view = ZoomGeometry.defaultViewSensor(texCropX = 1f, texCropY = 0.75f)
        val s = ZoomGeometry.split(view, emptyList(), texCropX = 1f, texCropY = 0.75f)
        assertRectEquals(RectNorm(0f, 0.125f, 1f, 0.875f), s.glRect)
        assertEquals("the aspect trim is not zoom", 1f, s.glResidual, 1e-3f)
        assertEquals(1f, s.hwRatio, TOL)
    }

    @Test
    fun `residual zoom is measured on top of the aspect trim, not including it`() {
        // A 2x view within a 16:9-from-4:3 trim, done entirely in GL.
        val view = ZoomGeometry.viewToSensor(centred(0.5f), texCropX = 1f, texCropY = 0.75f)
        val s = ZoomGeometry.split(view, emptyList(), texCropX = 1f, texCropY = 0.75f)
        assertEquals(2f, s.glResidual, 1e-3f)
    }

    @Test
    fun `the HAL is asked for a sensor-aspect crop even though the view is not`() {
        val view = ZoomGeometry.viewToSensor(centred(0.5f), texCropX = 1f, texCropY = 0.75f)
        val s = ZoomGeometry.split(view, lut(1f, 2f), texCropX = 1f, texCropY = 0.75f)
        // A 2x hardware crop is 0.5 x 0.5 of the array; the view needs 0.5 wide x 0.375 tall, whose
        // sensor-aspect crop is 0.5 x 0.5 - so 2x is exactly usable and GL is left with the trim.
        assertEquals(2f, s.hwRatio, TOL)
        assertEquals("GL back to just trimming", 1f, s.glResidual, 1e-3f)
        val back = ViewportRect.composeWithViewport(s.glRect, s.deliveredCrop)
        assertRectEquals(view, back)
    }

    // ---- the GL uniform's Y axis ----

    @Test
    fun `the uniform flips Y, because GL addresses the bottom edge and a view rect addresses the top`() {
        // A view hugging the TOP of what the user sees.
        val top = RectNorm(0f, 0f, 0.25f, 0.25f)
        val u = ZoomGeometry.texRectUniform(top, texCropX = 1f, texCropY = 0.75f)
        val rect = ZoomGeometry.shaderRect(top, texCropX = 1f, texCropY = 0.75f)
        assertEquals("x is untouched", rect.l, u[0], TOL)
        assertEquals("width untouched", rect.r - rect.l, u[2], TOL)
        assertEquals("height untouched", rect.b - rect.t, u[3], TOL)
        assertEquals("y is measured from the far edge", 1f - rect.b, u[1], TOL)
        // Concretely: the top quarter sits at t=0.125..0.3125, so bottom-up it starts at 0.6875.
        assertEquals(0.6875f, u[1], TOL)
    }

    @Test
    fun `a centred crop is unchanged by the flip - which is why this bug stayed hidden`() {
        // Every crop before zoom existed was centred, and a centred crop satisfies 1 - b == t
        // exactly, so both Y conventions agreed and nothing could tell them apart. This also
        // guarantees the flip cannot regress the un-zoomed path.
        for (tcy in listOf(1f, 0.75f, 0.421875f, 0.5f)) {
            val u = ZoomGeometry.texRectUniform(null, texCropX = 1f, texCropY = tcy)
            val rect = ZoomGeometry.shaderRect(null, texCropX = 1f, texCropY = tcy)
            assertEquals("centred crop: flipped y must equal t (texCropY=$tcy)", rect.t, u[1], TOL)
        }
    }

    @Test
    fun `top and bottom selections land on opposite ends, not the same one`() {
        val top = ZoomGeometry.texRectUniform(RectNorm(0f, 0f, 0.5f, 0.5f), 1f, 0.5f)
        val bottom = ZoomGeometry.texRectUniform(RectNorm(0f, 0.5f, 0.5f, 1f), 1f, 0.5f)
        assertTrue(
            "a top-of-frame selection must sample HIGHER up the bottom-up axis than a bottom one",
            top[1] > bottom[1],
        )
    }

    // ---- frame space <-> sensor space ----

    @Test
    fun `the un-zoomed view in sensor space is the aspect trim itself`() {
        // 16:9 output from a 4:3 sensor trims the height to 0.75.
        val v = ZoomGeometry.viewToSensor(RectNorm(0f, 0f, 1f, 1f), texCropX = 1f, texCropY = 0.75f)
        assertRectEquals(RectNorm(0f, 0.125f, 1f, 0.875f), v)
    }

    @Test
    fun `a zoomed frame-space view maps inside the aspect trim`() {
        val view = RectNorm(0f, 0f, 0.5f, 0.5f) // top-left quarter of what is displayed
        val v = ZoomGeometry.viewToSensor(view, texCropX = 1f, texCropY = 0.75f)
        assertRectEquals(RectNorm(0f, 0.125f, 0.5f, 0.5f), v)
    }

    @Test
    fun `the crop asked of the HAL is grown back to the sensor aspect`() {
        val view = RectNorm(0f, 0.125f, 0.5f, 0.5f)
        val crop = ZoomGeometry.sensorAspectCropFor(view, texCropX = 1f, texCropY = 0.75f)
        val (w, h) = side(crop)
        assertEquals("width unchanged", 0.5f, w, TOL)
        assertEquals("height grown by 1/0.75", 0.5f, h, TOL)
    }
}
