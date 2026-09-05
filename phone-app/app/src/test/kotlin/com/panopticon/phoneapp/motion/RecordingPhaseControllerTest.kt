package com.panopticon.phoneapp.motion

import com.panopticon.phoneapp.motion.RecordingPhaseController.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPhaseControllerTest {

    @Test
    fun `starts armed`() {
        assertEquals(Phase.ARMED, RecordingPhaseController().phase)
    }

    @Test
    fun `motion arms recording immediately and holds through the trailer`() {
        val c = RecordingPhaseController(trailerMs = 5_000)

        assertEquals(Phase.RECORDING, c.onFrame(motion = true, nowMs = 1_000))
        // Motion stops, but the trailer keeps it recording.
        assertEquals(Phase.RECORDING, c.onFrame(motion = false, nowMs = 3_000))
        assertEquals(Phase.RECORDING, c.onFrame(motion = false, nowMs = 5_999))
        // Trailer elapsed (6_000 - 1_000 == 5_000).
        assertEquals(Phase.ARMED, c.onFrame(motion = false, nowMs = 6_000))
    }

    @Test
    fun `renewed motion during the trailer resets the tail`() {
        val c = RecordingPhaseController(trailerMs = 5_000)

        c.onFrame(motion = true, nowMs = 1_000)
        c.onFrame(motion = false, nowMs = 4_000)
        // New motion at 4_500 - trailer now measured from here.
        c.onFrame(motion = true, nowMs = 4_500)
        assertEquals(Phase.RECORDING, c.onFrame(motion = false, nowMs = 9_000)) // 4_500 elapsed
        assertEquals(Phase.ARMED, c.onFrame(motion = false, nowMs = 9_500)) // 5_000 elapsed
    }

    @Test
    fun `trailerRemainingMs counts down while recording and is zero when armed`() {
        val c = RecordingPhaseController(trailerMs = 5_000)
        assertEquals(0L, c.trailerRemainingMs(0))

        c.onFrame(motion = true, nowMs = 1_000)
        assertEquals(4_000L, c.trailerRemainingMs(2_000))
        assertEquals(0L, c.trailerRemainingMs(7_000)) // clamped, not negative

        c.onFrame(motion = false, nowMs = 7_000)
        assertEquals(0L, c.trailerRemainingMs(7_000))
    }

    @Test
    fun `disarm forces back to armed`() {
        val c = RecordingPhaseController()
        c.onFrame(motion = true, nowMs = 1_000)
        assertEquals(Phase.RECORDING, c.phase)
        c.disarm()
        assertEquals(Phase.ARMED, c.phase)
    }
}
