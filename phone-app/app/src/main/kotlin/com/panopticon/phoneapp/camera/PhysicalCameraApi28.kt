package com.panopticon.phoneapp.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Isolated API-28 path for opening a capture session that is constrained to one
 * *physical* sub-camera of a logical multi-camera (`OutputConfiguration.setPhysicalCameraId`
 * + `SessionConfiguration`). Kept in its own class so the ART verifier only
 * loads it once the `SDK_INT >= P` check has passed - the same trap that bit
 * `CONTROL_ZOOM_RATIO` (see docs/QUIRKS.md).
 *
 * The device is still opened on the *logical* id; only the session's output
 * targets are pinned to the physical sensor.
 */
@RequiresApi(Build.VERSION_CODES.P)
internal object PhysicalCameraApi28 {

    fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        physicalId: String,
        executor: Executor,
        callback: CameraCaptureSession.StateCallback,
    ) {
        val configs = surfaces.map { s ->
            OutputConfiguration(s).apply { setPhysicalCameraId(physicalId) }
        }
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, executor, callback)
        device.createCaptureSession(sc)
    }
}
