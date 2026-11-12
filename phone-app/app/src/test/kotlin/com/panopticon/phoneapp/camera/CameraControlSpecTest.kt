package com.panopticon.phoneapp.camera

import com.panopticon.phoneapp.calibration.RectNorm
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlSpecTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `withPatch replaces the whole keys object, not field by field`() {
        val base = CameraControlSpec(
            manualControlEnabled = true,
            keys = CameraControlKeys(zoomRatio = 2.0f, aeExposureCompensation = -3),
        )
        // A patch carrying only zoomRatio drops the AE compensation - the controller
        // is expected to send the full desired key set.
        val next = base.withPatch(CameraStatePatch(keys = CameraControlKeys(zoomRatio = 4.0f)))
        assertEquals(4.0f, next.keys.zoomRatio)
        assertNull(next.keys.aeExposureCompensation)
        assertTrue(next.manualControlEnabled) // untouched by this patch
    }

    @Test
    fun `withPatch can toggle the master switch without touching keys`() {
        val base = CameraControlSpec(manualControlEnabled = true, keys = CameraControlKeys(zoomRatio = 3.0f))
        val next = base.withPatch(CameraStatePatch(manualControlEnabled = false))
        assertFalse(next.manualControlEnabled)
        assertEquals(3.0f, next.keys.zoomRatio)
    }

    @Test
    fun `spec round-trips through JSON`() {
        val spec = CameraControlSpec(
            manualControlEnabled = true,
            keys = CameraControlKeys(
                zoomRatio = 2.5f,
                aeExposureCompensation = 2,
                aeLock = true,
                manualExposure = true,
                sensorExposureTimeNs = 8_000_000L,
                sensorSensitivityIso = 400,
            ),
        )
        val decoded = json.decodeFromString(CameraControlSpec.serializer(), json.encodeToString(CameraControlSpec.serializer(), spec))
        assertEquals(spec, decoded)
    }

    @Test
    fun `default spec is fully auto`() {
        val d = CameraControlSpec()
        assertFalse(d.manualControlEnabled)
        assertEquals(CameraControlKeys(), d.keys)
    }

    // ---- auto belongs to the phone ----

    private val caps = CameraCapabilities(
        cameraId = "0",
        zoomRatioRange = FloatRange2(1f, 8f),
        zoomViaRatioApi = true,
        aeCompensationRange = IntRange2(-12, 12),
        aeCompensationStepMilliEv = 166,
        exposureTimeRangeNs = LongRange2(10_000L, 1_000_000_000L),
        sensitivityRange = IntRange2(50, 6400),
        minFocusDistanceDiopters = 10f,
        hasManualSensor = true,
        hasManualFocus = true,
        maxAeRegions = 1,
        maxAfRegions = 1,
    )
    private val area = RectNorm(0.4f, 0.4f, 0.6f, 0.6f)
    private val zoomAndArea = CameraControlKeys(
        zoomRatio = 2f,
        zoomViewNorm = RectNorm(0.25f, 0.25f, 0.75f, 0.75f),
        aeRegionNorm = area,
        afRegionNorm = area,
    )

    @Test
    fun `auto takes the zoom, the exposure compensation and the area, nothing else`() {
        assertNull(CameraControlSpec.autoKeyConflict(zoomAndArea))
        assertNull(CameraControlSpec.autoKeyConflict(CameraControlKeys(aeSelectNorm = area, afSelectNorm = area, zoomSelectNorm = area)))
        assertNull(CameraControlSpec.autoKeyConflict(zoomAndArea.copy(aeExposureCompensation = 2)))
        for ((key, keys) in listOf(
            "aeLock" to zoomAndArea.copy(aeLock = true),
            "manualExposure" to zoomAndArea.copy(manualExposure = true),
            "sensorExposureTimeNs" to zoomAndArea.copy(sensorExposureTimeNs = 1_000_000L),
            "lensFocusDistanceDiopters" to zoomAndArea.copy(lensFocusDistanceDiopters = 0.5f),
            "awbMode" to zoomAndArea.copy(awbMode = 1),
            "wbRedGain" to zoomAndArea.copy(wbRedGain = 2f),
            "videoStabilizationMode" to zoomAndArea.copy(videoStabilizationMode = 1),
        )) {
            assertEquals(key, CameraControlSpec.autoKeyConflict(keys))
        }
    }

    @Test
    fun `a manual switch left off is no conflict`() {
        val off = zoomAndArea.copy(manualExposure = false, manualFocus = false, manualWhiteBalance = false, aeLock = false)
        assertNull(CameraControlSpec.autoKeyConflict(off))
    }

    @Test
    fun `entering auto keeps only the zoom, the exposure compensation and the area`() {
        val manual = zoomAndArea.copy(
            manualExposure = true, sensorExposureTimeNs = 5_000_000L, sensorSensitivityIso = 100,
            manualFocus = true, lensFocusDistanceDiopters = 1f, aeExposureCompensation = 3, awbMode = 5,
        )
        assertEquals(zoomAndArea.copy(aeExposureCompensation = 3), CameraControlSpec.autoKeys(manual))
    }

    @Test
    fun `an explicit area is one drawn, in auto`() {
        assertTrue(CameraControlSpec(keys = zoomAndArea).hasExplicitArea)
        assertTrue(CameraControlSpec(keys = zoomAndArea.copy(afRegionNorm = null)).hasExplicitArea)
        assertFalse(CameraControlSpec(keys = zoomAndArea.copy(aeRegionNorm = null, afRegionNorm = null)).hasExplicitArea)
        assertFalse(CameraControlSpec(manualControlEnabled = true, keys = zoomAndArea).hasExplicitArea)
    }

    @Test
    fun `what auto holds applies only with an explicit area and the capability`() {
        val held = AutoOverride(exposureCompensation = -3, focus = 0.25f)
        val explicit = CameraControlSpec(keys = zoomAndArea)
        assertEquals(-3, held.compensationFor(explicit))
        assertEquals(0.25f, held.focusFor(explicit, caps))

        val implicit = CameraControlSpec(keys = zoomAndArea.copy(aeRegionNorm = null, afRegionNorm = null))
        assertNull(held.compensationFor(implicit))
        assertNull(held.focusFor(implicit, caps))
        val manual = CameraControlSpec(manualControlEnabled = true, keys = zoomAndArea)
        assertNull(held.compensationFor(manual))


        val fixed = caps.copy(hasManualFocus = false, minFocusDistanceDiopters = 0f)
        assertNull(held.focusFor(explicit, fixed))
        assertNull(AutoOverride().compensationFor(explicit))
        assertNull("no compensation is none to apply", AutoOverride(exposureCompensation = 0).compensationFor(explicit))
    }

    @Test
    fun `auto runs with the user's compensation plus what tuning added, within range`() {
        val explicit = CameraControlSpec(keys = zoomAndArea.copy(aeExposureCompensation = 6))
        val tuned = AutoOverride(exposureCompensation = -2)
        assertEquals(4, AutoOverride.totalCompensation(explicit, caps, tuned))
        assertEquals("the user's alone", 6, AutoOverride.totalCompensation(explicit, caps, null))
        // With no area drawn, only the user's: tuning only acts on an explicit area.
        val implicit = CameraControlSpec(keys = CameraControlKeys(aeExposureCompensation = 6))
        assertEquals(6, AutoOverride.totalCompensation(implicit, caps, tuned))
        assertEquals("tuning alone", -2, AutoOverride.totalCompensation(CameraControlSpec(keys = zoomAndArea), caps, tuned))
        assertNull("neither: the HAL's default", AutoOverride.totalCompensation(CameraControlSpec(keys = zoomAndArea), caps, null))
        // Within the camera's range.
        val top = CameraControlSpec(keys = zoomAndArea.copy(aeExposureCompensation = 12))
        assertEquals(12, AutoOverride.totalCompensation(top, caps, AutoOverride(exposureCompensation = 3)))
    }
}
