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
         * Called on the GL thread for every analysed frame, so it must not block: copy what it
         * needs and return. [rgba] is reused by the caller after this returns.
         */
        fun onFrame(rgba: ByteArray, width: Int, height: Int, nowMs: Long, verdict: MotionAnalyzer.Verdict)
    }

    @Volatile var sink: Sink? = null
}
