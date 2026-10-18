package com.panopticon.phoneapp.motion

/**
 * Motion detection by tracked displacement: something counts as motion only when textured image
 * structure **moves**, and keeps moving, across many frames. It does not count pixels that changed.
 *
 * Its predecessor counted changed grid cells, with filters, and had no way to tell a TV
 * flickering behind blinds from a person walking past: both are lots of changed pixels. Every
 * false trigger it had fails at least one of these tests:
 *
 * | source                                  | fails                                          |
 * |-----------------------------------------|------------------------------------------------|
 * | flicker, lights, auto-exposure          | no displacement: [Census] codes do not change  |
 * | sensor noise                            | no shift explains it ([BlockMotion]); not sustained |
 * | rain, snow                              | no frame-to-frame match; never sustained       |
 * | insects                                 | small and erratic ([MotionTracker] particle rule) |
 * | the camera itself shaking               | every block moves alike ([BlockMotion.globalMotion]) |
 *
 * Because confirmation needs a sustained track, the per-pixel stages run deliberately
 * sensitive. Confirmation costs [Params.confirmMs] of latency, which the recorder's 3s pre-roll
 * absorbs: a clip still starts before the object appeared.
 *
 * The pipeline, per analysed frame (luma, nominally 160x120 at ~15fps):
 * 1. [NoiseModel] learns the per-brightness noise level from frame differences;
 * 2. [BlockMotion] finds blocks whose luma changed beyond that noise, and, only there, whether
 *    their structure moved and by how much, matching [Census] codes (computed lazily, only around
 *    such blocks) at full and half resolution;
 * 3. [MotionTracker] follows the resulting blobs and confirms the ones that move as objects do.
 *
 * `motionSensitivity` selects object-level criteria (how long, how far, how big), not pixel
 * thresholds. Pixel thresholds follow the measured noise at every level.
 *
 * Not thread-safe: one thread calls [accept] and [reset] (the analysis worker).
 */
class MotionDetector(
    sensitivity: String,
    /** Frames back for the slow-object comparison: ~0.27s at 15fps. */
    internal val lagFrames: Int = 4,
    /** Frames after a (re)start that only seed history, while exposure settles. */
    internal val warmupFrames: Int = 3,
) {
    var sensitivity: String = sensitivity
        set(value) {
            if (field == value) return
            field = value
            params = paramsFor(value)
        }

    private var params = paramsFor(sensitivity)

    /**
     * @property motion a confirmed track is in view.
     * @property confirmedTracks / [candidateTracks] tracks that are / are not yet confirmed.
     * @property activeBlocks blocks whose structure changed; [movingBlocks] those that moved.
     * @property globalMotion this frame was taken as the camera moving and skipped.
     */
    data class Result(
        val motion: Boolean,
        val confirmedTracks: Int = 0,
        val candidateTracks: Int = 0,
        val activeBlocks: Int = 0,
        val movingBlocks: Int = 0,
        val globalMotion: Boolean = false,
    )

    private val noise = NoiseModel()
    private val tracker = MotionTracker()
    private var blocks: BlockMotion? = null
    private var width = 0
    private var height = 0

    /** Luma and lazily computed census history, newest at [ringHead]; [lagFrames] + 1 deep. */
    private var full: Census.Frames? = null
    /** The same at half resolution, two deep (current and previous), alternating by [ringHead]. */
    private var half: Census.Frames? = null
    private var ringHead = -1
    private var ringFilled = 0
    private var halfHead = 0
    private val eps = IntArray(256)
    private val epsHalf = IntArray(256)
    private val changeLo = IntArray(256)
    private val changeHi = IntArray(256)
    private val lagLo = IntArray(256)
    private val lagHi = IntArray(256)
    private var framesSeen = 0

    /** The tracker's state, for the replay harness's snapshots. */
    internal val debugTracker: MotionTracker get() = tracker

    /**
     * @param luma one byte per pixel, row-major, [rowStride] bytes per row.
     * @param nowMs a monotonic timestamp; confirmation and coasting are timed with it.
     */
    fun accept(luma: ByteArray, width: Int, height: Int, rowStride: Int, nowMs: Long): Result {
        if (width != this.width || height != this.height) resize(width, height)
        val full = full!!
        val half = half!!
        val slots = lagFrames + 1
        val havePrev = ringFilled > 0
        val prevHead = ringHead
        ringHead = (ringHead + 1) % slots
        full.invalidate(ringHead)
        halfHead = halfHead xor 1
        half.invalidate(halfHead)
        // Keep our own copy: the caller reuses its buffer, and the history needs old frames.
        val lumaCur = full.luma[ringHead]
        if (rowStride == width) {
            System.arraycopy(luma, 0, lumaCur, 0, width * height)
        } else {
            for (y in 0 until height) System.arraycopy(luma, y * rowStride, lumaCur, y * width, width)
        }
        framesSeen++
        if (havePrev) noise.update(lumaCur, full.luma[prevHead], width, height)
        noise.epsTable(eps, EPS_K, MIN_EPS)
        noise.epsTable(epsHalf, EPS_K, MIN_EPS, scale = 0.5f)
        Census.downsample(lumaCur, width, height, half.luma[halfHead])
        if (ringFilled < slots) ringFilled++

        if (framesSeen <= warmupFrames || !havePrev) {
            // Seed only: the newest warm-up frame becomes the first reference.
            if (framesSeen <= warmupFrames) { ringFilled = 1 }
            return Result(motion = false)
        }

        val prev = (ringHead - 1 + slots) % slots
        val lagged = (ringHead - lagFrames + slots) % slots
        val haveLag = ringFilled > lagFrames
        noise.changeTable(changeLo, changeHi, CHANGE_K, MIN_CHANGE)
        if (haveLag) {
            noise.changeTable(lagLo, lagHi, CHANGE_K, MIN_CHANGE, noise.globalShift(lumaCur, full.luma[lagged], width, height))
        }
        val bm = blocks!!
        bm.analyse(
            full, ringHead, prev, if (haveLag) lagged else -1, lagFrames,
            half, halfHead, halfHead xor 1,
            eps, epsHalf, changeLo, changeHi, lagLo, lagHi,
        )

        if (bm.globalMotion()) {
            // The camera moved, not the scene. Nothing is learned; tracks coast through it.
            return Result(
                motion = false, confirmedTracks = tracker.confirmedAlive, candidateTracks = tracker.tracks.size,
                activeBlocks = bm.activeCount, movingBlocks = bm.movingCount, globalMotion = true,
            )
        }
        tracker.update(bm.blobs(), nowMs, params)
        val confirmed = tracker.confirmedAlive
        return Result(
            motion = confirmed > 0,
            confirmedTracks = confirmed,
            candidateTracks = tracker.tracks.size - tracker.tracks.count { it.confirmed },
            activeBlocks = bm.activeCount,
            movingBlocks = bm.movingCount,
        )
    }

    /** Forget history and tracks - after a gap in frames, or anything that changed the picture
     *  without the scene moving (see [CameraDisturbance]). The noise level survives: it is a
     *  property of the sensor and the light, which a zoom or a control change does not alter. */
    fun reset() {
        framesSeen = 0
        ringFilled = 0
        tracker.reset()
        blocks?.reset()
    }

    private fun resize(w: Int, h: Int) {
        width = w; height = h
        full = Census.Frames(w, h, lagFrames + 1)
        half = Census.Frames(w / 2, h / 2, 2)
        ringHead = -1
        blocks = BlockMotion(w, h)
        noise.reset()
        reset()
    }

    private companion object {
        /** Census dead band, in standard deviations of a neighbour-minus-centre difference. */
        const val EPS_K = 2f
        const val MIN_EPS = 2
        /** A pixel has changed beyond this many standard deviations of a two-frame difference
         *  (~0.3% of pure-noise pixels). */
        const val CHANGE_K = 3f
        const val MIN_CHANGE = 4

        /**
         * Object-level criteria per sensitivity, at the 160x120 analysis resolution. "high"
         * accepts a distant person (a few pixels across) walking steadily for under half a
         * second; "low" wants something sizeable moving for a full second.
         */
        fun paramsFor(sensitivity: String): MotionTracker.Params = when (sensitivity.lowercase()) {
            "high" -> MotionTracker.Params(confirmMs = 400, minPath = 4f, minSupport = 6, smallSupport = 40, minCoherence = 0.75f)
            "low" -> MotionTracker.Params(confirmMs = 1_000, minPath = 10f, minSupport = 30, smallSupport = 100, minCoherence = 0.85f)
            else -> MotionTracker.Params(confirmMs = 600, minPath = 6f, minSupport = 12, smallSupport = 60, minCoherence = 0.8f)
        }
    }
}
