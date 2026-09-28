package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOL = 1e-4f

/**
 * The transition between asking the hardware for a zoom and getting it.
 *
 * This is the bug that produced the majority of all recorded clips: the shader adopted the
 * *requested* crop on the frame after the request, while the buffer still held the previous one
 * for another ten frames. What follows pins the property that makes that impossible - the region
 * the viewer sees must not depend on how far through the handover the hardware is.
 */
class ZoomTransitionTest {

    /** A well-behaved camera: every requested ratio is delivered exactly. */
    private fun lut(vararg ratios: Float) =
        ratios.map { ZoomGeometry.LutEntry(requestedRatio = it, deliveredMagnification = it) }

    /**
     * Where the shader's crop lands in *full-frame* terms: the delivered crop composed with the
     * rect sampled inside it. This is the region the viewer actually ends up looking at, and it
     * is the thing that must not move during a transition.
     */
    private fun viewedRegion(split: ZoomGeometry.Split): RectNorm =
        ViewportRect.composeWithViewport(split.glRect, split.deliveredCrop)

    private fun assertRectEquals(message: String, expected: RectNorm, actual: RectNorm) {
        assertEquals("$message (l)", expected.l, actual.l, TOL)
        assertEquals("$message (t)", expected.t, actual.t, TOL)
        assertEquals("$message (r)", expected.r, actual.r, TOL)
        assertEquals("$message (b)", expected.b, actual.b, TOL)
    }

    @Test
    fun `the viewed region is identical at every stage of a hardware zoom handover`() {
        val lut = lut(1.5f, 2f, 3f, 4f)
        // An off-centre selection, so a mistake in placing the view inside the delivered crop
        // shows up as a translation and not only as a scale.
        val view = RectNorm(0.55f, 0.20f, 0.80f, 0.45f)
        val viewSensor = ZoomGeometry.viewToSensor(view, texCropX = 1f, texCropY = 0.75f)

        val requested = ZoomGeometry.split(viewSensor, lut, texCropX = 1f, texCropY = 0.75f)
        assertTrue("the fixture needs the hardware to do some of this zoom", requested.hwRatio > 1f)
        val destination = viewedRegion(requested)

        // Walk the ratio the HAL reports from "not started" to "landed". Ratios beyond the
        // request are not tested because the hardware never reaches them: rule 1 of
        // ZoomGeometry.split is that it is only ever asked for a magnification whose field of
        // view still contains the whole view, so anything in [1, requested] still has the view in
        // it and can be rendered exactly.
        val stages = (0..10).map { 1f + (requested.hwRatio - 1f) * it / 10f }
        stages.forEach { applied ->
            val stage = ZoomGeometry.splitFor(viewSensor, lut, applied, texCropX = 1f, texCropY = 0.75f)
            assertRectEquals("viewed region at applied ratio $applied", destination, viewedRegion(stage))
        }
    }

    @Test
    fun `zooming out reveals the wider view as the buffer widens, instead of jumping to it`() {
        // The one transition that cannot be pixel-exact throughout: going from a tight hardware
        // zoom to a wider view, the buffer genuinely does not contain the extra field of view
        // yet. The best available behaviour is to show as much as the buffer holds and grow into
        // the rest - which is what normaliseWithin's clamp produces, and which still beats
        // rendering the wrong region and snapping.
        val lut = lut(2f, 4f)
        val wideView = RectNorm(0.1f, 0.1f, 0.9f, 0.9f)
        val viewSensor = ZoomGeometry.viewToSensor(wideView, texCropX = 1f, texCropY = 1f)
        val destination = viewedRegion(ZoomGeometry.split(viewSensor, lut))

        var previousWidth = 0f
        listOf(4f, 3f, 2f, 1.5f, 1f).forEach { applied ->
            val region = viewedRegion(ZoomGeometry.splitFor(viewSensor, lut, applied))
            val width = region.r - region.l
            assertTrue("the visible region only ever grows on the way out", width >= previousWidth - TOL)
            assertTrue("and never exceeds the destination", width <= (destination.r - destination.l) + TOL)
            previousWidth = width
        }
        assertRectEquals("and lands exactly on the target", destination, viewedRegion(ZoomGeometry.splitFor(viewSensor, lut, 1f)))
    }

    @Test
    fun `GL gives up work to the hardware as the zoom lands, and the crop is never wrong on the way`() {
        val lut = lut(2f, 4f)
        val view = RectNorm(0.3f, 0.3f, 0.55f, 0.55f)
        val viewSensor = ZoomGeometry.viewToSensor(view, texCropX = 1f, texCropY = 1f)
        val requested = ZoomGeometry.split(viewSensor, lut)

        val before = ZoomGeometry.splitFor(viewSensor, lut, hwRatio = 1f)
        val after = ZoomGeometry.splitFor(viewSensor, lut, hwRatio = requested.hwRatio)

        assertEquals("un-started: the buffer is the whole frame", ZoomGeometry.FULL.r, before.deliveredCrop.r, TOL)
        assertTrue(
            "GL is doing more upscaling before the hardware helps than after",
            before.glResidual > after.glResidual,
        )
        assertRectEquals("same region either way", viewedRegion(before), viewedRegion(after))
    }

    @Test
    fun `an uncalibrated phone asks for nothing, so there is no transition to get wrong`() {
        val view = RectNorm(0.1f, 0.1f, 0.4f, 0.4f)
        val viewSensor = ZoomGeometry.viewToSensor(view, texCropX = 1f, texCropY = 1f)
        val split = ZoomGeometry.split(viewSensor, emptyList())
        assertEquals(1f, split.hwRatio, TOL)
        assertRectEquals(
            "splitFor at 1.0 is the same thing split() already produced",
            viewedRegion(split),
            viewedRegion(ZoomGeometry.splitFor(viewSensor, emptyList(), 1f)),
        )
    }

    // ---- ZoomFeedback ----

    @Test
    fun `a frame is matched to the zoom reported for that frame, not the newest one`() {
        val fb = ZoomFeedback()
        fb.record(sensorTimestampNs = 1_000L, hwRatio = 1.0f)
        fb.record(sensorTimestampNs = 2_000L, hwRatio = 1.6f)
        fb.record(sensorTimestampNs = 3_000L, hwRatio = 2.4f)

        assertEquals(1.0f, fb.appliedRatio(1_000L, requested = 4f), TOL)
        assertEquals(1.6f, fb.appliedRatio(2_500L, requested = 4f), TOL)
        assertEquals(2.4f, fb.appliedRatio(9_999L, requested = 4f), TOL)
        // A frame older than anything recorded: the oldest reading is the closest we have, and is
        // certainly better than assuming the request landed.
        assertEquals(1.0f, fb.appliedRatio(10L, requested = 4f), TOL)
    }

    @Test
    fun `before any result arrives GL does all the zoom rather than assuming the request landed`() {
        val fb = ZoomFeedback()
        assertEquals(1f, fb.appliedRatio(1_000L, requested = 4f), TOL)
    }

    @Test
    fun `a camera that reports nothing usable eventually falls back to trusting the request`() {
        val fb = ZoomFeedback()
        repeat(ZoomFeedback.UNREADABLE_BEFORE_TRUSTING_REQUEST.toInt() - 1) { fb.record(it.toLong(), null) }
        assertEquals("not yet - still hoping for a reading", 1f, fb.appliedRatio(1_000L, requested = 4f), TOL)
        fb.record(999L, null)
        assertEquals(4f, fb.appliedRatio(1_000L, requested = 4f), TOL)
    }

    @Test
    fun `the ring keeps the newest readings once it wraps`() {
        val fb = ZoomFeedback(capacity = 4)
        repeat(10) { fb.record(sensorTimestampNs = it * 1_000L, hwRatio = 1f + it) }
        // Entries 0..5 have been overwritten; the newest four (6..9) remain.
        assertEquals(1f + 9, fb.appliedRatio(9_000L, requested = 99f), TOL)
        assertEquals(1f + 6, fb.appliedRatio(6_500L, requested = 99f), TOL)
        // A query older than everything held falls back to the oldest surviving entry, not to the
        // request.
        assertNotEquals(99f, fb.appliedRatio(0L, requested = 99f))
        assertEquals(1f + 6, fb.appliedRatio(0L, requested = 99f), TOL)
    }

    @Test
    fun `clearing forgets the readings and the verdict about the device`() {
        val fb = ZoomFeedback()
        repeat(ZoomFeedback.UNREADABLE_BEFORE_TRUSTING_REQUEST.toInt()) { fb.record(it.toLong(), null) }
        assertEquals(4f, fb.appliedRatio(1_000L, requested = 4f), TOL)
        fb.clear()
        assertEquals(1f, fb.appliedRatio(1_000L, requested = 4f), TOL)
    }
}
