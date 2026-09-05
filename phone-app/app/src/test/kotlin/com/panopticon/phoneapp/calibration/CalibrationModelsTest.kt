package com.panopticon.phoneapp.calibration

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the on-the-wire shape of `GET /api/calibration/result` - the
 * controller (`internal/phoneapi/calibration.go` + `internal/calibration`)
 * decodes exactly these field names and this nesting, so a rename here would
 * silently break the cross-project contract.
 */
class CalibrationModelsTest {
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    private fun sampleResult() = CalibrationResult(
        runId = "cal-abc123",
        runAtMs = 1_755_270_015_231,
        deviceIdentity = ResultDeviceIdentity("Google", "Pixel 6", "oriole", "0.1.0"),
        cameras = mapOf(
            "0" to CameraCalibration(
                deviceIdentity = CameraDeviceIdentity(
                    cameraId = "0", facing = "back", focalLengthsMm = listOf(6.81f),
                    isLogicalMultiCam = true, physicalIds = listOf("2", "3"),
                    activeArrayWidth = 4032, activeArrayHeight = 3024,
                    croppingType = "FREEFORM", maxDigitalZoom = 10f,
                    zoomRatioRange = FloatRange2(0.67f, 10f),
                ),
                opticalRange = FloatRange2(0.67f, 2f),
                digitalRange = FloatRange2(2f, 10f),
                crossoverRatio = 2f,
                crossoverMethod = "active-physical-id",
                positionHonored = false,
                positionFailRatios = listOf(4f, 8f),
                qualityCollapseRatio = 6f,
                perResolution = mapOf(
                    "1920x1080" to ResolutionZoomMap(
                        1920, 1080,
                        listOf(
                            ZoomSample(
                                requestedRatio = 1f, reportedRatio = 1f, ratioHonored = true,
                                requestedCropNorm = RectNorm(0f, 0f, 1f, 1f),
                                effectiveCropNorm = RectNorm(0f, 0f, 1f, 1f),
                                sharpness = 1234.5, sharpnessRelToBaseline = 1.0,
                            ),
                            ZoomSample(
                                requestedRatio = 4f, reportedRatio = 4.02f, ratioHonored = true,
                                requestedCropNorm = RectNorm(0.375f, 0.375f, 0.625f, 0.625f),
                                effectiveCropNorm = RectNorm(0.376f, 0.375f, 0.624f, 0.625f),
                                positionRequestedNorm = RectNorm(0.5f, 0.5f, 0.75f, 0.75f),
                                positionReportedNorm = RectNorm(0.375f, 0.375f, 0.625f, 0.625f),
                                positionHonored = false,
                                activePhysicalId = "3", lensFocalLengthMm = 6.81f,
                                sharpness = 700.0, sharpnessRelToBaseline = 0.57,
                            ),
                        ),
                    ),
                ),
                steps = mapOf(
                    "zoom-map" to CalibrationStep(checksTotal = 2, checksPassed = 1),
                    "crop-region" to CalibrationStep(checksTotal = 1, checksPassed = 0),
                ),
            ),
        ),
    )

    @Test
    fun `result serialises with the field names the controller expects`() {
        val encoded = json.encodeToString(CalibrationResult.serializer(), sampleResult())

        assertTrue(encoded.contains("\"runId\":\"cal-abc123\""))
        assertTrue(encoded.contains("\"runAtMs\":1755270015231"))
        assertTrue(encoded.contains("\"deviceIdentity\":{\"manufacturer\":\"Google\""))
        assertTrue(encoded.contains("\"cameras\":{\"0\":{"))
        assertTrue(encoded.contains("\"opticalRange\":{\"lo\":"))
        assertTrue(encoded.contains("\"digitalRange\":{\"lo\":"))
        assertTrue(encoded.contains("\"crossoverRatio\":2"))
        assertTrue(encoded.contains("\"crossoverMethod\":\"active-physical-id\""))
        assertTrue(encoded.contains("\"positionHonored\":false"))
        assertTrue(encoded.contains("\"qualityCollapseRatio\":6"))
        assertTrue(encoded.contains("\"perResolution\":{\"1920x1080\":{"))
        assertTrue(encoded.contains("\"effectiveCropNorm\":{\"l\":"))
        assertTrue(encoded.contains("\"checksTotal\":2"))
        assertTrue(encoded.contains("\"checksPassed\":1"))
    }

    @Test
    fun `result round-trips`() {
        val original = sampleResult()
        val encoded = json.encodeToString(CalibrationResult.serializer(), original)
        val back = json.decodeFromString(CalibrationResult.serializer(), encoded)
        assertEquals(original, back)
    }

    @Test
    fun `status carries progressWithinStep as an index-total object`() {
        val status = CalibrationStatus(
            runId = "cal-1", status = "running", currentCameraId = "0",
            camerasCompleted = 1, camerasTotal = 3, currentStep = "1920x1080",
            stepsCompleted = 4, stepsTotal = 18,
            progressWithinStep = ProgressWithinStep(index = 9, total = 14),
            startedAtMs = 1_755_270_000_000,
        )
        val encoded = json.encodeToString(CalibrationStatus.serializer(), status)
        assertTrue(encoded.contains("\"progressWithinStep\":{\"index\":9,\"total\":14}"))
        assertTrue(encoded.contains("\"currentStep\":\"1920x1080\""))
    }
}
