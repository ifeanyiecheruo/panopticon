package com.panopticon.phoneapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.state.AppMode

/**
 * Debug-build-only `adb` hook to run [GlSoakTest] - a sustained soak of the GPU texture fan-out
 * API surface (SurfaceTexture + GLES external-OES + EGL window surface on a MediaCodec input
 * surface + MediaMuxer rotation) that the planned recording pipeline will depend on.
 *
 *   adb shell am broadcast -a com.panopticon.phoneapp.DEBUG_GL_SOAK
 *   adb shell am broadcast -a com.panopticon.phoneapp.DEBUG_GL_SOAK --el duration_ms 120000
 *
 * Requests STANDBY first so the normal pipeline releases the camera. Never shipped:
 * declared only in src/debug/AndroidManifest.xml.
 */
class DebugGlSoakReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.panopticon.phoneapp.DEBUG_GL_SOAK") return
        val durationMs = intent.getLongExtra("duration_ms", 10 * 60_000L)
        val app = PanopticonApplication.from(context)
        app.requestMode(AppMode.STANDBY)
        Log.i(TAG, "STANDBY requested; starting GL soak (${durationMs}ms) in 2s")
        Handler(Looper.getMainLooper()).postDelayed({
            GlSoakTest(context.applicationContext, durationMs = durationMs).run { summary ->
                summary.lineSequence().forEach { Log.i(TAG, it) }
            }
        }, 2_000)
    }

    companion object {
        private const val TAG = "GlSoak"
    }
}
