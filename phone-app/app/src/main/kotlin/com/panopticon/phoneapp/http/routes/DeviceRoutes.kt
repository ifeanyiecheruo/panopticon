package com.panopticon.phoneapp.http.routes

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.panopticon.phoneapp.BuildConfig
import com.panopticon.phoneapp.clips.SegmentStore
import com.panopticon.phoneapp.state.AppConfig
import com.panopticon.phoneapp.state.AppState
import com.panopticon.phoneapp.state.RecordingStatus
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(val manufacturer: String, val model: String, val device: String)

@Serializable
data class BuildInfo(val appVersionName: String, val appVersionCode: Int, val buildType: String, val gitSha: String)

@Serializable
data class StatusResponse(
    val mode: String,
    val status: String,
    val cameraHealthy: Boolean,
    val liveViewers: Int,
    val storageUsedBytes: Long,
    val storageCapBytes: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val serverTimeMs: Long,
)

@Serializable
data class ConfigResponse(
    val deviceName: String,
    val motionSensitivity: String,
    val storageCapBytes: Long,
    val ringBufferMaxAgeMs: Long,
)

// `rotationDegrees` is a camera-pipeline setting - read/written via
// GET/POST /api/camera/state, not here (phone-http-api.md).
@Serializable
data class ConfigPatch(
    val deviceName: String? = null,
    val motionSensitivity: String? = null,
    val storageCapBytes: Long? = null,
    val ringBufferMaxAgeMs: Long? = null,
)

fun Route.deviceRoutes(
    androidContext: Context,
    appConfig: AppConfig,
    appState: AppState,
    segmentStore: SegmentStore,
) {
    // Authenticated by the global installAuth() intercept.
    run {
        get("/api/device") {
            call.respond(DeviceInfo(manufacturer = Build.MANUFACTURER, model = Build.MODEL, device = Build.DEVICE))
        }

        get("/api/build-info") {
            call.respond(
                BuildInfo(
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    buildType = BuildConfig.BUILD_TYPE,
                    gitSha = BuildConfig.GIT_SHA,
                ),
            )
        }

        get("/api/status") {
            val cfg = appConfig.get()
            val battery = androidContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = battery.isCharging

            val status = when (appState.recordingStatus.value) {
                RecordingStatus.RECORDING -> "recording"
                RecordingStatus.UNAVAILABLE -> "unavailable"
                RecordingStatus.IDLE -> "idle"
                RecordingStatus.STOPPED -> "stopped"
            }

            call.respond(
                StatusResponse(
                    mode = appState.mode.value.name.lowercase(),
                    status = status,
                    cameraHealthy = appState.cameraHealthy.value,
                    liveViewers = appState.liveViewers.value,
                    storageUsedBytes = segmentStore.totalBytes(),
                    storageCapBytes = cfg.storageCapBytes,
                    batteryPercent = batteryPercent,
                    charging = charging,
                    serverTimeMs = System.currentTimeMillis(),
                ),
            )
        }

        get("/api/config") {
            val cfg = appConfig.get()
            call.respond(
                ConfigResponse(
                    deviceName = cfg.deviceName,
                    motionSensitivity = cfg.motionSensitivity,
                    storageCapBytes = cfg.storageCapBytes,
                    ringBufferMaxAgeMs = cfg.ringBufferMaxAgeMs,
                ),
            )
        }

        post("/api/config") {
            val patch = call.receive<ConfigPatch>()
            val updated = appConfig.update { current ->
                current.copy(
                    deviceName = patch.deviceName ?: current.deviceName,
                    motionSensitivity = patch.motionSensitivity ?: current.motionSensitivity,
                    storageCapBytes = patch.storageCapBytes ?: current.storageCapBytes,
                    ringBufferMaxAgeMs = patch.ringBufferMaxAgeMs ?: current.ringBufferMaxAgeMs,
                )
            }
            call.respond(
                ConfigResponse(
                    deviceName = updated.deviceName,
                    motionSensitivity = updated.motionSensitivity,
                    storageCapBytes = updated.storageCapBytes,
                    ringBufferMaxAgeMs = updated.ringBufferMaxAgeMs,
                ),
            )
        }
    }
}

/** Free bytes on the volume backing clip storage - used by the Home screen's storage stat. */
fun freeBytes(context: Context): Long {
    val stat = StatFs(context.getExternalFilesDir(null)?.absolutePath ?: Environment.getDataDirectory().absolutePath)
    return stat.availableBytes
}
