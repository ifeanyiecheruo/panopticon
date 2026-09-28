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
import kotlinx.coroutines.CompletableDeferred

/** A [ByteArrayOutputStream] that can hand back an already-written byte range without first
 * copying its *entire* backing array the way [ByteArrayOutputStream.toByteArray] does -
 * [readInProgressRange] serves an LL-HLS part/preload-hint fetch out of the segment still being
 * built, up to ~3x/sec per viewer, and would otherwise copy the whole in-progress segment just to
 * slice a small range out of it. */
private class SegmentBuffer : ByteArrayOutputStream() {
    fun copyRange(startOffset: Int, endOffsetExclusive: Int): ByteArray {
        val length = endOffsetExclusive - startOffset
        val out = ByteArray(length)
        System.arraycopy(buf, startOffset, out, 0, length)
        return out
    }
}

/**
 * Produces a rolling **LL-HLS** playlist (in-memory - see [currentPlaylist]) and finalized
 * `live-N.ts` segment files in a cache directory (not the ring buffer, not counted against the
 * storage cap, cleaned up as segments roll out of the sliding window) from encoded H.264 samples
 * fed to it via [feed].
 *
 * Ported from the abandoned prototype's `LiveHlsRelay`, keeping this project's own hardened
 * segment tuning: ~1s full segments in a [playlistWindowSize]-deep sliding window (see that
 * param's doc - a shallower window previously caused a 404 -> stall -> latency-ratchet spiral),
 * with ~[partTargetDurationUs] LL-HLS parts layered on top instead of the prototype's 2s/333ms/
 * 6-deep tuning. Within each full segment, access units are additionally grouped into parts -
 * byte ranges within the same growing [currentSegmentBuffer] the eventual finalized segment file
 * is written from (no separate part files, no fMP4/CMAF needed: hls.js transmuxes whatever
 * container it fetches into fMP4 for MSE client-side regardless of source, so plain TS byte
 * ranges work exactly the same as they would for a CMAF encoder). Every part flush republishes
 * the playlist with an `#EXT-X-PART` entry (`BYTERANGE` into the *in-progress* segment's future
 * filename - it becomes a real, byte-identical file once the segment finalizes) plus an
 * `#EXT-X-PRELOAD-HINT` for whatever comes next, and resolves any HTTP request blocked in
 * [awaitAtLeast] waiting on exactly that part. This is what lets a viewer start playing a
 * segment's first couple hundred ms before the whole ~1s segment has finished encoding, instead
 * of only ever seeing data in whole-segment increments - cutting glass-to-glass latency from
 * roughly `segmentDurationUs * 3` down to close to `partTargetDurationUs * 3`
 * (`PART-HOLD-BACK`). The hls.js latency workarounds this needs client-side are documented in
 * `docs/quirks/live-hls.md`.
 *
 * This does NOT own a camera surface or an encoder - it's fed by [LivePipeline]'s drain thread.
 * [feed] is non-blocking: the muxing + file I/O happens on this class's own worker thread via a
 * bounded, drop-on-full queue, so a slow flash write can never stall the encoder drain.
 */
class LiveHlsRelay(
    private val liveDir: File,
    private val segmentDurationUs: Long = DEFAULT_SEGMENT_DURATION_US,
    private val partTargetDurationUs: Long = DEFAULT_PART_TARGET_DURATION_US,
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
    private var currentSegmentBuffer = SegmentBuffer()

    private data class PlaylistEntry(val seq: Int, val durationSec: Double)
    private val playlistSegments = ArrayDeque<PlaylistEntry>()
    private var mediaSequence = 0

    // In-progress segment's parts (byte ranges within currentSegmentBuffer) - cleared by
    // startNewSegmentLocked. Only the *current* segment ever advertises parts; once it finalizes
    // it gets a normal #EXTINF entry instead and its parts stop being advertised (see
    // writePlaylist). The next part always starts where the last one ended (or 0, for the
    // segment's first part), so that boundary is read off currentSegmentParts rather than tracked
    // as its own field.
    private data class PartEntry(val startOffset: Int, val endOffset: Int, val durationSec: Double, val independent: Boolean)
    private val currentSegmentParts = mutableListOf<PartEntry>()
    private var partStartPtsUs = -1L

    // A pending LL-HLS blocking-reload wait for `(msn, part)` - see [awaitAtLeast]. Every entry
    // gets scanned on each resolve pass regardless (isAvailableLocked depends on live relay state,
    // not a key lookup), and this list is never more than a handful of concurrent viewers long, so
    // a flat list checked in full costs nothing extra over a keyed map while needing far less
    // machinery.
    private data class PendingWait(val msn: Int, val part: Int?, val deferred: CompletableDeferred<Unit>)
    private val waiters = mutableListOf<PendingWait>()

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

    /** Byte range [startOffset, endOffsetExclusive) of the segment currently being built, before
     * it's finalized to disk - what serves an LL-HLS part/preload-hint fetch for the in-progress
     * segment (see LiveRoutes' segment route). Null if `seq` isn't the segment currently in
     * progress, or the requested range extends past what's been muxed so far (a stale/racing
     * request - the caller should treat this the same as "not found"). */
    fun readInProgressRange(seq: Int, startOffset: Int, endOffsetExclusive: Int): ByteArray? = synchronized(stateLock) {
        if (seq != segmentSeq || segmentStartPtsUs < 0) return@synchronized null
        val size = currentSegmentBuffer.size()
        if (startOffset < 0 || endOffsetExclusive > size || startOffset >= endOffsetExclusive) return@synchronized null
        currentSegmentBuffer.copyRange(startOffset, endOffsetExclusive)
    }

    /** Returns a [CompletableDeferred] that completes once `(msn, part)` becomes available, or
     * `null` if it already is (caller should proceed immediately rather than await). `part == null`
     * means "the whole segment `msn`", matching LL-HLS's blocking-reload semantics for a playlist
     * request with `_HLS_msn` but no `_HLS_part` - see LiveRoutes' playlist route. */
    fun awaitAtLeast(msn: Int, part: Int?): CompletableDeferred<Unit>? = synchronized(stateLock) {
        if (isAvailableLocked(msn, part)) return@synchronized null
        val deferred = CompletableDeferred<Unit>()
        waiters.add(PendingWait(msn, part, deferred))
        deferred
    }

    private fun isAvailableLocked(msn: Int, part: Int?): Boolean {
        if (msn < segmentSeq) return true // fully finalized (or already evicted/ancient - caller's problem)
        if (msn > segmentSeq) return false // not started yet
        // msn == segmentSeq: the in-progress segment - not "available" as a whole segment until it
        // finalizes (msn < segmentSeq becomes true then); individual parts can be ready sooner.
        return if (part == null) false else part < currentSegmentParts.size
    }

    private fun resolvePendingWaitersLocked() {
        if (waiters.isEmpty()) return
        val satisfied = waiters.filter { isAvailableLocked(it.msn, it.part) }
        waiters.removeAll(satisfied)
        for (wait in satisfied) wait.deferred.complete(Unit)
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
        if (partStartPtsUs < 0) partStartPtsUs = ptsUs
        muxer.writeAccessUnit(currentSegmentBuffer, data, ptsUs, isKeyFrame)
        lastAccessUnitPtsUs = ptsUs
        if (ptsUs - partStartPtsUs >= partTargetDurationUs) finalizePartLocked()
    }

    private fun startNewSegmentLocked(ptsUs: Long) {
        segmentStartPtsUs = ptsUs
        currentSegmentBuffer = SegmentBuffer()
        currentSegmentParts.clear()
        partStartPtsUs = -1L
        muxer.writeHeader(currentSegmentBuffer)
        // The real first access unit written right after carries its own PCR, so no separate PCR
        // packet here - SPS/PPS adds little and stays well within spec tolerance.
        codecConfigBytes?.let { muxer.writeAccessUnit(currentSegmentBuffer, it, ptsUs, isKeyFrame = false) }
    }

    /** Flushes the still-open part covering [the last part's end offset, current buffer size) -
     * called both on a normal ~[partTargetDurationUs] timer (from [handleAccessUnit]) and is
     * deliberately NOT called when a segment rotates (see [finalizeCurrentSegmentLocked]'s doc) -
     * assumes the caller already holds [stateLock]. */
    private fun finalizePartLocked() {
        val startOffset = currentSegmentParts.lastOrNull()?.endOffset ?: 0
        val endOffset = currentSegmentBuffer.size()
        if (endOffset <= startOffset || partStartPtsUs < 0) {
            partStartPtsUs = -1L
            return
        }
        val durationSec = ((lastAccessUnitPtsUs - partStartPtsUs) / 1_000_000.0).coerceAtLeast(0.001)
        val independent = currentSegmentParts.isEmpty() // only the segment's first part starts on a keyframe+config
        currentSegmentParts.add(PartEntry(startOffset, endOffset, durationSec, independent))
        partStartPtsUs = -1L
        writePlaylist()
        resolvePendingWaitersLocked()
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
        // Not force-flushing a trailing part here on purpose: whatever's accumulated since the
        // last part flush is still fully present in the finalized file written above (a plain,
        // non-LL fetch of the whole segment gets every byte regardless), it just never got
        // advertised as its own #EXT-X-PART - a harmless, sub-part-duration sliver of extra
        // latency for LL-HLS viewers on the rare access unit that lands exactly on a segment
        // rotation, not worth the extra bookkeeping to avoid.
        writePlaylist()
        resolvePendingWaitersLocked()
        segmentStartPtsUs = -1L
    }

    private fun writePlaylist() {
        // Max over the segments *currently* in the window, not an all-time high-water mark - an
        // old bug here let one long-ago outlier (irregular keyframe timing, a bitrate
        // reconfigure, a GC pause, etc.) permanently inflate this for the rest of the broadcast
        // session, long after the segment it came from had already rolled out of the playlist.
        // That matters: hls.js's LatencyController caps how much a rebuffer is allowed to grow
        // the low-latency sync target at exactly this value, so a stale inflated TARGETDURATION
        // would quietly widen how far behind live a *future* stall is allowed to push playback.
        val targetDurationSec = kotlin.math.ceil(
            playlistSegments.maxOfOrNull { it.durationSec } ?: (segmentDurationUs / 1_000_000.0)
        ).toLong().coerceAtLeast(1L)
        val partTargetSec = partTargetDurationUs / 1_000_000.0
        val partHoldBackSec = 3.0 * partTargetSec
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        // LL-HLS (EXT-X-PART/EXT-X-PRELOAD-HINT/BYTERANGE within EXT-X-PART) requires
        // EXT-X-VERSION:9.
        sb.append("#EXT-X-VERSION:9\n")
        sb.append("#EXT-X-TARGETDURATION:$targetDurationSec\n")
        sb.append("#EXT-X-PART-INF:PART-TARGET=${"%.3f".format(partTargetSec)}\n")
        sb.append("#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=${"%.3f".format(partHoldBackSec)}\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:$mediaSequence\n")
        for (entry in playlistSegments) {
            sb.append("#EXTINF:${"%.3f".format(entry.durationSec)},\n")
            sb.append("live-${entry.seq}.ts\n")
        }
        // In-progress segment: only #EXT-X-PART/preload-hint entries, no #EXTINF yet - it gets one
        // above (via playlistSegments) once finalizeCurrentSegmentLocked() promotes it.
        if (segmentStartPtsUs >= 0) {
            val uri = "live-$segmentSeq.ts"
            for (part in currentSegmentParts) {
                sb.append("#EXT-X-PART:DURATION=${"%.3f".format(part.durationSec)},URI=\"$uri\"")
                sb.append(",BYTERANGE=\"${part.endOffset - part.startOffset}@${part.startOffset}\"")
                if (part.independent) sb.append(",INDEPENDENT=YES")
                sb.append("\n")
            }
            val preloadStart = currentSegmentParts.lastOrNull()?.endOffset ?: 0
            sb.append("#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"$uri\",BYTERANGE-START=$preloadStart\n")
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

        /** Target segment length. Short segments = finer granularity: a single slow fetch costs
         * ~1s of buffer, not 2-3s, and the DVR window holds proportionally more segments.
         * [LivePipeline] requests an explicit sync frame on this cadence so a segment can always
         * rotate on a keyframe at roughly this interval regardless of what the encoder's
         * `KEY_I_FRAME_INTERVAL` hint actually does on a given device. */
        const val DEFAULT_SEGMENT_DURATION_US = 1_000_000L

        /** ~3 parts per 1s segment. Also the source of truth for LiveRoutes' blocking-reload
         * timeout, so the two stay in sync. */
        const val DEFAULT_PART_TARGET_DURATION_US = 333_000L
    }
}
