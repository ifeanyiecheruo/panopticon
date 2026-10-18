package com.panopticon.phoneapp.motion

/**
 * Ternary census transform: each pixel becomes a 16-bit code saying, for each of its 8
 * neighbours, whether that neighbour is brighter than it by more than a dead band (`01`), darker
 * by more than it (`10`), or about the same (`00`).
 *
 * This is what makes the detector blind to flicker. A TV glow, a lamp or auto-exposure scales
 * and offsets brightness, but it rarely reverses which of two neighbouring pixels is brighter, so
 * the codes do not change. When something moves, the codes move with it. The dead band ([eps],
 * from [NoiseModel]) keeps flat regions from flipping bits on sensor noise. Matching then
 * compares codes by Hamming distance (`Integer.bitCount(a xor b)`).
 *
 * The one-pixel border gets code 0 (no structure).
 */
internal object Census {
    /** Number of set bits (= neighbours outside the dead band) for a pixel to count as textured. */
    const val TEXTURED_BITS = 2

    fun transform(luma: ByteArray, width: Int, height: Int, eps: IntArray, out: IntArray) =
        transformRect(luma, width, height, eps, out, 0, 0, width, height)

    /** [transform] over the rectangle [x0], [y0] (inclusive) to [x1], [y1] (exclusive) only. */
    fun transformRect(luma: ByteArray, width: Int, height: Int, eps: IntArray, out: IntArray, x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in y0 until y1) {
            val row = y * width
            if (y == 0 || y == height - 1) {
                for (x in x0 until x1) out[row + x] = 0
                continue
            }
            if (x0 == 0) out[row] = 0
            if (x1 == width) out[row + width - 1] = 0
            for (x in maxOf(1, x0) until minOf(width - 1, x1)) {
                val i = row + x
                val c = luma[i].toInt() and 0xFF
                val e = eps[c]
                val hi = c + e
                val lo = c - e
                var code = 0
                code = code or trit(luma[i - width - 1].toInt() and 0xFF, hi, lo, 0)
                code = code or trit(luma[i - width].toInt() and 0xFF, hi, lo, 2)
                code = code or trit(luma[i - width + 1].toInt() and 0xFF, hi, lo, 4)
                code = code or trit(luma[i - 1].toInt() and 0xFF, hi, lo, 6)
                code = code or trit(luma[i + 1].toInt() and 0xFF, hi, lo, 8)
                code = code or trit(luma[i + width - 1].toInt() and 0xFF, hi, lo, 10)
                code = code or trit(luma[i + width].toInt() and 0xFF, hi, lo, 12)
                code = code or trit(luma[i + width + 1].toInt() and 0xFF, hi, lo, 14)
                out[i] = code
            }
        }
    }

    /**
     * Census codes for a history of frames, computed **on demand** in [TILE]x[TILE] tiles and
     * cached per frame. Codes are only ever compared around blocks something changed in, so a
     * quiet scene computes almost none. Over the whole frame, census was two thirds of the
     * detector's cost (2026-10-17, JVM profile). Each slot holds one frame's luma; a slot reused
     * for a new frame must be [invalidate]d.
     *
     * Codes are computed with the dead band in force when they are first asked for, so a cached
     * reference frame and the current frame may use slightly different bands. The band follows
     * the noise estimate, which moves over seconds, so the difference is negligible.
     */
    class Frames(val width: Int, val height: Int, slots: Int) {
        val luma = Array(slots) { ByteArray(width * height) }
        val codes = Array(slots) { IntArray(width * height) }
        private val tilesX = (width + TILE - 1) / TILE
        private val tilesY = (height + TILE - 1) / TILE
        private val done = Array(slots) { BooleanArray(tilesX * tilesY) }

        fun invalidate(slot: Int) = done[slot].fill(false)

        /** Make [slot]'s codes valid over the rectangle (clipped to the frame). */
        fun ensure(slot: Int, x0: Int, y0: Int, x1: Int, y1: Int, eps: IntArray) {
            val tx0 = (x0.coerceAtLeast(0)) / TILE
            val ty0 = (y0.coerceAtLeast(0)) / TILE
            val tx1 = (x1.coerceAtMost(width) - 1) / TILE
            val ty1 = (y1.coerceAtMost(height) - 1) / TILE
            val d = done[slot]
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val k = ty * tilesX + tx
                if (d[k]) continue
                d[k] = true
                transformRect(
                    luma[slot], width, height, eps, codes[slot],
                    tx * TILE, ty * TILE, minOf(width, (tx + 1) * TILE), minOf(height, (ty + 1) * TILE),
                )
            }
        }
    }

    const val TILE = 8

    /** `01` if [n] > [hi], `10` if [n] < [lo], else `00`, at [shift]. Branchless - the sign
     *  bits of the two differences - because this runs eight times per pixel and
     *  unpredictable branches on noisy pixels were most of its cost. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun trit(n: Int, hi: Int, lo: Int, shift: Int): Int =
        (((hi - n) ushr 31) or (((n - lo) ushr 31) shl 1)) shl shift

    /** 2x2 box average into a half-size image (odd trailing row/column dropped). */
    fun downsample(src: ByteArray, width: Int, height: Int, out: ByteArray) {
        val w2 = width / 2
        val h2 = height / 2
        for (y in 0 until h2) {
            val a = 2 * y * width
            val b = a + width
            val o = y * w2
            for (x in 0 until w2) {
                val s = (src[a + 2 * x].toInt() and 0xFF) + (src[a + 2 * x + 1].toInt() and 0xFF) +
                    (src[b + 2 * x].toInt() and 0xFF) + (src[b + 2 * x + 1].toInt() and 0xFF)
                out[o + x] = ((s + 2) shr 2).toByte()
            }
        }
    }
}
