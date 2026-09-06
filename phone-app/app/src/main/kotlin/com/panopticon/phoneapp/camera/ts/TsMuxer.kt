package com.panopticon.phoneapp.camera.ts

import java.io.ByteArrayOutputStream

/**
 * Minimal single-program, video-only H.264-to-MPEG-TS muxer, vendored because Android's stock
 * MediaMuxer cannot emit MPEG-TS (only MP4/WebM/3GP/OGG). Just enough of the spec for hls.js /
 * ffplay / VLC to play a live HLS feed: one PAT, one PMT (video-only, stream type 0x1B = H.264),
 * PCR carried on the video PID, and PES-wrapped Annex-B access units (Android's hardware AVC
 * encoder emits Annex-B start-code-prefixed NALs, which is what PES/TS expects for H.264).
 *
 * Not a general-purpose muxer: no audio, no multi-program support, no B-frame DTS handling beyond
 * "PTS == DTS" (fine for a live camera feed with no B-frames).
 *
 * Ported verbatim from the abandoned prototype (`panopticon-prototype/`), which paid for the
 * 188-byte-packet bug called out in [TsMuxer]'s test - see `docs/QUIRKS.md`'s Live HLS section.
 */
class TsMuxer(private val videoPid: Int = 0x100, private val pmtPid: Int = 0x1000) {

    private var continuityCounters = HashMap<Int, Int>()

    /** Writes the PAT + PMT once at the start of every segment (segments must be self-contained). */
    fun writeHeader(out: ByteArrayOutputStream) {
        writePat(out)
        writePmt(out)
    }

    /** Wraps one Annex-B access unit (may contain multiple NAL units) into PES + TS packets. */
    fun writeAccessUnit(out: ByteArrayOutputStream, data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        val pts90k = usToPts90k(ptsUs)
        val pes = buildPesPacket(data, pts90k)
        writeTsPackets(out, videoPid, pes, payloadUnitStart = true, withPcr = isKeyFrame, pcrValue = pts90k * 300)
    }

    private fun usToPts90k(us: Long): Long = (us * 90) / 1000

    private fun nextContinuity(pid: Int): Int {
        val next = (continuityCounters.getOrDefault(pid, 0)) and 0x0F
        continuityCounters[pid] = (next + 1) and 0x0F
        return next
    }

    private fun buildPesPacket(payload: ByteArray, pts90k: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00); out.write(0x00); out.write(0x01) // start code
        out.write(0xE0) // stream id: video
        val ptsBytes = encodePts(pts90k, marker = 0x2)
        // PES packet length: 0 is allowed/conventional for unbounded elementary video streams.
        out.write(0x00); out.write(0x00)
        out.write(0x80) // '10' + flags
        out.write(0x80) // PTS_DTS_flags = '10' (PTS only)
        out.write(ptsBytes.size)
        out.write(ptsBytes)
        out.write(payload)
        return out.toByteArray()
    }

    private fun encodePts(pts: Long, marker: Int): ByteArray {
        val b = ByteArray(5)
        b[0] = (((marker shl 4) or (((pts shr 30) and 0x7).toInt() shl 1) or 1)).toByte()
        b[1] = (((pts shr 22) and 0xFF)).toByte()
        b[2] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        b[3] = (((pts shr 7) and 0xFF)).toByte()
        b[4] = ((((pts and 0x7F).toInt() shl 1) or 1)).toByte()
        return b
    }

    private fun writeTsPackets(
        out: ByteArrayOutputStream,
        pid: Int,
        payload: ByteArray,
        payloadUnitStart: Boolean,
        withPcr: Boolean = false,
        pcrValue: Long = 0
    ) {
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val packet = ByteArray(188)
            packet[0] = 0x47
            val pusi = if (first && payloadUnitStart) 0x40 else 0x00
            packet[1] = (pusi or ((pid shr 8) and 0x1F)).toByte()
            packet[2] = (pid and 0xFF).toByte()

            val cc = nextContinuity(pid)
            val hasPcr = first && withPcr

            // Bytes available for [adaptation field][payload] after the fixed 4-byte TS header.
            val availableAfterHeader = 188 - 4
            val remainingPayload = payload.size - offset

            // An adaptation field is needed either to carry the PCR, or - if the payload alone
            // wouldn't fill the packet - purely as a stuffing container (TS packets are always
            // exactly 188 bytes; a short payload must be padded, not left short). Either way the
            // field costs 1 byte for its own length prefix, plus 7 more if it's carrying a PCR.
            // (Previous bug: the stuffing-only case computed available payload space as if no
            // adaptation field would be added at all, then added one anyway without reserving
            // its length-prefix byte - overflowing every padded, non-PCR packet by exactly 1
            // byte, i.e. nearly every PAT/PMT write.)
            val needsAdaptationField = hasPcr || remainingPayload < availableAfterHeader
            val adaptationOverhead = if (needsAdaptationField) 1 + (if (hasPcr) 7 else 0) else 0

            val availableForPayload = availableAfterHeader - adaptationOverhead
            val bytesToWrite = minOf(remainingPayload, availableForPayload)
            val stuffing = if (needsAdaptationField) availableForPayload - bytesToWrite else 0

            val adaptationFieldControl = if (needsAdaptationField) 0x30 else 0x10
            packet[3] = (adaptationFieldControl or cc).toByte()

            var pos = 4
            if (needsAdaptationField) {
                // adaptation_field_length does not count its own length byte.
                val adaptationFieldLength = (if (hasPcr) 7 else 0) + stuffing
                packet[pos++] = adaptationFieldLength.toByte()
                if (hasPcr) {
                    packet[pos++] = 0x50.toByte() // PCR_flag set
                    val pcrBase = pcrValue / 300
                    val pcrExt = pcrValue % 300
                    packet[pos++] = ((pcrBase shr 25) and 0xFF).toByte()
                    packet[pos++] = ((pcrBase shr 17) and 0xFF).toByte()
                    packet[pos++] = ((pcrBase shr 9) and 0xFF).toByte()
                    packet[pos++] = ((pcrBase shr 1) and 0xFF).toByte()
                    packet[pos++] = ((((pcrBase and 1) shl 7) or 0x7E) or ((pcrExt shr 8) and 1)).toByte()
                    packet[pos++] = (pcrExt and 0xFF).toByte()
                }
                repeat(stuffing) { packet[pos++] = 0xFF.toByte() }
            }

            System.arraycopy(payload, offset, packet, pos, bytesToWrite)
            offset += bytesToWrite
            out.write(packet)
            first = false
        }
    }

    private fun crc32Mpeg2(data: ByteArray, offset: Int, length: Int): Long {
        var crc = 0xFFFFFFFFL
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toLong() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000L != 0L) (crc shl 1) xor 0x04C11DB7L else crc shl 1
                crc = crc and 0xFFFFFFFFL
            }
        }
        return crc
    }

    private fun writePat(out: ByteArrayOutputStream) {
        val section = ByteArrayOutputStream()
        section.write(0x00) // table id
        // section_syntax_indicator(1)+'0'+reserved(2)+length(12), filled after body built
        val body = ByteArrayOutputStream()
        body.write(0x00); body.write(0x01) // transport_stream_id
        body.write(0xC1) // reserved+version+current_next_indicator
        body.write(0x00) // section_number
        body.write(0x00) // last_section_number
        body.write(0x00); body.write(0x01) // program_number = 1
        body.write((0xE0 or ((pmtPid shr 8) and 0x1F))) // reserved + PMT PID high
        body.write(pmtPid and 0xFF)
        val bodyBytes = body.toByteArray()
        val sectionLength = bodyBytes.size + 4 // + CRC32
        section.write(0x80 or ((sectionLength shr 8) and 0x0F))
        section.write(sectionLength and 0xFF)
        section.write(bodyBytes)
        val crcInput = section.toByteArray().copyOfRange(1, section.size())
        val crc = crc32Mpeg2(crcInput, 0, crcInput.size)
        section.write(((crc shr 24) and 0xFF).toInt())
        section.write(((crc shr 16) and 0xFF).toInt())
        section.write(((crc shr 8) and 0xFF).toInt())
        section.write((crc and 0xFF).toInt())

        val tsPayload = ByteArrayOutputStream()
        tsPayload.write(0x00) // pointer_field
        tsPayload.write(section.toByteArray())
        writeTsPackets(out, PAT_PID, tsPayload.toByteArray(), payloadUnitStart = true)
    }

    private fun writePmt(out: ByteArrayOutputStream) {
        val section = ByteArrayOutputStream()
        section.write(0x02) // table id (TS_program_map_section)
        val body = ByteArrayOutputStream()
        body.write(0x00); body.write(0x01) // program_number
        body.write(0xC1) // version/current_next
        body.write(0x00) // section_number
        body.write(0x00) // last_section_number
        body.write((0xE0 or ((videoPid shr 8) and 0x1F))) // PCR_PID high (video carries PCR)
        body.write(videoPid and 0xFF)
        body.write(0xF0); body.write(0x00) // program_info_length = 0
        // one elementary stream: H.264 video
        body.write(0x1B) // stream_type = H.264
        body.write((0xE0 or ((videoPid shr 8) and 0x1F)))
        body.write(videoPid and 0xFF)
        body.write(0xF0); body.write(0x00) // ES_info_length = 0
        val bodyBytes = body.toByteArray()
        val sectionLength = bodyBytes.size + 4
        section.write(0x80 or ((sectionLength shr 8) and 0x0F))
        section.write(sectionLength and 0xFF)
        section.write(bodyBytes)
        val crcInput = section.toByteArray().copyOfRange(1, section.size())
        val crc = crc32Mpeg2(crcInput, 0, crcInput.size)
        section.write(((crc shr 24) and 0xFF).toInt())
        section.write(((crc shr 16) and 0xFF).toInt())
        section.write(((crc shr 8) and 0xFF).toInt())
        section.write((crc and 0xFF).toInt())

        val tsPayload = ByteArrayOutputStream()
        tsPayload.write(0x00) // pointer_field
        tsPayload.write(section.toByteArray())
        writeTsPackets(out, pmtPid, tsPayload.toByteArray(), payloadUnitStart = true)
    }

    companion object {
        private const val PAT_PID = 0x0000
    }
}
