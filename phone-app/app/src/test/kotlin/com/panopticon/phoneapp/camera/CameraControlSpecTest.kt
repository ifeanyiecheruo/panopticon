package com.panopticon.phoneapp.camera

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
}
