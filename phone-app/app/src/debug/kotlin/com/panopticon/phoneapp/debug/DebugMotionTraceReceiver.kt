package com.panopticon.phoneapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.panopticon.phoneapp.motion.MotionAnalyzer
import com.panopticon.phoneapp.motion.MotionTrace
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-build-only `adb` hook that records the motion detector's exact input - the luma analysis
 * readback, before any encoder touches it - for offline replay (see [MotionTrace] and
 * `MotionReplayTest`).
 *
 *   adb shell am broadcast -n com.panopticon.phoneapp/.debug.DebugMotionTraceReceiver \
 *       -a com.panopticon.phoneapp.DEBUG_MOTION_TRACE_START [--ei every 1] [--ei scale 1] [--ei max_mb 8000]
 *   adb shell am broadcast -n com.panopticon.phoneapp/.debug.DebugMotionTraceReceiver \
 *       -a com.panopticon.phoneapp.DEBUG_MOTION_TRACE_STOP
 *   adb pull /sdcard/Android/data/com.panopticon.phoneapp/files/motion-trace
 *
 * Keeps one analysed frame in `every`, averaged down by `scale` in each direction. The defaults
 * keep everything the detector sees: 160x120 luma at the analysis rate (~15fps), ~1GB/hour.
 * Replay needs the detector's own resolution and rate, since tracking is tuned in pixels per frame.
 * Rolls to a new file every [FILE_ROLL_MS] and stops itself at `max_mb`. Frames are written on a
 * thread of its own and dropped, never waited for, when it falls behind: the analysis thread this
 * taps feeds the recording gate.
 *
 * File format (big-endian): header `"PMTR2"`, int width, int height (after `scale`), int channels (1); then per
 * frame: long elapsedRealtimeMs, long wallClockMs, byte flags (bit0 motion, bit1 suppressed, bit2 global
 * motion), short confirmedTracks, short candidateTracks, short activeBlocks, short movingBlocks,
 * width*height luma bytes in readback order (unrotated, GL bottom-up rows).
 * (`PMTR1`, from the previous detector, carried RGB and a float changedFraction instead.)
 */
class DebugMotionTraceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                val every = intent.getIntExtra("every", 1).coerceAtLeast(1)
                val scale = intent.getIntExtra("scale", 1).coerceAtLeast(1)
                val maxBytes = intent.getIntExtra("max_mb", 8_000).toLong() * 1_000_000L
                val dir = File(context.getExternalFilesDir(null), "motion-trace").apply { mkdirs() }
                synchronized(Companion) {
                    active?.stop()
                    active = TraceWriter(dir, every, scale, maxBytes).also { it.start() }
                }
                Log.i(TAG, "tracing every $every frame(s) at 1/$scale scale to $dir, cap ${maxBytes / 1_000_000}MB")
            }
            ACTION_STOP -> synchronized(Companion) {
                active?.stop()
                active = null
            }
        }
    }

    private class TraceWriter(private val dir: File, private val every: Int, private val scale: Int, private val maxBytes: Long) {
        private class Frame(val luma: ByteArray) {
            var elapsedMs = 0L
            var wallMs = 0L
            var flags = 0
            val counts = IntArray(4)
        }

        private val free = ArrayBlockingQueue<Frame>(POOL)
        private val full = ArrayBlockingQueue<Frame>(POOL)
        @Volatile private var running = true
        private var counter = 0L
        private val dropped = AtomicLong()
        private val written = AtomicLong()
        private var bytes = 0L
        private val thread = Thread(::drain, "motion-trace")

        private val sink = MotionTrace.Sink(::onFrame)

        fun start() {
            MotionTrace.sink = sink
            thread.start()
        }

        fun stop() {
            unhook()
            running = false
            thread.interrupt()
        }

        /** Only our own sink: a writer being replaced must not unhook its replacement. */
        private fun unhook() = synchronized(MotionTrace) { if (MotionTrace.sink === sink) MotionTrace.sink = null }

        /** GL thread: copy and hand off, never wait. */
        private fun onFrame(luma: ByteArray, width: Int, height: Int, nowMs: Long, v: MotionAnalyzer.Verdict) {
            if (counter++ % every != 0L) return
            val ow = width / scale
            val oh = height / scale
            val f = free.poll() ?: if (allocated < POOL) Frame(ByteArray(ow * oh)).also { allocated++ } else null
            if (f == null || f.luma.size != ow * oh) {
                dropped.incrementAndGet()
                return
            }
            val out = f.luma
            val n = scale * scale
            var d = 0
            for (oy in 0 until oh) {
                for (ox in 0 until ow) {
                    var sum = 0
                    for (dy in 0 until scale) {
                        var s = (oy * scale + dy) * width + ox * scale
                        for (dx in 0 until scale) { sum += luma[s].toInt() and 0xFF; s++ }
                    }
                    out[d++] = ((sum + n / 2) / n).toByte()
                }
            }
            f.elapsedMs = nowMs
            f.wallMs = System.currentTimeMillis()
            val r = v.detail
            f.flags = (if (v.motion) 1 else 0) or (if (v.suppressed) 2 else 0) or (if (r.globalMotion) 4 else 0)
            f.counts[0] = r.confirmedTracks; f.counts[1] = r.candidateTracks
            f.counts[2] = r.activeBlocks; f.counts[3] = r.movingBlocks
            this.width = ow
            this.height = oh
            if (!full.offer(f)) { free.offer(f); dropped.incrementAndGet() }
        }

        @Volatile private var width = 0
        @Volatile private var height = 0
        private var allocated = 0

        private fun drain() {
            var out: DataOutputStream? = null
            var fileStartMs = 0L
            try {
                while (running) {
                    val f = try { full.take() } catch (_: InterruptedException) { break }
                    try {
                        if (out == null || f.wallMs - fileStartMs >= FILE_ROLL_MS) {
                            out?.close()
                            fileStartMs = f.wallMs
                            val name = "trace_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(f.wallMs)) + ".bin"
                            out = DataOutputStream(BufferedOutputStream(FileOutputStream(File(dir, name)), 1 shl 18)).apply {
                                writeBytes("PMTR2"); writeInt(width); writeInt(height); writeInt(1)
                            }
                            Log.i(TAG, "writing $name (written ${written.get()}, dropped ${dropped.get()}, ${bytes / 1_000_000}MB)")
                        }
                        out.writeLong(f.elapsedMs); out.writeLong(f.wallMs); out.writeByte(f.flags)
                        for (c in f.counts) out.writeShort(c.coerceAtMost(Short.MAX_VALUE.toInt()))
                        out.write(f.luma)
                        bytes += 25 + f.luma.size
                        written.incrementAndGet()
                    } finally {
                        free.offer(f)
                    }
                    if (bytes >= maxBytes) {
                        Log.i(TAG, "size cap reached; stopping")
                        break
                    }
                    // The recorder shares this volume; a trace must never be what fills it
                    // (2026-09-29: it helped fill the disk, and the app then crashed on ENOSPC).
                    if (written.get() % 100L == 0L && dir.usableSpace < MIN_FREE_BYTES) {
                        Log.w(TAG, "under ${MIN_FREE_BYTES / 1_000_000}MB free; stopping")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "trace writer failed", e)
            } finally {
                unhook()
                runCatching { out?.close() }
                Log.i(TAG, "stopped: written ${written.get()}, dropped ${dropped.get()}, ${bytes / 1_000_000}MB")
            }
        }
    }

    companion object {
        private const val TAG = "MotionTrace"
        private const val ACTION_START = "com.panopticon.phoneapp.DEBUG_MOTION_TRACE_START"
        private const val ACTION_STOP = "com.panopticon.phoneapp.DEBUG_MOTION_TRACE_STOP"
        private const val POOL = 16
        private const val FILE_ROLL_MS = 10 * 60_000L
        private const val MIN_FREE_BYTES = 2_000_000_000L
        private var active: TraceWriter? = null
    }
}
