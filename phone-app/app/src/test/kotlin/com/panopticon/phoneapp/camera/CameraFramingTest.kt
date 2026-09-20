package com.panopticon.phoneapp.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOL = 1e-4f

/**
 * Regression coverage for the live-preview/recording squash bug: the GL crop was computed from
 * the width/height Camera2 reports for the source buffer, but on cameras whose
 * `SurfaceTexture.getTransformMatrix()` swaps X/Y outright (confirmed on a Pixel 6 back camera)
 * the buffer is stored transposed, so the *displayed* image's width/height are the other way
 * round. Cropping a 4000x3000-shaped image when the display is really 3000x4000 produced a
 * square region stretched into a 16:9 viewport - a 1.78x horizontal stretch.
 *
 * Measured on-device, against a reference frame rendered through an isotropic-by-construction
 * letterboxed viewport: windows read 0.644 (h/w) before the fix where ground truth was 1.129,
 * and 1.121 after - i.e. the model below predicted the broken output to within 1.4%.
 *
 * These tests pin that down with plain numeric assertions instead of eyeballing a rendered frame,
 * which is what let the bug survive several "looks fine to me" checks.
 *
 * Sticks to the plain-Int overloads throughout: `android.util.Size`'s own accessors throw "not
 * mocked" under this module's plain-JVM unit tests (no Robolectric), so exercising the Size-based
 * convenience wrappers here would test the stub jar, not this code.
 */
class CameraFramingTest {

    // The exact matrix android.graphics.SurfaceTexture.getTransformMatrix() returned on a real
    // Pixel 6 back camera (captured via a one-off debug log, see git history) - a column-major
    // 4x4 that maps (x,y) -> (1-y, 1-x): a genuine axis swap, not a flip.
    private val swappedMatrix = floatArrayOf(
        0f, -1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        1f, 1f, 0f, 1f,
    )

    // A typical non-swapping matrix: identity with a plain Y-flip, (x,y) -> (x, 1-y).
    private val flipOnlyMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f,
    )

    @Test
    fun `axesSwapped detects a real swap matrix and clears a plain flip`() {
        assertTrue(CameraFraming.axesSwapped(swappedMatrix))
        assertFalse(CameraFraming.axesSwapped(flipOnlyMatrix))
    }

    @Test
    fun `naturalSourceSize swaps width and height only when the matrix does`() {
        val (sw, sh) = CameraFraming.naturalSourceSize(4000, 3000, swappedMatrix)
        assertEquals(3000, sw)
        assertEquals(4000, sh)
        val (uw, uh) = CameraFraming.naturalSourceSize(4000, 3000, flipOnlyMatrix)
        assertEquals(4000, uw)
        assertEquals(3000, uh)
    }

    @Test
    fun `crop computed from the swap-corrected size matches the real observed values`() {
        // The actual sizes/logcat output from the Pixel 6 session this bug was found in.
        val (naturalW, naturalH) = CameraFraming.naturalSourceSize(4000, 3000, swappedMatrix)
        assertEquals(3000, naturalW)
        assertEquals(4000, naturalH)
        val (cropX, cropY) = CameraFraming.computeTexCrop(naturalW, naturalH, 3840, 2160)
        assertEquals(1.0f, cropX, TOL)
        assertEquals(0.421875f, cropY, TOL)
    }

    /**
     * The geometric invariant that actually rules out an anamorphic squash: the physical region
     * of the *natural* (post-transform) image a crop selects - width x [cropX], height x [cropY] -
     * must have the same aspect ratio as the recording target, for every combination this app can
     * hit (both a matched-aspect camera and one whose matrix swaps axes). This is what "no
     * distortion" means in numbers, independent of any rendered frame or screenshot.
     */
    @Test
    fun `cropped natural region always matches the recording target's aspect ratio`() {
        val cases = listOf(
            // (raw sensor-reported width/height, recording target width/height, matrix swaps axes)
            Case(4000, 3000, 3840, 2160, swapped = true), // Pixel 6 back camera, this bug
            Case(4000, 3000, 3840, 2160, swapped = false), // a camera that doesn't swap
            Case(4032, 3024, 1920, 1080, swapped = false),
            Case(3000, 4000, 1280, 720, swapped = true), // sensor already reported portrait-first
        )
        for (c in cases) {
            val matrix = if (c.swapped) swappedMatrix else flipOnlyMatrix
            val (naturalW, naturalH) = CameraFraming.naturalSourceSize(c.rawWidth, c.rawHeight, matrix)
            val (cropX, cropY) = CameraFraming.computeTexCrop(naturalW, naturalH, c.dstWidth, c.dstHeight)
            val croppedAspect = (naturalW * cropX) / (naturalH * cropY)
            val targetAspect = c.dstWidth.toFloat() / c.dstHeight
            assertEquals(
                "raw=${c.rawWidth}x${c.rawHeight} swapped=${c.swapped}: " +
                    "cropped natural region must match the recording aspect ratio",
                targetAspect,
                croppedAspect,
                1e-3f,
            )
        }
    }

    private data class Case(val rawWidth: Int, val rawHeight: Int, val dstWidth: Int, val dstHeight: Int, val swapped: Boolean)
}
