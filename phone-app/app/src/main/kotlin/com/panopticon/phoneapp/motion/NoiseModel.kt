package com.panopticon.phoneapp.motion

import kotlin.math.roundToInt

/**
 * The camera's temporal noise as a function of brightness - a "noise level function" - learned
 * from the frames themselves, plus this frame's brightness shift. Together they say how big a
 * frame-to-frame difference has to be before it is a change rather than noise ([changeTable]),
 * and how wide the census transform's dead band should be ([epsTable]).
 *
 * One number for the whole frame cannot do it: sensor noise varies by brightness, and the
 * 2026-09-28 traces put a pixel's typical frame-to-frame change at ~2 by day and ~12 at night.
 * So everything is kept per brightness band ([BINS] of them, by the pixel's current luma).
 *
 * Per band, from a histogram of signed frame differences:
 * - the **shift** is their median: how much that band brightened or darkened as a whole this
 *   frame (auto-exposure, a lamp dimming the whole scene). It is removed before anything is
 *   called a change;
 * - the **noise** is their median absolute deviation from that shift, converted to a standard
 *   deviation (for Gaussian noise sigma, a difference of two samples has MAD 0.954 * sigma).
 *   The median ignores the minority of pixels a moving object covers, and measuring around the
 *   shift keeps an exposure step from reading as a burst of noise.
 *
 * Not thread-safe; owned by one [MotionDetector].
 */
internal class NoiseModel {
    /** Per-band temporal noise standard deviation, in luma levels (smoothed over frames). */
    val sigma = FloatArray(BINS) { INITIAL_SIGMA }
    /** Per-band median signed difference, this frame only. */
    val shift = FloatArray(BINS)
    private val hist = IntArray(BINS * HIST)
    private val counts = IntArray(BINS)
    private val dev = IntArray(HIST)
    private var samples = 0

    fun reset() {
        sigma.fill(INITIAL_SIGMA)
        shift.fill(0f)
        samples = 0
    }

    /** Fold one frame pair in. Samples every other pixel of every other row: a quarter of the
     *  frame is thousands of samples, far more than a median needs. */
    fun update(cur: ByteArray, prev: ByteArray, width: Int, height: Int) {
        signedHistogram(cur, prev, width, height)
        // Fast at first, so a fresh detector is calibrated within a second; slow after, so a
        // burst of real motion cannot drag the estimate far.
        val alpha = if (samples < WARM_SAMPLES) 0.3f else 0.05f
        var global = -1f
        for (b in 0 until BINS) {
            if (counts[b] < MIN_BIN_COUNT) { shift[b] = 0f; continue }
            val m = median(hist, b * HIST, counts[b])
            shift[b] = m - OFFSET
            // Absolute deviations from the median, re-binned.
            dev.fill(0)
            val mi = m.roundToInt()
            for (v in 0 until HIST) dev[kotlin.math.abs(v - mi).coerceAtMost(HIST - 1)] += hist[b * HIST + v]
            val s = median(dev, 0, counts[b]) / 0.954f
            if (samples == 0) sigma[b] = s else sigma[b] += alpha * (s - sigma[b])
            if (global < 0f) global = sigma[b]
        }
        // A band never yet populated starts from a populated one, so a light coming on later is
        // not judged against the initial guess.
        if (samples == 0 && global >= 0f) {
            for (b in 0 until BINS) if (counts[b] < MIN_BIN_COUNT) sigma[b] = global
        }
        samples++
    }

    /** One global median signed difference between two frames (for the lagged comparison, whose
     *  noise is the same sensor's but whose exposure may have moved further). */
    fun globalShift(cur: ByteArray, ref: ByteArray, width: Int, height: Int): Float {
        signedHistogram(cur, ref, width, height)
        val all = IntArray(HIST)
        var n = 0
        for (b in 0 until BINS) { for (v in 0 until HIST) all[v] += hist[b * HIST + v]; n += counts[b] }
        return if (n == 0) 0f else median(all, 0, n) - OFFSET
    }

    private fun signedHistogram(cur: ByteArray, prev: ByteArray, width: Int, height: Int) {
        hist.fill(0)
        counts.fill(0)
        for (y in 0 until height step 2) {
            var p = y * width
            val end = p + width
            while (p < end) {
                val c = cur[p].toInt() and 0xFF
                val d = (c - (prev[p].toInt() and 0xFF) + OFFSET).coerceIn(0, HIST - 1)
                val b = c ushr BIN_SHIFT
                hist[b * HIST + d]++
                counts[b]++
                p += 2
            }
        }
    }

    /** Interpolated median of a histogram of integers: uninterpolated, a daylight median could
     *  only ever be 0, 1 or 2. */
    private fun median(h: IntArray, base: Int, count: Int): Float {
        val half = count / 2f
        var cum = 0
        for (v in 0 until HIST) {
            val n = h[base + v]
            if (cum + n >= half) return v - 0.5f + (half - cum) / n.coerceAtLeast(1)
            cum += n
        }
        return (HIST - 1).toFloat()
    }

    /**
     * Fill [lo] / [hi] (256 entries, by current luma) with the range of frame differences that
     * are *not* a change: this frame's band shift, plus or minus [k] standard deviations of a
     * two-frame difference (sqrt(2) * sigma), and never narrower than [minDelta]. [globalShift],
     * when given, replaces the per-band shifts (for the lagged comparison).
     */
    fun changeTable(lo: IntArray, hi: IntArray, k: Float, minDelta: Int, globalShift: Float? = null) {
        for (v in 0 until 256) {
            val b = v ushr BIN_SHIFT
            val s = globalShift ?: shift[b]
            val half = maxOf(minDelta.toFloat(), k * SQRT2 * sigma[b])
            lo[v] = (s - half).roundToInt()
            hi[v] = (s + half).roundToInt()
        }
    }

    /**
     * Fill [out] (256 entries, indexed by the centre pixel's luma) with the census dead band:
     * [k] standard deviations of a neighbour-minus-centre difference (sqrt(2) * sigma, two noisy
     * pixels), never below [minEps]. [scale] shrinks sigma for a pyramid level, where averaging
     * 2x2 pixels halves it.
     */
    fun epsTable(out: IntArray, k: Float, minEps: Int, scale: Float = 1f) {
        for (v in 0 until 256) {
            out[v] = maxOf(minEps, (k * SQRT2 * sigma[v ushr BIN_SHIFT] * scale).roundToInt())
        }
    }

    private companion object {
        const val BINS = 8
        const val BIN_SHIFT = 5 // 256 / 8
        /** Signed differences -63..+63 are kept; beyond that they are clamped (a real change). */
        const val OFFSET = 63
        const val HIST = 2 * OFFSET + 1
        const val MIN_BIN_COUNT = 200
        const val WARM_SAMPLES = 10
        const val INITIAL_SIGMA = 2f
        const val SQRT2 = 1.4142135f
    }
}
