package com.panopticon.phoneapp.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size

/**
 * Shared recording-size selection so RECORD ([CameraGlPipeline]) and LIVE ([LivePipeline]) pick
 * the exact same size for the same requested `videoResolution` - both now capture through a
 * `SurfaceTexture` (see [CameraFraming]/[GlBlit]), so there's a single, shared enumeration and
 * fallback ladder rather than two pipelines guessing independently and risking divergent aspect
 * ratios (which used to show up as a live-preview/recording viewport mismatch, worse the more the
 * user had zoomed in).
 */
internal object RecordingSizeSelection {

    /** Exact match on [want] if present, else 720p, else the largest size at or below 720p,
     *  else a hardcoded 1280x720. Shared fallback ladder for both pipelines. */
    fun select(supported: List<Size>, want: Size?): Size {
        want?.let { w -> supported.firstOrNull { it.width == w.width && it.height == w.height }?.let { return it } }
        return supported.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: supported.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
    }

    /**
     * The size RECORD mode ([CameraGlPipeline]) captures and encodes at for [id]: the viewing size
     * named by [videoResolution], exactly as LIVE does - never reduced for zoom.
     *
     * It used to be reduced (to 1024x576 at 4.2x on a Pixel 6) on the reasoning that digital zoom
     * is a crop-and-upscale, so a zoomed view only holds `viewing x fraction` real pixels. That
     * stopped being true when zoom moved into the camera: most of it is now `CONTROL_ZOOM_RATIO`,
     * which the ISP crops from the full sensor and reads out at the *stream* size, so a smaller
     * stream threw away real detail before GL's residual crop magnified what was left. At 2.7x
     * hardware + 1.6x GL that left ~640 real pixels across a recording against ~2400 in the live
     * preview (2026-09-29) - visibly soft clips beside a sharp preview. The bitrate is fixed
     * (see CameraGlPipeline's ENCODER_BIT_RATE), so full size costs encoder work, not storage.
     *
     * Falls back to 1280x720 when the camera/codec can't be queried.
     */
    fun recordingSize(cameraManager: CameraManager, id: String, videoResolution: String): Size =
        recordModeSize(cameraManager, id, videoResolution) ?: Size(1280, 720)

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
