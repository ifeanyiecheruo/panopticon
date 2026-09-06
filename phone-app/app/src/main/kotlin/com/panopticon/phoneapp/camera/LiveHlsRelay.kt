package com.panopticon.phoneapp.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.panopticon.phoneapp.camera.ts.TsMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Produces a rolling **plain HLS** playlist (in-memory - see [currentPlaylist]) and finalized
 * `live-N.ts` segment files in a cache directory (not the ring buffer, not counted against the
 * storage cap, cleaned up as segments roll out of the sliding window) from encoded H.264 samples
 * fed to it via [feed].
 *
 * Adapted from the abandoned prototype's `LiveHlsRelay`, **with LL-HLS removed**: no
 * `EXT-X-PART` / `PRELOAD-HINT` byte-range parts, no blocking playlist reloads, no in-progress
 * range reads. Just whole ~[segmentDurationUs] segments in a [playlistWindowSize]-deep sliding
 * window. Glass-to-glass latency is roughly `segmentDurationUs * 3` (hls.js starts 3 segments
 * back by default). The prototype's LL-HLS machinery and the hls.js latency workarounds it needed
 * are documented in `docs/QUIRKS.md` as carried-forward work to adopt if that latency proves too
 * high in practice.
 *
 * This does NOT own a camera surface or an encoder - it's fed by [LivePipeline]'s drain thread.
 * [feed] is non-blocking: the muxing + file I/O happens on this class's own worker thread via a
 * bounded, drop-on-full queue, so a slow flash write can never stall the encoder drain.
 */
class LiveHlsRelay(
    private val liveDir: File,
    private val segmentDurationUs: Long = DEFAULT_SEGMENT_DURATION_US,
    // A deep window is deliberate: with ~1s segments this is ~[playlistWindowSize]s of DVR, so a
    // player that briefly falls behind the live edge (a slow fetch, a GC pause, hls.js's
    // post-stall latency ratchet) still finds every segment it asks for instead of a 404 that
    // spirals into permanent rebuffering. ~16 small .ts in cache costs a few MB.
    private val playlistWindowSize: Int = 16,
) {
    private val muxer = TsMuxer()

    // Mutable relay state touched by this class's own worker thread (workerLoop) and read by
    // whatever Ktor request thread serves a playlist fetch (currentPlaylist is a plain volatile
    // read; everything else stays on the worker thread). writePlaylist swaps a whole String
    // reference, so a reader never sees a half-built playlist.
    private val stateLock = Any()

    private var segmentSeq = 0
    private var segmentStartPtsUs = -1L
    private var lastAccessUnitPtsUs = -1L
    private var currentSegmentBuffer = ByteArrayOutputStream()

    private data class PlaylistEntry(val seq: Int, val durationSec: Double)
    private val playlistSegments = ArrayDeque<PlaylistEntry>()
    private var mediaSequence = 0

    // The playlist is purely an ephemeral live view - never archived, never needed for crash
    // recovery - so it lives as a plain in-memory String (a single reference swap on each
    // rebuild) that LiveRoutes serves directly, rather than round-tripping through this device's
    // slow flash on every viewer GET.
    @Volatile private var playlistText: String? = null

    /** Current playlist content, or null if nothing's been produced yet. */
    fun currentPlaylist(): String? = playlistText

    /** Bytes of a finalized segment file, or null if it's rolled out of the window / not written
     * yet. Served straight from disk - segments are the only thing worth persisting here. */
    fun readSegment(seq: Int): ByteArray? {
        val f = File(liveDir, "live-$seq.ts")
        return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
    }

    // Derived once from the encoder's MediaFormat (csd-0/csd-1). Re-prepended to every new segment
    // (see startNewSegmentLocked) so each one is independently decodable - a player can join at
    // any segment in the window.
    private var codecConfigBytes: ByteArray? = null

    private data class QueuedSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int, val format: MediaFormat)

    private val queue = LinkedBlockingQueue<QueuedSample>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(true)
    private val workerThread = Thread(::workerLoop, "LiveHlsRelay-worker").apply { start() }

    // Segment-file writes still involve real disk I/O on hardware confirmed (in the prototype) to
    // stall over a second on an unlucky write - keep them off the worker thread. A single-thread
    // executor keeps them ordered for stop()'s drain to make sense.
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "LiveHlsRelay-io") }

    init {
        liveDir.mkdirs()
        clearStaleFiles()
    }

    /** Called from [LivePipeline]'s drain thread for every encoded sample, in order. Non-blocking. */
    fun feed(data: ByteArray, presentationTimeUs: Long, flags: Int, format: MediaFormat) {
        if (!queue.offer(QueuedSample(data, presentationTimeUs, flags, format))) {
            Log.w(TAG, "live sample queue full - dropping a sample")
        }
    }

    fun stop() {
        running.set(false)
        workerThread.interrupt()
        workerThread.join(2000)
        synchronized(stateLock) { finalizeCurrentSegmentLocked() }
        ioExecutor.shutdown()
        runCatching { ioExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        clearStaleFiles()
        playlistText = null
    }

    private fun workerLoop() {
        while (running.get()) {
            val sample = try {
                queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                return
            }
            try {
                processSample(sample)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to process one live sample - continuing", t)
            }
        }
    }

    // The encoder runs continuously from LIVE-mode entry; the first viewer can connect long after,
    // so rebase to 0 at the first sample to keep every segment's timestamps small.
    private var baseTimestampUs = -1L

    private fun processSample(sample: QueuedSample) {
        if (codecConfigBytes == null) codecConfigBytes = extractCodecConfig(sample.format)
        if (sample.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        if (baseTimestampUs < 0) baseTimestampUs = sample.presentationTimeUs
        val isKeyFrame = (sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        handleAccessUnit(sample.data, sample.presentationTimeUs - baseTimestampUs, isKeyFrame)
    }

    private fun extractCodecConfig(format: MediaFormat): ByteArray? {
        val sps = runCatching { format.getByteBuffer("csd-0") }.getOrNull() ?: return null
        val out = ByteArrayOutputStream()
        appendBuffer(out, sps)
        runCatching { format.getByteBuffer("csd-1") }.getOrNull()?.let { appendBuffer(out, it) }
        return out.toByteArray()
    }

    // csd-0/csd-1 from an AVC encoder's MediaFormat are already Annex-B NAL units - no reformatting.
    private fun appendBuffer(out: ByteArrayOutputStream, buffer: ByteBuffer) {
        val dup = buffer.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        out.write(bytes)
    }

    private fun handleAccessUnit(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) = synchronized(stateLock) {
        if (segmentStartPtsUs < 0) {
            startNewSegmentLocked(ptsUs)
        } else if (isKeyFrame && ptsUs - segmentStartPtsUs >= segmentDurationUs) {
            finalizeCurrentSegmentLocked()
            startNewSegmentLocked(ptsUs)
        }
        muxer.writeAccessUnit(currentSegmentBuffer, data, ptsUs, isKeyFrame)
        lastAccessUnitPtsUs = ptsUs
    }

    private fun startNewSegmentLocked(ptsUs: Long) {
        segmentStartPtsUs = ptsUs
        currentSegmentBuffer = ByteArrayOutputStream()
        muxer.writeHeader(currentSegmentBuffer)
        // The real first access unit written right after carries its own PCR, so no separate PCR
        // packet here - SPS/PPS adds little and stays well within spec tolerance.
        codecConfigBytes?.let { muxer.writeAccessUnit(currentSegmentBuffer, it, ptsUs, isKeyFrame = false) }
    }

    private fun finalizeCurrentSegmentLocked() {
        if (segmentStartPtsUs < 0) return
        val seq = segmentSeq++
        val bytes = currentSegmentBuffer.toByteArray()
        // Real span of media, not the nominal target - hls.js tracks buffer level from actual
        // demuxed timestamps, so a playlist that always claims exactly segmentDurationUs drifts
        // out of sync with reality over many segments.
        val actualDurationSec = if (lastAccessUnitPtsUs > segmentStartPtsUs) {
            (lastAccessUnitPtsUs - segmentStartPtsUs) / 1_000_000.0
        } else {
            segmentDurationUs / 1_000_000.0
        }
        playlistSegments.addLast(PlaylistEntry(seq, actualDurationSec))
        val evictedSeqs = mutableListOf<Int>()
        while (playlistSegments.size > playlistWindowSize) {
            evictedSeqs.add(playlistSegments.removeFirst().seq)
            mediaSequence++
        }
        // The playlist advertises `seq` as done before its bytes are on disk (write is dispatched
        // off-lock below). A fetch landing in that narrow gap gets a momentary 404; hls.js retries
        // a failed fragment quickly, a far smaller cost than holding stateLock across a slow write.
        ioExecutor.execute {
            runCatching { File(liveDir, "live-$seq.ts").writeBytes(bytes) }
                .onFailure { Log.w(TAG, "Failed to write live segment $seq", it) }
            for (evictedSeq in evictedSeqs) File(liveDir, "live-$evictedSeq.ts").delete()
        }
        writePlaylist()
        segmentStartPtsUs = -1L
    }

    private fun writePlaylist() {
        // Max over the segments currently in the window, not an all-time high-water mark.
        val targetDurationSec = kotlin.math.ceil(
            playlistSegments.maxOfOrNull { it.durationSec } ?: (segmentDurationUs / 1_000_000.0)
        ).toLong().coerceAtLeast(1L)
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:$targetDurationSec\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:$mediaSequence\n")
        // Join a few seconds behind the live edge, not at it - leaves headroom for a slow fetch
        // before the player would have to stall. hls.js and Safari both honour this.
        sb.append("#EXT-X-START:TIME-OFFSET=-${"%.3f".format(START_OFFSET_SEC)},PRECISE=YES\n")
        for (entry in playlistSegments) {
            sb.append("#EXTINF:${"%.3f".format(entry.durationSec)},\n")
            sb.append("live-${entry.seq}.ts\n")
        }
        playlistText = sb.toString()
    }

    private fun clearStaleFiles() {
        liveDir.listFiles { f -> f.name == "live.m3u8" || f.name.matches(Regex("live-\\d+\\.ts")) }
            ?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "LiveHlsRelay"
        private const val QUEUE_CAPACITY = 300
        private const val POLL_TIMEOUT_MS = 500L
        private const val START_OFFSET_SEC = 4.0

        /** Target segment length. Short segments = finer granularity: a single slow fetch costs
         * ~1s of buffer, not 2-3s, and the DVR window holds proportionally more segments.
         * [LivePipeline] requests an explicit sync frame on this cadence so a segment can always
         * rotate on a keyframe at roughly this interval regardless of what the encoder's
         * `KEY_I_FRAME_INTERVAL` hint actually does on a given device. */
        const val DEFAULT_SEGMENT_DURATION_US = 1_000_000L
    }
}
