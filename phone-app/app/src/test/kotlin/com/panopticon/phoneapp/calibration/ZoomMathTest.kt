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
    fun `positionHonored flags a recentred crop`() {
        val requested = ZoomMath.offsetCropForRatio(active, 2f, 0.6f, 0.6f)
        val recentred = ZoomMath.centeredCropForRatio(active, 2f)
        assertTrue(ZoomMath.positionHonored(requested, requested, tolPx = 40))
        assertFalse(ZoomMath.positionHonored(requested, recentred, tolPx = 40))
    }

    @Test
    fun `varianceOfLaplacian is high on a checkerboard and near zero on a flat field`() {
        val w = 64
        val h = 64
        val flat = ByteArray(w * h) { 128.toByte() }
        val checker = ByteArray(w * h) { i ->
            val x = i % w; val y = i / w
            (if ((x + y) % 2 == 0) 0 else 255).toByte()
        }
        val flatVar = ZoomMath.varianceOfLaplacian(flat, w, h, w, 48)
        val checkerVar = ZoomMath.varianceOfLaplacian(checker, w, h, w, 48)
        assertEquals(0.0, flatVar, 1e-6)
        assertTrue("checker=$checkerVar", checkerVar > 10_000.0)
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
    fun `deriveQualityCollapse returns the first ratio below the drop threshold`() {
        val ratios = listOf(1f, 2f, 3f, 4f, 5f)
        val rel = listOf(1.0, 0.9, 0.7, 0.5, 0.3)
        assertEquals(4f, ZoomMath.deriveQualityCollapse(ratios, rel)!!, 1e-4f)
        assertNull(ZoomMath.deriveQualityCollapse(ratios, listOf(1.0, 1.0, 0.95, 0.9, 0.8)))
    }
}
