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
import com.panopticon.phoneapp.MainActivity
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.R
import com.panopticon.phoneapp.camera.CameraPipeline
import com.panopticon.phoneapp.http.PanopticonHttpServer
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.state.RecordingStatus

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
    private var cameraPipeline: CameraPipeline? = null
    private var httpServer: PanopticonHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        app = PanopticonApplication.from(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
        )

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
                    clipStore = app.clipStore,
                    onModeChanged = ::handleModeChanged,
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
                if (cameraPipeline == null) startCameraPipeline()
            }
            AppMode.LIVE -> {
                // Stub for this slice: RECORD/LIVE stay mutually exclusive (per the architecture
                // doc), so tear down the recording pipeline. No real live encoder/relay exists
                // yet - out of scope here.
                cameraPipeline?.release()
                cameraPipeline = null
                app.appState.setRecordingStatus(RecordingStatus.IDLE)
            }
        }
    }

    private fun startCameraPipeline() {
        app.appState.setRecordingStatus(RecordingStatus.RECORDING)
        cameraPipeline = CameraPipeline(
            context = applicationContext,
            clipsDir = app.clipStore.clipsDir,
            onClipFinished = { file, createdAtMs, durationMs, width, height ->
                app.clipStore.addClip(file, createdAtMs, durationMs, width, height)
                Log.i(TAG, "clip finished: ${file.name} (${durationMs}ms, ${width}x$height, ${file.length()} bytes)")
            },
            onHealthChanged = { healthy ->
                app.appState.setCameraHealthy(healthy)
                if (!healthy) app.appState.setRecordingStatus(RecordingStatus.UNAVAILABLE)
                else if (app.appState.mode.value == AppMode.RECORD) app.appState.setRecordingStatus(RecordingStatus.RECORDING)
            },
        ).also { it.start() }
    }

    override fun onDestroy() {
        cameraPipeline?.release()
        cameraPipeline = null
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
