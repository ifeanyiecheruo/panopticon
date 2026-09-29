package com.panopticon.phoneapp.camera

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Sentinel for "no run in flight" - see [CameraHealth.runStartedAtMs]. */
private const val NOT_RUNNING = -1L

/**
 * In-memory, deliberately **loosely typed** per-camera health counters, served by
 * `GET /api/camera/health`.
 *
 * ## Why loosely typed
 *
 * This exists to answer a question we cannot yet state precisely: the recording pipeline's
 * encoder stops producing output after a few minutes of otherwise perfect 30fps, the supervisor's
 * stall timeout fires, and the rebuilt pipeline's first frames get recorded as a false motion
 * event (see docs/status/camera-stall-investigation.md). Nobody knows which counter settles that
 * yet, so the wire shape is a free-form JSON object of names to numbers and strings: a new probe
 * is one [count] call, with no wire type, no controller model and no migration to keep in step.
 * Once the failure is understood the handful of fields that turned out to matter can be promoted
 * to a real schema; until then the cost of guessing wrong is a line.
 *
 * ## What it is not
 *
 * Process-lifetime only - there is no persistence, and the process restarting resets everything.
 * That is deliberate and sufficient: the failure being chased restarts the *pipeline*, many times
 * per hour, inside one process. A counter that survived a process restart would answer a
 * different question than the one being asked.
 *
 * Thread-safe: written from the supervisor coroutine, the GL thread, the drain thread and the
 * camera callback thread; read from a Ktor worker.
 */
class CameraHealthRegistry(
    private val recentEventLimit: Int = 64,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val startedAtMs = wallClock()
    private val cameras = LinkedHashMap<String, CameraHealth>()

    /** The record for [cameraId], created on first use. [role] is `record` or `live` - the same
     *  physical camera behaves differently under the two pipelines, and keeping them in one
     *  bucket would average away exactly that difference. */
    @Synchronized
    fun forCamera(cameraId: String, role: String): CameraHealth =
        cameras.getOrPut("$role:$cameraId") {
            CameraHealth(cameraId, role, recentEventLimit, wallClock, monotonic)
        }

    @Synchronized
    fun snapshot(): CameraHealthResponse = CameraHealthResponse(
        generatedAtMs = wallClock(),
        processUptimeMs = wallClock() - startedAtMs,
        cameras = JsonObject(cameras.mapValues { (_, v) -> v.snapshot() }),
    )
}

/** `GET /api/camera/health`. Only [cameras]' *shape* is unstable; the envelope is not. */
@Serializable
data class CameraHealthResponse(
    val generatedAtMs: Long,
    val processUptimeMs: Long,
    val cameras: JsonObject,
)

/**
 * One camera under one pipeline role. Every mutator is cheap enough to call per frame except
 * [event], which allocates and is for things that happen a handful of times per run.
 */
class CameraHealth internal constructor(
    private val cameraId: String,
    private val role: String,
    private val recentEventLimit: Int,
    private val wallClock: () -> Long,
    private val monotonic: () -> Long,
) {
    private val counters = LinkedHashMap<String, Long>()
    private val gauges = LinkedHashMap<String, String>()
    private val recent = ArrayDeque<Triple<Long, String, String?>>()

    /**
     * Per-run accounting. A "run" is one pass of the pipeline supervisor's loop: camera opened,
     * session up, frames flowing, until something ends it.
     *
     * [NOT_RUNNING], not 0, while none is in flight: a monotonic clock legitimately reads 0 just
     * after boot, and a sentinel that collides with a real value would make the first run after a
     * reboot report zero uptime - the run most worth measuring.
     */
    private var runStartedAtMs = NOT_RUNNING
    private var runs = 0L
    private var runsEnded = 0L
    private var upMsTotal = 0L
    private var upMsMin = Long.MAX_VALUE
    private var upMsMax = 0L
    private var lastUpMs = 0L
    /** Per end-reason: count and total uptime that preceded it - the two numbers mean time
     *  between failures of that kind is made of. */
    private val endReasons = LinkedHashMap<String, LongArray>()

    @Synchronized
    fun count(name: String, by: Long = 1) {
        counters[name] = (counters[name] ?: 0L) + by
    }

    /** A last-known value rather than a total - kept as a string so anything can be recorded
     *  without inventing a type for it (a `Size`, an fps range, an error message). */
    @Synchronized
    fun gauge(name: String, value: String) {
        gauges[name] = value
    }

    fun gauge(name: String, value: Number) = gauge(name, value.toString())

    /** Counts *and* timestamps into the recent ring, so a rare event's cadence is visible and not
     *  just its total. */
    @Synchronized
    fun event(name: String, detail: String? = null) {
        counters[name] = (counters[name] ?: 0L) + 1
        recent.addLast(Triple(wallClock(), name, detail))
        while (recent.size > recentEventLimit) recent.removeFirst()
    }

    @Synchronized
    fun runStarted() {
        runStartedAtMs = monotonic()
        runs++
        event("runStarted")
    }

    /**
     * The run ended because of [reason] (`stall`, `noFirstOutput`, `cameraError`, `stopped`, ...).
     * Records how long it had been up, which is the numerator of every mean-time-between figure
     * this class reports.
     */
    @Synchronized
    fun runEnded(reason: String, detail: String? = null) {
        val upMs = if (runStartedAtMs == NOT_RUNNING) 0L else monotonic() - runStartedAtMs
        val counted = runStartedAtMs != NOT_RUNNING
        runStartedAtMs = NOT_RUNNING
        lastUpMs = upMs
        // A runEnded with no matching runStarted (the camera failed to open at all) has no
        // uptime to contribute; counting it would drag every mean towards zero.
        if (counted) {
            runsEnded++
            upMsTotal += upMs
            if (upMs < upMsMin) upMsMin = upMs
            if (upMs > upMsMax) upMsMax = upMs
            val slot = endReasons.getOrPut(reason) { longArrayOf(0L, 0L) }
            slot[0]++
            slot[1] += upMs
        }
        event("runEnded:$reason", detail)
    }

    @Synchronized
    internal fun snapshot(): JsonObject = buildJsonObject {
        put("cameraId", cameraId)
        put("role", role)
        put("runs", runs)
        put("runsEnded", runsEnded)
        put("currentUpMs", if (runStartedAtMs == NOT_RUNNING) 0L else monotonic() - runStartedAtMs)
        put("lastUpMs", lastUpMs)
        put("upMsTotal", upMsTotal)
        put("upMsMin", if (upMsMin == Long.MAX_VALUE) 0L else upMsMin)
        put("upMsMax", upMsMax)
        // The headline number: how long the pipeline survives on average before something ends
        // it. Only completed runs count - the one in flight has not failed yet, and folding it in
        // would drag the mean down every time it is read.
        put("meanUpMs", if (runsEnded == 0L) 0L else upMsTotal / runsEnded)
        put(
            "meanUpMsByEndReason",
            buildJsonObject {
                endReasons.forEach { (reason, v) ->
                    put(
                        reason,
                        buildJsonObject {
                            put("count", v[0])
                            put("meanUpMs", if (v[0] == 0L) 0L else v[1] / v[0])
                            put("totalUpMs", v[1])
                        },
                    )
                }
            },
        )
        put("counters", JsonObject(counters.mapValues { (_, v) -> JsonPrimitive(v) }))
        put("gauges", JsonObject(gauges.mapValues { (_, v) -> JsonPrimitive(v) }))
        put(
            "recent",
            buildJsonArray {
                recent.forEach { (atMs, name, detail) ->
                    add(
                        buildJsonObject {
                            put("atMs", atMs)
                            put("event", name)
                            if (detail != null) put("detail", detail)
                        },
                    )
                }
            },
        )
    }
}
