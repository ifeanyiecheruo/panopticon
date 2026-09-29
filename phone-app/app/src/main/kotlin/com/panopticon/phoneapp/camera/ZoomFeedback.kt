package com.panopticon.phoneapp.camera

import android.hardware.camera2.CaptureResult
import android.os.Build
import com.panopticon.phoneapp.calibration.ZoomRatioApi30

/**
 * The zoom the camera says it is **actually** delivering, per captured frame.
 *
 * Every `CaptureResult` describes one frame and carries that frame's sensor timestamp;
 * `SurfaceTexture.getTimestamp()` on a camera-fed texture is the same value from the same clock.
 * So results recorded here can be looked up by the timestamp of the frame the GL thread is about
 * to draw, and the shader can be told what the buffer in its hands really contains instead of
 * what we asked for some frames ago. That is the whole point: the request and the delivery are
 * different things for ~10 frames after a zoom change, and rendering as if they were not is what
 * produced the false motion events documented in docs/quirks/camera2-recording-pipeline.md.
 *
 * A small ring is enough - the lookup is always for a frame that has just arrived, so the useful
 * history is a fraction of a second.
 */
internal class ZoomFeedback(private val capacity: Int = 64) {
    private val timestamps = LongArray(capacity)
    private val ratios = FloatArray(capacity)
    private var next = 0
    private var size = 0

    /** Results that carried a usable zoom signal, and results that did not. Their ratio is what
     *  [appliedRatio] uses to decide whether this device can be read back at all. */
    @Volatile var readable: Long = 0L
        private set
    @Volatile var unreadable: Long = 0L
        private set

    @Synchronized
    fun record(sensorTimestampNs: Long, hwRatio: Float?) {
        if (hwRatio == null || !hwRatio.isFinite()) {
            unreadable++
            return
        }
        readable++
        timestamps[next] = sensorTimestampNs
        ratios[next] = hwRatio
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun clear() {
        next = 0
        size = 0
        readable = 0L
        unreadable = 0L
    }

    /**
     * The magnification the buffer timestamped [frameTimestampNs] holds.
     *
     * Three cases, in order:
     *  - **something has been read back** - the newest result at or before this frame, or the
     *    oldest held if the frame predates all of them.
     *  - **nothing yet, but results are arriving** - `1.0`. Honest: no zoom has been confirmed, so
     *    GL does all of it. Correct framing, merely softer, for the handful of frames before the
     *    first result lands.
     *  - **this device reports neither key** ([UNREADABLE_BEFORE_TRUSTING_REQUEST] results in a
     *    row with nothing in them) - fall back to [requested], i.e. the old assume-it-landed
     *    behaviour, because permanently refusing to use the hardware zoom would be a worse
     *    regression than a transient on a device we cannot observe. The motion gate still covers
     *    the transition there; only the picture snaps, not the clip list.
     */
    @Synchronized
    fun appliedRatio(frameTimestampNs: Long, requested: Float): Float {
        if (size == 0) {
            return if (unreadable >= UNREADABLE_BEFORE_TRUSTING_REQUEST) requested else 1f
        }
        var best = -1
        var bestTs = Long.MIN_VALUE
        var oldest = 0
        var oldestTs = Long.MAX_VALUE
        for (i in 0 until size) {
            val ts = timestamps[i]
            if (ts < oldestTs) { oldestTs = ts; oldest = i }
            if (ts <= frameTimestampNs && ts > bestTs) { bestTs = ts; best = i }
        }
        return if (best >= 0) ratios[best] else ratios[oldest]
    }

    companion object {
        const val UNREADABLE_BEFORE_TRUSTING_REQUEST = 30L
    }
}

/**
 * Pulls the delivered magnification out of a `CaptureResult`, by whichever route this camera's
 * zoom is actually driven through - the same fork [CameraControlApply.applyZoom] writes down.
 *
 * Reading the *other* key is not a useful fallback: the Pixel 6 reports `SCALER_CROP_REGION` as
 * the full active array at every ratio while `CONTROL_ZOOM_RATIO` tracks correctly (see
 * docs/quirks/calibration-zoom.md), so a device that zooms by ratio must be read by ratio or not
 * at all. A value outside the camera's own declared range is treated as no value: it can only be
 * a HAL reporting something it does not mean, and acting on it would put the crop somewhere the
 * user never asked for.
 */
internal object AppliedZoomReader {
    fun read(result: CaptureResult, caps: CameraCapabilities?): Float? {
        val raw = if (caps?.zoomViaRatioApi == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ZoomRatioApi30.readResult(result)
        } else {
            val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: return null
            val w = caps?.activeArrayWidth ?: 0
            if (w <= 0 || crop.width() <= 0) return null
            w.toFloat() / crop.width().toFloat()
        }
        if (raw == null || !raw.isFinite()) return null
        val hi = caps?.zoomRatioRange?.hi?.takeIf { it >= 1f } ?: ZoomGeometry.MAX_MAGNIFICATION
        // Only the high side is rejected as nonsense. A sub-1.0 report is a real reading on a
        // camera whose wide end zooms *out* (the Pixel 6's 0.67x), and the split never asks for
        // one, so it means "no magnification" rather than "bad data" - clamping it keeps the
        // device on the readable path instead of tipping it into the trust-the-request fallback.
        return if (raw > hi * 1.01f) null else raw.coerceAtLeast(1f)
    }
}
