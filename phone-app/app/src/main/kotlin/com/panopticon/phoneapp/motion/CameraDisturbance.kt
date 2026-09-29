package com.panopticon.phoneapp.motion

/**
 * Something **we** did to the camera that changes the picture, while nothing in the scene moved.
 *
 * A frame-difference detector ([MotionDetector]) cannot tell one of these apart from a person
 * walking through the frame - both are "most of the grid changed between two frames". The
 * difference is knowledge the camera code has and the detector does not, so the camera code has
 * to hand it over: see [MotionGate].
 */
enum class CameraDisturbance {
    /** The pipeline is coming up: first frames, exposure and white balance still converging, the
     *  aspect trim not yet measured, no zoom applied yet. */
    PIPELINE_START,

    /** A hardware zoom request is in flight and the buffer contents are still moving towards it. */
    ZOOM,

    /** Manual controls (exposure, focus, white balance, metering regions) were re-applied to the
     *  repeating request. */
    CONTROLS,

    /** The capture session or its outputs were reconfigured - a resolution, rotation or camera
     *  change. */
    RECONFIGURE,
}

/**
 * The half of motion detection that camera code talks to: "the next few frames are my doing, do
 * not read them as motion".
 *
 * Implemented by the motion side ([MotionAnalyzer]) and handed to the camera components, rather
 * than the camera broadcasting events the detector subscribes to. Two reasons:
 *
 *  - **It is synchronous.** A disturbance has to be in force *before* the frame it explains is
 *    analysed. An event posted to another thread races the very frames it exists to suppress, and
 *    loses often enough to matter - the whole failure this guards against happens inside ~300ms.
 *  - **It is scoped, not fire-and-forget.** [beginDisturbance]/[endDisturbance] bracket a period
 *    whose end is genuinely known (a `CaptureResult` confirming the zoom landed, say), which a
 *    "something changed" notification cannot express - it can only offer a guessed timeout.
 *
 * Calls may arrive from several threads (the supervisor coroutine, the GL thread, the camera
 * callback thread) and may overlap; implementations track each [CameraDisturbance] independently
 * so an unrelated overlapping one cannot end another early. Repeating a [beginDisturbance] that is
 * already in force is a no-op, and ending one that was never begun is harmless - a caller on an
 * error path should not have to reason about which of the two it is.
 */
interface MotionGate {
    /** Suppress motion from now until the matching [endDisturbance]. */
    fun beginDisturbance(source: CameraDisturbance)

    /** [source] is over. Motion stays suppressed for a short settling window afterwards, and the
     *  next frame analysed becomes the detector's new reference. */
    fun endDisturbance(source: CameraDisturbance)

    companion object {
        /** For a pipeline with no motion detection attached - LIVE, which by design never
         *  analyses frames (docs/design/http-api.md, "motion detection stays out of live
         *  preview"). Lets both pipelines carry the same wiring without a null to forget. */
        val None: MotionGate = object : MotionGate {
            override fun beginDisturbance(source: CameraDisturbance) = Unit
            override fun endDisturbance(source: CameraDisturbance) = Unit
        }
    }
}
