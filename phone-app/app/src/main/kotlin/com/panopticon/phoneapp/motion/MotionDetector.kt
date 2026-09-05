package com.panopticon.phoneapp.motion

/**
 * Frame-difference motion detector. Deliberately simple - no OpenCV, no
 * background model, no MOG2: it subsamples the luma (Y) plane on a fixed grid,
 * counts grid cells whose brightness changed more than [pixelDeltaThreshold]
 * since the previous frame, and reports motion when the changed fraction
 * crosses a per-sensitivity threshold.
 *
 * This is enough to gate recording on "something in the scene moved" for a
 * fixed security camera. It is knowingly naive about lighting steps (a light
 * switching on trips it), rolling gain/exposure (the [warmupFrames] guard and
 * the delta threshold absorb small global shifts), and slow drift. Tuning the
 * thresholds against the real Pixel 6 in real lighting is expected follow-up
 * work - the numbers below are starting points, not measured values.
 *
 * Not thread-safe: [accept] is called from a single camera-callback thread.
 */
class MotionDetector(
    sensitivity: String,
    private val gridCols: Int = 32,
    private val gridRows: Int = 24,
    private val pixelDeltaThreshold: Int = 18,
    private val warmupFrames: Int = 3,
) {
    /** Fraction of grid cells that must change for a frame to count as motion. */
    private val changedFractionThreshold: Double = when (sensitivity.lowercase()) {
        "high" -> 0.010
        "low" -> 0.060
        else -> 0.028 // medium / anything unrecognised
    }

    private val cellCount = gridCols * gridRows
    private var previous: IntArray? = null
    private var framesSeen = 0

    data class Result(val motion: Boolean, val changedFraction: Double)

    /**
     * @param luma packed Y plane, row-major, [rowStride] bytes per row (>= width).
     * @return whether motion was detected between this frame and the last one.
     *   Always `false` for the first [warmupFrames] frames (sensor still
     *   settling exposure/white-balance right after a session (re)configure).
     */
    fun accept(luma: ByteArray, width: Int, height: Int, rowStride: Int): Result {
        val sample = IntArray(cellCount)
        var i = 0
        for (gy in 0 until gridRows) {
            val y = (gy * height) / gridRows
            val rowBase = y * rowStride
            for (gx in 0 until gridCols) {
                val x = (gx * width) / gridCols
                sample[i++] = luma[rowBase + x].toInt() and 0xFF
            }
        }

        framesSeen++
        val prev = previous
        previous = sample
        if (prev == null || framesSeen <= warmupFrames) {
            return Result(motion = false, changedFraction = 0.0)
        }

        var changed = 0
        for (c in 0 until cellCount) {
            if (kotlin.math.abs(sample[c] - prev[c]) >= pixelDeltaThreshold) changed++
        }
        val fraction = changed.toDouble() / cellCount
        return Result(motion = fraction >= changedFractionThreshold, changedFraction = fraction)
    }

    /** Drop the reference frame - call after a gap where frames stopped arriving. */
    fun reset() {
        previous = null
        framesSeen = 0
    }
}
