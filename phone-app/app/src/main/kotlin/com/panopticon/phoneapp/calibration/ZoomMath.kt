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

    /**
     * Whether the reported `SCALER_CROP_REGION` *metadata* matched an
     * intentionally off-centre request (centre within [tolPx]). NB: a HAL can
     * echo the requested region here while actually centring the output - use
     * [frameShifted] on the pixels to catch that.
     */
    fun positionMetadataMatch(requested: IntRect, reported: IntRect?, tolPx: Int): Boolean {
        if (reported == null) return false
        return abs(requested.centerX - reported.centerX) <= tolPx &&
            abs(requested.centerY - reported.centerY) <= tolPx
    }

    /**
     * Did the off-centre-cropped frame [offCentre] actually show a different
     * part of the scene than the centred frame [centred] at the same zoom? If
     * the two are near-identical, the HAL ignored the crop position (whatever
     * its metadata said). Both are the same-size Y planes; compares a
     * grid-subsampled mean-absolute-difference, normalised by mean luma so it's
     * brightness-independent. [minMeanLuma] guards against a black scene giving
     * a meaningless verdict.
     */
    fun frameShifted(
        centred: ByteArray, offCentre: ByteArray,
        width: Int, height: Int, rowStride: Int,
        threshold: Double = 0.06,
        minMeanLuma: Double = 6.0,
    ): Boolean? {
        val cols = 48
        val rows = 36
        var diff = 0.0
        var lumaSum = 0.0
        var n = 0
        for (gy in 0 until rows) {
            val py = (gy * height) / rows
            val row = py * rowStride
            for (gx in 0 until cols) {
                val px = (gx * width) / cols
                val a = centred[row + px].toInt() and 0xFF
                val b = offCentre[row + px].toInt() and 0xFF
                diff += abs(a - b)
                lumaSum += a
                n++
            }
        }
        if (n == 0) return null
        val meanLuma = lumaSum / n
        if (meanLuma < minMeanLuma) return null // too dark to tell
        return (diff / n) / meanLuma > threshold
    }

    /**
     * Contrast-normalised sharpness: variance of a 3x3 Laplacian over a centred
     * [patch]x[patch] window of the Y plane, divided by mean-luma squared.
     * The raw Laplacian scales with luma amplitude, so its variance scales with
     * luma^2 - dividing that out makes the number comparable across exposures
     * (a brighter frame of the same scene detail scores the same). High = crisp.
     */
    fun sharpness(y: ByteArray, width: Int, height: Int, rowStride: Int, patch: Int): Double {
        val p = min(patch, min(width, height))
        if (p < 3) return 0.0
        val x0 = (width - p) / 2
        val y0 = (height - p) / 2
        fun lum(px: Int, py: Int): Int = y[py * rowStride + px].toInt() and 0xFF

        var lapSum = 0.0
        var lapSumSq = 0.0
        var lumaSum = 0.0
        var n = 0
        for (py in y0 + 1 until y0 + p - 1) {
            for (px in x0 + 1 until x0 + p - 1) {
                val lap = (4 * lum(px, py) -
                    lum(px - 1, py) - lum(px + 1, py) -
                    lum(px, py - 1) - lum(px, py + 1)).toDouble()
                lapSum += lap
                lapSumSq += lap * lap
                lumaSum += lum(px, py)
                n++
            }
        }
        if (n == 0) return 0.0
        val lapMean = lapSum / n
        val lapVar = lapSumSq / n - lapMean * lapMean
        val meanLuma = lumaSum / n
        return lapVar / (meanLuma * meanLuma + 1.0)
    }

    /** Median of the per-frame [sharpness] values - robust to a single noisy frame. */
    fun medianSharpness(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
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
     * First requested ratio where sharpness (relative to the baseline of the
     * *best* early sample) drops below [dropFraction] **and stays below it** for
     * the rest of the sweep (or at least [sustain] more samples). The
     * sustained-drop requirement stops a single noisy frame from firing a false
     * "quality collapsed here". null if it never sustainedly drops.
     */
    fun deriveQualityCollapse(
        ratios: List<Float>,
        sharpnessRelToBaseline: List<Double>,
        dropFraction: Double = 0.5,
        sustain: Int = 2,
    ): Float? {
        val n = min(ratios.size, sharpnessRelToBaseline.size)
        for (i in 0 until n) {
            if (sharpnessRelToBaseline[i] >= dropFraction) continue
            val window = (i until minOf(n, i + 1 + sustain))
            if (window.all { sharpnessRelToBaseline[it] < dropFraction }) {
                return ratios[i]
            }
        }
        return null
    }
}
