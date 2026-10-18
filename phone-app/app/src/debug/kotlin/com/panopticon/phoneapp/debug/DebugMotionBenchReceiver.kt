package com.panopticon.phoneapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.panopticon.phoneapp.motion.MotionDetector
import java.util.Locale
import java.util.Random

/**
 * Debug-build-only `adb` hook that times the motion detector on this device, on generated
 * frames, so its cost can be measured on the phones that run it rather than on a desktop JVM:
 *
 *   adb shell am broadcast -n com.panopticon.phoneapp/.debug.DebugMotionBenchReceiver \
 *       -a com.panopticon.phoneapp.DEBUG_MOTION_BENCH [--ei frames 1500]
 *   adb logcat -s MotionBench
 *
 * Scenes, at 160x120: a still textured scene with sensor noise (the idle cost - what a quiet
 * camera pays all day), the same with a person-sized object walking through (one active
 * region), and with rain over the whole frame (every block active - the worst case). Each runs
 * twice and the second, JIT-warm pass is reported. Runs on a background thread at normal
 * priority, alongside whatever the camera pipeline is doing.
 */
class DebugMotionBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val frames = intent.getIntExtra("frames", 1500).coerceIn(100, 20_000)
        Thread({ run(frames) }, "motion-bench").start()
    }

    private fun run(frames: Int) {
        val w = 160
        val h = 120
        val rnd = Random(1)
        val bg = FloatArray(w * h)
        // Blobby texture, features a few pixels across.
        val cw = w / 3 + 2
        val coarse = FloatArray(cw * (h / 3 + 2)) { 110 + (rnd.nextFloat() * 2 - 1) * 50 }
        for (y in 0 until h) for (x in 0 until w) {
            val gx = x / 3f; val gy = y / 3f; val x0 = gx.toInt(); val y0 = gy.toInt(); val ax = gx - x0; val ay = gy - y0
            val top = coarse[y0 * cw + x0] * (1 - ax) + coarse[y0 * cw + x0 + 1] * ax
            val bot = coarse[(y0 + 1) * cw + x0] * (1 - ax) + coarse[(y0 + 1) * cw + x0 + 1] * ax
            bg[y * w + x] = top * (1 - ay) + bot * ay
        }
        // Pre-generate a loop of frames so generation is not what is timed.
        fun scene(kind: String): Array<ByteArray> = Array(64) { t ->
            val f = bg.copyOf()
            if (kind == "walker") {
                val x0 = (t * 2) % (w - 12)
                for (y in 50 until 76) for (x in x0 until x0 + 12) f[y * w + x] = 40f + ((x * 7 + y * 13) % 50)
            }
            if (kind == "rain") repeat(40) {
                val x = rnd.nextInt(w); val y = rnd.nextInt(h - 8)
                for (k in 0 until 8) f[(y + k) * w + x] += 60f
            }
            ByteArray(w * h) { i -> (f[i] + rnd.nextGaussian() * 3).toInt().coerceIn(0, 255).toByte() }
        }
        val report = StringBuilder()
        for (kind in listOf("idle", "walker", "rain")) {
            val loop = scene(kind)
            var perFrameUs = 0.0
            repeat(2) {
                val d = MotionDetector("medium")
                val t0 = System.nanoTime()
                for (t in 0 until frames) d.accept(loop[t % loop.size], w, h, w, t * 66L)
                perFrameUs = (System.nanoTime() - t0) / 1e3 / frames
            }
            report.append(String.format(Locale.US, "%s %.2fms/frame; ", kind, perFrameUs / 1000))
        }
        Log.i(TAG, "medium, 160x120, $frames frames per pass: $report")
    }

    private companion object {
        const val TAG = "MotionBench"
    }
}
