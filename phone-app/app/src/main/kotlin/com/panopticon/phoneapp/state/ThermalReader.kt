package com.panopticon.phoneapp.state

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.serialization.Serializable

/**
 * How hot the device thinks it is, as the platform is willing to say it.
 *
 * **Not a temperature.** Degrees are not available to an ordinary app -
 * `HardwarePropertiesManager.getDeviceTemperatures()` is privileged - and they are not what this
 * is for anyway. What matters is how close the device is to shedding load, which is exactly what
 * `PowerManager` reports: a severity level, and (API 30+) a normalised forecast of headroom to the
 * throttling threshold.
 *
 * @param supported false when the device cannot report thermal state at all (API < 29). The
 *   controller renders no icon in that case rather than an empty or guessed one - a phone that
 *   cannot answer is not the same as a phone that is cold. The BLU G5 (API 28) is this case.
 * @param severity `PowerManager`'s own 0..6 scale, or -1 when [supported] is false. This is the
 *   value the UI's segments are driven from: seven discrete steps, already ordered by how bad
 *   things are, with no scaling for us to invent.
 * @param level [severity] as a name - `none`, `light`, `moderate`, `severe`, `critical`,
 *   `emergency`, `shutdown`, or `unknown`.
 * @param headroom 0..1+ forecast of thermal headroom (API 30+), where 1.0 is the throttling
 *   threshold and above 1.0 means throttling is already expected. Null when unavailable. Finer
 *   grained than [severity] and the more useful of the two for the stall investigation, because
 *   it moves *before* the severity level does - see docs/status/camera-stall-investigation.md.
 */
@Serializable
data class ThermalStatus(
    val supported: Boolean,
    val severity: Int,
    val level: String,
    val headroom: Float? = null,
) {
    companion object {
        val UNSUPPORTED = ThermalStatus(supported = false, severity = -1, level = "unknown")
    }
}

/**
 * Reads [ThermalStatus] off `PowerManager`, API-gated.
 *
 * Each version-specific key is reached through its own `@RequiresApi` object, never named in a
 * method that also runs on older devices: ART resolves every member a method mentions when it
 * verifies that method, so a runtime `SDK_INT` guard around the *call* is not enough. Reconfirmed
 * on a real API 28 device - see docs/quirks/calibration-zoom.md and `ZoomApiCompat.kt`, which
 * this follows.
 */
object ThermalReader {

    /**
     * `getThermalHeadroom` is rate limited: called more often than once per second it returns
     * `NaN` rather than a value. `/api/status` is a poll endpoint with no guarantee about how
     * often the controller hits it, so the last good reading is held for [HEADROOM_MIN_INTERVAL_MS]
     * and served from here in between. Without this, a controller polling at 2Hz would see the
     * headroom blink to null on every other request.
     */
    private const val HEADROOM_MIN_INTERVAL_MS = 1_000L

    @Volatile private var lastHeadroom: Float? = null
    @Volatile private var lastHeadroomAtMs = 0L

    fun read(context: Context): ThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalStatus.UNSUPPORTED
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return ThermalStatus.UNSUPPORTED
        val severity = runCatching { ThermalApi29.status(pm) }.getOrNull() ?: return ThermalStatus.UNSUPPORTED
        return ThermalStatus(
            supported = true,
            severity = severity,
            level = ThermalApi29.levelName(severity),
            headroom = headroom(pm),
        )
    }

    private fun headroom(pm: PowerManager): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeadroomAtMs < HEADROOM_MIN_INTERVAL_MS) return lastHeadroom
        lastHeadroomAtMs = now
        // NaN means "ask again later" (too soon, or not enough data collected yet), not "cold".
        val v = runCatching { ThermalApi30.headroom(pm, FORECAST_SECONDS) }.getOrNull()
        lastHeadroom = if (v == null || v.isNaN() || v.isInfinite()) null else v
        return lastHeadroom
    }

    /** How far ahead to forecast headroom. Zero asks for "right now", which is the honest thing
     *  for a status display; a forecast would be reporting a prediction as a reading. */
    private const val FORECAST_SECONDS = 0

    /** The highest severity `PowerManager` defines - what the UI scales its segments against. */
    const val MAX_SEVERITY = 6
}

@RequiresApi(Build.VERSION_CODES.Q)
private object ThermalApi29 {
    fun status(pm: PowerManager): Int = pm.currentThermalStatus

    /** Kept in here with the rest of the API-29 surface. The `THERMAL_STATUS_*` constants are
     *  compile-time ints and so would almost certainly be inlined rather than looked up, but
     *  "almost certainly" is how the `NoSuchFieldError` in docs/quirks/calibration-zoom.md got
     *  shipped, and the gated object costs nothing. */
    fun levelName(severity: Int): String = when (severity) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private object ThermalApi30 {
    fun headroom(pm: PowerManager, forecastSeconds: Int): Float = pm.getThermalHeadroom(forecastSeconds)
}
