package com.panopticon.phoneapp.motion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Per-block displacement between census frames: which blocks have structure that *moved*, and
 * by how much. The step that turns "pixels changed" into "something moved".
 *
 * For each [block]x[block] block:
 *
 * 1. **Activity.** Count the pixels whose luma changed since the previous frame by more than
 *    noise allows (the [NoiseModel.changeTable] passed in, which also takes out this frame's
 *    exposure shift). A block is active when that count clearly beats its own running baseline
 *    ([ACT_K] times it, and never under [MIN_SUPPORT] pixels), so a patch of foliage noise raises
 *    its own bar and nobody else's. The same is done against the frame [lag] frames back, for
 *    objects too slow to change much between adjacent frames. Activity is only a gate on where
 *    to spend the search below: flicker can make a block active, and then fails step 2.
 *    Census codes are deliberately *not* used to decide what changed: on smooth gradients many
 *    neighbour differences sit at the dead band, and noise flips them constantly.
 *
 * 2. **Displacement, on the changed pixels only.** For an active block, find the shift v under
 *    which the changed pixels' current codes best match the reference frame's codes at p - v,
 *    i.e. where that structure came from. Unchanged background pixels are left out of the sum,
 *    so a small object in front of a textured background is not outvoted by the background
 *    (which would always prefer v = 0). A vector is only kept when it:
 *    - explains the change: its cost is at most [ALPHA] of the zero-shift cost. Flicker and
 *      noise change codes in place, and no shift explains them better than none;
 *    - is unique: the best cost outside its 3x3 neighbourhood is worse by at least [UNIQUENESS]
 *      of the gain. Repetitive texture (blinds, railings) fails this;
 *    - is consistent forwards and backwards: searching the other way (reference into current)
 *      finds the opposite shift, within 1px. This is what rejects *disocclusion*. Where
 *      something was and is now gone (a raindrop, the trailing edge of anything), the uncovered
 *      background matches almost any shift pointing off the old object, and looks like
 *      confident motion one way only.
 *
 *    The search runs at full resolution within +-[R0] px. If nothing is found there, it runs at
 *    half resolution within +-[R1] px (+-2*R1 at full) and refines at full resolution. If both
 *    fail, it runs against the lagged frame and divides by [lag]. Each frame-pair costs at most
 *    one of these per active block, and nothing at all for an inactive one, so a quiet scene
 *    costs about one Hamming pass over the frame.
 *
 * 3. **Grouping** ([blobs]): 8-connected moving blocks with similar vectors become one blob.
 *
 * Not thread-safe; owned by one [MotionDetector].
 */
internal class BlockMotion(
    private val width: Int,
    private val height: Int,
    private val block: Int = 8,
) {
    val cols = width / block
    val rows = height / block
    val count = cols * rows
    private val w1 = width / 2
    private val h1 = height / 2
    private val block1 = block / 2

    /** This frame's results, per block. */
    val active = BooleanArray(count)
    val moving = BooleanArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    /** Changed-pixel count, and their coordinate sums (for a centroid). */
    val support = IntArray(count)
    private val sumX = IntArray(count)
    private val sumY = IntArray(count)

    var activeCount = 0; private set
    var movingCount = 0; private set

    private val base = FloatArray(count)
    private val baseLag = FloatArray(count)
    private var baseSamples = 0

    // Scratch.
    private val pix = IntArray(block * block)
    private val pixLag = IntArray(block * block)
    private val pix1 = IntArray(block1 * block1)
    private val mask1 = BooleanArray(block1 * block1)
    private val costs = IntArray((2 * maxOf(R0, R1) + 1).let { it * it })
    private val m = Match()

    private class Match {
        var dx = 0; var dy = 0
        var best = 0; var second = 0; var zero = 0
    }

    fun reset() {
        base.fill(0f)
        baseLag.fill(0f)
        baseSamples = 0
    }

    private lateinit var full: Census.Frames
    private lateinit var half: Census.Frames
    private var cur = 0
    private var prev = 0
    private var lagged = -1
    private var hcur = 0
    private var hprev = 0
    private var eps = IntArray(0)
    private var epsHalf = IntArray(0)

    /**
     * @param full luma and (lazily computed) census history at full resolution: [cur], [prev],
     *   and [lagged] ([lag] frames back, or -1 while the history is shorter than that).
     * @param half the same at half resolution, for [hcur] and [hprev].
     * @param eps / [epsHalf] census dead bands to compute codes with (see [NoiseModel.epsTable]).
     * @param lo / [hi] and [lagLo] / [lagHi] the unchanged ranges of a luma difference against
     *   the previous and the lagged frame, by current luma (see [NoiseModel.changeTable]).
     */
    fun analyse(
        full: Census.Frames, cur: Int, prev: Int, lagged: Int, lag: Int,
        half: Census.Frames, hcur: Int, hprev: Int,
        eps: IntArray, epsHalf: IntArray, lo: IntArray, hi: IntArray, lagLo: IntArray, lagHi: IntArray,
    ) {
        this.full = full; this.half = half
        this.cur = cur; this.prev = prev; this.lagged = lagged; this.hcur = hcur; this.hprev = hprev
        this.eps = eps; this.epsHalf = epsHalf
        val y0 = full.luma[cur]
        val yp = full.luma[prev]
        val yq = if (lagged >= 0) full.luma[lagged] else null
        activeCount = 0; movingCount = 0
        val alpha = maxOf(1f / (baseSamples + 1), BASE_ALPHA)
        for (by in 0 until rows) for (bx in 0 until cols) {
            val b = by * cols + bx
            val bx0 = bx * block
            val by0 = by * block
            var n = 0; var nLag = 0; var sx = 0; var sy = 0
            for (y in by0 until by0 + block) {
                var i = y * width + bx0
                for (x in bx0 until bx0 + block) {
                    val v = y0[i].toInt() and 0xFF
                    val d = v - (yp[i].toInt() and 0xFF)
                    if (d < lo[v] || d > hi[v]) { pix[n++] = i; sx += x; sy += y }
                    if (yq != null) {
                        val dq = v - (yq[i].toInt() and 0xFF)
                        if (dq < lagLo[v] || dq > lagHi[v]) pixLag[nLag++] = i
                    }
                    i++
                }
            }
            support[b] = n; sumX[b] = sx; sumY[b] = sy
            val act = baseSamples >= BOOT_FRAMES && n >= maxOf(MIN_SUPPORT, ceil(ACT_K * base[b] + 1f).toInt())
            val actLag = baseSamples >= BOOT_FRAMES && yq != null &&
                nLag >= maxOf(MIN_SUPPORT, ceil(ACT_K * baseLag[b] + 1f).toInt())
            // Baselines learn from blocks judged quiet, so a moving object does not raise its own
            // bar - except while bootstrapping (every block looks active against a baseline of
            // zero, so nothing would ever be learned) and, very slowly, from a block that stays
            // busy for minutes, which is noise or foliage rather than an object.
            val boot = baseSamples < BOOT_FRAMES
            base[b] += (if (!act || boot) alpha else alpha * BUSY_RATE) * (n - base[b])
            if (yq != null) baseLag[b] += (if (!actLag || boot) alpha else alpha * BUSY_RATE) * (nLag - baseLag[b])
            active[b] = act
            if (act) activeCount++
            moving[b] = false
            if (act && n >= MIN_SEARCH_SUPPORT) displacement(b, bx, by, n)
            if (!moving[b] && actLag && nLag >= MIN_SEARCH_SUPPORT && lagDisplacement(b, bx, by, nLag, lag)) {
                // A slow object's pixels changed against the lagged frame, not the previous one,
                // so its size and position come from those.
                centroidOf(b, pixLag, nLag)
            }
            if (moving[b]) movingCount++
        }
        baseSamples++
    }

    private fun centroidOf(b: Int, list: IntArray, n: Int) {
        var sx = 0; var sy = 0
        for (k in 0 until n) { sx += list[k] % width; sy += list[k] / width }
        sumX[b] = sx; sumY[b] = sy; support[b] = n
    }

    /** Census codes valid for [slot] of [frames] over a block and [margin] pixels around it. */
    private fun ensure(frames: Census.Frames, slot: Int, x0: Int, y0: Int, size: Int, margin: Int, eps: IntArray) =
        frames.ensure(slot, x0 - margin - 1, y0 - margin - 1, x0 + size + margin + 1, y0 + size + margin + 1, eps)

    private fun displacement(b: Int, bx: Int, by: Int, n: Int) {
        val c0 = full.codes[cur]
        val p0 = full.codes[prev]
        // Full resolution, small shifts. Both directions are searched (see [consistent]), so
        // both frames need codes over the block and the search range around it.
        ensure(full, cur, bx * block, by * block, block, R0, eps)
        ensure(full, prev, bx * block, by * block, block, R0, eps)
        search(c0, p0, width, height, pix, n, 0, 0, R0, m)
        if (acceptable(m, n) && consistent(p0, c0, width, height, pix, n, m.dx, m.dy, R0)) {
            setVector(b, m.dx.toFloat(), m.dy.toFloat()); return
        }

        // Half resolution, larger shifts, then refined. A half-resolution pixel is in the
        // search when any of the four full-resolution pixels under it changed.
        var n1 = 0
        mask1.fill(false)
        for (k in 0 until n) {
            val i = pix[k]
            mask1[((i / width) % block) / 2 * block1 + ((i % width) % block) / 2] = true
        }
        val x1 = bx * block1
        val y1 = by * block1
        for (j in 0 until block1) for (k in 0 until block1) {
            if (mask1[j * block1 + k]) pix1[n1++] = (y1 + j) * w1 + x1 + k
        }
        if (n1 < MIN_SUPPORT_HALF) return
        val c1 = half.codes[hcur]
        val p1 = half.codes[hprev]
        ensure(half, hcur, x1, y1, block1, R1, epsHalf)
        ensure(half, hprev, x1, y1, block1, R1, epsHalf)
        search(c1, p1, w1, h1, pix1, n1, 0, 0, R1, m)
        if (!acceptable(m, n1) || !consistent(p1, c1, w1, h1, pix1, n1, m.dx, m.dy, R1)) return
        val zero0 = cost(c0, p0, width, height, pix, n, 0, 0)
        ensure(full, prev, bx * block, by * block, block, 2 * R1 + 1, eps)
        search(c0, p0, width, height, pix, n, 2 * m.dx, 2 * m.dy, 1, m)
        if ((m.dx != 0 || m.dy != 0) && explains(m.best, zero0, n)) setVector(b, m.dx.toFloat(), m.dy.toFloat())
    }

    private fun lagDisplacement(b: Int, bx: Int, by: Int, nLag: Int, lag: Int): Boolean {
        val c0 = full.codes[cur]
        val q0 = full.codes[lagged]
        ensure(full, cur, bx * block, by * block, block, R0, eps)
        ensure(full, lagged, bx * block, by * block, block, R0, eps)
        search(c0, q0, width, height, pixLag, nLag, 0, 0, R0, m)
        if (!acceptable(m, nLag) || !consistent(q0, c0, width, height, pixLag, nLag, m.dx, m.dy, R0)) return false
        setVector(b, m.dx.toFloat() / lag, m.dy.toFloat() / lag)
        return true
    }

    private val back = Match()

    /** Forward-backward check: searching [ref] into [cur] over the same pixels must find about
     *  minus ([dx], [dy]), and that shift must be acceptable in its own right. */
    private fun consistent(ref: IntArray, cur: IntArray, w: Int, h: Int, list: IntArray, n: Int, dx: Int, dy: Int, r: Int): Boolean {
        search(ref, cur, w, h, list, n, 0, 0, r, back)
        return abs(back.dx + dx) <= 1 && abs(back.dy + dy) <= 1 && acceptable(back, n)
    }

    private fun setVector(b: Int, x: Float, y: Float) {
        moving[b] = true; vx[b] = x; vy[b] = y
    }

    private fun acceptable(m: Match, n: Int): Boolean {
        if (m.dx == 0 && m.dy == 0) return false
        if (!explains(m.best, m.zero, n)) return false
        return m.second - m.best >= UNIQUENESS * (m.zero - m.best)
    }

    /**
     * Whether a shift's cost [best] explains the change, against [zero] over [n] pixels: it must
     * remove most of the mismatch ([ALPHA]) and at least [MIN_GAIN_BITS] per pixel. The second
     * test is what noise fails. A noisy pixel only just over the change threshold has little
     * mismatch to remove, and among dozens of candidate shifts one will remove a good fraction
     * of it by chance. A real object's pixels move whole codes.
     */
    private fun explains(best: Int, zero: Int, n: Int): Boolean =
        best <= ALPHA * zero && zero - best >= MIN_GAIN_BITS * n

    /**
     * Exhaustive search within +-[r] of ([cx], [cy]); fills [out] with the best shift, its cost,
     * the best cost outside its 3x3 neighbourhood, and the zero-shift cost.
     *
     * Two shortcuts, neither of which changes what [acceptable] decides:
     * - when the zero-shift mismatch is under [MIN_GAIN_BITS] per pixel, no shift can explain
     *   enough of it, and the search is skipped (out = zero shift). Flicker leaves census codes
     *   in place, so this is where flickering blocks stop;
     * - each candidate's sum stops once past `(ALPHA + UNIQUENESS) * zero`. A candidate costing
     *   that much can be neither an acceptable best (over `ALPHA * zero`) nor a second-best close
     *   enough to fail the uniqueness test.
     */
    private fun search(cur: IntArray, ref: IntArray, w: Int, h: Int, list: IntArray, n: Int, cx: Int, cy: Int, r: Int, out: Match) {
        prepare(cur, w, list, n)
        val zero = costPrepared(ref, w, h, n, 0, 0, Int.MAX_VALUE)
        out.zero = zero
        if (zero < MIN_GAIN_BITS * n) {
            out.dx = 0; out.dy = 0; out.best = zero; out.second = zero
            return
        }
        val cap = ((ALPHA + UNIQUENESS) * zero).toInt() + 1
        val side = 2 * r + 1
        var best = Int.MAX_VALUE; var bdx = 0; var bdy = 0
        for (j in 0 until side) for (k in 0 until side) {
            val dx = cx + k - r
            val dy = cy + j - r
            val c = if (dx == 0 && dy == 0) zero else costPrepared(ref, w, h, n, dx, dy, cap)
            costs[j * side + k] = c
            if (c < best || (c == best && abs(dx) + abs(dy) < abs(bdx) + abs(bdy))) { best = c; bdx = dx; bdy = dy }
        }
        var second = Int.MAX_VALUE
        for (j in 0 until side) for (k in 0 until side) {
            val dx = cx + k - r
            val dy = cy + j - r
            if (abs(dx - bdx) <= 1 && abs(dy - bdy) <= 1) continue
            second = minOf(second, costs[j * side + k])
        }
        out.dx = bdx; out.dy = bdy; out.best = best
        out.second = if (second == Int.MAX_VALUE) best else second
    }

    /** Sum of Hamming distances between each listed pixel's current code and the reference code
     *  at that pixel minus (dx, dy); a shift that leaves the frame costs [OUT_OF_FRAME] per pixel. */
    private fun cost(cur: IntArray, ref: IntArray, w: Int, h: Int, list: IntArray, n: Int, dx: Int, dy: Int): Int {
        prepare(cur, w, list, n)
        return costPrepared(ref, w, h, n, dx, dy, Int.MAX_VALUE)
    }

    // The listed pixels' coordinates and current codes, unpacked once per search rather than
    // once per candidate shift: an integer divide per pixel per shift dominated the search on
    // the BLU G5's Cortex-A55s.
    private val px = IntArray(block * block)
    private val py = IntArray(block * block)
    private val pc = IntArray(block * block)
    private var minX = 0; private var maxX = 0; private var minY = 0; private var maxY = 0

    private fun prepare(cur: IntArray, w: Int, list: IntArray, n: Int) {
        minX = Int.MAX_VALUE; maxX = Int.MIN_VALUE; minY = Int.MAX_VALUE; maxY = Int.MIN_VALUE
        for (k in 0 until n) {
            val i = list[k]
            val x = i % w
            val y = i / w
            px[k] = x; py[k] = y; pc[k] = cur[i]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }

    /** Cost of one shift over the [prepare]d pixels; stops early (returning something over
     *  [cap]) once the sum passes [cap]. */
    private fun costPrepared(ref: IntArray, w: Int, h: Int, n: Int, dx: Int, dy: Int, cap: Int): Int {
        var s = 0
        if (minX - dx >= 0 && maxX - dx < w && minY - dy >= 0 && maxY - dy < h) {
            // Whole shifted footprint inside the frame: no per-pixel bounds checks.
            val off = -dy * w - dx
            for (k in 0 until n) {
                s += Integer.bitCount(pc[k] xor ref[py[k] * w + px[k] + off])
                if (s > cap) return s
            }
            return s
        }
        for (k in 0 until n) {
            val x = px[k] - dx
            val y = py[k] - dy
            s += if (x < 0 || y < 0 || x >= w || y >= h) OUT_OF_FRAME else Integer.bitCount(pc[k] xor ref[y * w + x])
            if (s > cap) return s
        }
        return s
    }

    /** One connected group of moving blocks. Coordinates in full-resolution pixels; velocity in
     *  pixels per analysed frame. */
    class Blob(
        val support: Int,
        val cx: Float,
        val cy: Float,
        val vx: Float,
        val vy: Float,
        val blocks: Int,
    ) {
        val speed: Float get() = hypot(vx, vy)
    }

    private val label = IntArray(count)
    private val stack = IntArray(count)

    /** Group moving blocks into blobs: 8-connected neighbours whose vectors agree to within
     *  1px plus a third of their speed (parts of one object, or one swaying tree, move alike). */
    fun blobs(): List<Blob> {
        if (movingCount == 0) return emptyList()
        label.fill(-1)
        val out = ArrayList<Blob>()
        for (start in 0 until count) {
            if (!moving[start] || label[start] >= 0) continue
            var sp = 0
            stack[sp++] = start
            label[start] = start
            var sup = 0; var sx = 0L; var sy = 0L; var wvx = 0f; var wvy = 0f; var wsum = 0; var nb = 0
            while (sp > 0) {
                val b = stack[--sp]
                val w = support[b].coerceAtLeast(1)
                sup += support[b]; sx += sumX[b]; sy += sumY[b]
                wvx += vx[b] * w; wvy += vy[b] * w; wsum += w; nb++
                val bx = b % cols
                val by = b / cols
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = bx + dx; val ny = by + dy
                    if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue
                    val o = ny * cols + nx
                    if (!moving[o] || label[o] >= 0) continue
                    val tol = 1f + 0.35f * maxOf(hypot(vx[b], vy[b]), hypot(vx[o], vy[o]))
                    if (abs(vx[o] - vx[b]) > tol || abs(vy[o] - vy[b]) > tol) continue
                    label[o] = start
                    stack[sp++] = o
                }
            }
            val cnt = sup.coerceAtLeast(1)
            out.add(Blob(sup, sx.toFloat() / cnt, sy.toFloat() / cnt, wvx / wsum, wvy / wsum, nb))
        }
        return out
    }

    /**
     * Whether this frame's vectors say the *camera* moved: a large share of the textured blocks
     * moving, nearly all by the same vector of at least 0.75px.
     */
    fun globalMotion(): Boolean {
        // Cheap test first: only count textured blocks (a pass over the frame) when enough moved
        // to possibly be the camera.
        if (movingCount < GLOBAL_MIN_BLOCKS) return false
        if (movingCount < (GLOBAL_SHARE * texturedBlocks()).toInt()) return false
        val xs = FloatArray(movingCount)
        val ys = FloatArray(movingCount)
        var k = 0
        for (b in 0 until count) if (moving[b]) { xs[k] = vx[b]; ys[k] = vy[b]; k++ }
        val mx = xs.sortedArray()[k / 2]
        val my = ys.sortedArray()[k / 2]
        if (hypot(mx, my) < 0.75f) return false
        var agree = 0
        for (i in 0 until k) if (abs(xs[i] - mx) <= 1f && abs(ys[i] - my) <= 1f) agree++
        return agree >= GLOBAL_AGREE * k
    }

    /** Blocks with structure to match: a quarter of their pixels have a neighbour differing by
     *  more than the census dead band (judged from luma, so it costs no census codes). */
    private fun texturedBlocks(): Int {
        val y0 = full.luma[cur]
        var blocks = 0
        for (by in 0 until rows) for (bx in 0 until cols) {
            var tex = 0
            for (y in by * block until by * block + block) {
                var i = y * width + bx * block
                for (x in bx * block until bx * block + block) {
                    val v = y0[i].toInt() and 0xFF
                    if (x + 1 < width && abs((y0[i + 1].toInt() and 0xFF) - v) > eps[v] ||
                        y + 1 < height && abs((y0[i + width].toInt() and 0xFF) - v) > eps[v]
                    ) tex++
                    i++
                }
            }
            if (tex >= block * block / 4) blocks++
        }
        return blocks
    }

    internal companion object {
        const val MIN_SUPPORT = 4
        /** Fewer changed pixels than this are not searched: a match over a handful of pixels
         *  says little, and in rain such blocks were most of the searches. */
        const val MIN_SEARCH_SUPPORT = 8
        const val MIN_SUPPORT_HALF = 2
        const val ACT_K = 3f
        const val BASE_ALPHA = 1f / 150f
        const val R0 = 3
        const val R1 = 4
        const val BOOT_FRAMES = 3
        const val BUSY_RATE = 0.05f
        /**
         * The best shift must leave at most this share of the zero-shift mismatch. Not 0.5: half
         * the changed pixels of a small object are disocclusion (the background it uncovered),
         * which no shift explains, and at 0.5 most distant cars on the Pixel 6's street view were
         * rejected outright (2026-10-17). 0.65 let synthetic rain through.
         */
        const val ALPHA = 0.6f
        const val MIN_GAIN_BITS = 1.5f
        const val UNIQUENESS = 0.15f
        const val OUT_OF_FRAME = 8
        const val GLOBAL_MIN_BLOCKS = 12
        const val GLOBAL_SHARE = 0.4f
        const val GLOBAL_AGREE = 0.7f
    }
}
