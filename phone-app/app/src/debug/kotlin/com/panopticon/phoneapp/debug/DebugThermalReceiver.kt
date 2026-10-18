package com.panopticon.phoneapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.state.BatteryTemperature
import com.panopticon.phoneapp.state.ThermalGuard
import com.panopticon.phoneapp.state.ThermalPolicy

/**
 * Debug-build-only `adb` hook for the thermal guard, so it can be tripped on a phone sitting on a
 * desk without pairing a controller.
 *
 * The thermal-status path (API 29+, and the only one those devices use): stand in for the
 * platform's severity, 0..6 (`severe` = 3 trips, `moderate` = 2 or below resumes). Works on API 28
 * too, which has no real severity to override. `--ei severity -1` drops the stand-in:
 *
 *   adb shell am broadcast -n com.panopticon.phoneapp/.debug.DebugThermalReceiver \
 *       -a com.panopticon.phoneapp.DEBUG_THERMAL --ei severity 3
 *
 * The battery-temperature fallback (devices with no thermal-status API): lower the critical
 * threshold under the current battery temperature - the same persisted thresholds
 * `POST /api/config` sets, validated the same way:
 *
 *   adb shell am broadcast -n com.panopticon.phoneapp/.debug.DebugThermalReceiver \
 *       -a com.panopticon.phoneapp.DEBUG_THERMAL --ef critical_c 26 --ef safe_c 24
 *
 * The configured levels themselves, as `POST /api/config` sets them (and validated the same way):
 *
 *   ... -a com.panopticon.phoneapp.DEBUG_THERMAL --es critical_level moderate --es safe_level light
 *
 * And:
 *
 *   ... -a com.panopticon.phoneapp.DEBUG_THERMAL --ez reset true   # default thresholds and levels, no stand-in
 *   ... -a com.panopticon.phoneapp.DEBUG_THERMAL                   # just log the state
 *
 * Either takes effect on the guard's next sample (within 10s). Logs under tag `DebugThermal`;
 * the trip and resume themselves log under `ThermalGuard`. Never shipped - declared only in
 * src/debug/AndroidManifest.xml.
 */
class DebugThermalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.panopticon.phoneapp.DEBUG_THERMAL") return
        val app = PanopticonApplication.from(context)
        val current = app.appConfig.get()

        val reset = intent.getBooleanExtra("reset", false)
        if (reset || intent.hasExtra("severity")) {
            val severity = if (reset) -1 else intent.getIntExtra("severity", -1)
            ThermalGuard.simulatedSeverity = severity.takeIf { it >= 0 }
            Log.i(TAG, "simulated severity now ${ThermalGuard.simulatedSeverity ?: "off (the platform's own)"}")
        }

        val criticalLevel = if (reset) ThermalPolicy.DEFAULT_CRITICAL_LEVEL
        else intent.getStringExtra("critical_level")?.lowercase() ?: current.thermalCriticalLevel
        val safeLevel = if (reset) ThermalPolicy.DEFAULT_SAFE_LEVEL
        else intent.getStringExtra("safe_level")?.lowercase() ?: current.thermalSafeLevel
        if (criticalLevel != current.thermalCriticalLevel || safeLevel != current.thermalSafeLevel) {
            val error = ThermalPolicy.validateLevels(criticalLevel, safeLevel)
            if (error != null) {
                Log.e(TAG, "rejected: $error")
                return
            }
            app.appConfig.update { it.copy(thermalCriticalLevel = criticalLevel, thermalSafeLevel = safeLevel) }
            Log.i(TAG, "levels now critical=$criticalLevel safe=$safeLevel")
        }

        val critical = if (reset) ThermalPolicy.DEFAULT_CRITICAL_C else intent.getFloatExtra("critical_c", current.thermalCriticalTempC)
        val safe = if (reset) ThermalPolicy.DEFAULT_SAFE_C else intent.getFloatExtra("safe_c", current.thermalSafeTempC)
        if (critical != current.thermalCriticalTempC || safe != current.thermalSafeTempC) {
            val error = ThermalPolicy.validate(critical, safe)
            if (error != null) {
                Log.e(TAG, "rejected: $error")
                return
            }
            app.appConfig.update { it.copy(thermalCriticalTempC = critical, thermalSafeTempC = safe) }
            Log.i(TAG, "thresholds now critical=${critical}C safe=${safe}C")
        }
        Log.i(
            TAG,
            "battery=${BatteryTemperature.readC(context)}C mode=${app.appState.mode.value}" +
                " recordingStatus=${app.appState.recordingStatus.value} guard=${app.appState.thermalShutdown.value}",
        )
    }

    private companion object {
        const val TAG = "DebugThermal"
    }
}
