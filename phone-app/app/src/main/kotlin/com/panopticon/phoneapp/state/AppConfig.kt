package com.panopticon.phoneapp.state

import android.content.Context
import android.os.Build
import com.panopticon.phoneapp.camera.CameraControlSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persisted device configuration - GET/POST /api/config in phone-http-api.md.
 * Backed by a single SharedPreferences JSON blob rather than per-field prefs: this is a thin
 * vertical slice and the whole document is small and always read/written together.
 *
 * [activeCameraId] and [cameraControls] back the camera-selection and camera-control routes;
 * they live here (not just in memory) so a camera + manual-control state tuned while watching
 * the live preview also governs recording and survives an app restart.
 */
@Serializable
data class DeviceConfig(
    val deviceName: String = "Panopticon ${Build.MODEL}",
    val motionSensitivity: String = "medium", // low | medium | high (stubbed - not wired to a real detector yet)
    val storageCapBytes: Long = 8_000_000_000L,
    val ringBufferMaxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    val rotationDegrees: Int = 0,
    /** Record/broadcast size "<w>x<h>"; "" = let the pipeline pick (720p-ish). */
    val videoResolution: String = "",
    /** Physical camera id the pipelines open; "" = resolve the default back camera. */
    val activeCameraId: String = "",
    val cameraControls: CameraControlSpec = CameraControlSpec(),
)

class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences("panopticon_config", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun get(): DeviceConfig {
        val raw = prefs.getString(KEY, null) ?: return DeviceConfig()
        return try {
            json.decodeFromString<DeviceConfig>(raw)
        } catch (e: Exception) {
            DeviceConfig()
        }
    }

    @Synchronized
    fun update(mutator: (DeviceConfig) -> DeviceConfig): DeviceConfig {
        val next = mutator(get())
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
        return next
    }

    companion object {
        private const val KEY = "device_config_json"
    }
}
