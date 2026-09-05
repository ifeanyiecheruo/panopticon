package com.panopticon.phoneapp.calibration

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure zoom-geometry + image-metric helpers for the calibration probe.
 * Deliberately free of `android.graphics.Rect` / Camera2 types so it unit-tests
 * on the plain JVM - `CalibrationRunner` converts to/from framework types at
 * the edges.
 */
object ZoomMath {

    /** Integer pixel rectangle in sensor active-array coordinates. */
    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2f
        val centerY get() = (top + bottom) / 2f
    }

    /**
     * A centred crop of [active] that yields magnification [ratio] (>= 1).
     * Ratio 1 -> the whole array; ratio 2 -> the centre quarter; etc.
     */
    fun centeredCropForRatio(active: IntRect, ratio: Float): IntRect {
        val r = max(1f, ratio)
        val cw = (active.width / r).roundToInt().coerceIn(1, active.width)
        val ch = (active.height / r).roundToInt().coerceIn(1, active.height)
        val l = active.left + (active.width - cw) / 2
        val t = active.top + (active.height - ch) / 2
        return IntRect(l, t, l + cw, t + ch)
    }

    /**
     * Like [centeredCropForRatio] but shifted by [fx],[fy] (each in -1..1) of
     * the available slack - used to probe whether the HAL honours an off-centre
     * zoom rect or silently recentres it.
     */
    fun offsetCropForRatio(active: IntRect, ratio: Float, fx: Float, fy: Float): IntRect {
        val c = centeredCropForRatio(active, ratio)
        val slackX = (active.width - c.width) / 2
        val slackY = (active.height - c.height) / 2
        val dx = (fx.coerceIn(-1f, 1f) * slackX).roundToInt()
        val dy = (fy.coerceIn(-1f, 1f) * slackY).roundToInt()
        return IntRect(c.left + dx, c.top + dy, c.right + dx, c.bottom + dy)
    }

    /** [rect] expressed as fractions (0..1) of [active]. */
    fun normalize(rect: IntRect, active: IntRect): RectNorm {
        val w = active.width.toFloat().coerceAtLeast(1f)
        val h = active.height.toFloat().coerceAtLeast(1f)
        return RectNorm(
            l = (rect.left - active.left) / w,
            t = (rect.top - active.top) / h,
            r = (rect.right - active.left) / w,
            b = (rect.bottom - active.top) / h,
        )
    }

    /** Magnification implied by a crop rect's area relative to the full array. */
    fun ratioFromCrop(rect: IntRect, active: IntRect): Float {
        val aw = active.width.toFloat()
        val ah = active.height.toFloat()
        val fw = if (rect.width > 0) aw / rect.width else 1f
        val fh = if (rect.height > 0) ah / rect.height else 1f
        return (fw + fh) / 2f
    }

    /**
     * True if [reported] is within tolerance of [requested]. `tol` scales with
     * the request (a 5% slop by default) since HALs quantise the crop.
     */
    fun ratioHonored(requested: Float, reported: Float?, tolFraction: Float = 0.05f): Boolean {
        if (reported == null) return false
        val tol = max(0.02f, requested * tolFraction)
        return abs(reported - requested) <= tol
    }

    /** True if [reported]'s centre is within [tolPx] of [requested]'s centre. */
    fun positionHonored(requested: IntRect, reported: IntRect?, tolPx: Int): Boolean {
        if (reported == null) return false
        return abs(requested.centerX - reported.centerX) <= tolPx &&
            abs(requested.centerY - reported.centerY) <= tolPx
    }

    /**
     * Variance of a 3x3 Laplacian over a centred [patch]x[patch] window of the
     * Y plane - a cheap focus/sharpness proxy (high = crisp, low = soft/blurred).
     */
    fun varianceOfLaplacian(y: ByteArray, width: Int, height: Int, rowStride: Int, patch: Int): Double {
        val p = min(patch, min(width, height))
        if (p < 3) return 0.0
        val x0 = (width - p) / 2
        val y0 = (height - p) / 2
        fun lum(px: Int, py: Int): Int = y[py * rowStride + px].toInt() and 0xFF

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (py in y0 + 1 until y0 + p - 1) {
            for (px in x0 + 1 until x0 + p - 1) {
                val lap = (4 * lum(px, py) -
                    lum(px - 1, py) - lum(px + 1, py) -
                    lum(px, py - 1) - lum(px, py + 1)).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    // ---- Derived per-camera summaries ----

    data class Split(
        val opticalRange: FloatRange2,
        val digitalRange: FloatRange2,
        val crossoverRatio: Float?,
        val method: String,
    )

    /**
     * Given the swept ratios (ascending) plus, per ratio, which physical camera
     * was active and/or the reported lens focal length, work out where optical
     * zoom ends and digital zoom begins.
     *
     * Optical zoom shows up as the active physical id (or focal length)
     * *changing* as the ratio climbs; once it settles on the tele-most element
     * and only the crop shrinks, the rest is digital. A single physical sensor
     * => everything is digital.
     */
    fun deriveOpticalDigitalSplit(
        ratios: List<Float>,
        activePhysicalIds: List<String?>,
        focalLengths: List<Float?>,
    ): Split {
        if (ratios.isEmpty()) {
            return Split(FloatRange2(1f, 1f), FloatRange2(1f, 1f), null, "none")
        }
        val lo = ratios.first()
        val hi = ratios.last()

        val distinctIds = activePhysicalIds.filterNotNull().distinct()
        val distinctFocals = focalLengths.filterNotNull().distinct()

        if (distinctIds.size >= 2) {
            // Crossover = first ratio at which the active id reaches its final value.
            val finalId = activePhysicalIds.last { it != null }
            var crossIdx = activePhysicalIds.indexOfFirst { it == finalId }
            if (crossIdx < 0) crossIdx = 0
            val cross = ratios[crossIdx]
            return Split(FloatRange2(lo, cross), FloatRange2(cross, hi), cross, "active-physical-id")
        }
        if (distinctFocals.size >= 2) {
            val finalF = focalLengths.last { it != null }
            var crossIdx = focalLengths.indexOfFirst { it != null && abs(it - finalF!!) < 0.01f }
            if (crossIdx < 0) crossIdx = 0
            val cross = ratios[crossIdx]
            return Split(FloatRange2(lo, cross), FloatRange2(cross, hi), cross, "focal-length")
        }
        // Single physical element: no optical range to speak of.
        return Split(FloatRange2(1f, 1f), FloatRange2(lo, hi), null, "single-camera")
    }

    /**
     * First requested ratio whose sharpness (relative to the ratio-1.0 baseline)
     * has dropped below [dropFraction] - the point digital zoom visibly degrades.
     * null if it never does within the swept range.
     */
    fun deriveQualityCollapse(
        ratios: List<Float>,
        sharpnessRelToBaseline: List<Double>,
        dropFraction: Double = 0.6,
    ): Float? {
        for (i in ratios.indices) {
            if (i < sharpnessRelToBaseline.size && sharpnessRelToBaseline[i] < dropFraction) {
                return ratios[i]
            }
        }
        return null
    }
}
