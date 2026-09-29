package com.panopticon.phoneapp.state

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Whether the device is on external power, and from what: `ac`, `usb`, `wireless`, `dock`, or
 * `none`.
 *
 * Deliberately separate from `BatteryManager.isCharging`, which follows the charge *status*: a
 * phone on a charger too weak for its load (or one holding its charge level, as adaptive charging
 * does) reports "not charging" while plugged in, indistinguishable from unplugged. The plug is
 * what `EXTRA_PLUGGED` reports, read off the sticky battery broadcast.
 */
object PowerSource {
    fun read(context: Context): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BATTERY_PLUGGED_DOCK -> "dock"
            0 -> "none"
            else -> "ac" // plugged by some source newer than this code; plugged is what matters
        }
    }

    /** `BatteryManager.BATTERY_PLUGGED_DOCK`, API 33+ - literal so older APIs compile against it. */
    private const val BATTERY_PLUGGED_DOCK = 8
}

/**
 * Net battery current, averaged over the last [WINDOW_SAMPLES] samples taken every
 * [SAMPLE_PERIOD_MS]: positive while the battery is gaining charge, negative while it's draining -
 * which is the answer to "is this phone going to die on its charger", whatever the status fields
 * say. `CURRENT_NOW` is an instantaneous reading that jumps with every CPU/radio burst, so a single
 * sample on request would say little; and Android's status fields lag the real current by far more
 * than this window (a stronger charger shows up in the current within seconds, in `dumpsys
 * battery` much later).
 *
 * Samples on its own daemon thread for the life of the process - one property read every few
 * seconds - because the controller asks for status too rarely to average anything itself.
 */
class BatteryCurrentSampler(context: Context) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val samplesUa = ArrayDeque<Int>()
    private var supported = true

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "BatteryCurrentSampler").apply { isDaemon = true }
    }

    init {
        executor.scheduleWithFixedDelay(::sample, 0, SAMPLE_PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    private fun sample() {
        // Documented as microamperes, positive = into the battery. Integer.MIN_VALUE is the
        // platform's "this device can't report it".
        val ua = runCatching { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
            .getOrDefault(Int.MIN_VALUE)
        synchronized(samplesUa) {
            if (ua == Int.MIN_VALUE) {
                supported = false
                return
            }
            samplesUa.addLast(ua)
            while (samplesUa.size > WINDOW_SAMPLES) samplesUa.removeFirst()
        }
    }

    /** Average net current in mA, or null if the device can't report it (or hasn't yet). */
    fun averageMa(): Int? = synchronized(samplesUa) {
        if (!supported || samplesUa.isEmpty()) null else (samplesUa.average() / 1000.0).roundToInt()
    }

    private companion object {
        const val SAMPLE_PERIOD_MS = 5_000L
        const val WINDOW_SAMPLES = 12 // one minute
    }
}
