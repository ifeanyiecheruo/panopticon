package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import com.panopticon.phoneapp.camera.AreaTuning.LumaPlane
import com.panopticon.phoneapp.camera.AreaTuning.Nudger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaTuningTest {

    private val whole = RectNorm(0f, 0f, 1f, 1f)

    /** Run [trials] trials of [n] against [score], as the tuner does: measure the current
     *  setting, try the next, keep it or go back. Returns every value tried. */
    private fun climb(n: Nudger, trials: Int, score: (Int, Int) -> Double): List<Int> {
        val tried = mutableListOf<Int>()
        for (i in 0 until trials) {
            val c = n.next() ?: break
            tried += c
            n.result(c, score(n.value, i), score(c, i))
        }
        return tried
    }

    @Test
    fun `climbs from auto's own setting to the best nearby, a step at a time`() {
        val n = Nudger(-6, 6) { 0.02 }
        val tried = climb(n, 20) { v, _ -> -0.1 * (v - 3) * (v - 3) }
        assertEquals(3, n.value)
        // One step from what was kept, every time.
        assertTrue(tried.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) <= 2 })
        assertTrue("settled at its best both ways", n.misses >= 2)
    }

    @Test
    fun `stays within its range of auto's own`() {
        val n = Nudger(-2, 2) { 0.02 }
        val tried = climb(n, 20) { v, _ -> v.toDouble() }
        assertEquals(2, n.value)
        assertTrue(tried.all { it in -2..2 })
    }

    @Test
    fun `leaves auto alone when no step measurably helps`() {
        val n = Nudger(-6, 6) { 0.02 }
        // An unchanged scene's wobble, inside the margin.
        climb(n, 20) { v, i -> 5.0 + 0.005 * ((v + i) % 2) }
        assertEquals(0, n.value)
    }

    @Test
    fun `follows a best setting that drifts`() {
        val n = Nudger(-8, 8) { 0.02 }
        // The best moves from 0 to 4 over the trials, as the light changes.
        climb(n, 60) { v, i -> val best = minOf(4, i / 10); -0.1 * (v - best) * (v - best) }
        assertEquals(4, n.value)
    }

    @Test
    fun `waits longer once both ways have failed`() {
        val n = Nudger(-6, 6) { 0.02 }
        assertEquals(10_000L, n.waitMs(10_000, 300_000))
        climb(n, 2) { _, _ -> 1.0 }
        assertEquals(2, n.misses)
        assertEquals(20_000L, n.waitMs(10_000, 300_000))
        climb(n, 20) { _, _ -> 1.0 }
        assertEquals(300_000L, n.waitMs(10_000, 300_000))
        // A step that pays off goes back to the short wait.
        n.result(n.next()!!, 1.0, 2.0)
        assertEquals(10_000L, n.waitMs(10_000, 300_000))
    }

    @Test
    fun `exposure is biased by at most a stop of compensation`() {
        fun caps(lo: Int, hi: Int, stepMilliEv: Int) = CameraCapabilities(
            cameraId = "0",
            zoomRatioRange = FloatRange2(1f, 1f),
            zoomViaRatioApi = false,
            aeCompensationRange = IntRange2(lo, hi),
            aeCompensationStepMilliEv = stepMilliEv,
        )
        // A Pixel: +-4 EV in sixths.
        val pixel = AreaTuning.exposureNudger(caps(-24, 24, 166))!!
        assertEquals(-6, pixel.lo)
        assertEquals(6, pixel.hi)
        // Thirds, but only +-2/3 EV available.
        val narrow = AreaTuning.exposureNudger(caps(-2, 2, 333))!!
        assertEquals(-2, narrow.lo)
        assertEquals(2, narrow.hi)
        // On top of the user's own compensation, within the camera's range: +3 1/3 EV leaves
        // only +2/3 EV above it.
        val high = AreaTuning.exposureNudger(caps(-24, 24, 166), base = 20)!!
        assertEquals(-6, high.lo)
        assertEquals(4, high.hi)
        assertNull("no compensation", AreaTuning.exposureNudger(caps(0, 0, 166)))
        assertNull("no step", AreaTuning.exposureNudger(caps(-24, 24, 0)))
    }

    @Test
    fun `a way that misbehaved is never tried again`() {
        val n = Nudger(-6, 6) { 0.02 }
        n.result(n.next()!!, 5.0, 6.0) // +1 kept
        n.stopGoing(+1)
        assertEquals(1, n.hi)
        // It can only go back down now.
        repeat(10) { n.next()?.let { c -> assertTrue("tried $c", c <= 1); n.result(c, 5.0, 4.0) } }
        assertEquals(1, n.value)
    }

    @Test
    fun `a clipped area is not pushed further into the clip`() {
        val fine = AreaTuning.Tones(6.2, 60, 239, 140, 0.0, 0.004, 1000)
        assertTrue(AreaTuning.exposureAllows(fine, +1))
        assertTrue(AreaTuning.exposureAllows(fine, -1))
        // The Pixel 7 Pro's area under AE metering: its top 1% at white.
        val white = fine.copy(clipHi = 0.03)
        assertFalse("brightening a clipped area ran auto exposure away", AreaTuning.exposureAllows(white, +1))
        assertTrue(AreaTuning.exposureAllows(white, -1))
        assertFalse(AreaTuning.exposureAllows(fine.copy(clipLo = 0.05), -1))
    }

    @Test
    fun `auto exposure following a step, or running away from it`() {
        val sixth = 1.0 / 6
        // Measured on the Pixel 7 Pro: a sixth of a stop moved the exposure 0.14-0.24 stops when
        // it followed, and 2 stops and climbing when it ran away.
        assertTrue(0.24 < AreaTuning.maxExposureResponse(sixth))
        assertTrue(2.0 > AreaTuning.maxExposureResponse(sixth))
        assertEquals(AreaTuning.maxExposureResponse(sixth), AreaTuning.maxExposureResponse(-sixth), 0.0)
    }

    @Test
    fun `the watchdog ignores a scene's wobble but not a real drift`() {
        // The wobble a trial's margin allows is no drift.
        assertFalse(AreaTuning.contrastDrifted(6.30, 6.30 - AreaTuning.EXPOSURE_GAIN_BITS))
        assertFalse(AreaTuning.contrastDrifted(6.30, 6.25))
        assertTrue(AreaTuning.contrastDrifted(6.30, 6.15))
        // Better than tuned is never a reason to start over.
        assertFalse(AreaTuning.contrastDrifted(6.30, 6.60))
        assertFalse(AreaTuning.sharpnessDrifted(1000.0, 1000.0 * (1 - AreaTuning.FOCUS_GAIN_SHARE)))
        assertTrue(AreaTuning.sharpnessDrifted(1000.0, 800.0))
        assertFalse("nothing measured when tuned", AreaTuning.sharpnessDrifted(0.0, 0.0))
    }

    @Test
    fun `focus steps are a fine fraction of the lens's range`() {
        assertEquals(10f / 320, AreaTuning.focusStep(10f), 1e-6f)
        assertEquals(0.005f, AreaTuning.focusStep(0.1f), 1e-6f)
        // Sharpness margins are relative: 3% of a big score is a big step.
        val n = AreaTuning.focusNudger()
        n.result(n.next()!!, 1000.0, 1020.0)
        assertEquals(0, n.value)
        n.result(n.next()!!, 1000.0, 1040.0)
        assertEquals(-1, n.value)
    }

    @Test
    fun `tones and sharpness`() {
        val flat = LumaPlane(10, 10, ByteArray(100) { 128.toByte() })
        val t = AreaTuning.tones(flat, whole)!!
        assertEquals(0.0, t.entropy, 0.0)
        assertEquals(128, t.median)
        assertEquals(0.0, AreaTuning.sharpness(flat), 0.0)

        val half = LumaPlane(10, 10, ByteArray(100) { if (it >= 50) 255.toByte() else 0 })
        val h = AreaTuning.tones(half, whole)!!
        assertEquals(1.0, h.entropy, 1e-9)
        assertEquals(0.5, h.clipHi, 1e-9)
        assertEquals(0.5, h.clipLo, 1e-9)
        val top = AreaTuning.tones(half, RectNorm(0f, 0f, 1f, 0.5f))!!
        assertEquals(0.0, top.entropy, 0.0)
        assertEquals(50, top.pixels)
        assertNull(AreaTuning.tones(half, RectNorm(0.5f, 0.5f, 0.5f, 0.9f)))

        val stripes = LumaPlane(20, 20, ByteArray(400) { if ((it % 20) / 2 % 2 == 0) 200.toByte() else 0 })
        assertTrue(AreaTuning.sharpness(stripes) > 0)
    }

    @Test
    fun `the area within its grown region`() {
        val area = RectNorm(0.4f, 0.4f, 0.6f, 0.6f)
        val grown = AreaTuning.grow(area, AreaTuning.FOCUS_MARGIN)
        assertEquals(RectNorm(0.35f, 0.35f, 0.65f, 0.65f).l, grown.l, 1e-6f)
        val inner = AreaTuning.within(area, grown)
        assertEquals(1f / 6, inner.l, 1e-5f)
        assertEquals(5f / 6, inner.r, 1e-5f)
        // Clamped to the frame at the edges.
        assertEquals(0f, AreaTuning.grow(RectNorm(0.01f, 0f, 0.2f, 0.2f), 0.05f).l, 0f)
    }

}
