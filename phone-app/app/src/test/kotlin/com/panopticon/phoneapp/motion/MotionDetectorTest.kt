package com.panopticon.phoneapp.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses a 32x24 buffer with rowStride == width * pixelStride, so the detector's 32x24
 * grid maps one-to-one onto pixels: flipping exactly K pixels changes exactly K grid
 * cells, i.e. a changed fraction of K/768.
 */
class MotionDetectorTest {
    private val w = 32
    private val h = 24
    private val cells = w * h

    private fun frame(fill: Int) = ByteArray(cells) { fill.toByte() }

    /** Copy of [base] with the first [k] bytes set to [value]. */
    private fun frameWithChangedCells(base: ByteArray, k: Int, value: Int) =
        base.copyOf().also { for (i in 0 until k) it[i] = value.toByte() }

    private fun MotionDetector.acceptFrame(luma: ByteArray) = accept(luma, w, h, w)

    /** A detector with short lag/noise windows, fed enough still frames of [still] for its noise
     *  estimate to be ready: warm-up, a full lag, then its minimum samples. */
    private fun settled(sens: String, still: (Int) -> ByteArray = { frame(100) }) =
        MotionDetector(sens, referenceLagFrames = 5, noiseMinSamples = 5).also { d ->
            repeat(3 + 5 + 5 + 2) { t -> d.acceptFrame(still(t)) }
        }

    // ---- coarse robustness ----

    @Test
    fun `a frame-wide exposure step is the camera, not motion`() {
        // Auto-exposure hunting at night: every cell steps by the same amount at once.
        for (sens in listOf("low", "medium", "high")) {
            assertFalse(sens, settled(sens).acceptFrame(frame(115)).motion)
        }
    }

    @Test
    fun `a light switching on is still motion`() {
        // A much bigger step than any exposure hunting - all but MAX_EXPOSURE_SHIFT of it counts.
        for (sens in listOf("low", "medium", "high")) {
            assertTrue(sens, settled(sens).acceptFrame(frame(200)).motion)
        }
    }

    @Test
    fun `scattered specks do not add up to a changed region`() {
        // 48 cells changed, but no two touching: noise, not a patch.
        val specks = frame(100).also { for (i in 0 until 48) it[(i / 8) * 4 * w + (i % 8) * 4] = 250.toByte() }
        assertFalse(settled("medium").acceptFrame(specks).motion)
        // The same count as one patch (3 rows of 16) is.
        val patch = frame(100).also { for (y in 0 until 3) for (x in 0 until 16) it[y * w + x] = 250.toByte() }
        assertTrue(settled("medium").acceptFrame(patch).motion)
    }

    @Test
    fun `cell noise raises the bar for the coarse test`() {
        // Rows 0-7 flicker by 12 between frames (a quarter of the frame, so the frame-wide shift
        // stays 0); the rest is still. A +30 patch is lost in the flicker, not in the still part.
        fun still(t: Int) = frame(100).also { for (y in 0 until 8) for (x in 0 until w) it[y * w + x] = (100 + if ((x + t) % 2 == 0) 6 else -6).toByte() }
        var t = 0
        val d = settled("medium") { i -> t = i + 1; still(i) }
        fun patched(y0: Int) = still(t++).also { for (y in y0 until y0 + 3) for (x in 0 until 16) it[y * w + x] = ((it[y * w + x].toInt() and 0xFF) + 30).toByte() }
        assertFalse("inside the flicker", d.acceptFrame(patched(2)).motion)
        assertTrue("in the still part", d.acceptFrame(patched(12)).motion)
    }

    @Test
    fun `identical frames never register motion`() {
        val d = MotionDetector("medium")
        val f = frame(120)
        repeat(6) { d.acceptFrame(f) }
        val r = d.acceptFrame(f)
        assertFalse(r.motion)
        assertEquals(0.0, r.changedFraction, 0.0)
    }

    @Test
    fun `first frames are swallowed as warmup`() {
        val d = MotionDetector("high")
        // Even a huge change on frame 2 is ignored (warmupFrames default 3).
        d.acceptFrame(frame(0))
        assertFalse(d.acceptFrame(frame(255)).motion)
    }

    @Test
    fun `a whole-frame brightness jump is motion at every sensitivity`() {
        for (sens in listOf("low", "medium", "high")) {
            val d = MotionDetector(sens)
            val dark = frame(40)
            repeat(4) { d.acceptFrame(dark) }
            assertTrue("sensitivity=$sens", d.acceptFrame(frame(220)).motion)
        }
    }

    @Test
    fun `a small global shift below the pixel-delta threshold is ignored`() {
        val d = MotionDetector("high")
        repeat(4) { d.acceptFrame(frame(100)) }
        // +8 per pixel, below high's cell delta of 10.
        assertFalse(d.acceptFrame(frame(108)).motion)
    }

    @Test
    fun `higher sensitivity trips on a smaller moving region`() {
        val base = frame(100)

        fun freshDetector(sens: String) = MotionDetector(sens).also { repeat(4) { _ -> it.acceptFrame(base) } }

        val k12 = frameWithChangedCells(base, 12, 255) // 12/768 = 0.0156: only "high" (0.015)
        assertTrue(freshDetector("high").acceptFrame(k12).motion)
        assertFalse(freshDetector("medium").acceptFrame(k12).motion)
        assertFalse(freshDetector("low").acceptFrame(k12).motion)

        val k31 = frameWithChangedCells(base, 31, 255) // 31/768 = 0.0404: "medium" (0.040)
        assertTrue(freshDetector("medium").acceptFrame(k31).motion)
        assertFalse(freshDetector("low").acceptFrame(k31).motion)

        val k62 = frameWithChangedCells(base, 62, 255) // 62/768 = 0.0807: "low" (0.080)
        assertTrue(freshDetector("low").acceptFrame(k62).motion)
    }

    @Test
    fun `reset drops the reference frame so the next frame is warmup again`() {
        val d = MotionDetector("high")
        repeat(5) { d.acceptFrame(frame(80)) }
        d.reset()
        // Back in warmup: a big change right after reset is not reported.
        assertFalse(d.acceptFrame(frame(240)).motion)
    }

    @Test
    fun `changing sensitivity keeps the reference frame`() {
        // Callers used to pick up a changed setting by rebuilding the detector on a timer, which
        // silently dropped the reference and re-ran the warm-up every time - blind for a few
        // frames every refresh, whether or not the setting had actually changed.
        val d = MotionDetector("low")
        repeat(5) { d.acceptFrame(frame(80)) }
        d.sensitivity = "high"

        val k12 = frameWithChangedCells(frame(80), 12, 255) // 0.0156: trips "high", not "low"
        val r = d.acceptFrame(k12)
        assertTrue("the new threshold is in force immediately", r.motion)
        assertEquals("and against the frames before it, not a fresh warm-up", 12.0 / 768.0, r.changedFraction, 1e-9)
    }

    @Test
    fun `re-setting the same sensitivity is a no-op`() {
        val d = MotionDetector("medium")
        repeat(5) { d.acceptFrame(frame(80)) }
        repeat(10) { d.sensitivity = "medium" }
        assertTrue(d.acceptFrame(frame(240)).motion)
    }

    // ---- colour ----

    private fun rgba(r: Int, g: Int, b: Int) = ByteArray(cells * 4).also {
        for (p in 0 until cells) { it[p * 4] = r.toByte(); it[p * 4 + 1] = g.toByte(); it[p * 4 + 2] = b.toByte(); it[p * 4 + 3] = -1 }
    }

    private fun MotionDetector.acceptRgba(px: ByteArray) = accept(px, w, h, w * 4, pixelStride = 4)

    @Test
    fun `a change of colour alone is motion`() {
        // The case that motivated per-channel comparison: a window losing a blue glow, with
        // brightness (green) all but unchanged.
        val d = MotionDetector("high")
        val before = rgba(80, 80, 95)
        repeat(4) { d.acceptRgba(before) }
        val after = before.copyOf().also { for (p in 0 until 20) it[p * 4 + 2] = 80.toByte() } // blue -15 in 20 cells
        assertTrue(d.acceptRgba(after).motion)
    }

    @Test
    fun `alpha is not compared`() {
        val d = MotionDetector("high")
        repeat(4) { d.acceptRgba(rgba(80, 80, 80)) }
        val alphaOnly = rgba(80, 80, 80).also { for (p in 0 until cells) it[p * 4 + 3] = 0 }
        assertFalse(d.acceptRgba(alphaOnly).motion)
    }

    // ---- lagged reference ----

    @Test
    fun `a change too slow to see frame to frame is caught against the lagged reference`() {
        // +2 a frame never crosses a cell delta between neighbours, but by the eighth frame it is
        // +16 against a reference from before it started.
        val d = MotionDetector("high")
        repeat(4) { d.acceptFrame(frame(100)) }
        var motion = false
        for (step in 1..8) motion = d.acceptFrame(frameWithChangedCells(frame(100), 40, 100 + 2 * step)).motion
        assertTrue(motion)
    }

    @Test
    fun `a change that has stopped stops being motion once it is older than the lag`() {
        val d = MotionDetector("high", referenceLagFrames = 5)
        repeat(4) { d.acceptFrame(frame(100)) }
        val moved = frame(200)
        assertTrue(d.acceptFrame(moved).motion)
        repeat(4) { assertTrue("still differs from a reference before the change", d.acceptFrame(moved).motion) }
        assertFalse("the reference has caught up", d.acceptFrame(moved).motion)
    }

    @Test
    fun `warm-up frames never become the reference`() {
        // Exposure is still converging during warm-up; comparing against those frames for the
        // next second would report the convergence itself as motion.
        val d = MotionDetector("high", referenceLagFrames = 10, warmupFrames = 3)
        d.acceptFrame(frame(20))
        d.acceptFrame(frame(60))
        d.acceptFrame(frame(100)) // last warm-up frame: settled
        assertFalse(d.acceptFrame(frame(100)).motion)
    }

    // ---- fine block test ("high" only) ----

    private val rw = 160
    private val rh = 120

    /** A 160x120 luma frame - the GL readback's size - with [fill] everywhere. */
    private fun readback(fill: Int) = ByteArray(rw * rh) { fill.toByte() }

    private fun MotionDetector.acceptReadback(px: ByteArray) = accept(px, rw, rh, rw)

    /** "high" with short lag/noise windows, fed enough still frames for the fine test's noise
     *  estimate to be ready: warm-up, a full lag, then its minimum samples. */
    private fun settledHigh(frame: (Int) -> ByteArray = { readback(100) }): MotionDetector =
        MotionDetector("high", referenceLagFrames = 5, noiseMinSamples = 5).also { d ->
            repeat(3 + 5 + 5 + 2) { t -> d.acceptReadback(frame(t)) }
        }

    /** Sensor-noise stand-in: every 2x2 block (one fine cell) sits 6 above or below [base],
     *  flipping each frame - so against a reference 5 frames back every fine cell moves by 12,
     *  while coarse cells, averaging many blocks, barely move. */
    private fun noisy(t: Int, base: Int = 100) = ByteArray(rw * rh) { p ->
        val x = p % rw
        val y = p / rw
        (base + if (((x / 2) + (y / 2) + t) % 2 == 0) 6 else -6).toByte()
    }

    @Test
    fun `a small compact change trips high through the fine grid alone`() {
        // A 6x6-pixel patch - a head behind a laptop - is about one coarse cell (0.13%, far below
        // high's 1.5%) but several fine cells, forming a 2x2 block.
        val d = settledHigh()
        val patch = readback(100).also { for (y in 50 until 56) for (x in 70 until 76) it[y * rw + x] = 130.toByte() }
        val r = d.acceptReadback(patch)
        assertTrue(r.motion)
        assertTrue(r.block)
        assertTrue("the coarse test alone would not have fired", r.changedFraction < 0.015)
    }

    @Test
    fun `the same small change is not motion at medium or low`() {
        for (sens in listOf("medium", "low")) {
            val d = MotionDetector(sens)
            repeat(4) { d.acceptReadback(readback(100)) }
            val patch = readback(100).also { for (y in 50 until 56) for (x in 70 until 76) it[y * rw + x] = 130.toByte() }
            assertFalse(sens, d.acceptReadback(patch).motion)
        }
    }

    @Test
    fun `a one-pixel line along an edge is not a block`() {
        // What the camera shifting by a fraction of a pixel looks like: every high-contrast edge
        // changes along a thin line. Two pixels tall at most after averaging - never a 2x2 block
        // of changed fine cells, and far too thin to move a coarse cell's mean.
        val d = settledHigh()
        // +40 on one row: +20 on a row of fine cells, only +8 on the coarse ones.
        val line = readback(100).also { for (x in 0 until rw) it[61 * rw + x] = 140.toByte() }
        val r = d.acceptReadback(line)
        assertFalse(r.block)
        assertFalse(r.motion)
    }

    @Test
    fun `sensor noise raises the bar for the fine test`() {
        // Night: every fine cell already moves by 12 from noise alone, so a change has to beat
        // 6x that. A modest patch that would trip in daylight is lost in it; a strong one is not.
        var t = 0
        val d = settledHigh { i -> t = i + 1; noisy(i) }
        fun patched(add: Int) = noisy(t++).also { for (y in 50 until 56) for (x in 70 until 76) it[y * rw + x] = ((it[y * rw + x].toInt() and 0xFF) + add).toByte() }

        assertFalse("no motion from noise alone", d.acceptReadback(noisy(t++)).motion)
        assertFalse("+30 is under 6x a noise of 12", d.acceptReadback(patched(30)).block)
        // Well clear of the bar: the +30 frame just above was itself folded into those cells'
        // noise (it didn't beat it), lifting their bar from 72 to ~95.
        assertTrue("+140 is not", d.acceptReadback(patched(140)).block)
    }

    @Test
    fun `the fine test waits for a noise estimate after a reset`() {
        // Right after a reset the estimate is empty; firing on it would turn every camera
        // restart or zoom into a burst of false clips at night.
        val d = settledHigh()
        d.reset()
        val patch = readback(100).also { for (y in 50 until 56) for (x in 70 until 76) it[y * rw + x] = 130.toByte() }
        repeat(4) { d.acceptReadback(readback(100)) }
        assertFalse("not ready yet", d.acceptReadback(patch).block)
        repeat(12) { d.acceptReadback(readback(100)) }
        assertTrue("ready once it has a full lag and its minimum samples", d.acceptReadback(patch).block)
    }

    @Test
    fun `a frame smaller than the grid still samples every cell`() {
        // 16x12 against a 32x24 grid: each cell must still read a pixel rather than divide by zero.
        val d = MotionDetector("medium")
        repeat(4) { d.accept(ByteArray(16 * 12) { 0 }, 16, 12, 16) }
        assertTrue(d.accept(ByteArray(16 * 12) { 120 }, 16, 12, 16).motion)
    }
}
