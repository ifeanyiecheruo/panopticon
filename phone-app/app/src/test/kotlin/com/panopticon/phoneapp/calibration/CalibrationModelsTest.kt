package com.panopticon.phoneapp.calibration

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `a result persisted before the complete field existed reads as complete`() {
        val encoded = json.encodeToString(CalibrationResult.serializer(), sampleResult())
            .replace(",\"complete\":true", "")

        val back = json.decodeFromString(CalibrationResult.serializer(), encoded)

        assertTrue(back.complete)
    }

    @Test
    fun `status reports a stopped sweep's result as partial`() {
        assertEquals("completed", sampleResult().runStatus())
        assertEquals("partial", sampleResult().copy(complete = false).runStatus())
    }

    @Test
    fun `a partial result from this app version is resumed`() {
        val partial = sampleResult().copy(complete = false)

        assertEquals(partial, resumableFrom(partial, appVersionName = "0.1.0"))
    }

    @Test
    fun `a complete result is re-run from scratch`() {
        assertNull(resumableFrom(sampleResult(), appVersionName = "0.1.0"))
    }

    @Test
    fun `a partial result from another app version is not resumed`() {
        assertNull(resumableFrom(sampleResult().copy(complete = false), appVersionName = "0.2.0"))
    }

    @Test
    fun `no persisted result starts fresh`() {
        assertNull(resumableFrom(null, appVersionName = "0.1.0"))
    }

    private val back get() = sampleResult().cameras.getValue("0")
    private val identity get() = sampleResult().deviceIdentity
    private fun withResolutions(cam: CameraCalibration, vararg keys: String) =
        cam.copy(perResolution = keys.associateWith { cam.perResolution.getValue("1920x1080") })

    @Test
    fun `a finished camera replaces only its own completed calibration`() {
        val oldFront = back.copy(qualityCollapseRatio = 2f)
        val completed = sampleResult().copy(cameras = mapOf("0" to back, "1" to oldFront))
        val newBack = withResolutions(back, "1920x1080", "1280x720")

        val promoted = withCameraPromoted(completed, "cal-new", 2_000L, identity, "0", newBack)!!

        assertEquals(newBack.copy(runId = "cal-new", runAtMs = 2_000L), promoted.cameras["0"])
        // The camera the new run hasn't finished keeps serving, stamped with the run that made it.
        assertEquals(oldFront.copy(runId = "cal-abc123", runAtMs = 1_755_270_015_231), promoted.cameras["1"])
        assertEquals("cal-new", promoted.runId)
        assertTrue(promoted.complete)
    }

    @Test
    fun `the first camera ever finished starts the completed result`() {
        val promoted = withCameraPromoted(null, "cal-new", 2_000L, identity, "0", back)!!

        assertEquals(setOf("0"), promoted.cameras.keys)
        assertEquals("cal-new", promoted.cameras.getValue("0").runId)
    }

    @Test
    fun `re-finishing a camera with the same data changes nothing`() {
        val completed = withCameraPromoted(null, "cal-1", 1_000L, identity, "0", back)

        assertNull(withCameraPromoted(completed, "cal-2", 2_000L, identity, "0", back))
    }

    @Test
    fun `zoom uses completed data, and partial data only for a camera with none`() {
        val completed = sampleResult().copy(cameras = mapOf("0" to back))
        val partialBack = withResolutions(back, "640x480")
        val partialFront = withResolutions(back, "320x240")
        val inProgress = sampleResult().copy(complete = false, cameras = mapOf("0" to partialBack, "1" to partialFront))

        val view = zoomView(completed, inProgress)!!

        assertEquals(back, view.cameras["0"])
        assertEquals(partialFront, view.cameras["1"])
    }

    @Test
    fun `zoom with nothing in progress is the completed result`() {
        assertEquals(sampleResult(), zoomView(sampleResult(), null))
        assertNull(zoomView(null, null))
    }
}
