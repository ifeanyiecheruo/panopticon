package com.panopticon.phoneapp.calibration

import com.panopticon.phoneapp.calibration.ZoomMath.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {

    private val active = IntRect(0, 0, 4000, 3000)

    @Test
    fun `centeredCropForRatio shrinks symmetrically`() {
        val c = ZoomMath.centeredCropForRatio(active, 2f)
        assertEquals(2000, c.width)
        assertEquals(1500, c.height)
        assertEquals(2000f, c.centerX, 0.5f)
        assertEquals(1500f, c.centerY, 0.5f)

        val whole = ZoomMath.centeredCropForRatio(active, 1f)
        assertEquals(active.width, whole.width)
        assertEquals(active.height, whole.height)
    }

    @Test
    fun `offsetCropForRatio shifts toward a corner but stays in-bounds`() {
        val off = ZoomMath.offsetCropForRatio(active, 2f, 1f, 1f)
        // full positive slack => crop pinned to the bottom-right
        assertEquals(active.right, off.right)
        assertEquals(active.bottom, off.bottom)
        assertEquals(2000, off.width)
    }

    @Test
    fun `normalize expresses a rect as fractions of the active array`() {
        val n = ZoomMath.normalize(IntRect(1000, 750, 3000, 2250), active)
        assertEquals(0.25f, n.l, 1e-4f)
        assertEquals(0.25f, n.t, 1e-4f)
        assertEquals(0.75f, n.r, 1e-4f)
        assertEquals(0.75f, n.b, 1e-4f)
    }

    @Test
    fun `ratioFromCrop inverts centeredCropForRatio`() {
        for (r in listOf(1f, 1.5f, 2f, 4f, 8f)) {
            val c = ZoomMath.centeredCropForRatio(active, r)
            assertEquals(r, ZoomMath.ratioFromCrop(c, active), 0.03f)
        }
    }

    @Test
    fun `ratioHonored tolerates HAL quantisation but rejects a real miss`() {
        assertTrue(ZoomMath.ratioHonored(4f, 4.1f))
        assertFalse(ZoomMath.ratioHonored(4f, 2f))
        assertFalse(ZoomMath.ratioHonored(4f, null))
    }

    @Test
    fun `positionMetadataMatch compares reported crop centre to the request`() {
        val requested = ZoomMath.offsetCropForRatio(active, 2f, 0.6f, 0.6f)
        val recentred = ZoomMath.centeredCropForRatio(active, 2f)
        assertTrue(ZoomMath.positionMetadataMatch(requested, requested, tolPx = 40))
        assertFalse(ZoomMath.positionMetadataMatch(requested, recentred, tolPx = 40))
        assertFalse(ZoomMath.positionMetadataMatch(requested, null, tolPx = 40))
    }

    @Test
    fun `frameShifted is true when the pixels actually differ, false when near-identical`() {
        val w = 96
        val h = 72
        // A vertical gradient scene.
        val centred = ByteArray(w * h) { i -> ((i % w) * 255 / w).toByte() }
        // Same scene shifted left by ~20px (what an off-centre crop toward +x looks like).
        val shifted = ByteArray(w * h) { i ->
            val x = (i % w + 20).coerceAtMost(w - 1); val y = i / w
            (x * 255 / w).toByte()
        }
        val noiseOnly = ByteArray(w * h) { i -> ((i % w) * 255 / w + ((i * 7) % 3) - 1).coerceIn(0, 255).toByte() }

        assertTrue(ZoomMath.frameShifted(centred, shifted, w, h, w) == true)
        assertTrue(ZoomMath.frameShifted(centred, noiseOnly, w, h, w) == false)
    }

    @Test
    fun `frameShifted returns null on a too-dark scene`() {
        val w = 64; val h = 48
        val darkA = ByteArray(w * h) { 2 }
        val darkB = ByteArray(w * h) { 3 }
        assertNull(ZoomMath.frameShifted(darkA, darkB, w, h, w))
    }

    @Test
    fun `sharpness is high on a checkerboard, ~zero on flat, and brightness-normalised`() {
        val w = 64
        val h = 64
        fun checker(level: Int) = ByteArray(w * h) { i ->
            val x = i % w; val y = i / w
            (if ((x + y) % 2 == 0) 0 else level).toByte()
        }
        val flat = ByteArray(w * h) { 128.toByte() }
        assertEquals(0.0, ZoomMath.sharpness(flat, w, h, w, 48), 1e-9)
        val dim = ZoomMath.sharpness(checker(120), w, h, w, 48)
        val bright = ZoomMath.sharpness(checker(240), w, h, w, 48)
        assertTrue("dim=$dim", dim > 0.5)
        // Same relative texture at 2x brightness => normalised score within ~2x.
        assertTrue("dim=$dim bright=$bright", bright in (dim * 0.4)..(dim * 2.5))
    }

    @Test
    fun `medianSharpness ignores a single outlier`() {
        assertEquals(3.0, ZoomMath.medianSharpness(listOf(3.0, 3.1, 2.9, 99.0)), 0.3)
        assertEquals(0.0, ZoomMath.medianSharpness(emptyList()), 0.0)
    }

    @Test
    fun `deriveOpticalDigitalSplit finds the crossover from active-physical-id`() {
        val ratios = listOf(1f, 1.5f, 2f, 3f, 5f, 8f)
        // ultrawide -> wide -> tele at ratio 3, then digital.
        val ids = listOf<String?>("2", "2", "0", "3", "3", "3")
        val split = ZoomMath.deriveOpticalDigitalSplit(ratios, ids, ratios.map { null })
        assertEquals("active-physical-id", split.method)
        assertEquals(3f, split.crossoverRatio!!, 1e-4f)
        assertEquals(1f, split.opticalRange.lo, 1e-4f)
        assertEquals(3f, split.opticalRange.hi, 1e-4f)
        assertEquals(8f, split.digitalRange.hi, 1e-4f)
    }

    @Test
    fun `deriveOpticalDigitalSplit treats a single sensor as all-digital`() {
        val ratios = listOf(1f, 2f, 4f, 8f)
        val split = ZoomMath.deriveOpticalDigitalSplit(ratios, ratios.map { null }, ratios.map { 4.38f })
        assertEquals("single-camera", split.method)
        assertNull(split.crossoverRatio)
        assertEquals(1f, split.opticalRange.lo, 1e-4f)
        assertEquals(1f, split.opticalRange.hi, 1e-4f)
        assertEquals(1f, split.digitalRange.lo, 1e-4f)
        assertEquals(8f, split.digitalRange.hi, 1e-4f)
    }

    @Test
    fun `deriveQualityCollapse needs a sustained drop, not a single dip`() {
        val ratios = listOf(1f, 2f, 3f, 4f, 5f, 6f)
        // Sustained drop below 0.5 from ratio 4x onward.
        assertEquals(4f, ZoomMath.deriveQualityCollapse(ratios, listOf(1.0, 0.9, 0.7, 0.4, 0.3, 0.25))!!, 1e-4f)
        // A single noisy dip at 3x that recovers => not a collapse.
        assertNull(ZoomMath.deriveQualityCollapse(ratios, listOf(1.0, 0.9, 0.3, 0.9, 0.85, 0.8)))
        // Never drops => null.
        assertNull(ZoomMath.deriveQualityCollapse(ratios, listOf(1.0, 1.0, 0.9, 0.8, 0.7, 0.6)))
    }
}
