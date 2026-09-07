package com.panopticon.phoneapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.panopticon.phoneapp.CameraConfigChange
import com.panopticon.phoneapp.MainActivity
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.R
import com.panopticon.phoneapp.camera.CameraGlPipeline
import com.panopticon.phoneapp.camera.LivePipeline
import com.panopticon.phoneapp.http.PanopticonHttpServer
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.state.RecordingStatus
import java.io.File

private const val TAG = "PanopticonService"
private const val NOTIFICATION_CHANNEL_ID = "panopticon_recording"
private const val NOTIFICATION_ID = 1001

/**
 * The process's whole reason to exist, mirroring the old prototype's architecture: an unbound,
 * START_STICKY foreground service that owns the camera pipeline and starts the embedded HTTP
 * server in its own try/catch so a bind failure can never take down the camera pipeline itself.
 */
class PanopticonService : Service() {

    private lateinit var app: PanopticonApplication
    private var cameraPipeline: CameraGlPipeline? = null
    private var httpServer: PanopticonHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        app = PanopticonApplication.from(this)
        app.onModeChangeRequested = ::handleModeChanged
        app.onCameraConfigChanged = ::handleCameraConfigChanged
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The 3-arg startForeground(id, notification, foregroundServiceType) overload doesn't
        // exist before API 29 (Q) - it's not just that the type value is ignored, the method
        // itself throws NoSuchMethodError on older devices (confirmed on a real API 28 device).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (cameraPipeline == null) {
            startCameraPipeline()
        }

        if (httpServer == null) {
            try {
                val server = PanopticonHttpServer(
                    androidContext = applicationContext,
                    registry = app.controllerRegistry,
                    invites = app.inviteManager,
                    appConfig = app.appConfig,
                    appState = app.appState,
                    segmentStore = app.segmentStore,
                    calibrationRunner = app.calibrationRunner,
                    cameraCatalog = app.cameraCatalog,
                    onModeChanged = ::handleModeChanged,
                    onCameraConfigChanged = ::handleCameraConfigChanged,
                    liveProvider = { app.livePipeline },
                )
                server.start()
                httpServer = server
            } catch (e: Exception) {
                // A bind failure here must never take down the camera/recording pipeline -
                // see docs/QUIRKS.md ("crashed process's socket isn't released fast enough").
                Log.e(TAG, "HTTP server failed to start after retries", e)
            }
        }

        return START_STICKY
    }

    private fun handleModeChanged(mode: AppMode) {
        when (mode) {
            AppMode.RECORD -> {
                stopLivePipeline()
                if (cameraPipeline == null) startCameraPipeline()
            }
            AppMode.LIVE -> {
                // RECORD and LIVE are mutually exclusive - tear the recording pipeline down and
                // bring up the live one (armed-idle; POST /api/live/start begins broadcasting).
                releaseCameraPipeline()
                app.appState.setMotionActive(false)
                app.appState.setRecordingStatus(RecordingStatus.IDLE)
                startLivePipeline()
            }
            AppMode.STANDBY -> {
                // Explicit stop: fully release the camera so a calibration sweep can take it.
                releaseCameraPipeline()
                stopLivePipeline()
                app.appState.setMotionActive(false)
                app.appState.setRecordingStatus(RecordingStatus.STOPPED)
            }
        }
    }

    /**
     * React to a `/api/cameras/active` or `/api/camera/state` change. An active-camera change is
     * a disruptive reconfigure (rebuild whichever pipeline is running, matching how a mode switch
     * tears down and brings back up); a controls change is a light re-issue of the repeating
     * request. STANDBY has no pipeline - the persisted `DeviceConfig` is picked up next start.
     */
    private fun handleCameraConfigChanged(change: CameraConfigChange) {
        when (change) {
            CameraConfigChange.ACTIVE_CAMERA -> when (app.appState.mode.value) {
                AppMode.RECORD -> {
                    releaseCameraPipeline()
                    startCameraPipeline()
                }
                AppMode.LIVE -> {
                    stopLivePipeline()
                    startLivePipeline()
                }
                AppMode.STANDBY -> Unit
            }
            CameraConfigChange.CONTROLS -> {
                val spec = app.appConfig.get().cameraControls
                cameraPipeline?.applyControls(spec)
                app.livePipeline?.applyControls(spec)
            }
        }
    }

    private fun releaseCameraPipeline() {
        cameraPipeline?.release()
        cameraPipeline = null
        app.cameraGlPipeline = null
    }

    private fun startLivePipeline() {
        if (app.livePipeline != null) return
        app.appState.setLiveViewers(0)
        val cfg = app.appConfig.get()
        app.livePipeline = LivePipeline(
            context = applicationContext,
            liveDir = File(applicationContext.cacheDir, "live"),
            cameraId = app.cameraCatalog.resolveActiveId(cfg.activeCameraId),
            initialControls = cfg.cameraControls,
            onHealthChanged = { healthy -> app.appState.setCameraHealthy(healthy) },
            onBroadcastingChanged = { broadcasting ->
                app.appState.setLiveViewers(if (broadcasting) 1 else 0)
            },
        ).also { it.start() }
    }

    private fun stopLivePipeline() {
        app.livePipeline?.release()
        app.livePipeline = null
        app.appState.setLiveViewers(0)
    }

    private fun startCameraPipeline() {
        // Starts ARMED (analysing for motion), not RECORDING - the motion gate
        // decides when a segment is actually written.
        app.appState.setRecordingStatus(RecordingStatus.IDLE)
        app.appState.setMotionActive(false)
        val cfg = app.appConfig.get()
        cameraPipeline = CameraGlPipeline(
            context = applicationContext,
            segmentsDir = app.segmentStore.segmentsDir,
            appConfig = app.appConfig,
            cameraId = app.cameraCatalog.resolveActiveId(cfg.activeCameraId),
            initialControls = cfg.cameraControls,
            onSegmentFinished = { file, createdAtMs, durationMs, width, height ->
                app.segmentStore.addSegment(file, createdAtMs, durationMs, width, height)
                Log.i(TAG, "segment finished: ${file.name} (${durationMs}ms, ${width}x$height, ${file.length()} bytes)")
            },
            onHealthChanged = { healthy ->
                app.appState.setCameraHealthy(healthy)
                if (!healthy) {
                    app.appState.setRecordingStatus(RecordingStatus.UNAVAILABLE)
                    app.appState.setMotionActive(false)
                } else if (app.appState.mode.value == AppMode.RECORD) {
                    app.appState.setRecordingStatus(RecordingStatus.IDLE)
                }
            },
            onPhaseChanged = { recording ->
                if (app.appState.mode.value == AppMode.RECORD && app.appState.cameraHealthy.value) {
                    app.appState.setRecordingStatus(
                        if (recording) RecordingStatus.RECORDING else RecordingStatus.IDLE,
                    )
                }
                if (!recording) app.appState.setMotionActive(false)
            },
            onMotionChanged = { motion -> app.appState.setMotionActive(motion) },
        ).also {
            app.cameraGlPipeline = it
            it.start()
        }
    }

    override fun onDestroy() {
        app.onModeChangeRequested = null
        app.onCameraConfigChanged = null
        releaseCameraPipeline()
        stopLivePipeline()
        httpServer?.stop()
        httpServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Panopticon recording",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Panopticon is running")
            .setContentText("Recording and serving the phone HTTP API")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: android.content.Context) {
            val intent = Intent(context, PanopticonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
