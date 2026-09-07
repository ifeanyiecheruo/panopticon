package com.panopticon.phoneapp.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraLabelsTest {

    @Test
    fun `single back lens is just Wide`() {
        assertEquals("Wide", CameraLabels.label(4.4f, "back", listOf(4.4f)))
    }

    @Test
    fun `single front lens is Front`() {
        assertEquals("Front", CameraLabels.label(2.7f, "front", listOf(2.7f)))
    }

    @Test
    fun `shortest of a back cluster with a real gap is Ultra-wide`() {
        val peers = listOf(1.9f, 5.4f) // ultrawide + wide, gap well over 15%
        assertEquals("Ultra-wide", CameraLabels.label(1.9f, "back", peers))
        assertEquals("Wide", CameraLabels.label(5.4f, "back", peers))
    }

    @Test
    fun `longest of a back cluster with a real gap is Tele`() {
        val peers = listOf(2.0f, 6.9f, 18.0f) // ultrawide, wide, tele
        assertEquals("Ultra-wide", CameraLabels.label(2.0f, "back", peers))
        assertEquals("Wide", CameraLabels.label(6.9f, "back", peers))
        assertEquals("Tele", CameraLabels.label(18.0f, "back", peers))
    }

    @Test
    fun `near-identical focal lengths stay Wide - no false ultra-wide or tele`() {
        val peers = listOf(4.3f, 4.4f)
        assertEquals("Wide", CameraLabels.label(4.3f, "back", peers))
        assertEquals("Wide", CameraLabels.label(4.4f, "back", peers))
    }

    @Test
    fun `unknown focal length falls back to the facing base label`() {
        assertEquals("Wide", CameraLabels.label(null, "back", listOf(1.9f, 5.4f)))
        assertEquals("Front", CameraLabels.label(0f, "front", listOf(2.7f)))
    }

    @Test
    fun `sortKey orders back before front and wide before tele`() {
        val backWide = CameraLabels.sortKey("back", 5.4f)
        val backTele = CameraLabels.sortKey("back", 18.0f)
        val front = CameraLabels.sortKey("front", 2.7f)
        assert(backWide < backTele)
        assert(backTele < front)
    }
}
