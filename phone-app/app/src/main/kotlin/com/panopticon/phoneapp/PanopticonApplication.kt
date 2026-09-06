package com.panopticon.phoneapp

import android.app.Application
import com.panopticon.phoneapp.calibration.CalibrationRunner
import com.panopticon.phoneapp.calibration.CalibrationStore
import com.panopticon.phoneapp.clips.SegmentStore
import com.panopticon.phoneapp.pairing.ControllerRegistry
import com.panopticon.phoneapp.pairing.InviteManager
import com.panopticon.phoneapp.state.AppConfig
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.state.AppState

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
    val appState = AppState()

    /**
     * Registered by [com.panopticon.phoneapp.service.PanopticonService] so the
     * Compose UI can drive a mode change (e.g. the Calibrate screen's "Stop
     * recording" button) through exactly the same path `POST /api/mode` uses.
     */
    @Volatile
    var onModeChangeRequested: ((AppMode) -> Unit)? = null

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
    }

    companion object {
        fun from(context: android.content.Context): PanopticonApplication =
            context.applicationContext as PanopticonApplication
    }
}
