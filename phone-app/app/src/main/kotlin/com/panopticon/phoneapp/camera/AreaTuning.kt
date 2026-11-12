package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fine-tuning auto for an explicit area of interest, by measuring frames of the live camera
 * (docs/design/decisions/0017-area-of-interest-fine-tuning.md). Framework-free and unit-tested;
 * [AutoAreaTuner] runs it against the real camera.
 *
 * The phone's own auto exposure and autofocus, pointed at the area (its AE and AF regions), get
 * close. What is left is a little: exposure that leaves the area's tones as spread out as possible
 * - the gallery player stretches the area's levels and applies a gamma afterwards, so what limits
 * the picture is how many distinct levels the area was captured with - and focus that makes it as
 * sharp as possible. Both are reached by small, slow nudges ([Nudger]) of the levers the manual
 * sliders use - exposure compensation, and the focus distance - from where auto put them.
 */
object AreaTuning {

    /** An 8-bit luma image, rows top down. */
    class LumaPlane(val w: Int, val h: Int, val pix: ByteArray) {
        init {
            require(pix.size >= w * h) { "plane $w x $h needs ${w * h} bytes, has ${pix.size}" }
        }

        fun at(x: Int, y: Int): Int = pix[y * w + x].toInt() and 0xFF
    }

    /** The luma statistics of an area, on the 0-255 scale. */
    data class Tones(
        /** Shannon entropy of the area's luma histogram in bits: how many distinct levels its
         *  pixels spread over (at most 8). Rises as the tones spread, falls as they pile up
         *  against black or white - so it peaks at the exposure that leaves the most levels for
         *  the player to stretch. */
        val entropy: Double,
        /** The levels 1% of the pixels fall below and above - the player's black and white points. */
        val lo: Int,
        val hi: Int,
        val median: Int,
        /** Shares of pixels at, or within a few levels of, black and white. */
        val clipLo: Double,
        val clipHi: Double,
        val pixels: Int,
    )

    /** How near black or white a level counts as clipped. */
    private const val CLIP_MARGIN = 4

    /** The tones of [r] (fractions of [p], top down), or null when it covers no pixel. */
    fun tones(p: LumaPlane, r: RectNorm): Tones? {
        val (x0, y0, x1, y1) = bounds(p, r)
        val hist = IntArray(256)
        var n = 0
        for (y in y0 until y1) {
            val row = y * p.w
            for (x in x0 until x1) hist[p.pix[row + x].toInt() and 0xFF]++
            n += x1 - x0
        }
        if (n <= 0) return null
        var entropy = 0.0
        var clipLo = 0.0
        var clipHi = 0.0
        for (v in 0..255) {
            val c = hist[v]
            if (c == 0) continue
            val q = c.toDouble() / n
            entropy -= q * log2(q)
            if (v <= CLIP_MARGIN) clipLo += q
            if (v >= 255 - CLIP_MARGIN) clipHi += q
        }
        fun level(share: Double): Int {
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc > share * n) return v
            }
            return 255
        }
        return Tones(entropy, level(0.01), level(0.99), level(0.5), clipLo, clipHi, n)
    }

    /**
     * The mean squared Sobel gradient (Tenengrad) over all of [p]: the energy of its edges, which
     * a sharper picture concentrates and a blurred one spreads thin. Only comparable between
     * frames at the same exposure. 0 for a plane too small for the 3x3 kernel.
     */
    fun sharpness(p: LumaPlane): Double {
        if (p.w < 3 || p.h < 3) return 0.0
        var sum = 0.0
        for (y in 1 until p.h - 1) {
            for (x in 1 until p.w - 1) {
                val a = p.at(x - 1, y - 1); val b = p.at(x, y - 1); val c = p.at(x + 1, y - 1)
                val d = p.at(x - 1, y); val f = p.at(x + 1, y)
                val g = p.at(x - 1, y + 1); val h = p.at(x, y + 1); val i = p.at(x + 1, y + 1)
                val gx = (c + 2 * f + i) - (a + 2 * d + g)
                val gy = (g + 2 * h + i) - (a + 2 * b + c)
                sum += (gx * gx + gy * gy).toDouble()
            }
        }
        return sum / ((p.w - 2) * (p.h - 2))
    }

    /**
     * How far beyond the area sharpness is measured, as a share of the frame on each side.
     *
     * Measured strictly inside the area, defocus can *raise* the score: the edges around an area
     * (a window's frame, say) blur into it, and a blurred edge is a gradient spread over more
     * pixels. On the Pixel 7 Pro an area inside a window scored four times higher well out of
     * focus than in focus, for exactly that reason. With the margin the whole blur of those edges
     * is counted, and an edge's total gradient energy is greatest when it is sharpest.
     */
    const val FOCUS_MARGIN = 0.05f

    fun grow(r: RectNorm, m: Float): RectNorm =
        RectNorm(max(0f, r.l - m), max(0f, r.t - m), min(1f, r.r + m), min(1f, r.b + m))

    /** [inner] as a fraction of [outer] - where the area sits inside the grown region read back. */
    fun within(inner: RectNorm, outer: RectNorm): RectNorm {
        val w = max(1e-6f, outer.r - outer.l)
        val h = max(1e-6f, outer.b - outer.t)
        return RectNorm((inner.l - outer.l) / w, (inner.t - outer.t) / h, (inner.r - outer.l) / w, (inner.b - outer.t) / h)
    }

    private fun bounds(p: LumaPlane, r: RectNorm): IntArray {
        fun clamp(v: Float, n: Int) = min(n, max(0, (v * n).roundToInt()))
        return intArrayOf(clamp(r.l, p.w), clamp(r.t, p.h), clamp(r.r, p.w), clamp(r.b, p.h))
    }

    // ---- nudging ----

    /**
     * A slow hill climb over one control's steps, starting where the phone's own auto put it
     * (step 0) and never straying more than [lo]..[hi] steps from there.
     *
     * Each trial moves one step, keeps it only if it measurably helped, and otherwise turns
     * round. Once both directions have failed it is at a peak, and it waits longer and longer
     * before trying again - so a tuned area is left alone, but a slow change in the light or the
     * scene is still followed.
     */
    class Nudger(lo: Int, hi: Int, private val margin: (base: Double) -> Double) {
        init {
            require(lo <= 0 && hi >= 0) { "the range $lo..$hi must hold auto's own step 0" }
        }

        var lo = lo
            private set
        var hi = hi
            private set

        /** Never go further in [direction] (+1 or -1) than where it is now: that way misbehaves. */
        fun stopGoing(direction: Int) {
            if (direction > 0) hi = value else lo = value
        }

        var value = 0
            private set
        private var dir = 1

        /** Trials in a row that didn't help. Two means both ways were tried: it is at a peak. */
        var misses = 0
            private set

        /** The next step to try, or null when it can go neither way. */
        fun next(): Int? {
            if (value + dir in lo..hi) return value + dir
            if (value - dir in lo..hi) {
                dir = -dir
                return value + dir
            }
            return null
        }

        /** Record a trial of [candidate]: kept when [trial] beats [base] by the margin. */
        fun result(candidate: Int, base: Double, trial: Double): Boolean {
            val step = if (candidate > value) 1 else -1
            val keep = trial > base + margin(base)
            if (keep) {
                value = candidate
                dir = step
                misses = 0
            } else {
                dir = -step
                misses++
            }
            return keep
        }

        /** How long to wait before the next trial: [baseMs] while trials pay off, doubling each
         *  time both ways have failed, up to [maxMs]. */
        fun waitMs(baseMs: Long, maxMs: Long): Long = min(maxMs, baseMs shl min(misses / 2, 16))
    }

    /** A trial step is kept only if it beats the current setting by more than the wobble of an
     *  unchanged scene, averaged over [FRAMES_PER_MEASURE] frames: this many bits of contrast, or
     *  this share of sharpness. */
    const val EXPOSURE_GAIN_BITS = 0.02
    const val FOCUS_GAIN_SHARE = 0.03

    /** Frames averaged into each measurement. */
    const val FRAMES_PER_MEASURE = 3

    /** How far exposure may be biased from what auto exposure chose: a fine-tune, not a takeover. */
    const val MAX_EXPOSURE_BIAS_EV = 1.0

    /** The exposure-compensation steps within [MAX_EXPOSURE_BIAS_EV] that [caps] allows on top of
     *  the user's own compensation [base], as a [Nudger]; null when there are none. */
    fun exposureNudger(caps: CameraCapabilities, base: Int = 0): Nudger? {
        val r = caps.aeCompensationRange
        val step = caps.aeCompensationStepMilliEv
        if (step <= 0 || r.lo >= r.hi) return null
        val most = max(1, (MAX_EXPOSURE_BIAS_EV * 1000 / step).toInt())
        val lo = max(r.lo - base, -most)
        val hi = min(r.hi - base, most)
        if (lo > 0 || hi < 0 || lo == hi) return null
        return Nudger(lo, hi) { EXPOSURE_GAIN_BITS }
    }

    /** An area with more than this share of its pixels at white isn't brightened, nor one this
     *  much at black darkened: auto exposure can't meter what is clipped, and on the Pixel 7 Pro
     *  brightening a clipped area made it run away - a sixth of a stop of compensation ramped
     *  the exposure two stops and climbing, to solid white. */
    const val CLIPPED_SHARE = 0.01

    /** Which ways [t]'s tones allow exposure to be nudged: +1 brighter, -1 darker. */
    fun exposureAllows(t: Tones, direction: Int): Boolean =
        if (direction > 0) t.clipHi <= CLIPPED_SHARE else t.clipLo <= CLIPPED_SHARE

    /** How far auto exposure may move in answer to a compensation change of [stepEv] before it
     *  counts as running away rather than following: the step, three times over, plus a quarter
     *  stop of the light's own wobble. */
    fun maxExposureResponse(stepEv: Double): Double = 3 * kotlin.math.abs(stepEv) + 0.25

    /** Focus steps: a 320th of the lens's range, at most [FOCUS_WINDOW_STEPS] either side of where
     *  autofocus settled. */
    const val FOCUS_WINDOW_STEPS = 8

    fun focusStep(maxDiopters: Float): Float = max(0.005f, maxDiopters / 320f)

    fun focusNudger(): Nudger = Nudger(-FOCUS_WINDOW_STEPS, FOCUS_WINDOW_STEPS) { base -> base * FOCUS_GAIN_SHARE }

    /** Holding autofocus's own distance manually may lose sharpness (the Pixel 7 Pro telephoto
     *  does): by more than this share, focus is left to autofocus. */
    const val FOCUS_HOLD_LOSS = 0.05

    // ---- the watchdog ----

    /**
     * How far the area may drift below what it measured when tuned - by the same measures as the
     * trials, taken with nothing changed - before auto starts over from its own baseline. Well
     * beyond a trial's margin, so the wobble of an unchanged scene never sets it off: what it
     * catches is a bias tuned for this morning's light that no longer suits this afternoon's.
     */
    const val DRIFT_CONTRAST_BITS = 0.1
    const val DRIFT_SHARPNESS_SHARE = 0.15

    /** Whether contrast [now] has drifted down from [tuned] far enough to tune again. */
    fun contrastDrifted(tuned: Double, now: Double): Boolean = now < tuned - DRIFT_CONTRAST_BITS

    /** Whether sharpness [now] has drifted down from [tuned] far enough to tune again. */
    fun sharpnessDrifted(tuned: Double, now: Double): Boolean =
        tuned > 0 && now < tuned * (1 - DRIFT_SHARPNESS_SHARE)
}
