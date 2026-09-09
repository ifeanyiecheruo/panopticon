package com.panopticon.phoneapp.calibration

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Range
import androidx.annotation.RequiresApi

/**
 * API-gated `CaptureRequest`/`CaptureResult` keys, each isolated in its own
 * class so the Dalvik/ART verifier only loads it when the runtime SDK check
 * has already passed. Referencing e.g. `CaptureResult.CONTROL_ZOOM_RATIO`
 * (API 30) directly inside a method also used on API 28 throws
 * `NoSuchFieldError` when that method is verified - the `if (SDK_INT >= R)`
 * guard around the *call* doesn't help. Reconfirmed on a real API 28 device
 * (BLU G5) - see docs/quirks/calibration-zoom.md.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object ZoomRatioApi30 {
    fun setRequest(b: CaptureRequest.Builder, ratio: Float) {
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
    }

    fun readResult(r: CaptureResult): Float? = r.get(CaptureResult.CONTROL_ZOOM_RATIO)

    fun ratioRange(chars: CameraCharacteristics): Range<Float>? =
        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
}

@RequiresApi(Build.VERSION_CODES.Q)
internal object ActivePhysicalIdApi29 {
    fun read(r: TotalCaptureResult): String? =
        r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
}

@RequiresApi(Build.VERSION_CODES.P)
internal object LogicalCameraApi28 {
    fun physicalIds(chars: CameraCharacteristics): List<String> =
        chars.physicalCameraIds.toList()

    fun isLogicalMultiCam(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }
}
