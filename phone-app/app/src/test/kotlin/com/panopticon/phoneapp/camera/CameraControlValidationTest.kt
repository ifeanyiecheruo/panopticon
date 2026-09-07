package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraControlValidationTest {

    private val fullCaps = CameraCapabilities(
        cameraId = "0",
        zoomRatioRange = FloatRange2(1.0f, 8.0f),
        zoomViaRatioApi = true,
        aeCompensationRange = IntRange2(-12, 12),
        aeCompensationStepMilliEv = 333,
        exposureTimeRangeNs = LongRange2(10_000L, 100_000_000L),
        sensitivityRange = IntRange2(50, 3200),
        minFocusDistanceDiopters = 10.0f,
        hasManualSensor = true,
        hasManualFocus = true,
        hasManualWhiteBalance = true,
        wbGainRange = FloatRange2(1.0f, 8.0f),
        awbModes = listOf(0, 1, 2, 5, 6),
        videoStabilizationModes = listOf(0, 1),
        opticalStabilizationModes = listOf(0, 1),
        croppingType = "FREEFORM",
        activeArrayWidth = 4032,
        activeArrayHeight = 3024,
    )

    private val weakCaps = fullCaps.copy(
        zoomRatioRange = FloatRange2(1.0f, 2.0f),
        zoomViaRatioApi = false,
        exposureTimeRangeNs = null,
        sensitivityRange = null,
        minFocusDistanceDiopters = 0f,
        hasManualSensor = false,
        hasManualFocus = false,
        hasManualWhiteBalance = false,
        awbModes = listOf(1),
        videoStabilizationModes = listOf(0),
        opticalStabilizationModes = emptyList(),
        croppingType = "unknown",
    )

    @Test
    fun `all-null keys always validate`() {
        assertNull(CameraControlValidation.validate(CameraControlKeys(), fullCaps))
        assertNull(CameraControlValidation.validate(CameraControlKeys(), weakCaps))
    }

    @Test
    fun `zoom in range passes, out of range names the key`() {
        assertNull(CameraControlValidation.validate(CameraControlKeys(zoomRatio = 4.0f), fullCaps))
        assertEquals(
            "zoomRatio",
            CameraControlValidation.validate(CameraControlKeys(zoomRatio = 9.0f), fullCaps)?.key,
        )
        assertEquals(
            "zoomRatio",
            CameraControlValidation.validate(CameraControlKeys(zoomRatio = 3.0f), weakCaps)?.key,
        )
    }

    @Test
    fun `crop rect must be a sane sub-rect of 0-1`() {
        assertNull(
            CameraControlValidation.validate(
                CameraControlKeys(cropRegionNorm = RectNorm(0.1f, 0.1f, 0.6f, 0.7f)), fullCaps,
            ),
        )
        // r <= l
        assertEquals(
            "cropRegionNorm",
            CameraControlValidation.validate(
                CameraControlKeys(cropRegionNorm = RectNorm(0.6f, 0.1f, 0.5f, 0.7f)), fullCaps,
            )?.key,
        )
        // out of 0..1
        assertEquals(
            "cropRegionNorm",
            CameraControlValidation.validate(
                CameraControlKeys(cropRegionNorm = RectNorm(0.1f, 0.1f, 1.4f, 0.7f)), fullCaps,
            )?.key,
        )
    }

    @Test
    fun `AE compensation is range-checked`() {
        assertNull(CameraControlValidation.validate(CameraControlKeys(aeExposureCompensation = -6), fullCaps))
        assertEquals(
            "aeExposureCompensation",
            CameraControlValidation.validate(CameraControlKeys(aeExposureCompensation = 20), fullCaps)?.key,
        )
    }

    @Test
    fun `manual exposure rejected without MANUAL_SENSOR`() {
        assertEquals(
            "manualExposure",
            CameraControlValidation.validate(CameraControlKeys(manualExposure = true), weakCaps)?.key,
        )
    }

    @Test
    fun `manual exposure time and iso are range-checked when manual exposure is on`() {
        assertNull(
            CameraControlValidation.validate(
                CameraControlKeys(
                    manualExposure = true,
                    sensorExposureTimeNs = 8_000_000L,
                    sensorSensitivityIso = 400,
                ),
                fullCaps,
            ),
        )
        assertEquals(
            "sensorExposureTimeNs",
            CameraControlValidation.validate(
                CameraControlKeys(manualExposure = true, sensorExposureTimeNs = 1L),
                fullCaps,
            )?.key,
        )
        assertEquals(
            "sensorSensitivityIso",
            CameraControlValidation.validate(
                CameraControlKeys(manualExposure = true, sensorSensitivityIso = 99999),
                fullCaps,
            )?.key,
        )
    }

    @Test
    fun `exposure time and iso are not checked while manual exposure is off`() {
        assertNull(
            CameraControlValidation.validate(
                CameraControlKeys(manualExposure = false, sensorExposureTimeNs = 1L, sensorSensitivityIso = 99999),
                fullCaps,
            ),
        )
    }

    @Test
    fun `awb mode must be one the camera lists`() {
        assertNull(CameraControlValidation.validate(CameraControlKeys(awbMode = 5), fullCaps))
        assertEquals(
            "awbMode",
            CameraControlValidation.validate(CameraControlKeys(awbMode = 3), fullCaps)?.key,
        )
        // weakCaps only lists AUTO(1)
        assertEquals(
            "awbMode",
            CameraControlValidation.validate(CameraControlKeys(awbMode = 5), weakCaps)?.key,
        )
    }

    @Test
    fun `video stabilization mode must be one the camera lists`() {
        assertNull(
            CameraControlValidation.validate(CameraControlKeys(videoStabilizationMode = 1), fullCaps),
        )
        assertEquals(
            "videoStabilizationMode",
            CameraControlValidation.validate(CameraControlKeys(videoStabilizationMode = 1), weakCaps)?.key,
        )
    }

    @Test
    fun `optical stabilization mode must be one the camera lists`() {
        assertNull(
            CameraControlValidation.validate(CameraControlKeys(opticalStabilizationMode = 1), fullCaps),
        )
        // weakCaps has no OIS at all
        assertEquals(
            "opticalStabilizationMode",
            CameraControlValidation.validate(CameraControlKeys(opticalStabilizationMode = 1), weakCaps)?.key,
        )
    }

    @Test
    fun `manual white balance rejected without MANUAL_POST_PROCESSING, gains range-checked with it`() {
        assertEquals(
            "manualWhiteBalance",
            CameraControlValidation.validate(CameraControlKeys(manualWhiteBalance = true), weakCaps)?.key,
        )
        assertNull(
            CameraControlValidation.validate(
                CameraControlKeys(manualWhiteBalance = true, wbRedGain = 2.0f, wbGreenGain = 1.5f, wbBlueGain = 3.0f),
                fullCaps,
            ),
        )
        assertEquals(
            "wbBlueGain",
            CameraControlValidation.validate(
                CameraControlKeys(manualWhiteBalance = true, wbBlueGain = 12.0f),
                fullCaps,
            )?.key,
        )
    }

    @Test
    fun `wb gains not checked while manual white balance is off`() {
        assertNull(
            CameraControlValidation.validate(
                CameraControlKeys(manualWhiteBalance = false, wbBlueGain = 99.0f),
                fullCaps,
            ),
        )
    }

    @Test
    fun `manual focus rejected without support, distance range-checked with it`() {
        assertEquals(
            "manualFocus",
            CameraControlValidation.validate(CameraControlKeys(manualFocus = true), weakCaps)?.key,
        )
        assertNull(
            CameraControlValidation.validate(
                CameraControlKeys(manualFocus = true, lensFocusDistanceDiopters = 3.0f), fullCaps,
            ),
        )
        assertEquals(
            "lensFocusDistanceDiopters",
            CameraControlValidation.validate(
                CameraControlKeys(manualFocus = true, lensFocusDistanceDiopters = 25.0f), fullCaps,
            )?.key,
        )
    }
}
