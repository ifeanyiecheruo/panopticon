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

    /** The size RECORD mode ([CameraGlPipeline]) would pick for [id] given [videoResolution],
     *  used by LIVE to keep its own aspect ratio in step. `null` if the camera/codec can't be
     *  queried (caller should fall back to its own independent selection). */
    fun recordModeSize(cameraManager: CameraManager, id: String, videoResolution: String): Size? = runCatching {
        val map = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val camSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val caps = avcVideoCapabilities()
        val supported = camSizes.filter { caps == null || caps.isSizeSupported(it.width, it.height) }
        select(supported, parseVideoSize(videoResolution))
    }.getOrNull()

    private fun avcVideoCapabilities() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
}
