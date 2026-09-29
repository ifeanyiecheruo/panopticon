package com.panopticon.phoneapp.motion

/**
 * Frame-difference motion detector. Deliberately simple - no OpenCV, no background model, no
 * MOG2: it averages the frame into a fixed grid of cells, compares each cell against the same
 * cell [referenceLagFrames] frames ago, counts cells where any colour channel moved by at least
 * the sensitivity's cell delta, and reports motion when the changed fraction crosses the
 * sensitivity's threshold.
 *
 * Three choices here were measured against real Pixel 6 footage (a night-time scene across the
 * street, where a window's blinds being drawn went undetected even on "high" - see
 * docs/status/motion-detection-tuning.md):
 *
 * - **Every colour channel, not just luma.** The blinds changed the window's colour (a blue
 *   screen glow gone) with almost no change in brightness: -4 on green against -12 on blue. A
 *   luma-only detector could not see it at any threshold.
 * - **A reference ~1s old, not the previous frame.** A slow change - blinds, a fading light - is
 *   spread over many frames and each step is below any usable threshold; against a lagged
 *   reference the whole change shows at once. The cost is that motion is reported for up to
 *   [referenceLagFrames] after something stops, which a recording gate absorbs in post-roll.
 * - **Cell means, not one pixel per cell.** A lagged reference exposes more sensor noise than
 *   the previous frame does (the ISP's temporal noise reduction correlates adjacent frames), and
 *   averaging each cell's block is what keeps the lower thresholds that noise allows usable.
 *
 * "high" adds a second, finer test for something small moving - a head behind a laptop in a
 * window across the street covers one coarse cell, far below any usable fraction. It compares a
 * [fineCols]x[fineRows] grid the same way and fires on any 2x2 block of changed fine cells. The
 * block is what separates a real object from the camera shifting by a fraction of a pixel: that
 * lights up thin one-cell lines along every high-contrast edge in the frame at once, and on a
 * daytime trace (2026-09-28) produced bursts of 20-90 fine cells but never a 2x2 block.
 *
 * What counts as a changed fine cell is relative to that cell's own noise: a change of at least
 * [BLOCK_NOISE_MULTIPLE] times its running mean 1s-change, and never less than [BLOCK_MIN_DELTA].
 * A fixed number could not work: a pixel's typical 1s change is ~2 in daylight and ~12 at night
 * (2026-09-28 traces), so the daytime threshold of 10 fired on 90% of night frames - noise
 * forming blocks across the whole frame - and any threshold quiet at night (15-20) missed every
 * daytime head movement. The noise estimate only learns from cells that did not change, so a
 * moving object does not raise its own bar, and the rule stays off after a [reset] until it has
 * [noiseMinSamples] properly lagged samples.
 *
 * The coarse test is made robust the same way, plus two things of its own (2026-09-29 night
 * trace, exposure compensation +7: this test alone fired on 30-100% of frames):
 * - a coarse cell must beat [COARSE_NOISE_MULTIPLE] times its own noise as well as the
 *   sensitivity's cell delta;
 * - the whole frame's brightness shift is taken out first - the median shift across cells, per
 *   channel - because auto-exposure hunting at night moved every cell by 6-18 levels at once,
 *   in steps a noise estimate cannot absorb. Only up to [MAX_EXPOSURE_SHIFT] is taken out, so a
 *   light switching on (a far bigger step) still reads as a change;
 * - only cells with a changed neighbour count, so scattered noise specks cannot add up to the
 *   fraction the way one real patch does.
 * What was left of that night was a TV flickering behind the blinds of the window that started
 * all of this - a real change.
 *
 * It relies on the [warmupFrames] guard for exposure settling after a (re)start.
 *
 * Not thread-safe: [accept] is called from a single camera-callback thread.
 */
class MotionDetector(
    sensitivity: String,
    private val gridCols: Int = 32,
    private val gridRows: Int = 24,
    /** 80x60 is 2x2 pixels of the GL pipeline's 160x120 readback. */
    private val fineCols: Int = 80,
    private val fineRows: Int = 60,
    /** ~1s at the 30fps the GL pipeline analyses at. */
    internal val referenceLagFrames: Int = 30,
    internal val warmupFrames: Int = 3,
    /** The noise estimate's time constant - ~5s at 30fps, fast enough to follow dusk. */
    internal val noiseWindowFrames: Int = 150,
    /** Lagged samples the noise estimate needs before the fine test may fire - ~1s at 30fps. */
    internal val noiseMinSamples: Int = 30,
) {
    /**
     * Settable, because the alternative is what this class used to get: callers rebuilt the whole
     * detector on a timer to pick up a changed setting, and every rebuild silently threw away
     * the reference frames and restarted [warmupFrames]. A threshold is just a number - changing
     * it has no business invalidating the reference, and doing so blinded detection for a few
     * frames on every refresh whether or not the setting had actually changed.
     */
    var sensitivity: String = sensitivity
        set(value) {
            if (field == value) return
            field = value
            thresholds = thresholdsFor(value)
        }

    private var thresholds: Thresholds = thresholdsFor(sensitivity)

    private val cellCount = gridCols * gridRows
    private val fineCount = fineCols * fineRows
    /** Scratch for the fine test: which fine cells changed this frame. */
    private val fineChanged = BooleanArray(fineCount)
    /** Running mean |1s change| per fine / coarse cell and channel - see the class doc. */
    private var fineNoise = FloatArray(0)
    private var coarseNoise = FloatArray(0)
    private var noiseSamples = 0
    /** Scratch for the coarse test. */
    private val coarseChanged = BooleanArray(cellCount)
    private val shiftScratch = IntArray(cellCount)
    private val exposureShift = IntArray(3)

    /** The last [referenceLagFrames] samples, oldest first; the head is the reference. */
    private val history = ArrayDeque<IntArray>(referenceLagFrames + 1)
    /** The most recently evicted sample's array, reused for the next one so steady state
     *  allocates nothing. */
    private var spare: IntArray? = null
    private var historyChannels = 0
    private var framesSeen = 0

    /**
     * @property changedFraction of coarse cells.
     * @property block whether the fine 2x2-block test fired (only ever true on "high").
     */
    data class Result(val motion: Boolean, val changedFraction: Double, val block: Boolean = false)

    /**
     * A coarse cell counts as changed when any channel's mean moved by at least [cellDelta]
     * (0-255), and the frame counts as motion when at least [changedFraction] of cells changed -
     * or, with [smallObjects], when any 2x2 block of fine cells all changed beyond their noise.
     */
    private data class Thresholds(val cellDelta: Int, val changedFraction: Double, val smallObjects: Boolean = false)

    /**
     * @param pixels packed interleaved pixels, row-major, [rowStride] bytes per row. The first
     *   `min(pixelStride, 3)` bytes of each pixel are compared (so RGBA ignores alpha, and a
     *   single-channel luma plane is `pixelStride = 1`).
     * @return whether motion was detected between this frame and the reference. Always `false`
     *   for the first [warmupFrames] frames (sensor still settling exposure/white-balance right
     *   after a session (re)configure) - and those frames never become a reference.
     */
    fun accept(pixels: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int = 1): Result {
        val channels = pixelStride.coerceIn(1, 3)
        if (channels != historyChannels) {
            history.clear()
            historyChannels = channels
            fineNoise = FloatArray(fineCount * channels)
            coarseNoise = FloatArray(cellCount * channels)
            noiseSamples = 0
        }
        // One array per frame holding both grids, coarse first, so they share one history.
        val sample = takeSpare(channels)
        sampleInto(sample, 0, gridCols, gridRows, pixels, width, height, rowStride, pixelStride, channels)
        sampleInto(sample, cellCount * channels, fineCols, fineRows, pixels, width, height, rowStride, pixelStride, channels)

        framesSeen++
        if (framesSeen <= warmupFrames || history.isEmpty()) {
            // Only the newest warm-up frame is kept, so the first real comparison is against a
            // settled frame rather than one from mid-convergence.
            history.clear()
            history.addLast(sample)
            return Result(motion = false, changedFraction = 0.0)
        }

        val reference = history.first()
        val t = thresholds
        // Learn only once the reference is a full lag back: right after a reset it is younger,
        // its changes smaller, and learning from them would set the bar too low.
        val learn = history.size >= referenceLagFrames
        val ready = noiseSamples >= noiseMinSamples
        // A plain mean over the first samples, then an exponential one.
        val alpha = maxOf(1f / (noiseSamples + 1), 1f / noiseWindowFrames)
        val changed = coarseChangedCount(sample, reference, channels, t.cellDelta, learn, ready, alpha)
        // Always run, whatever the sensitivity, so the noise estimate is ready if it changes.
        val block = fineBlock(sample, reference, channels, learn, ready, alpha) && t.smallObjects
        if (learn) noiseSamples++
        history.addLast(sample)
        if (history.size > referenceLagFrames) spare = history.removeFirst()
        val fraction = changed.toDouble() / cellCount
        return Result(motion = fraction >= t.changedFraction || block, changedFraction = fraction, block = block)
    }

    /**
     * Coarse cells that changed beyond the cell delta and their own noise, once the frame-wide
     * exposure shift is taken out, counting only those with a changed 4-neighbour. Unchanged
     * cells feed the noise estimate; until it is [ready] the cell delta alone applies.
     */
    private fun coarseChangedCount(
        sample: IntArray, reference: IntArray, channels: Int, delta: Int,
        learn: Boolean, ready: Boolean, alpha: Float,
    ): Int {
        for (ch in 0 until channels) {
            for (c in 0 until cellCount) shiftScratch[c] = sample[c * channels + ch] - reference[c * channels + ch]
            java.util.Arrays.sort(shiftScratch)
            exposureShift[ch] = shiftScratch[cellCount / 2].coerceIn(-MAX_EXPOSURE_SHIFT, MAX_EXPOSURE_SHIFT)
        }
        val noise = coarseNoise
        for (c in 0 until cellCount) {
            var changed = false
            for (ch in 0 until channels) {
                val i = c * channels + ch
                val d = kotlin.math.abs(sample[i] - exposureShift[ch] - reference[i]).toFloat()
                val bar = if (ready) maxOf(delta.toFloat(), COARSE_NOISE_MULTIPLE * noise[i]) else delta.toFloat()
                if (d >= bar) changed = true
                else if (learn) noise[i] += alpha * (d - noise[i])
            }
            coarseChanged[c] = changed
        }
        var count = 0
        for (y in 0 until gridRows) {
            for (x in 0 until gridCols) {
                val i = y * gridCols + x
                if (!coarseChanged[i]) continue
                val supported = (x > 0 && coarseChanged[i - 1]) || (x < gridCols - 1 && coarseChanged[i + 1]) ||
                    (y > 0 && coarseChanged[i - gridCols]) || (y < gridRows - 1 && coarseChanged[i + gridCols])
                if (supported) count++
            }
        }
        return count
    }

    /**
     * Marks fine cells whose change beats their noise, folds the unchanged ones into the noise
     * estimate, and reports whether any 2x2 block changed - never before the estimate is [ready].
     */
    private fun fineBlock(
        sample: IntArray, reference: IntArray, channels: Int, learn: Boolean, ready: Boolean, alpha: Float,
    ): Boolean {
        val offset = cellCount * channels
        val noise = fineNoise
        for (c in 0 until fineCount) {
            var changed = false
            for (ch in 0 until channels) {
                val i = c * channels + ch
                // Same exposure shift as the coarse test (computed there, just before this).
                val d = kotlin.math.abs(sample[offset + i] - exposureShift[ch] - reference[offset + i]).toFloat()
                val beats = ready && d >= maxOf(BLOCK_MIN_DELTA, BLOCK_NOISE_MULTIPLE * noise[i])
                if (beats) changed = true
                else if (learn) noise[i] += alpha * (d - noise[i])
            }
            fineChanged[c] = changed
        }
        if (!ready) return false
        for (y in 0 until fineRows - 1) {
            val row = y * fineCols
            for (x in 0 until fineCols - 1) {
                val i = row + x
                if (fineChanged[i] && fineChanged[i + 1] && fineChanged[i + fineCols] && fineChanged[i + fineCols + 1]) return true
            }
        }
        return false
    }

    /** Drop the reference frames - call after a gap where frames stopped arriving, or after
     *  anything that changed the picture without the scene moving (see [CameraDisturbance]). */
    fun reset() {
        history.clear()
        framesSeen = 0
        fineNoise.fill(0f)
        coarseNoise.fill(0f)
        noiseSamples = 0
    }

    private fun takeSpare(channels: Int): IntArray {
        val s = spare
        spare = null
        val size = (cellCount + fineCount) * channels
        return if (s != null && s.size == size) s else IntArray(size)
    }

    /** Per-cell, per-channel mean of the block each cell covers. Cells are at least one pixel,
     *  so a frame smaller than the grid degrades to point sampling rather than empty cells. */
    private fun sampleInto(
        out: IntArray, offset: Int, cols: Int, rows: Int,
        pixels: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int, channels: Int,
    ) {
        var i = offset
        for (gy in 0 until rows) {
            val y0 = (gy * height) / rows
            val y1 = maxOf(y0 + 1, ((gy + 1) * height) / rows).coerceAtMost(height)
            for (gx in 0 until cols) {
                val x0 = (gx * width) / cols
                val x1 = maxOf(x0 + 1, ((gx + 1) * width) / cols).coerceAtMost(width)
                val n = (y1 - y0) * (x1 - x0)
                for (ch in 0 until channels) {
                    var sum = 0
                    for (y in y0 until y1) {
                        var p = y * rowStride + x0 * pixelStride + ch
                        for (x in x0 until x1) {
                            sum += pixels[p].toInt() and 0xFF
                            p += pixelStride
                        }
                    }
                    out[i++] = (sum + n / 2) / n
                }
            }
        }
    }

    /**
     * Fit to 72 Pixel 6 clips (2026-09-27/28) replayed through this algorithm, against the old
     * luma / previous-frame one: "high" is the only level that catches the drawn blinds, and no
     * level misses anything its old counterpart caught. Medium and low were raised to stay close
     * to their old trigger rates, since a lagged reference is more sensitive at the same numbers.
     * High's small-object test: see [BLOCK_NOISE_MULTIPLE].
     */
    private fun thresholdsFor(sensitivity: String): Thresholds = when (sensitivity.lowercase()) {
        "high" -> Thresholds(cellDelta = 10, changedFraction = 0.015, smallObjects = true)
        "low" -> Thresholds(cellDelta = 22, changedFraction = 0.080)
        else -> Thresholds(cellDelta = 26, changedFraction = 0.040) // medium / anything unrecognised
    }

    private companion object {
        /**
         * Fit to 2026-09-28 traces of the building across the street. Daytime (pixel noise ~2): a
         * person's head behind a laptop was caught about as often as by the old fixed delta of 10,
         * with nothing firing elsewhere. Night (noise ~12, 19:28-21:48): 0.3% of frames fired
         * outside the lit centre-right room, all of it a light moving in another lit window,
         * against 90% for the fixed delta.
         */
        const val BLOCK_NOISE_MULTIPLE = 6f
        const val BLOCK_MIN_DELTA = 8f

        /**
         * Fit to the 2026-09-29 night (22:43-04:43, 30-100% of frames firing on the fixed delta):
         * 5x brought it to 2-15 short events per 10 minutes, all of them a lit window changing;
         * 4x still let noise through (22-56 events). Daytime and the early evening unchanged.
         */
        const val COARSE_NOISE_MULTIPLE = 5f

        /** Most of a frame-wide shift that is still taken for the camera's exposure rather than the
         *  scene. Night exposure hunting moved the whole frame 6-18 levels; a light switching on is
         *  a much bigger step, and all but this much of it still counts. */
        const val MAX_EXPOSURE_SHIFT = 24
    }
}
