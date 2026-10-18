package com.panopticon.phoneapp.http.routes

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.panopticon.phoneapp.BuildConfig
import com.panopticon.phoneapp.clips.SegmentStore
import com.panopticon.phoneapp.http.ErrorBody
import com.panopticon.phoneapp.state.AppConfig
import com.panopticon.phoneapp.state.AppState
import com.panopticon.phoneapp.state.DeviceConfig
import com.panopticon.phoneapp.state.BatteryCurrentSampler
import com.panopticon.phoneapp.state.PowerSource
import com.panopticon.phoneapp.state.ThermalPolicy
import com.panopticon.phoneapp.state.ThermalReader
import com.panopticon.phoneapp.state.ThermalShutdownStatus
import com.panopticon.phoneapp.state.ThermalStatus
import com.panopticon.phoneapp.state.RecordingStatus
import io.ktor.http.HttpStatusCode
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
    /** `recording` | `idle` | `unavailable` | `stopped` | `overheated`. `overheated` wins over
     *  everything else: it means the thermal guard has shut camera work down - see
     *  [thermalShutdown] - and [mode] is the mode that resumes once the phone cools. */
    val status: String,
    val cameraHealthy: Boolean,
    val liveViewers: Int,
    val storageUsedBytes: Long,
    val storageCapBytes: Long,
    val batteryPercent: Int,
    /** `BatteryManager.isCharging` - the charge *status*, which reads false on a charger too weak
     *  for the load. Kept for older controllers; [plugged] and [batteryCurrentMa] are the two
     *  independent answers. */
    val charging: Boolean,
    /** On external power, whatever the battery is doing - see [PowerSource]. */
    val plugged: Boolean,
    /** `ac` | `usb` | `wireless` | `dock` | `none`. */
    val powerSource: String,
    /** Net battery current averaged over the last minute, mA: positive = gaining charge, negative
     *  = draining. Null when the device can't report it - see [BatteryCurrentSampler]. */
    val batteryCurrentMa: Int?,
    /** How close the device is to thermal throttling. Not a temperature - see [ThermalStatus].
     *  `supported` is false on devices that cannot report it (API < 29), and the controller
     *  renders no thermal icon at all for those rather than a guessed one. */
    val thermal: ThermalStatus,
    /** The thermal guard: whether it has shut the camera down, why, the battery temperature it
     *  last read, and the thresholds it trips and resumes at. */
    val thermalShutdown: ThermalShutdownStatus,
    val serverTimeMs: Long,
)

@Serializable
data class ConfigResponse(
    val deviceName: String,
    val motionSensitivity: String,
    val storageCapBytes: Long,
    val ringBufferMaxAgeMs: Long,
    val thermalCriticalLevel: String,
    val thermalSafeLevel: String,
    val thermalCriticalTempC: Float,
    val thermalSafeTempC: Float,
)

// `rotationDegrees` is a camera-pipeline setting - read/written via
// GET/POST /api/camera/state, not here (docs/design/http-api.md).
@Serializable
data class ConfigPatch(
    val deviceName: String? = null,
    val motionSensitivity: String? = null,
    val storageCapBytes: Long? = null,
    val ringBufferMaxAgeMs: Long? = null,
    val thermalCriticalLevel: String? = null,
    val thermalSafeLevel: String? = null,
    val thermalCriticalTempC: Float? = null,
    val thermalSafeTempC: Float? = null,
)

private fun DeviceConfig.toResponse() = ConfigResponse(
    deviceName = deviceName,
    motionSensitivity = motionSensitivity,
    storageCapBytes = storageCapBytes,
    ringBufferMaxAgeMs = ringBufferMaxAgeMs,
    thermalCriticalLevel = thermalCriticalLevel,
    thermalSafeLevel = thermalSafeLevel,
    thermalCriticalTempC = thermalCriticalTempC,
    thermalSafeTempC = thermalSafeTempC,
)

fun Route.deviceRoutes(
    androidContext: Context,
    appConfig: AppConfig,
    appState: AppState,
    segmentStore: SegmentStore,
    batteryCurrent: BatteryCurrentSampler,
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
            val powerSource = PowerSource.read(androidContext)

            val thermalShutdown = appState.thermalShutdown.value
            val status = if (thermalShutdown.active) "overheated" else when (appState.recordingStatus.value) {
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
                    plugged = powerSource != "none",
                    powerSource = powerSource,
                    batteryCurrentMa = batteryCurrent.averageMa(),
                    thermal = ThermalReader.read(androidContext),
                    thermalShutdown = thermalShutdown,
                    serverTimeMs = System.currentTimeMillis(),
                ),
            )
        }

        get("/api/config") {
            call.respond(appConfig.get().toResponse())
        }

        post("/api/config") {
            val patch = call.receive<ConfigPatch>()
            val current = appConfig.get()
            if (patch.thermalCriticalLevel != null || patch.thermalSafeLevel != null) {
                ThermalPolicy.validateLevels(
                    patch.thermalCriticalLevel ?: current.thermalCriticalLevel,
                    patch.thermalSafeLevel ?: current.thermalSafeLevel,
                )?.let { return@post call.respond(HttpStatusCode.BadRequest, ErrorBody(it)) }
            }
            if (patch.thermalCriticalTempC != null || patch.thermalSafeTempC != null) {
                ThermalPolicy.validate(
                    patch.thermalCriticalTempC ?: current.thermalCriticalTempC,
                    patch.thermalSafeTempC ?: current.thermalSafeTempC,
                )?.let { return@post call.respond(HttpStatusCode.BadRequest, ErrorBody(it)) }
            }
            // The guard reads the config on every sample, so a new threshold takes effect within
            // one sample period. Levels are stored lowercased, the form /api/status reports.
            val updated = appConfig.update { current ->
                current.copy(
                    deviceName = patch.deviceName ?: current.deviceName,
                    motionSensitivity = patch.motionSensitivity ?: current.motionSensitivity,
                    storageCapBytes = patch.storageCapBytes ?: current.storageCapBytes,
                    ringBufferMaxAgeMs = patch.ringBufferMaxAgeMs ?: current.ringBufferMaxAgeMs,
                    thermalCriticalLevel = patch.thermalCriticalLevel?.lowercase() ?: current.thermalCriticalLevel,
                    thermalSafeLevel = patch.thermalSafeLevel?.lowercase() ?: current.thermalSafeLevel,
                    thermalCriticalTempC = patch.thermalCriticalTempC ?: current.thermalCriticalTempC,
                    thermalSafeTempC = patch.thermalSafeTempC ?: current.thermalSafeTempC,
                )
            }
            call.respond(updated.toResponse())
        }
    }
}

/** Free bytes on the volume backing clip storage - used by the Home screen's storage stat. */
fun freeBytes(context: Context): Long {
    val stat = StatFs(context.getExternalFilesDir(null)?.absolutePath ?: Environment.getDataDirectory().absolutePath)
    return stat.availableBytes
}
