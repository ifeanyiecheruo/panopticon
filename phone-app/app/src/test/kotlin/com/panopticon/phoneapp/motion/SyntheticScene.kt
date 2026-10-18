package com.panopticon.phoneapp.motion

import java.util.Random
import kotlin.math.floor

/**
 * Deterministic synthetic luma video at the detector's analysis resolution, for tests: a textured
 * background, sensor noise, textured objects moving at sub-pixel precision, brightness changes
 * over regions, and per-frame particles. Real footage is what the detector is tuned on (see
 * `MotionReplayTest`); these pin down the behaviours each stage exists for.
 */
internal class SyntheticScene(
    val width: Int = 160,
    val height: Int = 120,
    seed: Long = 1L,
    /** Background mean and texture amplitude. */
    mean: Int = 110,
    amplitude: Int = 50,
    /** Temporal noise standard deviation, luma levels. */
    var noise: Double = 2.0,
) {
    private val rnd = Random(seed)
    private val background = texture(width, height, mean, amplitude, rnd)

    class Obj(
        val w: Int,
        val h: Int,
        val tex: FloatArray,
        /** Top-left corner at frame t. */
        val pos: (Int) -> Pair<Double, Double>,
        val visible: (Int) -> Boolean = { true },
    )

    /** Multiplies the pixels of a rectangle by [factor] at frame t (a TV's glow, a lamp). */
    class Light(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val factor: (Int) -> Double)

    val objects = ArrayList<Obj>()
    val lights = ArrayList<Light>()
    /** Whole-frame gain at frame t (auto-exposure). */
    var gain: (Int) -> Double = { 1.0 }
    /** Whole-frame integer shift at frame t (the camera moving). */
    var shift: (Int) -> Pair<Int, Int> = { 0 to 0 }
    /** Extra per-frame drawing (particles), after everything else but before noise. */
    var particles: ((Int, FloatArray, Random) -> Unit)? = null

    fun texturedObject(
        w: Int, h: Int, mean: Int = 60, amplitude: Int = 60, visible: (Int) -> Boolean = { true },
        pos: (Int) -> Pair<Double, Double>,
    ): Obj = Obj(w, h, texture(w, h, mean, amplitude, rnd), pos, visible).also { objects.add(it) }

    fun frame(t: Int): ByteArray {
        val f = FloatArray(width * height)
        val (sx, sy) = shift(t)
        for (y in 0 until height) for (x in 0 until width) {
            val bx = (x - sx).coerceIn(0, width - 1)
            val by = (y - sy).coerceIn(0, height - 1)
            f[y * width + x] = background[by * width + bx]
        }
        for (o in objects) {
            if (!o.visible(t)) continue
            val (px, py) = o.pos(t)
            val x0 = floor(px).toInt()
            val y0 = floor(py).toInt()
            val fx = px - x0
            val fy = py - y0
            for (j in 0 until o.h) for (i in 0 until o.w) {
                val x = x0 + i
                val y = y0 + j
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                // Sub-pixel position: sample the object's texture between texels.
                f[y * width + x] = sample(o.tex, o.w, o.h, i - fx, j - fy)
            }
        }
        for (l in lights) {
            val k = l.factor(t).toFloat()
            for (y in l.y0 until l.y1) for (x in l.x0 until l.x1) f[y * width + x] *= k
        }
        particles?.invoke(t, f, rnd)
        val g = gain(t).toFloat()
        val out = ByteArray(width * height)
        for (i in f.indices) {
            val v = f[i] * g + (rnd.nextGaussian() * noise).toFloat()
            out[i] = v.toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun sample(tex: FloatArray, w: Int, h: Int, x: Double, y: Double): Float {
        val cx = x.coerceIn(0.0, w - 1.0)
        val cy = y.coerceIn(0.0, h - 1.0)
        val x0 = floor(cx).toInt().coerceAtMost(w - 1)
        val y0 = floor(cy).toInt().coerceAtMost(h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val ax = (cx - x0).toFloat()
        val ay = (cy - y0).toFloat()
        val top = tex[y0 * w + x0] * (1 - ax) + tex[y0 * w + x1] * ax
        val bottom = tex[y1 * w + x0] * (1 - ax) + tex[y1 * w + x1] * ax
        return top * (1 - ay) + bottom * ay
    }

    companion object {
        /** Blobby texture with features a few pixels across: random values at 1/3 resolution,
         *  bilinearly upsampled. */
        fun texture(w: Int, h: Int, mean: Int, amplitude: Int, rnd: Random): FloatArray {
            val cw = w / 3 + 2
            val ch = h / 3 + 2
            val coarse = FloatArray(cw * ch) { (mean + (rnd.nextFloat() * 2 - 1) * amplitude) }
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val gx = x / 3f
                val gy = y / 3f
                val x0 = gx.toInt(); val y0 = gy.toInt()
                val ax = gx - x0; val ay = gy - y0
                val top = coarse[y0 * cw + x0] * (1 - ax) + coarse[y0 * cw + x0 + 1] * ax
                val bottom = coarse[(y0 + 1) * cw + x0] * (1 - ax) + coarse[(y0 + 1) * cw + x0 + 1] * ax
                out[y * w + x] = top * (1 - ay) + bottom * ay
            }
            return out
        }
    }
}

/** Runs [frames] frames of [scene] through [detector] at [fps]; returns per-frame results. */
internal fun MotionDetector.run(scene: SyntheticScene, frames: Int, fps: Int = 15, startMs: Long = 0L): List<MotionDetector.Result> {
    val out = ArrayList<MotionDetector.Result>(frames)
    for (t in 0 until frames) {
        out.add(accept(scene.frame(t), scene.width, scene.height, scene.width, startMs + t * 1000L / fps))
    }
    return out
}
