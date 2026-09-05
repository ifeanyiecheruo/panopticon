package com.panopticon.phoneapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.calibration.CalibrationRunner
import com.panopticon.phoneapp.state.AppMode

/**
 * Debug-build-only `adb` hook for driving a calibration sweep on a device
 * whose Compose UI can't be reached by uiautomator/screencap.
 *
 *   adb shell am broadcast -a com.panopticon.phoneapp.DEBUG_STANDBY
 *   adb shell am broadcast -a com.panopticon.phoneapp.DEBUG_CALIBRATE
 *   adb shell am broadcast -a com.panopticon.phoneapp.DEBUG_CAL_STATUS
 *   adb shell am broadcast -a com.panopticon.phoneapp.DEBUG_RECORD
 *
 * Never shipped: declared only in src/debug/AndroidManifest.xml.
 */
class DebugCalibrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = PanopticonApplication.from(context)
        when (intent.action) {
            "com.panopticon.phoneapp.DEBUG_STANDBY" -> {
                app.requestMode(AppMode.STANDBY)
                Log.i(TAG, "mode -> STANDBY")
            }
            "com.panopticon.phoneapp.DEBUG_RECORD" -> {
                app.requestMode(AppMode.RECORD)
                Log.i(TAG, "mode -> RECORD")
            }
            "com.panopticon.phoneapp.DEBUG_CALIBRATE" -> {
                app.requestMode(AppMode.STANDBY)
                when (val o = app.calibrationRunner.start()) {
                    is CalibrationRunner.StartOutcome.Started ->
                        Log.i(TAG, "started runId=${o.response.runId} cameras=${o.response.cameraIds}")
                    is CalibrationRunner.StartOutcome.AlreadyRunning ->
                        Log.i(TAG, "already running runId=${o.runId}")
                    is CalibrationRunner.StartOutcome.CameraBusy ->
                        Log.w(TAG, "camera busy: ${o.message}")
                    is CalibrationRunner.StartOutcome.NoCameras ->
                        Log.w(TAG, "no cameras: ${o.message}")
                }
            }
            "com.panopticon.phoneapp.DEBUG_CAL_STATUS" ->
                Log.i(TAG, "status: ${app.calibrationRunner.status(null)}")
        }
    }

    companion object {
        private const val TAG = "DebugCal"
    }
}
