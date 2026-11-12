package com.panopticon.phoneapp.camera

import android.os.SystemClock
import android.util.Log
import com.panopticon.phoneapp.calibration.RectNorm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * What auto adds for the explicit area of interest, from [AutoAreaTuner]: a small exposure
 * compensation on top of auto exposure (which stays on, following the light), and a focus
 * distance held near where autofocus settled. Null fields are left to the phone. The same levers
 * as the manual sliders, but phone-internal: never control keys, and the controller can't set them.
 */
data class AutoOverride(val exposureCompensation: Int? = null, val focus: Float? = null) {
    /** The compensation tuning adds for [spec]: only in auto, only with an explicit area. */
    fun compensationFor(spec: CameraControlSpec): Int? =
        exposureCompensation?.takeIf { it != 0 && spec.hasExplicitArea }

    fun focusFor(spec: CameraControlSpec, caps: CameraCapabilities): Float? =
        focus?.takeIf { spec.hasExplicitArea && caps.hasManualFocus && caps.minFocusDistanceDiopters > 0f }

    companion object {
        /**
         * The exposure compensation auto runs with: the user's `aeExposureCompensation` (allowed
         * in auto, like the zoom) plus what [auto] tuned on top, within the camera's range. Null
         * when neither asks for any, leaving the HAL's default.
         */
        fun totalCompensation(spec: CameraControlSpec, caps: CameraCapabilities, auto: AutoOverride?): Int? {
            val user = spec.keys.aeExposureCompensation
            val tuned = auto?.compensationFor(spec)
            if (user == null && tuned == null) return null
            return ((user ?: 0) + (tuned ?: 0)).coerceIn(caps.aeCompensationRange.lo, caps.aeCompensationRange.hi)
        }
    }
}

/**
 * Auto with an explicit area of interest, run on the phone: lets the phone's own auto settle on
 * the area, then fine-tunes from there with small, slow nudges, and keeps doing so - no controller
 * involved (docs/design/decisions/0017-area-of-interest-fine-tuning.md).
 *
 * - **Baseline.** When an area appears or moves, or the camera (re)starts with one, auto exposure
 *   and autofocus run on it until they converge. That is where tuning starts.
 * - **Exposure** stays auto's: auto exposure keeps following the light, and tuning only biases it
 *   with exposure compensation, one camera step (a sixth or a third of a stop) at a time and at
 *   most [AreaTuning.MAX_EXPOSURE_BIAS_EV] either way, kept when the area's tones spread further.
 * - **Focus** is held manually at autofocus's own distance - unless that is already less sharp
 *   than autofocus, when focus stays autofocus's - then nudged a 320th of the lens's range at a
 *   time, at most [AreaTuning.FOCUS_WINDOW_STEPS] steps either way, kept when sharper.
 * - A trial every [EXPOSURE_STEP_MS] / [FOCUS_STEP_MS] while they pay off; once a control is at
 *   its best both ways, the wait doubles up to [MAX_STEP_WAIT_MS], so a tuned area is left alone
 *   while a slow drift is still followed.
 * - A watchdog every [WATCH_MS] measures the area the same way, changing nothing; if it has
 *   drifted well below what it measured where tuning settled, tuning starts over from a new
 *   baseline.
 * - With no explicit area, or in manual, it holds nothing: auto is the phone's own.
 *
 * It reaches the camera only through [Hooks], which [CameraGlPipeline] implements.
 */
internal class AutoAreaTuner(private val hooks: Hooks, private val scope: CoroutineScope) {

    interface Hooks {
        fun spec(): CameraControlSpec
        fun caps(): CameraCapabilities?
        /** The explicit area in the output frame's own coordinates; null when out of view. */
        fun areaInFrame(): RectNorm?
        fun report(): CaptureReport?
        /** Publish [o] for the next request, and re-issue it if [reissue]. Returns the generation
         *  of the request that carries it. */
        fun setOverride(o: AutoOverride?, reissue: Boolean): Long
        /** The sensor timestamp of a result from request [generation] or later - with the lens
         *  at rest when [lensStill] - i.e. once that request has taken effect. */
        suspend fun awaitApplied(generation: Long, lensStill: Boolean): Long
        /** Likewise, once auto exposure ([exposure]) and/or autofocus ([focus]) have converged. */
        suspend fun awaitConverged(generation: Long, exposure: Boolean, focus: Boolean): Long
        /** Luma of [region] from the first frame captured at or after [minTimestampNs]. */
        suspend fun frame(region: RectNorm, maxEdge: Int, minTimestampNs: Long): AreaTuning.LumaPlane
    }

    @Volatile var status: AutoTuningStatus? = null
        private set

    private val lock = Any()
    private var areaKey: Triple<RectNorm?, RectNorm?, Int?>? = null
    private var job: Job? = null

    /**
     * The controls changed (or the session came up). Called before the request carrying [spec]
     * is built, so clearing the override here needs no re-issue of its own. A new area, or a new
     * exposure compensation from the user (a new baseline), starts again from the phone's own
     * auto; the same area - a zoom change, say - keeps going.
     */
    fun onControls(spec: CameraControlSpec, restart: Boolean = false) {
        synchronized(lock) {
            val k = spec.keys
            val key = if (spec.hasExplicitArea) Triple(k.aeRegionNorm, k.afRegionNorm, k.aeExposureCompensation) else null
            if (key == areaKey && !restart) return
            areaKey = key
            job?.cancel()
            job = null
            hooks.setOverride(null, reissue = false)
            if (key == null) {
                status = null
                return
            }
            status = AutoTuningStatus(state = AutoTuningStatus.WAITING)
            job = scope.launch {
                delay(START_DELAY_MS)
                run()
            }
        }
    }

    /** Start again from the phone's own auto now (`POST /api/camera/area-tune`). False when there
     *  is no explicit area. */
    fun retune(): Boolean = synchronized(lock) {
        if (areaKey == null) return false
        job?.cancel()
        hooks.setOverride(null, reissue = true)
        status = AutoTuningStatus(state = AutoTuningStatus.WAITING)
        job = scope.launch { run() }
        true
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            areaKey = null
            status = null
        }
    }

    private suspend fun run() {
        // Why the last tuning was dropped by the watchdog, for the status; null on a fresh start.
        var drift: String? = null
        while (coroutineContext.isActive) {
            val t = try {
                baseline(drift)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "baseline failed", e)
                hooks.setOverride(null, reissue = true)
                fail(e.message ?: e.javaClass.simpleName)
                null
            }
            if (t == null) {
                drift = null
                delay(RETRY_MS)
                continue
            }
            if (t.exposure == null && t.focus == null) {
                status = AutoTuningStatus(state = AutoTuningStatus.UNSUPPORTED, message = "this camera has neither exposure compensation nor manual focus")
                kotlinx.coroutines.awaitCancellation()
            }
            drift = t.nudge()
            if (drift != null) Log.i(TAG, "drifted since tuned ($drift): starting again from the phone's own auto")
        }
    }

    /** Let auto settle on the area and measure it there; null when the area can't be placed.
     *  [drift] is why the watchdog dropped the last tuning, if it did. */
    private suspend fun baseline(drift: String? = null): Trials? {
        val caps = hooks.caps()
        var area = hooks.areaInFrame()
        var waited = 0L
        // The area can only be placed in the frame once the first frame has fixed its trim.
        while (area == null && waited < AREA_WAIT_MS) {
            delay(250)
            waited += 250
            area = hooks.areaInFrame()
        }
        if (caps == null || area == null) {
            fail(if (caps == null) "camera not ready" else "the area of interest is out of view")
            return null
        }
        status = AutoTuningStatus(state = AutoTuningStatus.WAITING)
        val tuneFocus = caps.hasManualFocus && caps.minFocusDistanceDiopters > 0f
        var gen = hooks.setOverride(null, reissue = true)
        var ts = hooks.awaitConverged(gen, exposure = true, focus = tuneFocus)

        val t = Trials(area, caps)
        t.drift = drift
        t.exposure = AreaTuning.exposureNudger(caps, base = hooks.spec().keys.aeExposureCompensation ?: 0)
        t.phoneContrast = t.contrast(ts)
        t.contrastNow = t.phoneContrast
        t.tunedContrast = t.phoneContrast

        if (tuneFocus) {
            val af = t.sharpness(ts)
            t.phoneSharpness = af
            t.sharpnessNow = af
            val d = hooks.report()?.focusDistanceDiopters
            if (caps.cameraId in manualFocusLoses) {
                Log.i(TAG, "holding autofocus's distance lost sharpness on this camera before: focus stays autofocus's")
            } else if (d != null) {
                // Hold autofocus's own distance; nothing should change on screen.
                gen = hooks.setOverride(AutoOverride(focus = d), reissue = true)
                ts = hooks.awaitApplied(gen, lensStill = true)
                val held = t.sharpness(ts)
                if (held >= af * (1 - AreaTuning.FOCUS_HOLD_LOSS)) {
                    t.focusCentre = d
                    t.focus = AreaTuning.focusNudger()
                    t.sharpnessNow = held
                    t.tunedSharpness = held
                } else {
                    Log.i(TAG, "holding autofocus's distance loses sharpness (${"%.0f".format(held)} vs ${"%.0f".format(af)}): focus stays autofocus's")
                    manualFocusLoses += caps.cameraId
                    gen = hooks.setOverride(null, reissue = true)
                    // Let autofocus find the area again before anything is measured.
                    hooks.awaitConverged(gen, exposure = true, focus = true)
                }
            }
        }
        t.publishStatus(changedAtMs = null)
        Log.i(TAG, "baseline: contrast ${"%.2f".format(t.phoneContrast)} bits, sharpness ${"%.0f".format(t.phoneSharpness)}, tuning ${listOfNotNull(t.exposure?.let { "exposure" }, t.focus?.let { "focus" })}")
        return t
    }

    private fun fail(message: String) {
        status = AutoTuningStatus(state = AutoTuningStatus.FAILED, message = message)
    }

    /** The nudging, from a baseline. */
    private inner class Trials(val area: RectNorm, val caps: CameraCapabilities) {
        var exposure: AreaTuning.Nudger? = null
        var focus: AreaTuning.Nudger? = null
        var focusCentre = 0f
        val focusStep = AreaTuning.focusStep(caps.minFocusDistanceDiopters)
        var phoneContrast = 0.0
        var phoneSharpness = 0.0
        var contrastNow = 0.0
        var sharpnessNow = 0.0
        /** What the area measured where tuning last settled - at the baseline, or the last kept
         *  step - which the watchdog checks the area against. Sharpness only while focus is held. */
        var tunedContrast = 0.0
        var tunedSharpness = 0.0
        /** Why the watchdog dropped the tuning before this one, if it did. */
        var drift: String? = null
        private val grown = AreaTuning.grow(area, AreaTuning.FOCUS_MARGIN)
        private val aeStepEv = caps.aeCompensationStepMilliEv / 1000.0

        fun override(compensation: Int? = exposure?.value, focusAt: Int? = focus?.value) = AutoOverride(
            exposureCompensation = compensation?.takeIf { it != 0 },
            focus = focusAt?.let { focusCentre + it * focusStep }?.coerceIn(0f, caps.minFocusDistanceDiopters),
        )

        /** Mean tone entropy of the area over a few frames from [ts] on. */
        suspend fun contrast(ts: Long): Double = tones(ts).first

        /** Mean tone entropy of the area over a few frames from [ts] on, and the last frame's tones. */
        suspend fun tones(ts: Long): Pair<Double, AreaTuning.Tones> {
            var sum = 0.0
            var last: AreaTuning.Tones? = null
            repeat(AreaTuning.FRAMES_PER_MEASURE) {
                val t = AreaTuning.tones(hooks.frame(area, CONTRAST_MAX_EDGE, ts), WHOLE)
                    ?: throw IllegalStateException("area covers no pixel")
                sum += t.entropy
                last = t
            }
            return sum / AreaTuning.FRAMES_PER_MEASURE to last!!
        }

        /** Mean edge energy of the area (plus its margin) over a few frames from [ts] on. */
        suspend fun sharpness(ts: Long): Double {
            var sum = 0.0
            repeat(AreaTuning.FRAMES_PER_MEASURE) { sum += AreaTuning.sharpness(hooks.frame(grown, SHARPNESS_MAX_EDGE, ts)) }
            return sum / AreaTuning.FRAMES_PER_MEASURE
        }

        /** Nudge until cancelled, or until the watchdog finds the area has drifted from where it
         *  was tuned: then returns why, for [run] to start again from the phone's own auto. */
        suspend fun nudge(): String? {
            var nextExposureAt = SystemClock.elapsedRealtime() + EXPOSURE_STEP_MS
            var nextFocusAt = SystemClock.elapsedRealtime() + FOCUS_STEP_MS
            var nextWatchAt = SystemClock.elapsedRealtime() + WATCH_MS
            if (exposure == null) nextExposureAt = Long.MAX_VALUE
            if (focus == null) nextFocusAt = Long.MAX_VALUE
            while (coroutineContext.isActive) {
                val due = minOf(nextExposureAt, nextFocusAt, nextWatchAt)
                delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                if (nextWatchAt <= minOf(nextExposureAt, nextFocusAt)) {
                    val drifted = try {
                        watch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "the watchdog couldn't measure: ${e.message}")
                        null
                    }
                    if (drifted != null) return drifted
                    nextWatchAt = SystemClock.elapsedRealtime() + WATCH_MS
                    continue
                }
                val exposureTurn = nextExposureAt <= nextFocusAt
                val n = (if (exposureTurn) exposure else focus) ?: return null
                val waitMs = try {
                    if (exposureTurn) tryExposure(n) else tryFocus(n)
                    n.waitMs(if (exposureTurn) EXPOSURE_STEP_MS else FOCUS_STEP_MS, MAX_STEP_WAIT_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Back to what was kept, and try again later.
                    Log.w(TAG, "a trial failed: ${e.message}")
                    hooks.setOverride(override(), reissue = true)
                    RETRY_MS
                }
                val next = SystemClock.elapsedRealtime() + waitMs
                if (exposureTurn) nextExposureAt = next else nextFocusAt = next
            }
            return null
        }

        /**
         * The watchdog: the trials' own measures, taken with nothing changed, against what the
         * area measured where tuning settled ([tunedContrast], [tunedSharpness]). Null while the
         * tuning holds, else how it has drifted. The light changes slowly, and a trial only ever
         * moves one step from what it keeps - so a bias tuned for this morning can still sit on
         * the area this afternoon, worse than auto's own; starting over finds out.
         */
        private suspend fun watch(): String? {
            // Mid-move, auto exposure is changing the picture itself: look again next time.
            if (!awaitSteady(limitStops = null)) return null
            val reasons = mutableListOf<String>()
            if (exposure != null) {
                val c = contrast(0L)
                Log.d(TAG, "watch: contrast ${"%.3f".format(c)} bits, tuned at ${"%.3f".format(tunedContrast)}")
                if (AreaTuning.contrastDrifted(tunedContrast, c)) {
                    reasons += "tonal detail had fallen ${"%.2f".format(tunedContrast - c)} bits"
                }
            }
            if (focus != null) {
                val s = sharpness(0L)
                Log.d(TAG, "watch: sharpness ${"%.0f".format(s)}, tuned at ${"%.0f".format(tunedSharpness)}")
                if (AreaTuning.sharpnessDrifted(tunedSharpness, s)) {
                    reasons += "sharpness had fallen ${"%.0f".format(100 * (1 - s / tunedSharpness))}%"
                }
            }
            return reasons.takeIf { it.isNotEmpty() }?.joinToString(" and ")
        }

        private suspend fun tryExposure(n: AreaTuning.Nudger) {
            // Never measure auto exposure mid-move: the light is changing, or it is still
            // recovering from the last trial. Try again next time.
            if (!awaitSteady(limitStops = null)) {
                Log.d(TAG, "auto exposure is moving: no trial now")
                return
            }
            val candidate = n.next() ?: return
            val dir = if (candidate > n.value) 1 else -1
            val (base, tones) = tones(0L)
            if (!AreaTuning.exposureAllows(tones, dir)) {
                // Auto exposure can't meter what is clipped: don't push it that way.
                n.result(candidate, base, Double.NEGATIVE_INFINITY)
                Log.d(TAG, "the area clips ${if (dir > 0) "white" else "black"}: not ${if (dir > 0) "brightening" else "darkening"} it")
                contrastNow = base
                publishStatus(changedAtMs = null)
                return
            }
            val gen = hooks.setOverride(override(compensation = candidate), reissue = true)
            hooks.awaitApplied(gen, lensStill = false)
            if (!awaitSteady(limitStops = AreaTuning.maxExposureResponse(aeStepEv))) {
                // Auto exposure ran away from a small step (or never settled): straight back,
                // and never that way again until auto starts over on the area.
                n.result(candidate, base, Double.NEGATIVE_INFINITY)
                n.stopGoing(dir)
                hooks.setOverride(override(), reissue = true)
                Log.i(TAG, "auto exposure ran away from ${ev(candidate)} EV: back to ${ev(n.value)} EV, and no further that way")
                awaitSteady(limitStops = null)
                contrastNow = base
                publishStatus(changedAtMs = null)
                return
            }
            val trial = contrast(0L)
            val kept = n.result(candidate, base, trial)
            Log.d(TAG, "exposure ${ev(n.value)} EV: tried ${ev(candidate)} EV, ${"%.3f".format(trial)} vs ${"%.3f".format(base)} bits: ${if (kept) "kept" else "back"}")
            if (!kept) {
                hooks.setOverride(override(), reissue = true)
                awaitSteady(limitStops = null)
            }
            contrastNow = if (kept) trial else base
            if (kept) {
                tunedContrast = trial
                // Edge energy rises and falls with brightness: what the watchdog holds focus to
                // is re-measured at the exposure just kept.
                if (focus != null) tunedSharpness = sharpness(0L)
            }
            publishStatus(changedAtMs = if (kept) System.currentTimeMillis() else null)
        }

        /**
         * Waits for auto exposure to hold still - the reported exposure unchanged over a few
         * results - and says whether it did within [STEADY_TIMEOUT_MS]. With [limitStops], it is
         * a failure as soon as the exposure has moved further than that from where it started.
         */
        private suspend fun awaitSteady(limitStops: Double?): Boolean {
            val start = reportedStops()
            var prev = start
            var still = 0
            var waited = 0L
            while (waited < STEADY_TIMEOUT_MS) {
                delay(STEADY_POLL_MS)
                waited += STEADY_POLL_MS
                val now = reportedStops() ?: return true // nothing reported: nothing to wait for
                if (limitStops != null && start != null && kotlin.math.abs(now - start) > limitStops) return false
                still = if (prev != null && kotlin.math.abs(now - prev) < STEADY_STOPS) still + 1 else 0
                prev = now
                if (still >= STEADY_SAMPLES) return true
            }
            return false
        }

        /** The light auto exposure is gathering, in stops (log2 of exposure time x ISO). */
        private fun reportedStops(): Double? {
            val r = hooks.report() ?: return null
            val t = r.exposureTimeNs ?: return null
            val iso = r.sensitivityIso ?: return null
            return if (t > 0 && iso > 0) kotlin.math.log2(t.toDouble() * iso) else null
        }

        private suspend fun tryFocus(n: AreaTuning.Nudger) {
            val candidate = n.next() ?: return
            val base = sharpness(0L)
            val gen = hooks.setOverride(override(focusAt = candidate), reissue = true)
            val ts = hooks.awaitApplied(gen, lensStill = true)
            val trial = sharpness(ts)
            val kept = n.result(candidate, base, trial)
            Log.d(TAG, "focus step ${n.value}: tried $candidate, ${"%.0f".format(trial)} vs ${"%.0f".format(base)}: ${if (kept) "kept" else "back"}")
            if (!kept) hooks.setOverride(override(), reissue = true)
            sharpnessNow = if (kept) trial else base
            if (kept) tunedSharpness = trial
            publishStatus(changedAtMs = if (kept) System.currentTimeMillis() else null)
        }

        private fun ev(steps: Int) = "%+.2f".format(steps * aeStepEv)

        fun publishStatus(changedAtMs: Long?) {
            val o = override()
            status = AutoTuningStatus(
                state = AutoTuningStatus.TUNING,
                exposureCompensation = exposure?.value,
                exposureBiasEv = exposure?.let { it.value * aeStepEv },
                focusDistanceDiopters = if (focus != null) o.focus else null,
                contrast = contrastNow.takeIf { exposure != null },
                phoneContrast = phoneContrast.takeIf { exposure != null },
                sharpness = sharpnessNow.takeIf { phoneSharpness > 0 },
                phoneSharpness = phoneSharpness.takeIf { it > 0 },
                tunedAtMs = changedAtMs ?: status?.tunedAtMs,
                message = drift?.let { "re-tuned itself: $it since it was tuned" },
            )
        }
    }

    companion object {
        private const val TAG = "AutoAreaTuner"
        private val WHOLE = RectNorm(0f, 0f, 1f, 1f)

        /** Let a new area's controls land, and auto start on it, before anything is measured. */
        const val START_DELAY_MS = 3_000L
        /** Between trials while they pay off. Light and focus change slowly; there's no hurry. */
        const val EXPOSURE_STEP_MS = 10_000L
        const val FOCUS_STEP_MS = 15_000L
        /** The longest wait between trials, once a control has settled at its best. */
        const val MAX_STEP_WAIT_MS = 5 * 60_000L
        const val RETRY_MS = 60_000L
        /** How often the watchdog checks the area against where it was tuned. */
        const val WATCH_MS = 5 * 60_000L
        /** Auto exposure counts as steady once the reported exposure has moved less than
         *  [STEADY_STOPS] on [STEADY_SAMPLES] polls running, [STEADY_POLL_MS] apart. */
        const val STEADY_POLL_MS = 200L
        const val STEADY_SAMPLES = 3
        const val STEADY_STOPS = 0.03
        const val STEADY_TIMEOUT_MS = 6_000L

        /** Cameras on which holding autofocus's own distance manually loses sharpness (the Pixel
         *  7 Pro's telephoto): not tried again while the app runs, since each try hands the lens
         *  back to autofocus, which looks for focus again. */
        private val manualFocusLoses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** How long the baseline waits for the area to be placeable in the frame. */
        const val AREA_WAIT_MS = 5_000L
        /** Contrast only needs the area's tones; sharpness needs its detail. */
        const val CONTRAST_MAX_EDGE = 960
        const val SHARPNESS_MAX_EDGE = 1920
    }
}
