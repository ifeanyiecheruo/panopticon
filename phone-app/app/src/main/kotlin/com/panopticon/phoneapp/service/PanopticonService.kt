package com.panopticon.phoneapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import com.panopticon.phoneapp.state.ThermalGuard
import java.io.File

private const val TAG = "PanopticonService"
private const val NOTIFICATION_CHANNEL_ID = "panopticon_recording"
private const val NOTIFICATION_ID = 1001

/**
 * Shows up in `dumpsys power` and in battery attribution, so it is named for the thing it is
 * protecting rather than for the app.
 */
private const val WAKE_LOCK_TAG = "panopticon:camera-pipeline"

/** Shows up in `dumpsys wifi`; named, like [WAKE_LOCK_TAG], for what it protects. */
private const val WIFI_LOCK_TAG = "panopticon:http-server"

/**
 * The process's whole reason to exist, mirroring the old prototype's architecture: an unbound,
 * START_STICKY foreground service that owns the camera pipeline and starts the embedded HTTP
 * server in its own try/catch so a bind failure can never take down the camera pipeline itself.
 */
class PanopticonService : Service() {

    private lateinit var app: PanopticonApplication
    @Volatile private var cameraPipeline: CameraGlPipeline? = null
    private var httpServer: PanopticonHttpServer? = null
    private var thermalGuard: ThermalGuard? = null

    /**
     * Serialises every pipeline start/stop. Mode changes arrive on HTTP threads and the UI
     * thread, and thermal trips on the guard's own thread; without this a trip landing mid-way
     * through a mode switch could leave a pipeline running that nothing will ever stop.
     */
    private val pipelineLock = Any()

    /**
     * Held for as long as this service lives. A `camera` foreground service keeps the *process*
     * from being killed; it does nothing to stop the application processor suspending, and the
     * two are not the same thing. With the screen off this device attempts suspend every ~40s,
     * and after a few of those in a row the Exynos MFC encoder comes back unusable: every
     * `VIDIOC_QBUF` returns EINVAL, so the encoder accepts frames on its input surface and
     * emits nothing, until the supervisor's output-stall timeout tears the pipeline down and
     * rebuilds it. That is the shape of the stall - run uptimes quantised to multiples of the
     * suspend period, camera frames and capture results fresh at the moment of death, encoder
     * output four seconds stale. See docs/quirks/camera2-recording-pipeline.md.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Held for as long as this service lives, alongside [wakeLock]: keeping the CPU awake does not
     * keep the Wi-Fi radio associated. With the screen off the BLU G5 (API 28) drops off Wi-Fi
     * entirely while dozing - despite "keep Wi-Fi on during sleep" - and sometimes comes back
     * associated but with no route, so the HTTP server is up and nothing can reach it. A Wi-Fi
     * lock is what tells the platform this app needs the radio with the screen off.
     * See docs/quirks/android-service.md.
     */
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        app = PanopticonApplication.from(this)
        app.onModeChangeRequested = ::handleModeChanged
        app.onCameraConfigChanged = ::handleCameraConfigChanged
        createNotificationChannel()
        acquireWakeLock()
        acquireWifiLock()
        // Before onStartCommand brings the camera up: the first sample is synchronous, so a phone
        // that boots (or is restarted by START_STICKY) already hot never opens the camera at all.
        thermalGuard = ThermalGuard(applicationContext, app.appConfig, app.appState, ::handleThermalChanged)
            .also { it.start() }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            // No timeout: the lock's lifetime is the service's, and onDestroy releases it. A
            // timeout here would be a slow leak of exactly the failure it exists to prevent.
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "partial wake lock held for the camera pipeline")
        } catch (e: Exception) {
            // Recording without it still works, it just stalls every few minutes, so this is
            // worth a line in the log and not worth refusing to start over.
            Log.e(TAG, "could not acquire wake lock; expect encoder stalls with the screen off", e)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // FULL_HIGH_PERF, not FULL_LOW_LATENCY: low-latency only takes effect while the app is in the
    // foreground with the screen on, the opposite of when this lock matters. HIGH_PERF is
    // deprecated from API 34, where the platform manages the radio itself; on the older devices
    // that actually drop Wi-Fi it is the lock that keeps the radio up.
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            // No timeout, for the same reason as the wake lock: onDestroy releases it.
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Wi-Fi lock held for the HTTP server")
        } catch (e: Exception) {
            // Most devices keep Wi-Fi up without it; the ones that don't drop off the network
            // with the screen off. Worth a log line, not worth refusing to start over.
            Log.e(TAG, "could not acquire Wi-Fi lock; the phone may drop off Wi-Fi with the screen off", e)
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
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

        synchronized(pipelineLock) {
            if (cameraPipeline == null && !app.appState.overheated) startCameraPipeline()
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
                    cameraHealth = app.cameraHealth,
                    batteryCurrent = app.batteryCurrent,
                    onModeChanged = ::handleModeChanged,
                    onCameraConfigChanged = ::handleCameraConfigChanged,
                    liveProvider = { app.livePipeline },
                    recordProvider = { app.cameraGlPipeline },
                )
                server.start()
                httpServer = server
            } catch (e: Exception) {
                // A bind failure here must never take down the camera/recording pipeline -
                // see docs/quirks/android-service.md ("crashed process's socket isn't released fast enough").
                Log.e(TAG, "HTTP server failed to start after retries", e)
            }
        }

        return START_STICKY
    }

    private fun handleModeChanged(mode: AppMode) = synchronized(pipelineLock) {
        // Overheated: the mode is recorded (AppState already holds it) but nothing starts.
        // handleThermalChanged applies it once the phone has cooled.
        if (app.appState.overheated) return@synchronized
        applyMode(mode)
    }

    private fun applyMode(mode: AppMode) {
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
    private fun handleCameraConfigChanged(change: CameraConfigChange) = synchronized(pipelineLock) {
        // Nothing is running to reconfigure; the persisted config is picked up on resume.
        if (app.appState.overheated) return@synchronized
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

    /**
     * The thermal guard's edges (docs/design/decisions/0014-thermal-safety-shutdown.md).
     *
     * Tripped: stop everything that keeps the SoC busy - the recording pipeline (camera, GL,
     * encoder, motion analysis), the live pipeline, and a calibration sweep - and say why in the
     * notification. The HTTP server stays up, so the controller can see `overheated` and keep
     * syncing finished clips; so does the wake lock, without which the guard's own sampling
     * would stop with the CPU and nothing would notice the phone cooling.
     *
     * Cleared: re-apply whatever mode is current - the one in force at the trip, or whatever a
     * controller asked for since.
     */
    private fun handleThermalChanged(tripped: Boolean) = synchronized(pipelineLock) {
        if (tripped) {
            releaseCameraPipeline()
            stopLivePipeline()
            app.calibrationRunner.cancelActive()
            app.appState.setMotionActive(false)
            app.appState.setRecordingStatus(RecordingStatus.STOPPED)
            // Nothing is open to be unhealthy; the next pipeline reports for itself on resume.
            app.appState.setCameraHealthy(true)
        } else {
            applyMode(app.appState.mode.value)
        }
        updateNotification()
    }

    private fun releaseCameraPipeline() {
        // Detach before releasing: release() cancels the pipeline's supervisor, which reports
        // itself unhealthy on the way out, asynchronously. The callbacks below drop anything
        // from a pipeline that is no longer [cameraPipeline], so that stale "unhealthy" can't
        // overwrite the state set for the mode (or the thermal shutdown) that replaced it.
        val pipeline = cameraPipeline ?: return
        cameraPipeline = null
        app.cameraGlPipeline = null
        pipeline.release()
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
            videoResolution = cfg.videoResolution,
            rotationDegreesConfig = cfg.rotationDegrees,
            healthRegistry = app.cameraHealth,
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
        lateinit var pipeline: CameraGlPipeline
        pipeline = CameraGlPipeline(
            context = applicationContext,
            segmentsDir = app.segmentStore.segmentsDir,
            onStorageLow = { app.evictSegmentsAsync() },
            appConfig = app.appConfig,
            cameraId = app.cameraCatalog.resolveActiveId(cfg.activeCameraId),
            initialControls = cfg.cameraControls,
            healthRegistry = app.cameraHealth,
            onSegmentFinished = { file, createdAtMs, durationMs, width, height ->
                app.segmentStore.addSegment(file, createdAtMs, durationMs, width, height)
                Log.i(TAG, "segment finished: ${file.name} (${durationMs}ms, ${width}x$height, ${file.length()} bytes)")
                app.evictSegmentsAsync()
            },
            onHealthChanged = cb@{ healthy ->
                if (cameraPipeline !== pipeline) return@cb
                app.appState.setCameraHealthy(healthy)
                if (!healthy) {
                    app.appState.setRecordingStatus(RecordingStatus.UNAVAILABLE)
                    app.appState.setMotionActive(false)
                } else if (app.appState.mode.value == AppMode.RECORD) {
                    app.appState.setRecordingStatus(RecordingStatus.IDLE)
                }
            },
            onPhaseChanged = cb@{ recording ->
                if (cameraPipeline !== pipeline) return@cb
                if (app.appState.mode.value == AppMode.RECORD && app.appState.cameraHealthy.value) {
                    app.appState.setRecordingStatus(
                        if (recording) RecordingStatus.RECORDING else RecordingStatus.IDLE,
                    )
                }
                if (!recording) app.appState.setMotionActive(false)
            },
            onMotionChanged = cb@{ motion ->
                if (cameraPipeline !== pipeline) return@cb
                app.appState.setMotionActive(motion)
            },
        )
        cameraPipeline = pipeline
        app.cameraGlPipeline = pipeline
        pipeline.start()
    }

    override fun onDestroy() {
        app.onModeChangeRequested = null
        app.onCameraConfigChanged = null
        thermalGuard?.stop()
        thermalGuard = null
        synchronized(pipelineLock) {
            releaseCameraPipeline()
            stopLivePipeline()
        }
        httpServer?.stop()
        httpServer = null
        releaseWifiLock()
        releaseWakeLock()
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

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { Log.w(TAG, "could not update notification", it) }
    }

    private fun buildNotification(): Notification {
        val thermal = app.appState.thermalShutdown.value
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .apply {
                if (thermal.active) {
                    setContentTitle("Panopticon paused: overheating")
                    setContentText("Camera off (${thermal.readingText()}). Resumes ${thermal.resumeText()}.")
                } else {
                    setContentTitle("Panopticon is running")
                    setContentText("Recording and serving the phone HTTP API")
                }
            }
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
