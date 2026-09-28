package com.panopticon.phoneapp.camera

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHealthTest {

    private var wall = 1_000_000L
    private var mono = 0L

    private fun registry() = CameraHealthRegistry(
        recentEventLimit = 4,
        wallClock = { wall },
        monotonic = { mono },
    )

    private fun cameraJson(r: CameraHealthRegistry, key: String) =
        r.snapshot().cameras[key]!!.jsonObject

    private fun num(r: CameraHealthRegistry, key: String, field: String): Long =
        cameraJson(r, key)[field]!!.jsonPrimitive.content.toLong()

    @Test
    fun `mean uptime is reported per end reason, so a stall is not averaged with a mode switch`() {
        val r = registry()
        val h = r.forCamera("0", "record")

        h.runStarted(); mono += 150_000; h.runEnded("stall")
        h.runStarted(); mono += 190_000; h.runEnded("stall")
        h.runStarted(); mono += 10_000; h.runEnded("stopped")

        val byReason = cameraJson(r, "record:0")["meanUpMsByEndReason"]!!.jsonObject
        val stall = byReason["stall"]!!.jsonObject
        assertEquals(2L, stall["count"]!!.jsonPrimitive.content.toLong())
        assertEquals(170_000L, stall["meanUpMs"]!!.jsonPrimitive.content.toLong())

        val stopped = byReason["stopped"]!!.jsonObject
        assertEquals(1L, stopped["count"]!!.jsonPrimitive.content.toLong())
        assertEquals(10_000L, stopped["meanUpMs"]!!.jsonPrimitive.content.toLong())

        // The undifferentiated mean is still there, and is exactly the thing that would mislead
        // on its own: three runs averaging 116s hides a stall cadence of 170s.
        assertEquals(116_666L, num(r, "record:0", "meanUpMs"))
    }

    @Test
    fun `the run in flight is excluded from the mean but visible as current uptime`() {
        val r = registry()
        val h = r.forCamera("0", "record")
        h.runStarted(); mono += 100_000; h.runEnded("stall")
        h.runStarted(); mono += 40_000

        assertEquals("only the completed run counts", 100_000L, num(r, "record:0", "meanUpMs"))
        assertEquals(40_000L, num(r, "record:0", "currentUpMs"))
        assertEquals(2L, num(r, "record:0", "runs"))
        assertEquals(1L, num(r, "record:0", "runsEnded"))
    }

    @Test
    fun `record and live are separate records for the same camera`() {
        val r = registry()
        r.forCamera("0", "record").count("captureFailed", 3)
        r.forCamera("0", "live").count("captureFailed", 1)

        val rec = cameraJson(r, "record:0")["counters"]!!.jsonObject
        val live = cameraJson(r, "live:0")["counters"]!!.jsonObject
        assertEquals(3L, rec["captureFailed"]!!.jsonPrimitive.content.toLong())
        assertEquals(1L, live["captureFailed"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `the recent ring keeps the newest events and drops the oldest`() {
        val r = registry()
        val h = r.forCamera("0", "record")
        repeat(6) { wall += 1_000; h.event("captureFailed", "frame=$it") }

        val entries = cameraJson(r, "record:0")["recent"]!!.jsonArray
        assertEquals("limit of 4 is honoured", 4, entries.size)
        assertEquals("frame=2", entries.first().jsonObject["detail"]!!.jsonPrimitive.content)
        assertEquals("frame=5", entries.last().jsonObject["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an event both counts and timestamps`() {
        val r = registry()
        val h = r.forCamera("0", "record")
        h.event("captureBufferLost")
        h.event("captureBufferLost")
        val counters = cameraJson(r, "record:0")["counters"]!!.jsonObject
        assertEquals(2L, counters["captureBufferLost"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `gauges hold a last-known value of any shape`() {
        val r = registry()
        val h = r.forCamera("0", "record")
        h.gauge("recordingSize", "3840x2160")
        h.gauge("measuredFps", 29.97)
        h.gauge("measuredFps", 30.01)
        val gauges = cameraJson(r, "record:0")["gauges"]!!.jsonObject
        assertEquals("3840x2160", gauges["recordingSize"]!!.jsonPrimitive.content)
        assertEquals("30.01", gauges["measuredFps"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a run that never started reports no uptime rather than a wild one`() {
        val r = registry()
        // openCameraIfNeeded() threw, so runEnded arrives with no matching runStarted.
        r.forCamera("0", "record").runEnded("cameraOpenError")
        assertEquals(0L, num(r, "record:0", "lastUpMs"))
        assertEquals(0L, num(r, "record:0", "meanUpMs"))
    }

    @Test
    fun `the envelope carries process uptime so counters can be read as rates`() {
        val r = registry()
        wall += 3_600_000
        val snap = r.snapshot()
        assertEquals(3_600_000L, snap.processUptimeMs)
        assertTrue(snap.generatedAtMs > 0)
    }
}
