package com.panopticon.phoneapp.calibration

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the on-the-wire shape of `GET /api/calibration/result` - the
 * controller (`internal/phoneapi` + `internal/calibration`) decodes exactly
 * these field names and this nesting, and only ever reads `checksTotal` /
 * `checksPassed` per step, so a rename here would silently break the
 * cross-project contract.
 */
class CalibrationModelsTest {
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    @Test
    fun `result serialises with the field names the controller expects`() {
        val result = CalibrationResult(
            runId = "cal-abc123",
            runAtMs = 1_755_270_015_231,
            cameras = mapOf(
                "0" to CameraCalibration(
                    deviceIdentity = CameraDeviceIdentity(cameraId = "0", facing = "back", focalLengthMm = 5.4f),
                    steps = mapOf(
                        "crop-region" to CalibrationStep(
                            checksTotal = 2,
                            checksPassed = 2,
                            checks = listOf(
                                CalibrationCheck("SCALER_AVAILABLE_MAX_DIGITAL_ZOOM", "8.0", "8.0", true),
                                CalibrationCheck("CONTROL_ZOOM_RATIO_RANGE", "1.0..10.0", "1.0..10.0", true),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(CalibrationResult.serializer(), result)

        // Top-level contract keys.
        assertTrue(encoded.contains("\"runId\":\"cal-abc123\""))
        assertTrue(encoded.contains("\"runAtMs\":1755270015231"))
        assertTrue(encoded.contains("\"cameras\":{\"0\":{"))
        // Per-step summary keys the controller sums.
        assertTrue(encoded.contains("\"checksTotal\":2"))
        assertTrue(encoded.contains("\"checksPassed\":2"))
        assertTrue(encoded.contains("\"deviceIdentity\":{"))

        // Round-trips.
        val back = json.decodeFromString(CalibrationResult.serializer(), encoded)
        assertEquals(result, back)
    }

    @Test
    fun `status carries progressWithinStep as an index-total object`() {
        val status = CalibrationStatus(
            runId = "cal-1",
            status = "running",
            currentCameraId = "0",
            camerasCompleted = 1,
            camerasTotal = 3,
            currentStep = "zoom-quality",
            stepsCompleted = 1,
            stepsTotal = 2,
            progressWithinStep = ProgressWithinStep(index = 4, total = 15),
            startedAtMs = 1_755_270_000_000,
        )
        val encoded = json.encodeToString(CalibrationStatus.serializer(), status)
        assertTrue(encoded.contains("\"progressWithinStep\":{\"index\":4,\"total\":15}"))
        assertTrue(encoded.contains("\"camerasTotal\":3"))
    }
}
