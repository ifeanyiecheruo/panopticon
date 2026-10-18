package com.panopticon.phoneapp.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Behaviour of the tracked-displacement detector on synthetic scenes at 160x120, 15fps: what it
 * must catch, and every kind of change that is not motion and must not be.
 */
class MotionDetectorTest {

    private val fps = 15

    private fun firstMotion(r: List<MotionDetector.Result>): Int = r.indexOfFirst { it.motion }
    private fun motionFrames(r: List<MotionDetector.Result>): Int = r.count { it.motion }

    // ---- must detect ----

    @Test
    fun `a person-sized object walking into view is motion within a second`() {
        val s = SyntheticScene()
        // Enters from the left edge at frame 30, into a scene the detector has settled on, and is
        // fully in view from frame 38.
        s.texturedObject(12, 26) { t -> (-12.0 + 1.5 * (t - 30)) to 50.0 }
        val r = MotionDetector("medium").run(s, 120)
        val first = firstMotion(r)
        assertTrue("detected at frame $first", first in 30..38 + fps)
        assertTrue("and held while it moves", r.subList(first, 115).count { it.motion } > 0.9 * (115 - first))
    }

    @Test
    fun `a small distant person walking steadily is motion on high`() {
        val s = SyntheticScene()
        s.texturedObject(4, 9, mean = 30, amplitude = 20) { t -> (20.0 + 0.8 * t) to 60.0 }
        val r = MotionDetector("high").run(s, 120)
        assertTrue("detected at ${firstMotion(r)}", firstMotion(r) in 0..2 * fps)
    }

    @Test
    fun `the same small person is below what low asks for`() {
        val s = SyntheticScene()
        s.texturedObject(4, 9, mean = 30, amplitude = 20) { t -> (20.0 + 0.8 * t) to 60.0 }
        assertEquals(0, motionFrames(MotionDetector("low").run(s, 120)))
    }

    @Test
    fun `something moving a fraction of a pixel per frame is still found`() {
        val s = SyntheticScene()
        s.texturedObject(16, 16) { t -> (40.0 + 0.25 * t) to 40.0 }
        val r = MotionDetector("high").run(s, 90)
        assertTrue("detected at ${firstMotion(r)}", firstMotion(r) in 0..4 * fps)
    }

    @Test
    fun `a swaying tree counts as motion even though it goes nowhere`() {
        val s = SyntheticScene()
        s.texturedObject(40, 40, mean = 80, amplitude = 70) { t -> (60.0 + 3 * sin(2 * PI * t / 18.0)) to 30.0 }
        val r = MotionDetector("medium").run(s, 90)
        assertTrue("detected at ${firstMotion(r)}", firstMotion(r) in 0..2 * fps)
    }

    @Test
    fun `motion is held briefly after something stops, then released`() {
        val s = SyntheticScene()
        s.texturedObject(12, 26) { t -> (10.0 + 1.5 * minOf(t, 40)) to 50.0 }
        val r = MotionDetector("medium").run(s, 90)
        assertTrue(r[40].motion)
        assertFalse("released within a second of stopping", r[40 + fps].motion)
    }

    // ---- must not detect ----

    @Test
    fun `a flickering light over a textured region is not motion`() {
        val s = SyntheticScene(seed = 7)
        val rnd = java.util.Random(3)
        val levels = DoubleArray(600) { 0.5 + rnd.nextDouble() }
        s.lights.add(SyntheticScene.Light(60, 40, 100, 70) { t -> levels[t] })
        assertEquals(0, motionFrames(MotionDetector("high").run(s, 600)))
    }

    @Test
    fun `auto-exposure hunting over the whole frame is not motion`() {
        val s = SyntheticScene(seed = 8)
        s.gain = { t -> 1.0 + 0.2 * sin(t / 7.0) + if ((t / 20) % 2 == 0) 0.1 else -0.1 }
        assertEquals(0, motionFrames(MotionDetector("high").run(s, 450)))
    }

    @Test
    fun `a light switching on is not motion`() {
        val s = SyntheticScene(seed = 9)
        s.lights.add(SyntheticScene.Light(0, 0, 160, 70) { t -> if (t < 60) 0.6 else 1.4 })
        assertEquals(0, motionFrames(MotionDetector("high").run(s, 150)))
    }

    @Test
    fun `heavy night-time sensor noise is not motion`() {
        val s = SyntheticScene(seed = 10, mean = 35, amplitude = 25, noise = 12.0)
        assertEquals(0, motionFrames(MotionDetector("high").run(s, 900)))
    }

    @Test
    fun `rain is not motion`() {
        val s = SyntheticScene(seed = 11)
        // Drops move tens of pixels per frame: each frame's streaks are unrelated to the last's.
        s.particles = { _, f, rnd ->
            repeat(40) {
                val x = rnd.nextInt(160)
                val y = rnd.nextInt(110)
                for (k in 0 until 8) f[(y + k) * 160 + x] = (f[(y + k) * 160 + x] + 60f).coerceAtMost(255f)
            }
        }
        assertEquals(0, motionFrames(MotionDetector("high").run(s, 450)))
    }

    @Test
    fun `insects flitting about are not motion`() {
        val s = SyntheticScene(seed = 12, mean = 40, amplitude = 20, noise = 6.0)
        val rnd = java.util.Random(5)
        // Five bright 3x3 specks, each turning sharply every frame at 3px/frame.
        val bugs = Array(5) { doubleArrayOf(30.0 + 25 * it, 30.0 + 10 * it, rnd.nextDouble() * 2 * PI) }
        s.particles = { _, f, _ ->
            for (b in bugs) {
                b[2] += (rnd.nextDouble() - 0.5) * PI * 1.6
                b[0] = (b[0] + 3 * cos(b[2])).coerceIn(2.0, 155.0)
                b[1] = (b[1] + 3 * sin(b[2])).coerceIn(2.0, 115.0)
                for (dy in -1..1) for (dx in -1..1) f[(b[1].toInt() + dy) * 160 + b[0].toInt() + dx] = 230f
            }
        }
        assertEquals(0, motionFrames(MotionDetector("high").run(s, 450)))
    }

    @Test
    fun `the camera being knocked is not motion`() {
        val s = SyntheticScene(seed = 13)
        s.shift = { t -> if (t in 40..44) (2 * (t - 39)) to (t - 39) else if (t > 44) 10 to 5 else 0 to 0 }
        val r = MotionDetector("high").run(s, 120)
        assertEquals(0, motionFrames(r))
        assertTrue("seen as the camera moving", r.subList(40, 46).any { it.globalMotion })
    }

    // ---- lifecycle ----

    @Test
    fun `reset forgets tracks, so a confirmed object must be confirmed again`() {
        val s = SyntheticScene()
        s.texturedObject(12, 26) { t -> (10.0 + 1.5 * t) to 50.0 }
        val d = MotionDetector("medium")
        val r = d.run(s, 30)
        assertTrue(r.last().motion)
        d.reset()
        val after = (30 until 35).map { d.accept(s.frame(it), 160, 120, 160, it * 1000L / fps) }
        assertTrue(after.none { it.motion })
    }

    @Test
    fun `changing sensitivity keeps history and tracks`() {
        val s = SyntheticScene()
        s.texturedObject(12, 26) { t -> (10.0 + 1.5 * t) to 50.0 }
        val d = MotionDetector("medium")
        d.run(s, 30)
        d.sensitivity = "high"
        assertTrue(d.accept(s.frame(30), 160, 120, 160, 30 * 1000L / fps).motion)
    }

    @Test
    fun `a row stride wider than the image is honoured`() {
        val s = SyntheticScene()
        s.texturedObject(12, 26) { t -> (10.0 + 1.5 * t) to 50.0 }
        val d = MotionDetector("medium")
        var any = false
        for (t in 0 until 30) {
            val f = s.frame(t)
            val padded = ByteArray(176 * 120)
            for (y in 0 until 120) System.arraycopy(f, y * 160, padded, y * 176, 160)
            any = any or d.accept(padded, 160, 120, 176, t * 1000L / fps).motion
        }
        assertTrue(any)
    }
}
