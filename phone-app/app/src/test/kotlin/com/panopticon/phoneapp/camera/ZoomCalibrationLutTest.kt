package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.CalibrationResult
import com.panopticon.phoneapp.calibration.CameraCalibration
import com.panopticon.phoneapp.calibration.CameraDeviceIdentity
import com.panopticon.phoneapp.calibration.FloatRange2
import com.panopticon.phoneapp.calibration.RectNorm
import com.panopticon.phoneapp.calibration.ResolutionZoomMap
import com.panopticon.phoneapp.calibration.ResultDeviceIdentity
import com.panopticon.phoneapp.calibration.ZoomSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomCalibrationLutTest {

    private fun sample(
        requested: Float,
        reported: Float? = null,
        crop: RectNorm = RectNorm(0f, 0f, 1f, 1f),
    ) = ZoomSample(
        requestedRatio = requested,
        reportedRatio = reported,
        ratioHonored = true,
        requestedCropNorm = crop,
        effectiveCropNorm = crop,
    )

    private fun centredCrop(side: Float): RectNorm {
        val half = (1f - side) / 2f
        return RectNorm(half, half, 1f - half, 1f - half)
    }

    private fun result(
        cameras: Map<String, CameraCalibration>,
    ) = CalibrationResult(
        runId = "test",
        runAtMs = 0L,
        deviceIdentity = ResultDeviceIdentity("Google", "Pixel 6", "oriole", "0.1.0"),
        cameras = cameras,
    )

    private fun camera(
        perResolution: Map<String, ResolutionZoomMap>,
        id: String = "0",
    ) = CameraCalibration(
        deviceIdentity = CameraDeviceIdentity(cameraId = id, facing = "back"),
        opticalRange = FloatRange2(1f, 1f),
        digitalRange = FloatRange2(1f, 7f),
        perResolution = perResolution,
    )

    @Test
    fun `the reported ratio wins over a crop rect that never moves`() {
        // The Pixel 6 shape: CONTROL_ZOOM_RATIO drives the zoom, so SCALER_CROP_REGION keeps
        // reporting the FULL active array at every ratio. Believing the crop would say "even 7x
        // still shows the whole frame", and the solver would then ask for maximum zoom at any
        // zoom level. The reported ratio is the signal that actually carries the magnification.
        val full = RectNorm(0f, 0f, 1f, 1f)
        val lut = ZoomCalibrationLut.build(
            result(mapOf("0" to camera(mapOf("1920x1080" to ResolutionZoomMap(1920, 1080, listOf(
                sample(1f, reported = 1f, crop = full),
                sample(2f, reported = 2f, crop = full),
                sample(7f, reported = 7f, crop = full),
            )))))),
            cameraId = "0", width = 1920, height = 1080,
        )
        assertEquals(listOf(1f, 2f, 7f), lut.entries.map { it.requestedRatio })
        assertEquals(listOf(1f, 2f, 7f), lut.entries.map { it.deliveredMagnification })

        // And the consequence that matters: an un-zoomed view asks the camera for nothing.
        val s = ZoomGeometry.split(ZoomGeometry.FULL, lut.entries)
        assertEquals("no zoom wanted, so none requested", 1f, s.hwRatio, 1e-4f)
    }

    @Test
    fun `a legacy camera with no reported ratio falls back to the crop rect`() {
        // API 28 / SCALER_CROP_REGION path: reportedRatio is absent and the crop is the signal.
        val lut = ZoomCalibrationLut.build(
            result(mapOf("0" to camera(mapOf("1280x720" to ResolutionZoomMap(1280, 720, listOf(
                sample(1f, reported = null, crop = centredCrop(1f)),
                sample(2f, reported = null, crop = centredCrop(0.5f)),
            )))))),
            cameraId = "0", width = 1280, height = 720,
        )
        assertEquals(2, lut.entries.size)
        assertEquals(1f, lut.entries[0].deliveredMagnification, 1e-3f)
        assertEquals(2f, lut.entries[1].deliveredMagnification, 1e-3f)
    }

    @Test
    fun `a pinned physical sub-camera falls back to its logical parent`() {
        // The sweep walks the ids CameraManager reports, so "0:2" is never probed on its own.
        val lut = ZoomCalibrationLut.build(
            result(mapOf("0" to camera(mapOf("1920x1080" to ResolutionZoomMap(1920, 1080, listOf(
                sample(1f, reported = 1f), sample(4f, reported = 4f),
            )))))),
            cameraId = "0:2", width = 1920, height = 1080,
        )
        assertEquals("0", lut.sourceCameraId)
        assertEquals(2, lut.entries.size)
    }

    @Test
    fun `the nearest probed output size is used when the recording size was never swept`() {
        // Real case: the Pixel 6 records at 3840x2160 but the sweep topped out at 2688x1512.
        val lut = ZoomCalibrationLut.build(
            result(mapOf("0" to camera(mapOf(
                "640x360" to ResolutionZoomMap(640, 360, listOf(sample(1f, reported = 1f))),
                "2688x1512" to ResolutionZoomMap(2688, 1512, listOf(sample(1f, reported = 1f), sample(3f, reported = 3f))),
            )))),
            cameraId = "0", width = 3840, height = 2160,
        )
        assertEquals("2688x1512", lut.sourceResolution)
        assertEquals(2, lut.entries.size)
    }

    @Test
    fun `an uncalibrated phone yields an empty table, which means GL does everything`() {
        assertTrue(ZoomCalibrationLut.build(null, "0", 1920, 1080).isEmpty)
        assertTrue(ZoomCalibrationLut.build(result(emptyMap()), "0", 1920, 1080).isEmpty)

        val s = ZoomGeometry.split(centredCrop(0.5f), ZoomCalibrationLut.build(null, "0", 1920, 1080).entries)
        assertEquals("asks the camera for nothing", 1f, s.hwRatio, 1e-4f)
        assertEquals("so GL does the whole 2x", 2f, s.glResidual, 1e-3f)
    }

    @Test
    fun `a resolution entry with no samples is skipped rather than chosen`() {
        val lut = ZoomCalibrationLut.build(
            result(mapOf("0" to camera(mapOf(
                "1920x1080" to ResolutionZoomMap(1920, 1080, emptyList()),
                "1280x720" to ResolutionZoomMap(1280, 720, listOf(sample(2f, reported = 2f))),
            )))),
            cameraId = "0", width = 1920, height = 1080,
        )
        assertEquals("1280x720", lut.sourceResolution)
        assertEquals(1, lut.entries.size)
    }
}
