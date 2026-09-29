package com.panopticon.phoneapp.motion

/**
 * [MotionDetector] plus the knowledge of when not to trust it: the implementation of [MotionGate]
 * the camera pipelines hold.
 *
 * The detector compares a frame against the one before it. That comparison is only meaningful
 * across two frames the *camera* produced under the same conditions, so every frame captured
 * while we were changing those conditions has to be dropped - not merely reported as "no motion",
 * but dropped *as a reference*, or the first settled frame gets compared against a disturbed one
 * and the disturbance is detected one frame later instead.
 *
 * ## The settling window
 *
 * Suppression outlasts the disturbance itself. [endDisturbance] does not mean "the picture is
 * stable", it means "the thing we were waiting for has been confirmed" - the HAL may still be a
 * frame or two behind, auto-exposure re-converges after a zoom, and the detector needs a fresh
 * reference frame plus its own [MotionDetector.warmupFrames] before its verdict means anything.
 * So the gate stays shut for at least [settleFrames] more frames *and* [settleMs] more
 * milliseconds. Both are floors, because either alone has a hole: a frame count never expires if
 * frames stop arriving, and a deadline expires during a stall while no frames have been seen at
 * all.
 *
 * Not thread-safe by accident: [accept] is called from the single GL/camera-callback thread, but
 * [beginDisturbance]/[endDisturbance] arrive from the supervisor coroutine and the camera
 * callback thread too, so the gate state is synchronised. Contention is a handful of calls per
 * pipeline lifetime against one lock held for a few field writes.
 */
class MotionAnalyzer(
    /** Read per refresh rather than captured, so a sensitivity change from the controller lands
     *  without rebuilding the detector (which would throw away the reference frame). */
    private val sensitivity: () -> String,
    private val settleFrames: Int = 8,
    private val settleMs: Long = 400L,
    /**
     * How long one disturbance may hold the gate shut before it is dropped as abandoned.
     *
     * This is the safety net that keeps the whole mechanism from being able to fail dangerously.
     * Every [beginDisturbance] here is paired with an [endDisturbance] on some *other* thread and
     * often some other condition - a `CaptureResult` arriving, a session configuring. If any of
     * those paths is ever missed (an exception between the two, a HAL that stops reporting, a
     * future caller that forgets), an un-capped gate would leave a security camera silently not
     * recording, with nothing in the UI to say so. Expiring beats that: the worst case becomes a
     * handful of false clips, which is what this class exists to reduce, not a camera that has
     * quietly stopped watching.
     */
    private val maxHoldMs: Long = 3_000L,
    /** Monotonic, in the same units [accept]'s `nowMs` is given in. Injected so this whole class
     *  unit-tests on a plain JVM - nothing else here touches the framework. */
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val detector: MotionDetector = MotionDetector(sensitivity()),
) : MotionGate {

    /** What [accept] concluded, and whether it was allowed to conclude anything. */
    data class Verdict(
        val motion: Boolean,
        val changedFraction: Double,
        /** True when this frame was ours, not the scene's - see [MotionGate]. */
        val suppressed: Boolean,
        /** Whether the detector's fine small-object test fired - see [MotionDetector]. */
        val block: Boolean = false,
    )

    /** In-force disturbances, each with the time it began - see [maxHoldMs]. */
    private val active = HashMap<CameraDisturbance, Long>()
    private var settleFramesLeft = 0
    private var settleUntilMs = 0L

    /** Counters for `GET /api/camera/health`; plain reads, no lock needed for a monotonic long. */
    @Volatile var framesAnalysed: Long = 0L
        private set
    @Volatile var framesSuppressed: Long = 0L
        private set
    @Volatile var disturbances: Long = 0L
        private set

    /** Disturbances dropped by [maxHoldMs] rather than ended by their caller. Non-zero means a
     *  pairing bug or an unresponsive HAL, so it is surfaced in `GET /api/camera/health`. */
    @Volatile var disturbancesExpired: Long = 0L
        private set

    @Synchronized
    override fun beginDisturbance(source: CameraDisturbance) {
        if (active.put(source, nowMs()) == null) disturbances++
        // Armed now rather than at endDisturbance so a disturbance that ends between two frames
        // still costs a full settling window.
        settleFramesLeft = settleFrames
    }

    @Synchronized
    override fun endDisturbance(source: CameraDisturbance) {
        if (active.remove(source) == null) return
        if (active.isEmpty()) settleUntilMs = nowMs() + settleMs
    }

    /** Shut the gate unconditionally and forget every in-flight disturbance - a teardown, where
     *  the matching [endDisturbance] calls are never going to arrive. */
    @Synchronized
    fun reset() {
        active.clear()
        settleFramesLeft = settleFrames
        settleUntilMs = 0L
        detector.reset()
    }

    /**
     * Analyse one frame. Returns [Verdict.motion] `false` for any frame the gate is shut for, and
     * resets the detector's reference while it is, so the first frame through a re-opened gate
     * becomes the new reference rather than being compared against a disturbed one.
     */
    fun accept(pixels: ByteArray, width: Int, height: Int, rowStride: Int, nowMs: Long, pixelStride: Int = 1): Verdict {
        framesAnalysed++
        if (consumeSuppressed(nowMs)) {
            framesSuppressed++
            detector.reset()
            return Verdict(motion = false, changedFraction = 0.0, suppressed = true)
        }
        detector.sensitivity = sensitivity()
        val r = detector.accept(pixels, width, height, rowStride, pixelStride)
        return Verdict(motion = r.motion, changedFraction = r.changedFraction, suppressed = false, block = r.block)
    }

    /** True if this frame is suppressed; counts it against the frame floor if it is. */
    @Synchronized
    private fun consumeSuppressed(nowMs: Long): Boolean {
        expireStale()
        if (active.isNotEmpty()) return true
        if (settleFramesLeft > 0) {
            settleFramesLeft--
            return true
        }
        return nowMs < settleUntilMs
    }

    /** Drop disturbances whose [endDisturbance] is never coming - see [maxHoldMs]. Driven off
     *  [accept] rather than a timer: while no frames arrive there is nothing to suppress, and a
     *  gate that reopened during a stall would have nothing to protect anyway. */
    private fun expireStale() {
        if (active.isEmpty()) return
        val cutoff = nowMs() - maxHoldMs
        val it = active.entries.iterator()
        var expired = false
        while (it.hasNext()) {
            if (it.next().value <= cutoff) {
                it.remove()
                disturbancesExpired++
                expired = true
            }
        }
        if (expired && active.isEmpty()) settleUntilMs = nowMs() + settleMs
    }

    @Synchronized
    fun activeDisturbances(): Set<CameraDisturbance> = active.keys.toSet()

    private fun nowMs(): Long = clock()
}
