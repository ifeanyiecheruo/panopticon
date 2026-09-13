package com.panopticon.phoneapp.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import kotlin.math.abs

/**
 * Shared recording-size selection so RECORD ([CameraGlPipeline]) and LIVE ([LivePipeline]) agree
 * on the *aspect ratio* they capture at, even though they enumerate candidate sizes through two
 * different `StreamConfigurationMap` output classes (a `SurfaceTexture` for RECORD's GL layer, a
 * `MediaCodec` input surface for LIVE) and so can see different size lists for the same requested
 * `videoResolution`.
 *
 * Why this matters: both pipelines apply `SCALER_CROP_REGION` (zoom) as a centred crop of the
 * full sensor active array, then rely on the camera HAL's own further-crop-to-stream-aspect step
 * to fit that region into their capture stream. Two *centred* aspect-crops of the same base
 * region are equivalent regardless of whether they happen in one step or two - so RECORD's extra
 * GL crop ([CameraGlPipeline.computeTexCrop]) reproduces the exact same field of view as LIVE's
 * single HAL crop, PROVIDED both streams end up at the same aspect ratio. If they silently pick
 * different fallback aspects (e.g. RECORD falls back to a 16:9 720p while LIVE falls back to a
 * 4:3 size because 1280x720 isn't in the MediaCodec-class list on that device), the extra crop
 * each stream's HAL applies differs, and the mismatch is small in absolute terms but grows
 * proportionally larger the more the user has zoomed in - matching the reported symptom.
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

    /** Picks a LIVE-mode size from [supported] that matches [recordSize]'s aspect ratio, so the
     *  live preview shows the same field of view RECORD would actually capture. Falls back to the
     *  plain [select] ladder (restricted to aspect-matched candidates when any exist). */
    fun selectMatchingAspect(supported: List<Size>, want: Size?, recordSize: Size): Size {
        want?.let { w -> supported.firstOrNull { it.width == w.width && it.height == w.height }?.let { return it } }
        val recordAspect = recordSize.width.toDouble() / recordSize.height
        val aspectMatched = supported.filter { abs(it.width.toDouble() / it.height - recordAspect) < 0.02 }
        return select(aspectMatched.ifEmpty { supported }, null)
    }

    private fun avcVideoCapabilities() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
}
