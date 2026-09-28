package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Pixel 6's actual `outputResolutions`, so the sizes these tests pick are ones a real
 *  device offers rather than convenient round numbers. Plain pairs, not `android.util.Size`,
 *  whose accessors throw "not mocked" here - see [CameraFramingTest]. */
private val PIXEL6 = listOf(
    3840 to 2160, 3264 to 1836, 2688 to 1512, 1920 to 1080,
    1792 to 1008, 1280 to 720, 1024 to 576, 640 to 360,
)

private const val UHD_W = 3840
private const val UHD_H = 2160

/** A view covering `f` of each axis, i.e. a zoom of 1/f, placed off-centre to make sure position
 *  never enters into it - only the extent does. */
private fun view(f: Float, l: Float = 0.2f, t: Float = 0.3f) = RectNorm(l, t, l + f, t + f)

class RecordingSizeSelectionTest {

    @Test
    fun `un-zoomed records at the viewing size`() {
        assertEquals(UHD_W to UHD_H, RecordingSizeSelection.recordingSizeFor(PIXEL6, UHD_W, UHD_H, 1f, 1f))
    }

    @Test
    fun `zoomed in, picks the smallest size that still holds the detail`() {
        // 4.19x: 3840/4.19 = 917 across, 2160/4.19 = 516 down. 1024x576 covers both; 640x360
        // does not, and every larger size is waste.
        val got = RecordingSizeSelection.recordingSizeFor(PIXEL6, UHD_W, UHD_H, 1f / 4.19f, 1f / 4.19f)
        assertEquals(1024 to 576, got)
    }

    @Test
    fun `never exceeds the viewing size in either dimension`() {
        // The ceiling holds even when the viewing size is well below what the zoom would allow:
        // a 1x view of a 1280x720 ceiling may not reach for 3840x2160.
        for (f in listOf(1f, 0.9f, 0.5f, 0.25f, 0.01f)) {
            val (w, h) = RecordingSizeSelection.recordingSizeFor(PIXEL6, 1280, 720, f, f)
            assertTrue("$f -> ${w}x$h", w <= 1280 && h <= 720)
        }
    }

    @Test
    fun `a size that fits but changes shape is rejected`() {
        // 1600x1200 is 4:3. It is smaller than the 16:9 ceiling on both axes and big enough for
        // the demand, but recording it would trim a different field of view, so the 16:9
        // candidate has to win despite being larger in area.
        val mixed = PIXEL6 + (1600 to 1200)
        val got = RecordingSizeSelection.recordingSizeFor(mixed, UHD_W, UHD_H, 0.5f, 0.5f)
        assertEquals(1920 to 1080, got)
    }

    @Test
    fun `each axis is served independently, not by their average`() {
        // A view wide-but-short in frame space: 0.5 across, 0.125 down. Averaging the two (which
        // is what ZoomGeometry.ratioOf does for the slider) would call this 3.2x and pick a size
        // far too small to hold the 1920 pixels the wide axis still carries.
        val (w, h) = RecordingSizeSelection.recordingSizeFor(PIXEL6, UHD_W, UHD_H, 0.5f, 0.125f)
        assertTrue("width must cover 1920, got ${w}x$h", w >= 1920)
    }

    @Test
    fun `falls back to the viewing size when nothing qualifies`() {
        assertEquals(
            UHD_W to UHD_H,
            RecordingSizeSelection.recordingSizeFor(emptyList(), UHD_W, UHD_H, 0.25f, 0.25f),
        )
    }

    @Test
    fun `manual control off means no zoom, so no reduction`() {
        assertNull(RecordingSizeSelection.viewFractionOf(view(0.25f), manualControlEnabled = false))
        assertNull(RecordingSizeSelection.viewFractionOf(null, manualControlEnabled = true))
    }

    @Test
    fun `the view's extent becomes the per-axis fraction`() {
        val (fx, fy) = RecordingSizeSelection.viewFractionOf(view(0.25f), manualControlEnabled = true)!!
        assertEquals(0.25f, fx, 1e-5f)
        assertEquals(0.25f, fy, 1e-5f)
    }

    @Test
    fun `reducing is monotone in zoom`() {
        // Zooming further in can never ask for a bigger recording than zooming in less.
        var previous = Int.MAX_VALUE
        for (f in listOf(1f, 0.75f, 0.5f, 0.3f, 0.2f, 0.1f, 0.05f)) {
            val area = RecordingSizeSelection.recordingSizeFor(PIXEL6, UHD_W, UHD_H, f, f)
                .let { (w, h) -> w * h }
            assertTrue("f=$f area=$area previous=$previous", area <= previous)
            previous = area
        }
    }
}
