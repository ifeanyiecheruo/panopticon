package com.panopticon.phoneapp.motion

/**
 * The motion-gated recording state machine, replacing this slice's original
 * "always record" behaviour. Two phases:
 *
 *  - **ARMED** - camera session up, luma frames being analysed, nothing
 *    written to disk.
 *  - **RECORDING** - a clip is being written. Entered the instant motion is
 *    seen; held for [trailerMs] after motion last stopped (the "trailer" tail,
 *    so a clip doesn't cut the moment someone briefly stops moving), then back
 *    to ARMED.
 *
 * No pre-roll: `MediaRecorder` (this slice's encoder, see `CameraPipeline`)
 * can't back-date a buffer, so a clip starts at motion-detection time, not a
 * few seconds before. A pre-roll ring buffer is follow-up work tied to the
 * `MediaCodec`+`MediaMuxer` switch.
 *
 * Pure and synchronous - no coroutines, no clock of its own (caller passes
 * `nowMs`), so it unit-tests directly.
 */
class RecordingPhaseController(
    private val trailerMs: Long = 5_000L,
) {
    enum class Phase { ARMED, RECORDING }

    var phase: Phase = Phase.ARMED
        private set

    private var lastMotionMs: Long = 0L

    /**
     * Feed one analysed frame's verdict. Returns the phase after applying it,
     * so a caller can `if (onFrame(...) != previous) reconfigure()`.
     */
    fun onFrame(motion: Boolean, nowMs: Long): Phase {
        if (motion) {
            lastMotionMs = nowMs
            phase = Phase.RECORDING
            return phase
        }
        if (phase == Phase.RECORDING && nowMs - lastMotionMs >= trailerMs) {
            phase = Phase.ARMED
        }
        return phase
    }

    /** Milliseconds until the trailer expires (0 if not recording or already expired). */
    fun trailerRemainingMs(nowMs: Long): Long {
        if (phase != Phase.RECORDING) return 0L
        return (lastMotionMs + trailerMs - nowMs).coerceAtLeast(0L)
    }

    /** Force back to ARMED - e.g. when the pipeline stops or switches to LIVE. */
    fun disarm() {
        phase = Phase.ARMED
        lastMotionMs = 0L
    }
}
