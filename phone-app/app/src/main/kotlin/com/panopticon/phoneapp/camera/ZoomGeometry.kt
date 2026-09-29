package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import kotlin.math.max
import kotlin.math.min

/**
 * The pure geometry behind zoom: turning a rect the user drew over the live view into an absolute
 * view rect, and splitting that view between a hardware zoom request and a leftover GL crop.
 *
 * Deliberately free of Camera2 / `android.graphics` types so it unit-tests on a plain JVM;
 * [CameraControlApply] and [com.panopticon.phoneapp.http.routes.CameraRoutes] convert at the edges.
 *
 * ## Coordinate spaces
 *
 * - **viewer space** - `0..1` over the frame the controller is displaying: already rotated by
 *   `rotationDegrees`, already cropped by whatever zoom is in effect. Every rect the controller
 *   sends is in this space (see docs/design/http-api.md, "Coordinate spaces").
 * - **frame space** - `0..1` over the *un-zoomed* view, pre-rotation. The un-zoomed view is the
 *   full sensor frame trimmed to the output aspect ratio - the trim [CameraFraming.correctedTexCrop]
 *   computes - so frame space is what zoom state is stored in.
 * - **sensor space** - `0..1` over the sensor active array, which is what the HAL's
 *   `SCALER_CROP_REGION` and calibration's `effectiveCropNorm` use.
 *
 * Frame space is the useful one to hold state in, because *in it, a rect at the output aspect
 * ratio is simply a square*: normalising x by the frame's width and y by its height makes the
 * whole frame a unit square, so any sub-rect sharing its aspect is a unit-square sub-rect too.
 * That is why [fitToAspect] is "grow to a square", and why composing one selection onto another
 * ([compose]) preserves the aspect ratio exactly, with no accumulating drift, however many times
 * the user zooms in.
 */
internal object ZoomGeometry {

    /** The whole frame - the un-zoomed view, and the crop a HAL that was asked for no zoom
     *  delivers. */
    val FULL = RectNorm(0f, 0f, 1f, 1f)

    /** The largest GL upscale we will ask for. Past this the residual crop is mostly interpolated
     *  pixels; the capture resolution is deliberately *not* raised to feed it (that would cost
     *  bandwidth and thermals for detail the sensor never delivered), so this is a legibility
     *  guard, not a quality one. */
    const val MAX_GL_RESIDUAL = 8f

    /** Ceiling on a computed magnification, so a degenerate view can't ask for an absurd one. */
    const val MAX_MAGNIFICATION = 100f

    /** Smallest square (in the normalised space [r] is expressed in) that *contains* [r], nudged
     *  back inside `0..1` if it would overflow. Containing rather than fitting-inside is the point:
     *  nothing the user boxed is ever cropped away by the aspect correction.
     *
     *  Clamps to 1.0 on the long axis if the drag was so elongated that its square can't fit -
     *  a selection taller than the frame is full height, and as wide. */
    fun fitToAspect(r: RectNorm): RectNorm {
        val side = min(1f, max(r.r - r.l, r.b - r.t))
        return recentreWithin01(centreOf(r), side, side)
    }

    /** [selection] (viewer space, within the current view) composed onto [current] (frame space),
     *  giving the new absolute view in frame space. [rotationDegrees] is undone first, so the
     *  region the user saw is the region that gets captured.
     *
     *  [selection] is fitted to the output aspect *before* composing, so the result is always a
     *  square in frame space no matter what the user dragged. */
    fun compose(current: RectNorm, selection: RectNorm, rotationDegrees: Int): RectNorm {
        val fitted = fitToAspect(selection)
        val preRotation = ViewportRect.inverseRotateRect(fitted, rotationDegrees)
        return ViewportRect.composeWithViewport(preRotation, current)
    }

    /** The frame-space view for a magnification of [ratio] about [current]'s own centre - the
     *  zoom slider. Keeping the centre is what stops a slider nudge from throwing away the
     *  framing a rect selection just set up. [ratio] 1.0 is the whole frame. */
    fun viewForRatio(current: RectNorm, ratio: Float): RectNorm {
        val side = (1f / max(1f, ratio)).coerceIn(1e-4f, 1f)
        return recentreWithin01(centreOf(current), side, side)
    }

    /** [view]'s magnification relative to the un-zoomed frame - the number the slider shows. */
    fun ratioOf(view: RectNorm): Float {
        val w = (view.r - view.l).coerceAtLeast(1e-6f)
        val h = (view.b - view.t).coerceAtLeast(1e-6f)
        return 2f / (w + h)
    }

    /** [view] (frame space) expressed in sensor space, given the output-aspect trim
     *  ([CameraFraming.correctedTexCrop])'s factors: the un-zoomed view *is* that centred trim,
     *  so frame space is just "normalised within it". */
    fun viewToSensor(view: RectNorm, texCropX: Float, texCropY: Float): RectNorm =
        ViewportRect.composeWithViewport(view, defaultViewSensor(texCropX, texCropY))

    /** The un-zoomed view in sensor space: the full array centre-trimmed to the output aspect. */
    fun defaultViewSensor(texCropX: Float, texCropY: Float): RectNorm =
        recentreWithin01(0.5f to 0.5f, texCropX, texCropY)

    // ---- hardware / GL split ----

    /**
     * One calibration sample, reduced to the only thing that can safely drive the split: the
     * magnification the camera **actually delivered** for a requested zoom ratio.
     *
     * Deliberately not a crop rect. On a device that drives zoom through `CONTROL_ZOOM_RATIO`
     * (API 30+), `SCALER_CROP_REGION` keeps reporting the *full* active array at every ratio - the
     * Pixel 6 reports `effectiveCropNorm ~= (1.0, 1.0)` all the way from 0.67x to 7x - so a
     * crop-rect-driven solver would conclude that every ratio still delivers the whole frame and
     * drive the hardware to its maximum at any zoom level. [ZoomCalibrationLut] picks whichever
     * signal is trustworthy on a given device and hands the result over here.
     */
    data class LutEntry(val requestedRatio: Float, val deliveredMagnification: Float)

    /**
     * How a target view is split between the camera and GL.
     *
     * @param hwRatio the zoom ratio to ask the HAL for (`1.0` = ask for nothing). Always a
     *   **centred** magnification - see [split].
     * @param deliveredMagnification what that request is predicted to actually produce.
     * @param deliveredCrop the region of the full field of view the buffer will then hold.
     * @param glRect the sub-rect of [deliveredCrop] GL must show, normalised *within* it - what
     *   the shader samples, carrying the off-centre positioning and any leftover zoom together.
     * @param glResidual the magnification [glRect] adds on top of the aspect trim; `1.0` = none.
     *   Above that it is a plain upscale of pixels already captured, adding no detail.
     */
    data class Split(
        val hwRatio: Float,
        val deliveredMagnification: Float,
        val deliveredCrop: RectNorm,
        val glRect: RectNorm,
        val glResidual: Float,
    )

    /**
     * Split [viewSensor] (the region the viewer should end up seeing) between a hardware zoom and
     * a GL crop, using the measured [lut].
     *
     * Two rules, both load-bearing:
     *
     * 1. **The hardware never crops away part of the selection.** It is only ever asked for a
     *    magnification whose field of view still fully contains the view; GL trims the rest.
     *    Interpolating *between* measured samples is what makes that a smooth dial rather than a
     *    staircase, and because it interpolates the **measured** curve it follows a non-linear
     *    optical/digital handover instead of assuming `field of view = 1/ratio`.
     * 2. **The hardware zoom is always centred.** `CONTROL_ZOOM_RATIO` is a scalar and a centred
     *    crop is isotropic, so it denotes the same region whether or not this camera's buffer is
     *    transposed relative to the sensor ([CameraFraming.axesSwapped]) - there is no
     *    sensor-coordinate mapping to get wrong. An off-centre `SCALER_CROP_REGION` would need
     *    exactly that mapping, which is where the previous zoom attempt came unstuck, so every
     *    off-centre movement is left to GL. The cost is that a selection hugging an edge gets
     *    little hardware help: the largest centred field of view containing it is barely zoomed.
     *
     * [texCropX]/[texCropY] are the output-aspect trim factors the pipeline renders with
     * ([CameraFraming.correctedTexCrop]): the camera must be asked to cover a *sensor-aspect*
     * region (see [sensorAspectCropFor]), and [Split.glResidual] is measured against the un-zoomed
     * shader rect, which is that trim.
     *
     * An empty [lut] (an uncalibrated phone) means "ask for nothing, GL does it all" - always
     * correct, just soft.
     */
    fun split(
        viewSensor: RectNorm,
        lut: List<LutEntry>,
        texCropX: Float = 1f,
        texCropY: Float = 1f,
    ): Split {
        val target = sensorAspectCropFor(viewSensor, texCropX, texCropY)
        val wanted = maxCentredMagnificationContaining(target)
        return splitFor(viewSensor, lut, largestRequestDelivering(lut, wanted), texCropX, texCropY)
    }

    /**
     * The split that holds while the hardware is **actually at** [hwRatio] - which, for several
     * frames after a zoom request, is not the ratio [split] asked for.
     *
     * [split] answers "what should we ask for?"; this answers "given what the buffer in our hands
     * really contains, where in it is the view?". They are the same call once the HAL has caught
     * up, and they must be kept apart until then: a `CONTROL_ZOOM_RATIO` change takes effect some
     * frames after the request is issued (on the Pixel 6, via a reconfigure that also drops a
     * frame or two), and a shader that adopts the *requested* [Split.deliveredCrop] early is
     * normalising the view against a crop the buffer does not hold yet. It then samples a sub-rect
     * of a sub-rect: the wrong region, magnified further, and soft - until the hardware lands and
     * the picture visibly snaps.
     *
     * Feeding this the ratio read back off a `CaptureResult` instead makes every intermediate
     * frame geometrically correct. The view stays put; it merely sharpens as the hardware takes
     * over from GL. `1.0` here - the honest starting state, before any result has been seen - is
     * simply "GL does all of it", which is what an uncalibrated phone does permanently.
     */
    fun splitFor(
        viewSensor: RectNorm,
        lut: List<LutEntry>,
        hwRatio: Float,
        texCropX: Float = 1f,
        texCropY: Float = 1f,
    ): Split {
        val delivered = deliveredMagnificationFor(lut, hwRatio)
        val deliveredCrop = centredCropFor(delivered)
        // The shader samples the *view*, not the sensor-aspect region the buffer holds: the
        // difference between the two IS the aspect trim, so this one rect does the trimming, the
        // off-centre positioning and the residual zoom together, keeping the GL crop's single
        // owner (commit c32688f).
        val glRect = normaliseWithin(viewSensor, deliveredCrop)
        return Split(
            hwRatio = hwRatio,
            deliveredMagnification = delivered,
            deliveredCrop = deliveredCrop,
            glRect = glRect,
            glResidual = residualOf(glRect, texCropX, texCropY),
        )
    }

    /** The largest centred magnification whose field of view still fully contains [target].
     *  Decided by whichever edge of [target] reaches furthest from the centre, since a centred
     *  crop shrinks symmetrically - so a target touching any edge pins this to `1.0`. */
    fun maxCentredMagnificationContaining(target: RectNorm): Float {
        val half = maxOf(0.5f - target.l, target.r - 0.5f, 0.5f - target.t, target.b - 0.5f)
        if (half <= 1e-6f) return MAX_MAGNIFICATION
        return (0.5f / half).coerceIn(1f, MAX_MAGNIFICATION)
    }

    /** The centred field of view a magnification of [m] leaves, in full-frame terms. */
    fun centredCropFor(m: Float): RectNorm {
        val side = (1f / max(1f, m)).coerceIn(1e-4f, 1f)
        return recentreWithin01(0.5f to 0.5f, side, side)
    }

    /** The largest requested ratio in [lut] whose *measured* delivered magnification stays within
     *  [wanted], interpolating between the two bracketing samples. `1.0` when even the smallest
     *  measured zoom would overshoot; never extrapolates past what was measured. */
    private fun largestRequestDelivering(lut: List<LutEntry>, wanted: Float): Float {
        val usable = usableEntries(lut).sortedBy { it.deliveredMagnification }
        if (usable.isEmpty() || wanted <= 1f) return 1f
        if (wanted >= usable.last().deliveredMagnification) return usable.last().requestedRatio
        for (i in 0 until usable.size - 1) {
            val a = usable[i]
            val b = usable[i + 1]
            if (wanted >= a.deliveredMagnification && wanted <= b.deliveredMagnification) {
                val span = b.deliveredMagnification - a.deliveredMagnification
                val t = if (span > 1e-6f) (wanted - a.deliveredMagnification) / span else 0f
                return (a.requestedRatio + (b.requestedRatio - a.requestedRatio) * t).coerceAtLeast(1f)
            }
        }
        return 1f
    }

    /** The magnification [lut] says [ratio] will actually produce, interpolated. With nothing
     *  measured, the honest answer is the ratio itself. */
    private fun deliveredMagnificationFor(lut: List<LutEntry>, ratio: Float): Float {
        // Asking for no zoom delivers no zoom, whatever the table contains. This is not the same
        // as clamping to the lowest measured sample: a camera whose sweep starts above 1.0 (the
        // Pixel 6 back camera's first zoom-in sample is its 1.152x optical handover, since the
        // samples below that zoom *out*) would otherwise have an un-zoomed frame predicted as
        // 1.152x magnified, and the shader would crop for a zoom the camera was never asked for.
        if (ratio <= 1f) return 1f
        val usable = usableEntries(lut).sortedBy { it.requestedRatio }
        if (usable.isEmpty()) return max(1f, ratio)
        if (ratio <= usable.first().requestedRatio) return max(1f, usable.first().deliveredMagnification)
        if (ratio >= usable.last().requestedRatio) return max(1f, usable.last().deliveredMagnification)
        for (i in 0 until usable.size - 1) {
            val a = usable[i]
            val b = usable[i + 1]
            if (ratio >= a.requestedRatio && ratio <= b.requestedRatio) {
                val span = b.requestedRatio - a.requestedRatio
                val t = if (span > 1e-6f) (ratio - a.requestedRatio) / span else 0f
                val m = a.deliveredMagnification + (b.deliveredMagnification - a.deliveredMagnification) * t
                return max(1f, m)
            }
        }
        return max(1f, ratio)
    }

    /** Samples that can serve as a crop to trim back from. A zoom-*out* sample widens the field of
     *  view rather than narrowing it, so there is nothing there for GL to crop into. */
    private fun usableEntries(lut: List<LutEntry>) =
        lut.filter { it.requestedRatio >= 1f && it.deliveredMagnification >= 1f }

    /** [view] (sensor space) grown about its own centre to the sensor's aspect ratio, which is
     *  what the HAL must be asked for: the camera fills a sensor-aspect buffer, and a crop of any
     *  other shape comes back anamorphically stretched. GL then trims it back to [view]. */
    fun sensorAspectCropFor(view: RectNorm, texCropX: Float, texCropY: Float): RectNorm =
        CameraFraming.growBySameCentre(view, texCropX, texCropY)

    /**
     * The one rect the shader samples (`uTexRect` in [GlBlit]), in displayed-frame fractions:
     * [view] (frame space, null = un-zoomed) placed inside whatever the camera actually delivered.
     *
     * This is the single crop the pipelines render with - it folds the fixed output-aspect trim
     * and the residual zoom together, so there is still exactly one place the GL crop is decided.
     * With no zoom and no hardware crop it reduces to the plain centred trim the pipelines used
     * before zoom existed.
     *
     * [deliveredCrop] defaults to [FULL] - the case where the HAL was asked for no zoom at all and
     * GL is doing the whole thing.
     */
    fun shaderRect(
        view: RectNorm?,
        texCropX: Float,
        texCropY: Float,
        deliveredCrop: RectNorm = FULL,
    ): RectNorm = normaliseWithin(viewToSensor(view ?: FULL, texCropX, texCropY), deliveredCrop)

    /**
     * [shaderRect] packed for `GlBlit`'s `uTexRect` uniform as `(x, y, width, height)`, **with the
     * vertical axis flipped**.
     *
     * The flip is the whole point of this function. Every rect in this file is viewer-oriented -
     * y grows *downward*, like the screen and like the box the user dragged. GL's texcoords run
     * the other way: [GlBlit.quad] pairs texcoord `(0,0)` with clip position `(-1,-1)`, so
     * `uTexRect.y` addresses the **bottom** edge of the output, not the top. Handing it a
     * top-down `t` renders the frame's vertical mirror.
     *
     * This never showed up before zoom because the only crop was the centred aspect trim, which
     * is symmetric about `0.5` - `1 - b == t` exactly, so both conventions agreed. An off-centre
     * zoom rect is the first thing that can tell them apart. (That symmetry is also a free
     * regression check: for any centred crop this function must return the same `y` as `t`.)
     */
    fun texRectUniform(
        view: RectNorm?,
        texCropX: Float,
        texCropY: Float,
        deliveredCrop: RectNorm = FULL,
    ): FloatArray {
        val r = shaderRect(view, texCropX, texCropY, deliveredCrop)
        return floatArrayOf(r.l, 1f - r.b, r.r - r.l, r.b - r.t)
    }

    // ---- helpers ----

    private fun centreOf(r: RectNorm): Pair<Float, Float> = (r.l + r.r) / 2f to (r.t + r.b) / 2f

    /** True when [outer] fully contains [inner], with a small epsilon so float noise at an edge
     *  doesn't reject a crop that is really a perfect fit. */
    private fun contains(outer: RectNorm, inner: RectNorm): Boolean {
        val eps = 1e-4f
        return outer.l <= inner.l + eps && outer.t <= inner.t + eps &&
            outer.r >= inner.r - eps && outer.b >= inner.b - eps
    }

    /** [inner] re-expressed as a fraction of [outer] - the inverse of
     *  [ViewportRect.composeWithViewport]. */
    fun normaliseWithin(inner: RectNorm, outer: RectNorm): RectNorm {
        val w = (outer.r - outer.l).coerceAtLeast(1e-6f)
        val h = (outer.b - outer.t).coerceAtLeast(1e-6f)
        return RectNorm(
            l = ((inner.l - outer.l) / w).coerceIn(0f, 1f),
            t = ((inner.t - outer.t) / h).coerceIn(0f, 1f),
            r = ((inner.r - outer.l) / w).coerceIn(0f, 1f),
            b = ((inner.b - outer.t) / h).coerceIn(0f, 1f),
        )
    }

    /** How much extra magnification the shader rect [r] represents over the *un-zoomed* shader
     *  rect, which is the plain aspect trim [texCropX] x [texCropY]. 1.0 when GL is only doing the
     *  aspect trim it has always done. Capped at [MAX_GL_RESIDUAL]. */
    private fun residualOf(r: RectNorm, texCropX: Float, texCropY: Float): Float {
        val w = (r.r - r.l).coerceAtLeast(1e-6f)
        val h = (r.b - r.t).coerceAtLeast(1e-6f)
        return min(MAX_GL_RESIDUAL, (texCropX / w + texCropY / h) / 2f)
    }

    /** A [w] x [h] rect centred on [centre], shifted (never shrunk) back inside `0..1`. */
    private fun recentreWithin01(centre: Pair<Float, Float>, w: Float, h: Float): RectNorm {
        val cw = w.coerceIn(1e-4f, 1f)
        val ch = h.coerceIn(1e-4f, 1f)
        val l = (centre.first - cw / 2f).coerceIn(0f, 1f - cw)
        val t = (centre.second - ch / 2f).coerceIn(0f, 1f - ch)
        return RectNorm(l, t, l + cw, t + ch)
    }
}
