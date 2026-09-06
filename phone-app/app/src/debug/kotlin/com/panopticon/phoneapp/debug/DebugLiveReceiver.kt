package com.panopticon.phoneapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.state.AppMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-build-only `adb` hook that exercises the plain-HLS live pipeline end to end on-device,
 * without needing a paired controller:
 *
 *   adb shell am broadcast -n com.panopticon.phoneapp/.debug.DebugLiveReceiver \
 *       -a com.panopticon.phoneapp.DEBUG_LIVE
 *   adb shell am broadcast -n com.panopticon.phoneapp/.debug.DebugLiveReceiver \
 *       -a com.panopticon.phoneapp.DEBUG_LIVE --el duration_ms 30000
 *
 * Enters LIVE mode, starts broadcasting, then for `duration_ms` logs the playlist shape and
 * dumps the newest finalized segment to /sdcard/Download/live-dump-<n>.ts (pull + `ffprobe` it).
 * Returns to STANDBY and prints a LIVE PROBE PASS/FAIL verdict. Never shipped - declared only in
 * src/debug/AndroidManifest.xml.
 */
class DebugLiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.panopticon.phoneapp.DEBUG_LIVE") return
        val durationMs = intent.getLongExtra("duration_ms", 25_000L)
        val app = PanopticonApplication.from(context)
        Log.i(TAG, "LIVE requested; starting broadcast probe (${durationMs}ms) shortly")

        Handler(Looper.getMainLooper()).postDelayed({
            CoroutineScope(Dispatchers.Default).launch {
                runProbe(context.applicationContext, app, durationMs)
            }
        }, 3_000)
    }

    /** Force a fresh RECORD -> LIVE transition and wait for the service to wire up the pipeline.
     * The service's onModeChangeRequested hook may not exist yet on a cold, slow device when the
     * broadcast first lands, so a plain requestMode(LIVE) can silently no-op. */
    private suspend fun ensureLivePipeline(app: PanopticonApplication): Boolean {
        repeat(20) { attempt ->
            if (app.livePipeline != null) return true
            if (app.appState.mode.value == AppMode.LIVE) {
                // mode says LIVE but the service never acted - bounce it.
                app.requestMode(AppMode.STANDBY)
                delay(500)
            }
            app.requestMode(AppMode.LIVE)
            Log.i(TAG, "  waiting for live pipeline (attempt ${attempt + 1})")
            delay(1_000)
        }
        return app.livePipeline != null
    }

    private suspend fun runProbe(context: Context, app: PanopticonApplication, durationMs: Long) {
        // App-private external dir - writable with no runtime permission on every API level.
        // Pull with: adb pull /sdcard/Android/data/com.panopticon.phoneapp/files/<name>
        val dumpDir = context.getExternalFilesDir(null) ?: context.cacheDir
        var playlistsSeen = 0
        var maxSegments = 0
        var lastDumpedSeq = -1
        var bytesDumped = 0L
        var startFailed = false

        if (!ensureLivePipeline(app)) {
            Log.e(TAG, "LIVE PROBE FAIL: livePipeline still null after retries (service not up?)")
            return
        }
        val pipeline = app.livePipeline!!

        val started = pipeline.startBroadcasting()
        if (started <= 0) {
            startFailed = true
            Log.e(TAG, "LIVE PROBE FAIL: startBroadcasting() returned $started")
        }

        val deadline = System.currentTimeMillis() + durationMs
        while (!startFailed && System.currentTimeMillis() < deadline) {
            delay(2_000)
            pipeline.touch()
            val playlist = pipeline.currentPlaylist()
            if (playlist != null) {
                playlistsSeen++
                val segLines = playlist.lineSequence().filter { it.endsWith(".ts") }.toList()
                maxSegments = maxOf(maxSegments, segLines.size)
                val lastSeq = segLines.lastOrNull()
                    ?.removePrefix("live-")?.removeSuffix(".ts")?.toIntOrNull() ?: -1
                Log.i(TAG, "[+${(durationMs - (deadline - System.currentTimeMillis())) / 1000}s] " +
                    "playlist ${playlist.length}B, ${segLines.size} segments, newest live-$lastSeq.ts")
                if (lastSeq > lastDumpedSeq) {
                    val bytes = pipeline.readSegment(lastSeq)
                    if (bytes != null) {
                        runCatching {
                            File(dumpDir, "live-dump-$lastSeq.ts").writeBytes(bytes)
                            lastDumpedSeq = lastSeq
                            bytesDumped += bytes.size
                            Log.i(TAG, "  dumped live-dump-$lastSeq.ts (${bytes.size}B)")
                        }.onFailure { Log.w(TAG, "  dump failed", it) }
                    }
                }
            } else {
                Log.i(TAG, "  (no playlist yet)")
            }
        }

        pipeline.stopBroadcasting()
        app.requestMode(AppMode.STANDBY)

        val pass = !startFailed && playlistsSeen > 0 && maxSegments >= 2 && lastDumpedSeq >= 0
        Log.i(TAG, "==== LIVE PROBE ${if (pass) "PASS" else "FAIL"} ====")
        Log.i(TAG, "  playlists seen: $playlistsSeen, max segments in window: $maxSegments")
        Log.i(TAG, "  segments dumped: up to live-dump-$lastDumpedSeq.ts, ${bytesDumped}B total")
        Log.i(TAG, "  dump dir: ${dumpDir.absolutePath}")
    }

    companion object {
        private const val TAG = "LiveProbe"
    }
}
