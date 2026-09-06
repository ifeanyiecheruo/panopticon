package com.panopticon.phoneapp.clips

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentGroupingTest {

    private fun seg(start: Long, durMs: Long = 1_000, size: Long = 10) = SegmentEntry(
        filename = "seg_$start.mp4",
        createdAtMs = start,
        durationMs = durMs,
        endMs = start + durMs,
        sizeBytes = size,
        width = 1280,
        height = 720,
    )

    @Test
    fun `empty input yields no clips`() {
        assertEquals(emptyList<Clip>(), groupIntoClips(emptyList()))
    }

    @Test
    fun `gaplessly-rolled segments form one clip`() {
        // Each starts ~100ms after the previous ends (well within GAP_MS) - what
        // MediaRecorder.setNextOutputFile rollover produces.
        val segs = listOf(seg(0), seg(1_100), seg(2_200))
        val clips = groupIntoClips(segs)
        assertEquals(1, clips.size)
        assertEquals(3, clips[0].count)
        assertEquals(0L, clips[0].startedAtMs)
        assertEquals(3_200L, clips[0].endedAtMs)
        assertEquals(30L, clips[0].sizeBytes)
    }

    @Test
    fun `a gap wider than the threshold splits the run`() {
        // seg(0) ends at 1_000; the next run starts at 6_000 -> a real
        // motion-stop gap, far over GAP_MS.
        val segs = listOf(seg(0), seg(6_000), seg(7_000))
        val clips = groupIntoClips(segs)
        assertEquals(2, clips.size)
        // newest first
        assertEquals(6_000L, clips[0].startedAtMs)
        assertEquals(2, clips[0].count)
        assertEquals(0L, clips[1].startedAtMs)
        assertEquals(1, clips[1].count)
    }

    @Test
    fun `unsorted input is grouped in time order`() {
        val clips = groupIntoClips(listOf(seg(2_000), seg(0), seg(1_000)))
        assertEquals(1, clips.size)
        assertEquals(listOf(0L, 1_000L, 2_000L), clips[0].segments.map { it.createdAtMs })
    }

    @Test
    fun `exactly at the threshold still joins`() {
        // gap == GAP_MS is "contiguous" (only a strictly larger gap splits)
        val clips = groupIntoClips(listOf(seg(0), seg(1_000 + GAP_MS)))
        assertEquals(1, clips.size)
        assertEquals(2, clips[0].count)
    }

    @Test
    fun `one past the threshold splits`() {
        val clips = groupIntoClips(listOf(seg(0), seg(1_000 + GAP_MS + 1)))
        assertEquals(2, clips.size)
    }
}
