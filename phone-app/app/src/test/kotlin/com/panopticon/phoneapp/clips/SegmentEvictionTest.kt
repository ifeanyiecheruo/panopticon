package com.panopticon.phoneapp.clips

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentEvictionTest {
    private val now = 10_000_000L

    /** Ten 100-byte, 1s segments, oldest first: s0 ends 10s ago ... s9 ended 1s ago. */
    private val segs = (0 until 10).map { i ->
        val end = now - (10 - i) * 1_000L
        SegmentEntry("s$i.mp4", createdAtMs = end - 1_000L, durationMs = 1_000L, endMs = end, sizeBytes = 100L, width = 1, height = 1)
    }

    private fun plan(capBytes: Long = 0, maxAgeMs: Long = 0, bytesToFree: Long = 0) =
        planEviction(segs.shuffled(), capBytes, maxAgeMs, bytesToFree, now)

    @Test
    fun `within every limit nothing goes`() {
        assertEquals(emptyList<String>(), plan(capBytes = 1_000, maxAgeMs = 60_000))
    }

    @Test
    fun `over the cap drops oldest first until under it`() {
        assertEquals(listOf("s0.mp4", "s1.mp4", "s2.mp4"), plan(capBytes = 700))
    }

    @Test
    fun `segments past the max age go even under the cap`() {
        // Ended 10s, 9s and 8s ago: older than 7.5s.
        assertEquals(listOf("s0.mp4", "s1.mp4", "s2.mp4"), plan(capBytes = 10_000, maxAgeMs = 7_500))
    }

    @Test
    fun `a volume short of headroom frees at least that much, oldest first`() {
        // The disk is full for reasons of its own - the store is within its cap - yet the
        // recorder still needs room for the next segment.
        assertEquals(listOf("s0.mp4", "s1.mp4", "s2.mp4"), plan(capBytes = 10_000, bytesToFree = 250))
    }

    @Test
    fun `the strictest limit wins`() {
        assertEquals((0 until 5).map { "s$it.mp4" }, plan(capBytes = 800, maxAgeMs = 60_000, bytesToFree = 450))
    }

    @Test
    fun `a zero cap or age means that limit is off`() {
        assertEquals(emptyList<String>(), plan(capBytes = 0, maxAgeMs = 0))
    }
}
