package com.panopticon.phoneapp.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import com.panopticon.phoneapp.calibration.RectNorm
import kotlin.math.abs

/**
 * Shared sensor-aspect source-buffer sizing, centre-crop math, and rotation-aware output sizing
 * for the GL blit both [CameraGlPipeline] (RECORD) and [LivePipeline] (LIVE) use to get from
 * "whatever aspect ratio the camera's SurfaceTexture output happens to be" to a target aspect
 * ratio without an anamorphic squash, and then to the final on-screen orientation.
 */
internal object CameraFraming {
    private const val TAG = "CameraFraming"

    /**
     * The camera -> SurfaceTexture buffer size for capturing at [target]'s aspect ratio without a
     * squash. When the sensor's native aspect ratio matches [target]'s, this is just [target] (the
     * HAL centre-crops to it cleanly). Otherwise it's the smallest sensor-aspect SurfaceTexture
     * size that covers [target] in both dimensions - the HAL then fills that buffer with the full
     * un-squashed sensor image and [computeTexCrop] trims it to [target]'s aspect in GL. Falls
     * back to [target] if the camera exposes no usable sensor-aspect size (the pre-existing
     * squashed behaviour - logged).
     */
    fun pickSourceSize(cameraManager: CameraManager, id: String, target: Size): Size {
        val chars = cameraManager.getCameraCharacteristics(id)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val targetA = target.width.toDouble() / target.height
        val sensorA =
            if (active != null && active.height() > 0) active.width().toDouble() / active.height() else targetA
        if (abs(sensorA - targetA) < 0.02) return target

        val stSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val sensorAspect = stSizes.filter { abs(it.width.toDouble() / it.height - sensorA) < 0.04 }
        val pick = sensorAspect
            .filter { it.width >= target.width && it.height >= target.height }
            .minByOrNull { it.width.toLong() * it.height }
            ?: sensorAspect.maxByOrNull { it.width.toLong() * it.height }
        if (pick == null) {
            Log.w(TAG, "pickSourceSize: no sensor-aspect SurfaceTexture size (sensorA=$sensorA); recording may be squashed")
            return target
        }
        return pick
    }

    /**
     * (texCropX, texCropY) that centre-crop a `srcWidth x srcHeight` frame down to
     * `dstWidth x dstHeight`'s aspect ratio (1,1 when they already match). The source dimensions
     * must be the ones actually *displayed*, which is what [correctedTexCrop] works out - a raw
     * `sourceSize` lands on the wrong axis on cameras whose transform matrix swaps them.
     *
     * Takes plain `Int`s rather than `Size` so it is directly unit-testable on a plain JVM
     * (`android.util.Size`'s accessors throw "not mocked" there).
     */
    fun computeTexCrop(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Pair<Float, Float> {
        val srcA = srcWidth.toDouble() / srcHeight
        val dstA = dstWidth.toDouble() / dstHeight
        return when {
            srcA > dstA + 1e-4 -> (dstA / srcA).toFloat() to 1f
            srcA < dstA - 1e-4 -> 1f to (srcA / dstA).toFloat()
            else -> 1f to 1f
        }
    }

    /**
     * Whether a `SurfaceTexture.getTransformMatrix()` output swaps the X/Y axes outright, rather
     * than just flipping/scaling them - seen on some cameras (confirmed on a Pixel 6 back camera),
     * where the OES buffer is stored transposed relative to the width/height Camera2 reports for
     * it. [stMatrix] is the 16-float column-major matrix `getTransformMatrix` fills; compares how
     * much a unit step along raw X moves the natural X output (`stMatrix[0]`) against how much a
     * unit step along raw Y does (`stMatrix[4]`) - swapped when the latter dominates.
     */
    fun axesSwapped(stMatrix: FloatArray): Boolean = abs(stMatrix[0]) < abs(stMatrix[4])

    /** [sourceSize] as it actually comes out of the camera's transform matrix - width/height
     *  swapped when [axesSwapped] (the buffer is transposed relative to what Camera2 reports),
     *  else unchanged. Feed this, not the raw [sourceSize], to [computeTexCrop]. */
    fun naturalSourceSize(width: Int, height: Int, stMatrix: FloatArray): Pair<Int, Int> =
        if (axesSwapped(stMatrix)) height to width else width to height

    /**
     * The (texCropX, texCropY) to actually render with: [computeTexCrop] against the size the
     * camera really *displays*, which is [sourceSize] transposed when this camera's [stMatrix]
     * swaps axes. This is the only crop a pipeline should hand to the shader - the raw
     * `computeTexCrop(sourceSize, ...)` lands on the wrong axis on swapping devices, which is the
     * squash bug in docs/quirks/camera2-recording-pipeline.md. [stMatrix] only exists once frames
     * are flowing, so callers compute this on their first frame rather than at setup.
     */
    fun correctedTexCrop(sourceSize: Size, recordingSize: Size, stMatrix: FloatArray): Pair<Float, Float> {
        val (naturalW, naturalH) = naturalSourceSize(sourceSize.width, sourceSize.height, stMatrix)
        return computeTexCrop(naturalW, naturalH, recordingSize.width, recordingSize.height)
    }

    /** [size] with width/height swapped when [rotationDegrees] is 90 or 270 - the actual encoder
     *  output dimensions once the GL rotation in [GlBlit.quad] is applied. */
    fun rotatedOutputSize(size: Size, rotationDegrees: Int): Size =
        if (rotationDegrees == 90 || rotationDegrees == 270) Size(size.height, size.width) else size

    /** [rotationDegrees] if it's a valid capture-orientation value, else 0. */
    fun normalizedRotation(rotationDegrees: Int): Int = if (rotationDegrees in setOf(0, 90, 180, 270)) rotationDegrees else 0

    /** [rect] (same centre) grown by `(1/factorX, 1/factorY)` - the inverse of a centred crop that
     *  scaled it down by [factorX]/[factorY]. */
    fun growBySameCentre(rect: RectNorm, factorX: Float, factorY: Float): RectNorm {
        val w = (rect.r - rect.l) / factorX
        val h = (rect.b - rect.t) / factorY
        return recentre(rect, w, h)
    }

    /** [rect]'s centre, resized to [w]x[h], shifted (not shrunk) back into `0f..1f` if it would
     *  overflow. */
    private fun recentre(rect: RectNorm, w: Float, h: Float): RectNorm {
        val cx = (rect.l + rect.r) / 2
        val cy = (rect.t + rect.b) / 2
        var l = cx - w / 2
        var t = cy - h / 2
        var r = cx + w / 2
        var b = cy + h / 2
        if (l < 0f) { r -= l; l = 0f }
        if (r > 1f) { l -= (r - 1f); r = 1f }
        if (t < 0f) { b -= t; t = 0f }
        if (b > 1f) { t -= (b - 1f); b = 1f }
        return RectNorm(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }
}
