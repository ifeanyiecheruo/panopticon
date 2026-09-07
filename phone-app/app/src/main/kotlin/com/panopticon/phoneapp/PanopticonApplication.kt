package com.panopticon.phoneapp

import android.app.Application
import com.panopticon.phoneapp.calibration.CalibrationRunner
import com.panopticon.phoneapp.calibration.CalibrationStore
import com.panopticon.phoneapp.camera.CameraCatalog
import com.panopticon.phoneapp.camera.CameraGlPipeline
import com.panopticon.phoneapp.camera.LivePipeline
import com.panopticon.phoneapp.clips.SegmentStore
import com.panopticon.phoneapp.pairing.ControllerRegistry
import com.panopticon.phoneapp.pairing.InviteManager
import com.panopticon.phoneapp.state.AppConfig
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.state.AppState

/** What changed in `DeviceConfig` that the running camera pipeline needs to react to. */
enum class CameraConfigChange {
    /** `activeCameraId` changed - a disruptive reconfigure: the pipeline is rebuilt. */
    ACTIVE_CAMERA,

    /** `cameraControls` changed - a light re-issue of the repeating request. */
    CONTROLS,
}

/**
 * Holds the process-wide singletons shared between the foreground service (which owns the
 * camera + HTTP server) and the Compose UI (which reads/writes the same state over loopback-free
 * direct calls, since they run in the same process). No DI framework - deliberately simple for
 * this vertical slice.
 */
class PanopticonApplication : Application() {

    lateinit var appConfig: AppConfig
        private set
    lateinit var controllerRegistry: ControllerRegistry
        private set
    lateinit var inviteManager: InviteManager
        private set
    lateinit var segmentStore: SegmentStore
        private set
    lateinit var calibrationRunner: CalibrationRunner
        private set
    lateinit var cameraCatalog: CameraCatalog
        private set
    val appState = AppState()

    /**
     * Registered by [com.panopticon.phoneapp.service.PanopticonService] so the
     * Compose UI can drive a mode change (e.g. the Calibrate screen's "Stop
     * recording" button) through exactly the same path `POST /api/mode` uses.
     */
    @Volatile
    var onModeChangeRequested: ((AppMode) -> Unit)? = null

    /**
     * The LIVE-mode camera pipeline, or null whenever the phone isn't in LIVE mode.
     * [com.panopticon.phoneapp.service.PanopticonService] creates it on the switch into LIVE and
     * releases it on the way out; the HTTP live routes read it through here.
     */
    @Volatile
    var livePipeline: LivePipeline? = null

    /**
     * The RECORD-mode pipeline, or null outside RECORD. Registered by
     * [com.panopticon.phoneapp.service.PanopticonService] the same way as [livePipeline] so the
     * `/api/camera/state` route can push a control change onto whichever pipeline is running.
     */
    @Volatile
    var cameraGlPipeline: CameraGlPipeline? = null

    /**
     * Registered by [com.panopticon.phoneapp.service.PanopticonService] so the camera-selection /
     * manual-control routes can drive a reconfigure through the service (which owns the pipelines),
     * mirroring [onModeChangeRequested].
     */
    @Volatile
    var onCameraConfigChanged: ((CameraConfigChange) -> Unit)? = null

    fun requestMode(mode: AppMode) {
        if (appState.mode.value == mode) return
        appState.setMode(mode)
        onModeChangeRequested?.invoke(mode)
    }

    override fun onCreate() {
        super.onCreate()
        appConfig = AppConfig(this)
        controllerRegistry = ControllerRegistry(this)
        inviteManager = InviteManager()
        segmentStore = SegmentStore(this)
        segmentStore.reconcile()
        calibrationRunner = CalibrationRunner(this, CalibrationStore(this), appState)
        cameraCatalog = CameraCatalog(this)
    }

    companion object {
        fun from(context: android.content.Context): PanopticonApplication =
            context.applicationContext as PanopticonApplication
    }
}
