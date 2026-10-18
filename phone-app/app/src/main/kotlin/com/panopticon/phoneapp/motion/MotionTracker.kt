package com.panopticon.phoneapp.motion

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Follows [BlockMotion.Blob]s from frame to frame. Only a track that keeps moving long enough,
 * far enough and big enough becomes *confirmed*, and only a confirmed track is motion. This is
 * what lets every per-pixel threshold upstream stay low: noise, a flicker that happens to beat
 * the census, or one raindrop each give a blob for a frame or two and never a confirmed track.
 *
 * A track is confirmed once, and stays confirmed while it keeps being seen, when:
 * - it has been seen over at least [Params.confirmMs], in at least a third of the analysed
 *   frames since it was born. Brief misses are coasted through for up to [Params.coastMs];
 * - it has travelled at least [Params.minPath] px **along its path**. Not net displacement,
 *   because a swaying tree is meant to count, and it goes nowhere. (Small tracks need net
 *   displacement too - see below);
 * - its mean size (changed pixels) is at least [Params.minSupport];
 * - and, if it is small (under [Params.smallSupport]), its heading is consistent
 *   ([Params.minCoherence]: the length of the mean of its unit velocity vectors) and it got
 *   somewhere: [Params.minPath] px of *net* displacement, at no more than [MAX_RELATIVE_SPEED]
 *   of its own size per frame. This is the particle rule. A distant person walking is small,
 *   steady and goes places at a fraction of their height per frame; a bug is small, erratic,
 *   and covers its own length every frame even when it briefly flies straight; clutter that
 *   happens to line up for a few frames jitters around one spot. Large objects are exempt, so
 *   a tree can sway back and forth.
 *
 * Swarm rule: when many small tracks are born within a second (rain, snow, a cloud of insects
 * round a lamp), small tracks need twice [Params.confirmMs] for a while.
 *
 * Association is greedy nearest-neighbour against each track's constant-velocity prediction,
 * gated on both position and velocity.
 * Scenes here have a handful of objects, not a crowd.
 *
 * Not thread-safe; owned by one [MotionDetector].
 */
internal class MotionTracker {

    data class Params(
        val confirmMs: Long,
        val minPath: Float,
        val minSupport: Int,
        val smallSupport: Int,
        val minCoherence: Float,
        val coastMs: Long = 700L,
    )

    class Track(var x: Float, var y: Float, val bornMs: Long) {
        var vx = 0f; var vy = 0f
        var lastHitMs = bornMs
        var hits = 0
        var frames = 0
        var missFrames = 0
        var path = 0f
        var hx = 0f; var hy = 0f; var hn = 0
        /** Sum of measured velocities: net displacement, without the centroid's jitter. */
        var nx = 0f; var ny = 0f
        var supportSum = 0L
        var confirmed = false

        val meanSupport: Float get() = if (hits == 0) 0f else supportSum.toFloat() / hits
        val coherence: Float get() = if (hn == 0) 0f else hypot(hx, hy) / hn
        val net: Float get() = hypot(nx, ny)
    }

    val tracks = ArrayList<Track>()
    private var pairBuf = LongArray(0)
    private val smallBirthsMs = ArrayDeque<Long>()
    private var swarmUntilMs = Long.MIN_VALUE

    /** Confirmed tracks seen within their coast window, after the last [update]. */
    var confirmedAlive = 0; private set

    fun reset() {
        tracks.clear()
        smallBirthsMs.clear()
        swarmUntilMs = Long.MIN_VALUE
        confirmedAlive = 0
    }

    fun update(blobs: List<BlockMotion.Blob>, nowMs: Long, p: Params) {
        for (t in tracks) t.frames++
        val taken = BooleanArray(blobs.size)
        val matched = BooleanArray(tracks.size)

        // Greedy assignment, closest pairs first. Each candidate pair is packed into one long -
        // distance (a non-negative float's bits sort like the float) over the two indices - so a
        // busy frame (rain: dozens of tracks and blobs) sorts primitives rather than boxing
        // thousands of objects.
        var np = 0
        val need = tracks.size * blobs.size
        if (pairBuf.size < need) pairBuf = LongArray(need)
        val pairs = pairBuf
        for ((ti, t) in tracks.withIndex()) {
            val steps = t.missFrames + 1
            val px = t.x + t.vx * steps
            val py = t.y + t.vy * steps
            val gate = GATE_BASE + GATE_SPEED * hypot(t.vx, t.vy) * steps
            val tolV = VEL_BASE + VEL_SPEED * hypot(t.vx, t.vy)
            for ((bi, b) in blobs.withIndex()) {
                val d = hypot(b.cx - px, b.cy - py)
                // Velocity must agree as well as position. Real motion changes speed gradually,
                // even a tree reversing direction, while noise blobs carry random vectors. Gating
                // on position alone let any track live on whatever noise landed near it.
                if (d <= gate && hypot(b.vx - t.vx, b.vy - t.vy) <= tolV) {
                    pairs[np++] = (d.toRawBits().toLong() shl 32) or (ti.toLong() shl 16) or bi.toLong()
                }
            }
        }
        java.util.Arrays.sort(pairs, 0, np)
        for (k in 0 until np) {
            val ti = ((pairs[k] ushr 16) and 0xFFFF).toInt()
            val bi = (pairs[k] and 0xFFFF).toInt()
            if (matched[ti] || taken[bi]) continue
            matched[ti] = true; taken[bi] = true
            hit(tracks[ti], blobs[bi], nowMs)
        }
        for ((ti, t) in tracks.withIndex()) if (!matched[ti]) t.missFrames++
        tracks.removeAll { nowMs - it.lastHitMs > p.coastMs }

        // New tracks for unclaimed blobs.
        for ((bi, b) in blobs.withIndex()) {
            if (taken[bi] || tracks.size >= MAX_TRACKS) continue
            val t = Track(b.cx, b.cy, nowMs)
            t.frames = 1
            hit(t, b, nowMs)
            tracks.add(t)
            if (b.support < p.smallSupport) smallBirthsMs.addLast(nowMs)
        }
        while (smallBirthsMs.isNotEmpty() && nowMs - smallBirthsMs.first() > SWARM_WINDOW_MS) smallBirthsMs.removeFirst()
        if (smallBirthsMs.size >= SWARM_BIRTHS) swarmUntilMs = nowMs + SWARM_HOLD_MS
        val swarm = nowMs < swarmUntilMs

        var alive = 0
        for (t in tracks) {
            if (!t.confirmed) t.confirmed = qualifies(t, p, swarm)
            if (t.confirmed && nowMs - t.lastHitMs <= p.coastMs) alive++
        }
        confirmedAlive = alive
    }

    private fun hit(t: Track, b: BlockMotion.Blob, nowMs: Long) {
        t.x = b.cx; t.y = b.cy
        t.vx = b.vx; t.vy = b.vy
        t.lastHitMs = nowMs
        t.hits++
        t.missFrames = 0
        val s = b.speed
        t.path += s
        t.nx += b.vx; t.ny += b.vy
        if (s >= MIN_HEADING_SPEED) { t.hx += b.vx / s; t.hy += b.vy / s; t.hn++ }
        t.supportSum += b.support
    }

    private fun qualifies(t: Track, p: Params, swarm: Boolean): Boolean {
        val small = t.meanSupport < p.smallSupport
        val confirmMs = if (small && swarm) 2 * p.confirmMs else p.confirmMs
        if (t.lastHitMs - t.bornMs < confirmMs) return false
        if (t.hits < MIN_HITS || t.hits * MIN_HIT_FRACTION_INV < t.frames) return false
        if (t.path < p.minPath) return false
        if (t.meanSupport < p.minSupport) return false
        if (small) {
            if (t.hn < MIN_HITS || t.coherence < p.minCoherence || t.net < p.minPath) return false
            // Size from changed pixels: roughly twice the object's area for anything moving
            // its own size or more per frame, so this errs towards letting fast things through.
            if (t.path / t.hits > MAX_RELATIVE_SPEED * sqrt(t.meanSupport)) return false
        }
        return true
    }

    private companion object {
        const val GATE_BASE = 6f
        const val GATE_SPEED = 2f
        const val VEL_BASE = 1.5f
        const val VEL_SPEED = 0.6f
        const val MAX_TRACKS = 48
        const val MIN_HITS = 3
        /**
         * A track must be seen in at least 1 in this many analysed frames since it was born. Not
         * 1 in 2: a distant car on the Pixel 6's street view (2026-10-17, ~0.5px/frame) only
         * yields a confident vector every 2-3 frames, mostly from the lagged comparison, and at
         * 1 in 2 it was never confirmed although it was tracked steadily for over a second.
         */
        const val MIN_HIT_FRACTION_INV = 3
        const val MIN_HEADING_SPEED = 0.3f
        /** Per analysed frame (~15fps), in units of sqrt(changed pixels): ~6 sizes a second. */
        const val MAX_RELATIVE_SPEED = 0.4f
        const val SWARM_WINDOW_MS = 1_000L
        const val SWARM_HOLD_MS = 3_000L
        const val SWARM_BIRTHS = 6
    }
}
