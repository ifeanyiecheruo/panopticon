package com.panopticon.phoneapp.calibration

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "CalibrationStore"

/**
 * Persists the last completed [CalibrationResult] as a single JSON file under
 * app-specific external storage (same storage class as [com.panopticon.phoneapp.clips.SegmentStore],
 * no permission needed, wiped on uninstall). One file, always read/written
 * whole - the document is small and there's only ever one "last result".
 *
 * docs/design/http-api.md frames this as "written to the phone's local DB"; this
 * slice has no on-device DB (config + clip index are both SharedPreferences/
 * JSON), so a plain file matches the existing persistence style.
 */
class CalibrationStore(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val file: File =
        File(appContext.getExternalFilesDir(null), "calibration/last-result.json").also {
            it.parentFile?.mkdirs()
        }

    @Synchronized
    fun load(): CalibrationResult? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<CalibrationResult>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "could not parse persisted calibration result, ignoring", e)
            null
        }
    }

    @Synchronized
    fun save(result: CalibrationResult) {
        try {
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeText(json.encodeToString(result))
            if (!tmp.renameTo(file)) {
                // renameTo can fail across some FUSE-backed external dirs - fall
                // back to a direct overwrite rather than losing the result.
                file.writeText(tmp.readText())
                tmp.delete()
            }
            Log.i(TAG, "persisted calibration result ${result.runId} (${result.cameras.size} cameras)")
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist calibration result", e)
        }
    }
}
