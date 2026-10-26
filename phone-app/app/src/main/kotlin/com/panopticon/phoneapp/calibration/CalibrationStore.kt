package com.panopticon.phoneapp.calibration

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "CalibrationStore"

/**
 * Persists calibration as two JSON files under app-specific external storage (same storage class
 * as [com.panopticon.phoneapp.clips.SegmentStore], no permission needed, wiped on uninstall). Each
 * is always read/written whole - the documents are small.
 *
 * - **completed** (`last-result.json`, the name it has always had): every camera's most recent
 *   *completed* calibration. This is what `GET /api/calibration/result` serves. A camera's entry
 *   is replaced only when a newer sweep finishes that camera ([promoteCamera]), so a re-run that
 *   is cancelled part-way leaves the old data serving.
 * - **in-progress** (`in-progress.json`): the sweep that has steps left, `complete: false`,
 *   rewritten after every (camera, resolution) step. Steps it never reached are simply absent.
 *   Deleted once that sweep completes.
 *
 * docs/design/http-api.md frames this as "written to the phone's local DB"; this
 * slice has no on-device DB (config + clip index are both SharedPreferences/
 * JSON), so plain files match the existing persistence style.
 */
class CalibrationStore(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val dir = File(appContext.getExternalFilesDir(null), "calibration").also { it.mkdirs() }
    private val completedFile = File(dir, "last-result.json")
    private val inProgressFile = File(dir, "in-progress.json")

    @Synchronized
    fun loadCompleted(): CalibrationResult? = read(completedFile)

    @Synchronized
    fun loadInProgress(): CalibrationResult? = read(inProgressFile)

    /** What the camera pipelines build their zoom LUT from - see [zoomView]. */
    @Synchronized
    fun loadForZoom(): CalibrationResult? = zoomView(read(completedFile), read(inProgressFile))

    @Synchronized
    fun saveInProgress(result: CalibrationResult) = write(inProgressFile, result)

    @Synchronized
    fun clearInProgress() {
        inProgressFile.delete()
    }

    /** Makes [camera] that camera's completed calibration - see [withCameraPromoted]. */
    @Synchronized
    fun promoteCamera(runId: String, runAtMs: Long, deviceIdentity: ResultDeviceIdentity, cameraId: String, camera: CameraCalibration) {
        withCameraPromoted(read(completedFile), runId, runAtMs, deviceIdentity, cameraId, camera)
            ?.let { write(completedFile, it) }
    }

    /** Erases every calibration this phone holds, completed and in-progress. */
    @Synchronized
    fun erase() {
        completedFile.delete()
        inProgressFile.delete()
        Log.i(TAG, "erased calibration")
    }

    private fun read(file: File): CalibrationResult? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<CalibrationResult>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "could not parse persisted calibration ${file.name}, ignoring", e)
            null
        }
    }

    private fun write(file: File, result: CalibrationResult) {
        try {
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeText(json.encodeToString(result))
            if (!tmp.renameTo(file)) {
                // renameTo can fail across some FUSE-backed external dirs - fall
                // back to a direct overwrite rather than losing the result.
                file.writeText(tmp.readText())
                tmp.delete()
            }
            Log.i(TAG, "persisted calibration ${file.name} ${result.runId} (${result.cameras.size} cameras)")
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist calibration ${file.name}", e)
        }
    }
}
