package com.panopticon.phoneapp.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses a 32x24 luma buffer with rowStride == width, so the detector's 32x24
 * sampling grid maps one-to-one onto pixels: flipping exactly K bytes changes
 * exactly K grid cells, i.e. a changed fraction of K/768.
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
        // +12 per pixel, below the default pixelDeltaThreshold of 18.
        assertFalse(d.acceptFrame(frame(112)).motion)
    }

    @Test
    fun `higher sensitivity trips on a smaller moving region`() {
        val base = frame(100)

        // ~1% of cells changed: only "high" (threshold 0.010) should trip.
        fun freshDetector(sens: String) = MotionDetector(sens).also { repeat(4) { _ -> it.acceptFrame(base) } }

        val k8 = frameWithChangedCells(base, 8, 255) // 8/768 = 0.0104
        assertTrue(freshDetector("high").acceptFrame(k8).motion)
        assertFalse(freshDetector("medium").acceptFrame(k8).motion)
        assertFalse(freshDetector("low").acceptFrame(k8).motion)

        val k25 = frameWithChangedCells(base, 25, 255) // 25/768 = 0.0326
        assertTrue(freshDetector("medium").acceptFrame(k25).motion)
        assertFalse(freshDetector("low").acceptFrame(k25).motion)

        val k50 = frameWithChangedCells(base, 50, 255) // 50/768 = 0.0651
        assertTrue(freshDetector("low").acceptFrame(k50).motion)
    }

    @Test
    fun `reset drops the reference frame so the next frame is warmup again`() {
        val d = MotionDetector("high")
        repeat(5) { d.acceptFrame(frame(80)) }
        d.reset()
        // Back in warmup: a big change right after reset is not reported.
        assertFalse(d.acceptFrame(frame(240)).motion)
    }
}
