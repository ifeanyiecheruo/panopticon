package com.panopticon.phoneapp.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import com.panopticon.phoneapp.calibration.LogicalCameraApi28

private const val TAG = "CameraCatalog"

/**
 * Enumerates the device's cameras for `GET /api/cameras` and resolves the
 * "active" camera id the recording / live pipelines should open.
 *
 * Two id shapes:
 *  - a plain logical/physical id (`"0"`, `"1"`) - opened as-is;
 *  - `"<logical>:<physical>"` (`"0:2"`) - a logical multi-camera constrained to
 *    one of its physical sub-cameras. On phones like the Pixel 6 the ultrawide
 *    is a physical sub-camera of logical `0`, not a separate `cameraIdList`
 *    entry, so this is how the controller reaches it. API 28+; a device with no
 *    logical multi-cameras just lists the plain ids.
 *
 * Thin wrapper over `CameraManager` - label logic is in the framework-free
 * [CameraLabels]. Per-camera errors from a flaky HAL are swallowed, matching
 * `CalibrationRunner`.
 */
class CameraCatalog(context: Context) {

    private val cameraManager =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun ids(): List<String> = try {
        cameraManager.cameraIdList.toList()
    } catch (e: Exception) {
        Log.e(TAG, "cameraIdList threw", e)
        emptyList()
    }

    private fun charsOrNull(id: String): CameraCharacteristics? =
        runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()

    private fun facingOf(chars: CameraCharacteristics): String =
        when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }

    private fun firstFocalLength(chars: CameraCharacteristics): Float? =
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

    private data class Row(val id: String, val facing: String, val focal: Float?, val physical: Boolean)

    private fun rows(): List<Row> {
        val out = ArrayList<Row>()
        for (logicalId in ids()) {
            val chars = charsOrNull(logicalId) ?: continue
            out += Row(logicalId, facingOf(chars), firstFocalLength(chars), physical = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && LogicalCameraApi28.isLogicalMultiCam(chars)) {
                for (physId in LogicalCameraApi28.physicalIds(chars)) {
                    val pc = charsOrNull(physId) ?: continue
                    out += Row("$logicalId:$physId", facingOf(pc), firstFocalLength(pc), physical = true)
                }
            }
        }
        return out
    }

    /** Every selectable camera, in a stable wide->tele / back->front order. */
    fun list(activeCameraId: String): List<CameraInfo> {
        val resolvedActive = resolveActiveId(activeCameraId)
        val rows = rows()
        // Peers per facing so CameraLabels can place wide/ultra-wide/tele. Physical sub-cameras
        // are the meaningful spread on a logical multi-cam device, so key off those where present.
        val peersByFacing = rows.groupBy({ it.facing }) { it.focal ?: 0f }
        return rows
            .map { r ->
                val base = CameraLabels.label(r.focal, r.facing, peersByFacing[r.facing] ?: emptyList())
                CameraInfo(
                    cameraId = r.id,
                    facing = r.facing,
                    label = if (r.physical) base else "$base (auto)",
                    focalLengthMm = r.focal,
                    isActive = r.id == resolvedActive,
                )
            }
            .sortedWith(
                compareBy({ CameraLabels.sortKey(it.facing, it.focalLengthMm) }, { it.cameraId }),
            )
    }

    fun has(cameraId: String): Boolean = rows().any { it.id == cameraId }

    /** The first back-facing logical camera, or the first camera at all - the pre-slice default. */
    fun defaultBackCameraId(): String? {
        val all = ids()
        return all.firstOrNull { id ->
            charsOrNull(id)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: all.firstOrNull()
    }

    /**
     * Turn a persisted `activeCameraId` (which may be "" = unset, or a stale id
     * from a since-removed camera) into an id a pipeline can actually open.
     */
    fun resolveActiveId(activeCameraId: String): String? =
        activeCameraId.takeIf { it.isNotBlank() && has(it) } ?: defaultBackCameraId()
}
