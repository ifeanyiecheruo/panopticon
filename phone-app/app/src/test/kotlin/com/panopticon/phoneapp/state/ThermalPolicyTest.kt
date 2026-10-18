package com.panopticon.phoneapp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    /** The defaults: trip at severe (3), resume at moderate (2); 45°C / 40°C for the fallback. */
    private val limits = ThermalLimits()
    private val dwell = 60_000L

    private fun policy() = ThermalPolicy(resumeDwellMs = dwell)

    /** A device with the thermal-status API: severity only, no battery reading. */
    private fun ThermalPolicy.severity(severity: Int, atMs: Long) =
        evaluate(ThermalSample(severity = severity, batteryTempC = null), limits, atMs)

    /** A device without it (API < 29): battery temperature only. */
    private fun ThermalPolicy.battery(tempC: Float?, atMs: Long) =
        evaluate(ThermalSample(severity = -1, batteryTempC = tempC), limits, atMs)

    // ---- thermal-status API ----

    @Test
    fun `stays clear below severe`() {
        val p = policy()
        assertFalse(p.severity(0, atMs = 0))
        assertFalse(p.severity(2, atMs = 10))
        assertNull(p.reason)
    }

    @Test
    fun `trips at severe with no dwell`() {
        val p = policy()
        assertTrue(p.severity(3, atMs = 0))
        assertEquals(ThermalShutdownStatus.SOURCE_THERMAL_STATUS, p.reason)
    }

    @Test
    fun `with the API present battery temperature is ignored`() {
        val p = policy()
        // A hot battery the platform doesn't consider a problem does not trip...
        assertFalse(p.evaluate(ThermalSample(severity = 1, batteryTempC = 50f), limits, 0))
        // ...and a cool one doesn't hold off a trip the platform asks for.
        assertTrue(p.evaluate(ThermalSample(severity = 3, batteryTempC = 25f), limits, 10))
        assertEquals(ThermalShutdownStatus.SOURCE_THERMAL_STATUS, p.reason)
    }

    @Test
    fun `severe and worse hold the trip, moderate or better resumes after the dwell`() {
        val p = policy()
        p.severity(4, atMs = 0)
        assertTrue(p.severity(3, atMs = 1_000))
        assertTrue(p.severity(3, atMs = 1_000 + dwell * 2))
        assertTrue(p.severity(2, atMs = 2_000 + dwell * 2))
        assertTrue(p.severity(1, atMs = 2_000 + dwell * 3 - 1))
        assertFalse(p.severity(1, atMs = 2_000 + dwell * 3))
        assertNull(p.reason)
    }

    @Test
    fun `one warm severity during the dwell restarts it`() {
        val p = policy()
        p.severity(3, atMs = 0)
        p.severity(2, atMs = 1_000)
        p.severity(3, atMs = 30_000)
        assertTrue(p.severity(2, atMs = 40_000))
        assertTrue(p.severity(2, atMs = 40_000 + dwell - 1))
        assertFalse(p.severity(2, atMs = 40_000 + dwell))
    }

    @Test
    fun `trips again after resuming`() {
        val p = policy()
        p.severity(3, atMs = 0)
        p.severity(0, atMs = 1_000)
        assertFalse(p.severity(0, atMs = 1_000 + dwell))
        assertTrue(p.severity(3, atMs = 2_000 + dwell))
    }

    @Test
    fun `the default levels are severe and moderate`() {
        assertEquals(3, limits.criticalSeverity)
        assertEquals(2, limits.safeSeverity)
    }

    @Test
    fun `configured levels move both edges`() {
        val p = policy()
        val strict = ThermalLimits(criticalSeverity = 2, safeSeverity = 0)
        assertFalse(p.evaluate(ThermalSample(1, null), strict, 0))
        assertTrue(p.evaluate(ThermalSample(2, null), strict, 10)) // moderate now trips
        assertTrue(p.evaluate(ThermalSample(1, null), strict, 20)) // light no longer resumes
        assertTrue(p.evaluate(ThermalSample(1, null), strict, 20 + dwell * 2))
        assertTrue(p.evaluate(ThermalSample(0, null), strict, 30 + dwell * 2))
        assertFalse(p.evaluate(ThermalSample(0, null), strict, 30 + dwell * 3))
    }

    @Test
    fun `limits are built from config, falling back to defaults for an unknown stored level`() {
        val cfg = ThermalLimits.from(
            com.panopticon.phoneapp.state.DeviceConfig(
                deviceName = "t",
                thermalCriticalLevel = "critical",
                thermalSafeLevel = "bogus",
            ),
        )
        assertEquals(4, cfg.criticalSeverity)
        assertEquals(2, cfg.safeSeverity)
    }

    @Test
    fun `level validation`() {
        assertNull(ThermalPolicy.validateLevels("severe", "moderate"))
        assertNull(ThermalPolicy.validateLevels("light", "none"))
        assertNull(ThermalPolicy.validateLevels("CRITICAL", "Severe")) // case-insensitive
        assertNotNull(ThermalPolicy.validateLevels("none", "none")) // critical must be at least light
        assertNotNull(ThermalPolicy.validateLevels("emergency", "moderate")) // past the cap
        assertNotNull(ThermalPolicy.validateLevels("severe", "severe")) // safe must be below
        assertNotNull(ThermalPolicy.validateLevels("moderate", "severe"))
        assertNotNull(ThermalPolicy.validateLevels("hot", "moderate"))
    }

    // ---- battery-temperature fallback ----

    @Test
    fun `fallback stays clear below the critical temperature`() {
        val p = policy()
        assertFalse(p.battery(44.9f, atMs = 0))
    }

    @Test
    fun `fallback trips at the critical temperature with no dwell`() {
        val p = policy()
        assertTrue(p.battery(45f, atMs = 0))
        assertEquals(ThermalShutdownStatus.SOURCE_BATTERY, p.reason)
    }

    @Test
    fun `fallback holds between the thresholds`() {
        val p = policy()
        assertFalse(p.battery(42f, atMs = 0))
        assertTrue(p.battery(46f, atMs = 10))
        assertTrue(p.battery(42f, atMs = 20))
        assertTrue(p.battery(42f, atMs = 20 + dwell * 10))
    }

    @Test
    fun `fallback resumes only after holding at or under safe for the whole dwell`() {
        val p = policy()
        p.battery(46f, atMs = 0)
        assertTrue(p.battery(40f, atMs = 1_000))
        p.battery(40.1f, atMs = 30_000) // back over safe: the wait restarts
        assertTrue(p.battery(39f, atMs = 40_000))
        assertTrue(p.battery(39f, atMs = 40_000 + dwell - 1))
        assertFalse(p.battery(39f, atMs = 40_000 + dwell))
    }

    @Test
    fun `a device that reports nothing never trips`() {
        val p = policy()
        assertFalse(p.battery(null, atMs = 0))
        assertEquals(ThermalShutdownStatus.SOURCE_NONE, ThermalPolicy.sourceOf(ThermalSample(-1, null)))
    }

    @Test
    fun `validation`() {
        assertNull(ThermalPolicy.validate(45f, 40f))
        assertNull(ThermalPolicy.validate(21f, 20f))
        assertNotNull(ThermalPolicy.validate(61f, 40f)) // critical above the cap
        assertNotNull(ThermalPolicy.validate(19f, 15f)) // below the floor
        assertNotNull(ThermalPolicy.validate(40f, 40f)) // no hysteresis
        assertNotNull(ThermalPolicy.validate(40f, 39.5f)) // less than the minimum gap
        assertNotNull(ThermalPolicy.validate(Float.NaN, 40f))
    }
}
