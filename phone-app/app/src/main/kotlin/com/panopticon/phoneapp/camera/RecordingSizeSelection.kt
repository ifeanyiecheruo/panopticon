package com.panopticon.phoneapp.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import com.panopticon.phoneapp.calibration.RectNorm
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Shared recording-size selection so RECORD ([CameraGlPipeline]) and LIVE ([LivePipeline]) pick
 * the exact same size for the same requested `videoResolution` - both now capture through a
 * `SurfaceTexture` (see [CameraFraming]/[GlBlit]), so there's a single, shared enumeration and
 * fallback ladder rather than two pipelines guessing independently and risking divergent aspect
 * ratios (which used to show up as a live-preview/recording viewport mismatch, worse the more the
 * user had zoomed in).
 */
internal object RecordingSizeSelection {

    /** How far a candidate's aspect ratio may stray from the viewing size's before it is
     *  rejected. A differently-shaped recording size would change the recorded field of view -
     *  [CameraFraming.correctedTexCrop] trims the sensor to whatever aspect it is handed - so a
     *  size that merely *fits* is not good enough. 16:9 (1.778) and 4:3 (1.333) are far enough
     *  apart that this only ever excludes a genuine shape change. */
    private const val ASPECT_TOLERANCE = 0.02

    /** The two sizes a recording pipeline needs: what the user asked for, and what is worth
     *  actually capturing given how far they have zoomed in. */
    data class RecordSizes(val viewing: Size, val recording: Size)

    /** Exact match on [want] if present, else 720p, else the largest size at or below 720p,
     *  else a hardcoded 1280x720. Shared fallback ladder for both pipelines. */
    fun select(supported: List<Size>, want: Size?): Size {
        want?.let { w -> supported.firstOrNull { it.width == w.width && it.height == w.height }?.let { return it } }
        return supported.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: supported.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
    }

    /**
     * The smallest supported size that still holds every pixel the zoomed view actually carries.
     *
     * Digital zoom is a crop-and-upscale: at a view covering [fx]x[fy] of the frame, only
     * `viewing.width * fx` real pixels exist across it, and rendering those into a full-size
     * frame manufactures the rest. Capturing and encoding at the larger size buys no detail and
     * costs pixel work in the ISP and the encoder both - which is the load that has been cooking
     * this device. So the recording size is chosen to match the detail, not the frame.
     *
     * Two rules, in this order:
     *  - **Never larger than [viewing] in either dimension.** The viewing size is the ceiling the
     *    user set; this function may only spend less. Enforced as its own filter rather than
     *    being left to fall out of the arithmetic, so an estimator bug cannot silently raise it.
     *  - Big enough in *both* dimensions for the view, which is measured per-axis rather than
     *    from a single ratio: [ZoomGeometry.ratioOf] averages the two sides, and a view that is
     *    not square in frame space would be under-served by the average.
     *
     * Falls back to [viewing] when nothing qualifies - the ceiling is always a safe answer,
     * because it is what would have been recorded before any of this existed.
     */
    fun recordingSizeFor(supported: List<Size>, viewing: Size, fx: Float, fy: Float): Size {
        val (w, h) = recordingSizeFor(
            supported.map { it.width to it.height }, viewing.width, viewing.height, fx, fy,
        )
        return Size(w, h)
    }

    /** [recordingSizeFor]'s actual decision, in plain Ints. `android.util.Size`'s own accessors
     *  throw "not mocked" under this module's plain-JVM unit tests, so the logic worth testing
     *  lives here and the Size-shaped overload above is a one-line adapter - the same split
     *  [CameraFraming] uses, and for the same reason. */
    fun recordingSizeFor(
        supported: List<Pair<Int, Int>>,
        viewW: Int,
        viewH: Int,
        fx: Float,
        fy: Float,
    ): Pair<Int, Int> {
        val needW = ceil(viewW * fx.coerceIn(1e-4f, 1f)).toInt()
        val needH = ceil(viewH * fy.coerceIn(1e-4f, 1f)).toInt()
        val viewAspect = viewW.toDouble() / viewH
        return supported.asSequence()
            .filter { (w, h) -> w <= viewW && h <= viewH }
            .filter { (w, h) -> w >= needW && h >= needH }
            .filter { (w, h) -> abs(w.toDouble() / h - viewAspect) <= ASPECT_TOLERANCE }
            .minByOrNull { (w, h) -> w.toLong() * h }
            ?: (viewW to viewH)
    }

    /** The fraction of each axis [view] covers, for [recordingSizeFor]. `null` (no view stored,
     *  or manual control off - the master switch that also means no zoom) reads as "no zoom", so
     *  the recording size is left at the viewing size. */
    fun viewFractionOf(view: RectNorm?, manualControlEnabled: Boolean): Pair<Float, Float>? {
        if (!manualControlEnabled || view == null) return null
        return (view.r - view.l) to (view.b - view.t)
    }

    /** Both sizes RECORD mode ([CameraGlPipeline]) would use for [id]: the viewing size named by
     *  [videoResolution], and the reduced size [viewFraction]'s zoom justifies. `null` if the
     *  camera/codec can't be queried. */
    fun recordModeSizes(
        cameraManager: CameraManager,
        id: String,
        videoResolution: String,
        viewFraction: Pair<Float, Float>?,
    ): RecordSizes? = runCatching {
        val supported = supportedSizes(cameraManager, id)
        val viewing = select(supported, parseVideoSize(videoResolution))
        val recording = viewFraction
            ?.let { (fx, fy) -> recordingSizeFor(supported, viewing, fx, fy) }
            ?: viewing
        RecordSizes(viewing, recording)
    }.getOrNull()

    /** The size RECORD mode ([CameraGlPipeline]) would pick for [id] given [videoResolution],
     *  used by LIVE to keep its own aspect ratio in step. `null` if the camera/codec can't be
     *  queried (caller should fall back to its own independent selection). */
    fun recordModeSize(cameraManager: CameraManager, id: String, videoResolution: String): Size? =
        runCatching { select(supportedSizes(cameraManager, id), parseVideoSize(videoResolution)) }.getOrNull()

    private fun supportedSizes(cameraManager: CameraManager, id: String): List<Size> {
        // Callers hand this either a plain camera id or a "<logical>:<physical>" target, and
        // getCameraCharacteristics throws on the composite form. The pipeline happened to pass
        // the resolved half and the HTTP route the composite, so the same selection silently
        // disagreed between what was recorded and what the API reported it as.
        val (logicalId, physId) = CameraCapabilitiesReader.splitTarget(id)
        val chars = runCatching { cameraManager.getCameraCharacteristics(physId ?: logicalId) }
            .getOrElse { cameraManager.getCameraCharacteristics(logicalId) }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val camSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val caps = avcVideoCapabilities()
        return camSizes.filter { caps == null || caps.isSizeSupported(it.width, it.height) }
    }

    private fun avcVideoCapabilities() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
}
