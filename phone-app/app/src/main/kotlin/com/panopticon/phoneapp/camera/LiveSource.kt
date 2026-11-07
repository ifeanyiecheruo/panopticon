package com.panopticon.phoneapp.camera

import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * What the live-view routes (`/api/live/start`, `/live/...`) serve from. Two pipelines provide
 * one: [LivePipeline] in LIVE mode, with an encoder of its own, and [CameraGlPipeline] in RECORD
 * mode through a [LiveTap] on the recording encoder's output.
 */
interface LiveSource {
    /** Idempotent. The viewer count (1) once broadcasting; 0 if the camera can't broadcast;
     *  [STILL_ARMING] while it is still coming up and the caller should retry. */
    suspend fun startBroadcasting(): Int

    suspend fun stopBroadcasting()

    /** Called on every playlist/segment GET, so the inactivity watchdog knows someone is watching. */
    fun touch()

    fun currentPlaylist(): String?

    fun readSegment(seq: Int): ByteArray?

    /** LL-HLS blocking-reload wait for `(msn, part)` - see [LiveHlsRelay.awaitAtLeast]. */
    fun relayAwaitAtLeast(msn: Int, part: Int?): CompletableDeferred<Unit>?

    /** LL-HLS in-progress part/preload-hint byte range - see [LiveHlsRelay.readInProgressRange]. */
    fun relayReadInProgressRange(seq: Int, startOffset: Int, endOffsetExclusive: Int): ByteArray?

    companion object {
        /** [startBroadcasting] return value: the camera is still coming up; retry shortly. */
        const val STILL_ARMING = -1
    }
}

/**
 * Live view off an encoder that is already running for another reason - RECORD's, which encodes
 * continuously whether or not a clip is being written. The drain thread hands every encoded
 * access unit to [feed]; while nobody is watching that is one volatile read, and while someone
 * is it goes to a [LiveHlsRelay] exactly as [LivePipeline]'s own encoder output does. No second
 * encoder, no camera change: the view is the recording, as it is being recorded.
 *
 * The relay begins on the next keyframe (it drops what comes before), and [requestKeyFrame] asks
 * the encoder for one straight away so a viewer isn't kept waiting for the 1s GOP to come round.
 * Like [LivePipeline], an [inactivityTimeoutMs] watchdog stops the relay once no viewer has
 * fetched anything for that long.
 */
internal class LiveTap(
    private val liveDir: File,
    /** The encoder has produced its output format, so there is something to broadcast. */
    private val isProducing: () -> Boolean,
    private val requestKeyFrame: () -> Unit,
    private val onBroadcastingChanged: (Boolean) -> Unit,
    private val inactivityTimeoutMs: Long = 15_000L,
) : LiveSource {
    @Volatile private var relay: LiveHlsRelay? = null
    @Volatile private var lastAccessMs = 0L
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null
    /** "<w>x<h>" of the stream the relay was started on - see [outputSizeChanged]. */
    @Volatile private var relaySize: String? = null
    @Volatile private var currentSize: String? = null

    val isBroadcasting: Boolean get() = relay != null

    override suspend fun startBroadcasting(): Int {
        touch()
        synchronized(lock) {
            if (relay != null) return 1
            if (!isProducing()) return LiveSource.STILL_ARMING
            relay = LiveHlsRelay(liveDir)
            relaySize = currentSize
            watchdog = scope.launch { watch() }
        }
        runCatching(requestKeyFrame).onFailure { Log.w(TAG, "keyframe request failed", it) }
        onBroadcastingChanged(true)
        Log.i(TAG, "live view started off the recording encoder ($currentSize)")
        return 1
    }

    override suspend fun stopBroadcasting() = stop("stopped")

    override fun touch() {
        lastAccessMs = SystemClock.elapsedRealtime()
    }

    /** Drain thread, once per encoded access unit. Cheap when nobody is watching. */
    fun feed(data: ByteArray, presentationTimeUs: Long, flags: Int, format: MediaFormat) {
        relay?.feed(data, presentationTimeUs, flags, format)
    }

    /**
     * The pipeline is (re)starting its encoder at [width] x [height]. The relay carries the
     * stream's SPS/PPS from its first sample, so it survives a restart at the same size (the
     * player rides out the gap), but not a new size: then it stops, and the viewer starts over.
     */
    fun outputSizeChanged(width: Int, height: Int) {
        val size = "${width}x$height"
        currentSize = size
        if (relay != null && relaySize != size) stop("output size changed to $size")
    }

    /** The pipeline is going away. Doesn't block the caller: the relay's worker can take a
     *  moment to drain, and this runs on whatever thread is tearing the pipeline down. */
    fun close() {
        scope.cancel()
        val r = detach() ?: return
        Thread({ finish(r, "pipeline released") }, "PanopticonLiveTapStop").start()
    }

    private suspend fun watch() {
        while (scope.isActive && relay != null) {
            delay(WATCHDOG_POLL_MS)
            if (SystemClock.elapsedRealtime() - lastAccessMs > inactivityTimeoutMs) {
                stop("no viewer for ${inactivityTimeoutMs}ms")
                return
            }
        }
    }

    private fun stop(reason: String) {
        val r = detach() ?: return
        finish(r, reason)
    }

    /** Takes the relay out of service, so [feed] and the routes stop seeing it at once. */
    private fun detach(): LiveHlsRelay? = synchronized(lock) {
        watchdog?.cancel()
        watchdog = null
        relay.also { relay = null; relaySize = null }
    }

    private fun finish(r: LiveHlsRelay, reason: String) {
        r.stop()
        onBroadcastingChanged(false)
        Log.i(TAG, "live view off the recording encoder ended: $reason")
    }

    override fun currentPlaylist(): String? = relay?.currentPlaylist()

    override fun readSegment(seq: Int): ByteArray? = relay?.readSegment(seq)

    override fun relayAwaitAtLeast(msn: Int, part: Int?): CompletableDeferred<Unit>? = relay?.awaitAtLeast(msn, part)

    override fun relayReadInProgressRange(seq: Int, startOffset: Int, endOffsetExclusive: Int): ByteArray? =
        relay?.readInProgressRange(seq, startOffset, endOffsetExclusive)

    private companion object {
        const val TAG = "LiveTap"
        const val WATCHDOG_POLL_MS = 5_000L
    }
}
