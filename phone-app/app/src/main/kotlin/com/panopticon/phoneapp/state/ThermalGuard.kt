package com.panopticon.phoneapp.state

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.serialization.Serializable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val TAG = "ThermalGuard"

/**
 * The battery's own temperature in degrees C, off the sticky `ACTION_BATTERY_CHANGED` broadcast
 * (`EXTRA_TEMPERATURE`, tenths of a degree). Needs no privilege and exists on every API level,
 * which is why it is the thermal guard's fallback on devices with no thermal-status API (the BLU
 * G5's API 28). Where that API exists it is not read at all - see [ThermalPolicy].
 */
object BatteryTemperature {
    fun readC(context: Context): Float? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        // Missing extra, or the 0 some emulators and battery-less boards report.
        if (tenths == Int.MIN_VALUE || tenths == 0) return null
        return tenths / 10f
    }
}

/**
 * One reading the guard decides on. [severity] is `PowerManager`'s 0..6, or -1 where the device
 * has no thermal-status API; [batteryTempC] is only read in that case, and null otherwise.
 */
data class ThermalSample(val severity: Int, val batteryTempC: Float?)

/**
 * Where the guard trips and resumes, from `DeviceConfig` (settable through `POST /api/config`).
 * [criticalSeverity] / [safeSeverity] apply on devices with the thermal-status API, [criticalC] /
 * [safeC] only on devices without it - see [ThermalPolicy].
 */
data class ThermalLimits(
    val criticalSeverity: Int = ThermalPolicy.severityOf(ThermalPolicy.DEFAULT_CRITICAL_LEVEL)!!,
    val safeSeverity: Int = ThermalPolicy.severityOf(ThermalPolicy.DEFAULT_SAFE_LEVEL)!!,
    val criticalC: Float = ThermalPolicy.DEFAULT_CRITICAL_C,
    val safeC: Float = ThermalPolicy.DEFAULT_SAFE_C,
) {
    companion object {
        /** A stored level that isn't a known name (only possible by hand-editing prefs) falls
         *  back to its default rather than disabling the guard. */
        fun from(cfg: DeviceConfig) = ThermalLimits(
            criticalSeverity = ThermalPolicy.severityOf(cfg.thermalCriticalLevel)
                ?: ThermalPolicy.severityOf(ThermalPolicy.DEFAULT_CRITICAL_LEVEL)!!,
            safeSeverity = ThermalPolicy.severityOf(cfg.thermalSafeLevel)
                ?: ThermalPolicy.severityOf(ThermalPolicy.DEFAULT_SAFE_LEVEL)!!,
            criticalC = cfg.thermalCriticalTempC,
            safeC = cfg.thermalSafeTempC,
        )
    }
}

/**
 * The thermal guard's state, served on `/api/status` as `thermalShutdown`.
 *
 * @param active true while the phone has shut its camera work down for heat. `/api/status` then
 *   reports `status:"overheated"`, whatever mode was asked for.
 * @param source what the guard is judging by: `thermalStatus` (the platform's own thermal
 *   severity, API 29+), `batteryTemperature` (the fallback on older devices), or `none` (a device
 *   that reports neither, which can never trip).
 * @param reason the [source] that tripped it - `thermalStatus` or `batteryTemperature`. Null while
 *   not [active].
 * @param sinceMs wall clock of the trip, null while not [active].
 * @param level the platform's current severity name when [source] is `thermalStatus`.
 * @param criticalLevel / [safeLevel] the configured severities it trips at and resumes at, for
 *   `thermalStatus`.
 * @param batteryTempC the last battery reading when [source] is `batteryTemperature`, else null.
 * @param criticalTempC / [safeTempC] the configured fallback thresholds, echoed so a controller
 *   can say "resumes below 40°C" without a second request. Only applied when [source] is
 *   `batteryTemperature`.
 */
@Serializable
data class ThermalShutdownStatus(
    val active: Boolean = false,
    val source: String = SOURCE_NONE,
    val reason: String? = null,
    val sinceMs: Long? = null,
    val level: String? = null,
    val criticalLevel: String = ThermalPolicy.DEFAULT_CRITICAL_LEVEL,
    val safeLevel: String = ThermalPolicy.DEFAULT_SAFE_LEVEL,
    val batteryTempC: Float? = null,
    val criticalTempC: Float = ThermalPolicy.DEFAULT_CRITICAL_C,
    val safeTempC: Float = ThermalPolicy.DEFAULT_SAFE_C,
) {
    /** What the guard is reading, for the phone's own UI and notification. */
    fun readingText(): String = when (source) {
        SOURCE_THERMAL_STATUS -> "thermal status $level"
        SOURCE_BATTERY -> batteryTempC?.let { "battery %.1f°C".format(it) } ?: "battery"
        else -> "no reading"
    }

    /** When it will resume, phrased to follow "Resumes automatically ". */
    fun resumeText(): String = when (source) {
        SOURCE_THERMAL_STATUS -> "once thermal status is back to $safeLevel"
        else -> "once the battery is back under %.0f°C".format(safeTempC)
    }

    companion object {
        const val SOURCE_THERMAL_STATUS = "thermalStatus"
        const val SOURCE_BATTERY = "batteryTemperature"
        const val SOURCE_NONE = "none"
    }
}

/**
 * The hysteresis behind the thermal guard. Pure - no Android, no clock of its own - so the
 * edges are unit-tested rather than found in the sun.
 *
 * **One signal per device.** Where the platform reports a thermal status (API 29+), that is the
 * only input: it is the device maker's own verdict, drawn from every sensor the phone has
 * (skin, SoC, battery), against limits tuned for that hardware. Battery temperature is used only
 * where it is the best there is - devices with no thermal-status API - against the configured
 * degrees.
 *
 * **Trips** on the first sample at or over the critical point: the critical severity (default
 * `severe`), or the critical temperature. No dwell on the way up: by then the phone is already
 * hot, and neither reading is noisy.
 *
 * **Clears** only once the reading is at or under the safe point - the safe severity (default
 * `moderate`), or the safe temperature - held continuously for [resumeDwellMs]. The dwell is there so a phone sitting
 * right at the safe point doesn't restart the camera, warm a little, and get told to stop again
 * on the next sample; one reading back over safe restarts the wait.
 *
 * A device that reports neither can never trip - there is nothing to act on.
 */
class ThermalPolicy(private val resumeDwellMs: Long = DEFAULT_RESUME_DWELL_MS) {

    var tripped = false
        private set

    /** The source that tripped it, while [tripped]. */
    var reason: String? = null
        private set

    private var safeSinceMs: Long? = null

    /** Feed one sample; returns whether the guard is tripped afterwards. */
    fun evaluate(sample: ThermalSample, limits: ThermalLimits, nowMs: Long): Boolean {
        val source = sourceOf(sample)
        val temp = sample.batteryTempC
        val hot = when (source) {
            ThermalShutdownStatus.SOURCE_THERMAL_STATUS -> sample.severity >= limits.criticalSeverity
            ThermalShutdownStatus.SOURCE_BATTERY -> temp!! >= limits.criticalC
            else -> false
        }
        if (!tripped) {
            if (hot) {
                tripped = true
                reason = source
                safeSinceMs = null
            }
            return tripped
        }

        val cool = when (source) {
            ThermalShutdownStatus.SOURCE_THERMAL_STATUS -> sample.severity <= limits.safeSeverity
            ThermalShutdownStatus.SOURCE_BATTERY -> temp!! <= limits.safeC
            else -> true
        }
        if (!cool) {
            safeSinceMs = null
            return true
        }
        val since = safeSinceMs ?: nowMs.also { safeSinceMs = it }
        if (nowMs - since >= resumeDwellMs) {
            tripped = false
            reason = null
            safeSinceMs = null
        }
        return tripped
    }

    companion object {
        /** A Li-ion cell should not be charged above 45°C; past it the phone is in the sun. */
        const val DEFAULT_CRITICAL_C = 45f

        /** Five degrees of hysteresis: comfortably above what an idle phone indoors sits at. */
        const val DEFAULT_SAFE_C = 40f

        /** Lowest accepted for either threshold - low enough to trip a phone on a desk, which is
         *  how the guard is exercised on purpose. */
        const val MIN_THRESHOLD_C = 20f

        /** Highest accepted critical threshold. Phones shut themselves down not far above this,
         *  and a guard that waits for that is not a guard. */
        const val MAX_THRESHOLD_C = 60f

        /** The smallest gap allowed between critical and safe. */
        const val MIN_HYSTERESIS_C = 1f

        const val DEFAULT_RESUME_DWELL_MS = 2 * 60_000L

        /** `PowerManager`'s `THERMAL_STATUS_*` names, indexed by value (0..6) - literals so this
         *  stays pure JVM. The same names `ThermalReader` reports. */
        val LEVELS = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

        /** Where the platform itself says the device is "greatly throttled". */
        const val DEFAULT_CRITICAL_LEVEL = "severe"

        /** Where a Pixel 6 settles while recording on a charger indoors, so the camera stays off
         *  only while the phone is past its normal working state. */
        const val DEFAULT_SAFE_LEVEL = "moderate"

        /** Highest accepted critical level. Past `critical` the platform is already shedding load
         *  itself (`emergency`) or shutting down, and a guard that waits for that is not a guard. */
        const val MAX_CRITICAL_LEVEL = "critical"

        fun severityOf(level: String): Int? = LEVELS.indexOf(level.lowercase()).takeIf { it >= 0 }

        fun levelName(severity: Int): String = LEVELS.getOrElse(severity) { "unknown" }

        fun sourceOf(sample: ThermalSample): String = when {
            sample.severity >= 0 -> ThermalShutdownStatus.SOURCE_THERMAL_STATUS
            sample.batteryTempC != null -> ThermalShutdownStatus.SOURCE_BATTERY
            else -> ThermalShutdownStatus.SOURCE_NONE
        }

        /** Null when the pair of levels is acceptable, else why not - shared by `/api/config`.
         *  Critical must be `light`..[MAX_CRITICAL_LEVEL]; safe must be below it. */
        fun validateLevels(critical: String, safe: String): String? {
            val c = severityOf(critical) ?: return "thermalCriticalLevel must be one of ${LEVELS.joinToString()}"
            val s = severityOf(safe) ?: return "thermalSafeLevel must be one of ${LEVELS.joinToString()}"
            val max = severityOf(MAX_CRITICAL_LEVEL)!!
            return when {
                c < 1 || c > max -> "thermalCriticalLevel must be between ${LEVELS[1]} and $MAX_CRITICAL_LEVEL"
                s >= c -> "thermalSafeLevel must be below thermalCriticalLevel"
                else -> null
            }
        }

        /** Null when the pair of temperatures is acceptable, else why not - shared by `/api/config`. */
        fun validate(criticalC: Float, safeC: Float): String? = when {
            criticalC.isNaN() || safeC.isNaN() -> "thermal thresholds must be numbers"
            criticalC < MIN_THRESHOLD_C || criticalC > MAX_THRESHOLD_C ->
                "thermalCriticalTempC must be between $MIN_THRESHOLD_C and $MAX_THRESHOLD_C"
            safeC < MIN_THRESHOLD_C ->
                "thermalSafeTempC must be at least $MIN_THRESHOLD_C"
            criticalC - safeC < MIN_HYSTERESIS_C ->
                "thermalSafeTempC must be at least $MIN_HYSTERESIS_C below thermalCriticalTempC"
            else -> null
        }
    }
}

/**
 * Samples every [SAMPLE_PERIOD_MS] on its own daemon thread - and immediately on every
 * `PowerManager` thermal-status change (API 29+), so a trip doesn't wait for the next tick -
 * runs [ThermalPolicy], publishes the result to [AppState.thermalShutdown], and calls
 * [onTrippedChanged] on each edge.
 *
 * The periodic sample is still needed with the listener: the resume dwell is time-based, and
 * the battery-temperature fallback has no change callback at all.
 *
 * Owned by `PanopticonService`, which is what shuts the pipelines down and brings them back; the
 * guard itself only decides. Runs for the life of the service, including while tripped - it is
 * the only thing that will notice the phone has cooled.
 */
class ThermalGuard(
    private val context: Context,
    private val appConfig: AppConfig,
    private val appState: AppState,
    private val onTrippedChanged: (tripped: Boolean) -> Unit,
) {
    private val policy = ThermalPolicy()
    private var executor: ScheduledExecutorService? = null
    private var listener: Any? = null

    /** Takes one sample synchronously - so a phone that boots hot never starts its camera - then
     *  keeps sampling in the background. */
    @Synchronized
    fun start() {
        if (executor != null) return
        sample()
        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ThermalGuard").apply { isDaemon = true }
        }
        ex.scheduleWithFixedDelay(::sampleSafely, SAMPLE_PERIOD_MS, SAMPLE_PERIOD_MS, TimeUnit.MILLISECONDS)
        executor = ex
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            listener = pm?.let { runCatching { ThermalListenerApi29.add(it, ex) { sampleSafely() } }.getOrNull() }
        }
    }

    @Synchronized
    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            listener?.let { l -> pm?.let { runCatching { ThermalListenerApi29.remove(it, l) } } }
        }
        listener = null
        executor?.shutdownNow()
        executor = null
    }

    private fun sampleSafely() {
        // An exception escaping a scheduled task silently cancels every later run - which here
        // would be a guard that has quietly stopped guarding.
        try {
            sample()
        } catch (e: Exception) {
            Log.e(TAG, "thermal sample failed", e)
        }
    }

    @Synchronized
    private fun sample() {
        val cfg = appConfig.get()
        val thermal = ThermalReader.read(context)
        val severity = simulatedSeverity ?: thermal.severity
        val sample = ThermalSample(
            severity = severity,
            // Only where there is no thermal-status API - see ThermalPolicy.
            batteryTempC = if (severity < 0) BatteryTemperature.readC(context) else null,
        )
        val source = ThermalPolicy.sourceOf(sample)
        val was = policy.tripped
        val limits = ThermalLimits.from(cfg)
        val now = policy.evaluate(sample, limits, SystemClock.elapsedRealtime())
        val previous = appState.thermalShutdown.value
        appState.setThermalShutdown(
            ThermalShutdownStatus(
                active = now,
                source = source,
                reason = policy.reason,
                sinceMs = if (now) previous.sinceMs ?: System.currentTimeMillis() else null,
                level = if (severity >= 0) ThermalPolicy.levelName(severity) else null,
                criticalLevel = ThermalPolicy.levelName(limits.criticalSeverity),
                safeLevel = ThermalPolicy.levelName(limits.safeSeverity),
                batteryTempC = sample.batteryTempC,
                criticalTempC = cfg.thermalCriticalTempC,
                safeTempC = cfg.thermalSafeTempC,
            ),
        )
        if (now != was) {
            val reading = when (source) {
                ThermalShutdownStatus.SOURCE_THERMAL_STATUS ->
                    "thermalStatus=${ThermalPolicy.levelName(severity)}($severity)" +
                        " (critical=${ThermalPolicy.levelName(limits.criticalSeverity)}" +
                        " safe=${ThermalPolicy.levelName(limits.safeSeverity)})"
                else -> "battery=${sample.batteryTempC}C (critical=${cfg.thermalCriticalTempC}C safe=${cfg.thermalSafeTempC}C)"
            }
            Log.w(TAG, if (now) "overheated: $reading - shutting camera work down" else "cooled: $reading - resuming")
            onTrippedChanged(now)
        }
    }

    companion object {
        /** Temperature moves over minutes; this is plenty, and costs one cheap read. */
        private const val SAMPLE_PERIOD_MS = 10_000L

        /**
         * Debug hook: when set, used in place of the platform's thermal severity, so the guard's
         * thermal-status path can be exercised on a phone sitting cool on a desk (and on a device
         * with no thermal-status API at all). Only set by `DebugThermalReceiver`, which exists in
         * debug builds only; null in every release build.
         */
        @Volatile
        var simulatedSeverity: Int? = null
    }
}

/** API-29 surface kept in its own object, for the reason `ThermalReader` gives. */
@RequiresApi(Build.VERSION_CODES.Q)
private object ThermalListenerApi29 {
    fun add(pm: PowerManager, executor: Executor, onChange: () -> Unit): Any {
        val l = PowerManager.OnThermalStatusChangedListener { onChange() }
        pm.addThermalStatusListener(executor, l)
        return l
    }

    fun remove(pm: PowerManager, listener: Any) {
        pm.removeThermalStatusListener(listener as PowerManager.OnThermalStatusChangedListener)
    }
}
