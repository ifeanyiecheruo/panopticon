package com.panopticon.phoneapp.motion

/**
 * A tap on exactly what the motion detector sees, for tuning it against real scenes offline.
 *
 * Null - and so free - unless something installs a [Sink]; only the debug build does
 * (`debug/DebugMotionTraceReceiver`). Recorded clips are no substitute: they are encoded, only
 * exist when motion was already detected, and so can never show what the detector missed.
 */
object MotionTrace {
    fun interface Sink {
        /**
         * Called on the motion-analysis thread for every analysed frame, so it must not block:
         * copy what it needs and return. [luma] (one byte per pixel, row-major, GL bottom-up
         * rows, unrotated) is reused by the caller after this returns.
         */
        fun onFrame(luma: ByteArray, width: Int, height: Int, nowMs: Long, verdict: MotionAnalyzer.Verdict)
    }

    @Volatile var sink: Sink? = null
}
