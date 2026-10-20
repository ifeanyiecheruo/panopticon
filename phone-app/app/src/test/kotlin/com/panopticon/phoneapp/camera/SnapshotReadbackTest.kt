package com.panopticon.phoneapp.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotReadbackTest {

    @Test
    fun `a 4K frame is scaled to the max edge, keeping its aspect`() {
        assertEquals(960 to 540, SnapshotReadback.stillSize(3840, 2160, 960))
    }

    @Test
    fun `a portrait frame is bounded by its height`() {
        assertEquals(540 to 960, SnapshotReadback.stillSize(1080, 1920, 960))
    }

    @Test
    fun `a frame already within the max edge is never enlarged`() {
        assertEquals(854 to 480, SnapshotReadback.stillSize(854, 480, 960))
    }

    @Test
    fun `dimensions come out even`() {
        val (w, h) = SnapshotReadback.stillSize(1280, 721, 961)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }
}
