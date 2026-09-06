package com.panopticon.phoneapp.camera.ts

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for a real bug (from the prototype this muxer was ported from):
 * writeTsPackets() overflowed every padded, non-PCR TS packet (i.e. essentially every PAT/PMT
 * write - see writeHeader()) by exactly 1 byte, throwing ArrayIndexOutOfBoundsException from
 * System.arraycopy and crashing LiveHlsRelay's worker thread the moment any live-view segment
 * started. Every TS packet must be exactly 188 bytes, whatever its payload size - that's the one
 * invariant this suite checks across the shapes that actually occur: PAT/PMT (small, no PCR,
 * needs stuffing), keyframe access units (PCR, may or may not need stuffing), and non-keyframe
 * access units (no PCR, spans a mix of full and short packets).
 */
class TsMuxerTest {

    @Test
    fun `header packets are all exactly 188 bytes`() {
        val out = ByteArrayOutputStream()
        TsMuxer().writeHeader(out)
        assertPacketsWellFormed(out.toByteArray())
    }

    @Test
    fun `access unit packets are all exactly 188 bytes regardless of size`() {
        for (size in listOf(1, 16, 183, 184, 185, 500, 4096)) {
            val out = ByteArrayOutputStream()
            val payload = Random(size).nextBytes(size)
            TsMuxer().writeAccessUnit(out, payload, ptsUs = 12_345, isKeyFrame = size % 2 == 0)
            assertPacketsWellFormed(out.toByteArray())
        }
    }

    @Test
    fun `full segment header plus multiple access units all well formed`() {
        val out = ByteArrayOutputStream()
        val muxer = TsMuxer()
        muxer.writeHeader(out)
        for (i in 0 until 10) {
            val payload = Random(i).nextBytes(200 + i * 137)
            muxer.writeAccessUnit(out, payload, ptsUs = i * 33_333L, isKeyFrame = i == 0)
        }
        assertPacketsWellFormed(out.toByteArray())
    }

    private fun assertPacketsWellFormed(data: ByteArray) {
        assertTrue("output should be a whole number of 188-byte TS packets", data.size % 188 == 0)
        assertTrue("output should be non-empty", data.isNotEmpty())
        var offset = 0
        while (offset < data.size) {
            assertEquals("sync byte at packet offset $offset", 0x47.toByte(), data[offset])
            offset += 188
        }
    }
}
