package com.panopticon.phoneapp.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val W = 16
private const val H = 12

class MotionAnalyzerTest {

    private var now = 0L

    private fun analyzer(
        settleFrames: Int = 3,
        settleMs: Long = 100L,
        maxHoldMs: Long = 3_000L,
    ) = MotionAnalyzer(
        sensitivity = { "medium" },
        settleFrames = settleFrames,
        settleMs = settleMs,
        maxHoldMs = maxHoldMs,
        clock = { now },
    )

    private fun frame(value: Int) = ByteArray(W * H) { value.toByte() }

    private fun MotionAnalyzer.feed(value: Int) = accept(frame(value), W, H, W, now)

    /** Run enough uniform frames that the detector is past its warm-up and holding a reference. */
    private fun MotionAnalyzer.settle(value: Int = 0, frames: Int = 8) {
        repeat(frames) { now += 33; feed(value) }
    }

    @Test
    fun `a whole-frame change with no disturbance in force is motion`() {
        val a = analyzer()
        a.settle(value = 0)
        now += 33
        assertTrue(a.feed(120).motion)
    }

    @Test
    fun `the same change is not motion while a disturbance is in force`() {
        val a = analyzer()
        a.settle(value = 0)
        a.beginDisturbance(CameraDisturbance.ZOOM)
        now += 33
        val v = a.feed(120)
        assertFalse("our own zoom is not the scene moving", v.motion)
        assertTrue(v.suppressed)
    }

    @Test
    fun `the frame right after a disturbance ends is a new reference, not a comparison`() {
        val a = analyzer(settleFrames = 0, settleMs = 0L)
        a.settle(value = 0)
        a.beginDisturbance(CameraDisturbance.ZOOM)
        now += 33
        a.feed(120) // the disturbed frame itself, suppressed
        a.endDisturbance(CameraDisturbance.ZOOM)

        // First frame through the re-opened gate: it must be adopted as the reference rather than
        // compared against the pre-zoom frame, or the disturbance is simply detected one frame
        // late - which is exactly the failure this class exists to prevent.
        now += 33
        assertFalse(a.feed(120).motion)
        now += 33
        assertFalse("and the scene is still not moving", a.feed(120).motion)
    }

    @Test
    fun `suppression outlasts the disturbance by the settling window`() {
        val a = analyzer(settleFrames = 3, settleMs = 0L)
        a.settle(value = 0)
        a.beginDisturbance(CameraDisturbance.CONTROLS)
        a.endDisturbance(CameraDisturbance.CONTROLS)
        repeat(3) {
            now += 33
            assertTrue("still settling", a.feed(0).suppressed)
        }
        now += 33
        assertFalse("settled", a.feed(0).suppressed)
    }

    @Test
    fun `a settling window in milliseconds also holds when frames are scarce`() {
        val a = analyzer(settleFrames = 0, settleMs = 500L)
        a.settle(value = 0)
        a.beginDisturbance(CameraDisturbance.CONTROLS)
        a.endDisturbance(CameraDisturbance.CONTROLS)
        now += 100
        assertTrue(a.feed(0).suppressed)
        now += 500
        assertFalse(a.feed(0).suppressed)
    }

    @Test
    fun `overlapping disturbances each hold the gate, and the last one out opens it`() {
        val a = analyzer(settleFrames = 0, settleMs = 0L)
        a.settle(value = 0)
        a.beginDisturbance(CameraDisturbance.PIPELINE_START)
        a.beginDisturbance(CameraDisturbance.ZOOM)
        a.endDisturbance(CameraDisturbance.ZOOM)
        now += 33
        assertTrue("PIPELINE_START is still in force", a.feed(0).suppressed)
        a.endDisturbance(CameraDisturbance.PIPELINE_START)
        now += 33
        assertFalse(a.feed(0).suppressed)
    }

    @Test
    fun `ending a disturbance that was never begun is harmless`() {
        val a = analyzer(settleFrames = 0, settleMs = 0L)
        a.settle(value = 0)
        a.endDisturbance(CameraDisturbance.ZOOM)
        now += 33
        assertFalse(a.feed(0).suppressed)
    }

    @Test
    fun `beginning the same disturbance twice needs only one end`() {
        val a = analyzer(settleFrames = 0, settleMs = 0L)
        a.settle(value = 0)
        a.beginDisturbance(CameraDisturbance.ZOOM)
        a.beginDisturbance(CameraDisturbance.ZOOM)
        a.endDisturbance(CameraDisturbance.ZOOM)
        now += 33
        assertFalse("a re-request is one disturbance, not two", a.feed(0).suppressed)
    }

    @Test
    fun `an abandoned disturbance expires instead of blinding the camera forever`() {
        val a = analyzer(settleFrames = 0, settleMs = 0L, maxHoldMs = 1_000L)
        a.settle(value = 0)
        a.beginDisturbance(CameraDisturbance.ZOOM) // and nothing ever ends it

        now += 900
        assertTrue("within the cap, still trusted", a.feed(0).suppressed)

        now += 200 // past maxHoldMs
        a.feed(0) // the frame that notices and drops it
        assertEquals(1L, a.disturbancesExpired)

        a.settle(value = 0)
        now += 33
        assertTrue("motion detection is working again", a.feed(120).motion)
    }

    @Test
    fun `reset forgets in-flight disturbances so a teardown cannot leak one`() {
        val a = analyzer(settleFrames = 0, settleMs = 0L)
        a.beginDisturbance(CameraDisturbance.PIPELINE_START)
        a.reset()
        assertTrue(a.activeDisturbances().isEmpty())
        a.settle(value = 0)
        now += 33
        assertTrue(a.feed(120).motion)
    }

    @Test
    fun `suppressed frames are counted separately from analysed ones`() {
        val a = analyzer(settleFrames = 0, settleMs = 0L)
        a.beginDisturbance(CameraDisturbance.ZOOM)
        repeat(4) { now += 33; a.feed(0) }
        a.endDisturbance(CameraDisturbance.ZOOM)
        repeat(3) { now += 33; a.feed(0) }
        assertEquals(7L, a.framesAnalysed)
        assertEquals(4L, a.framesSuppressed)
        assertEquals(1L, a.disturbances)
        assertEquals(0L, a.disturbancesExpired)
    }
}
