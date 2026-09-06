package com.panopticon.phoneapp.ui.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.state.RecordingStatus
import com.panopticon.phoneapp.ui.theme.PanopticonColors

@Composable
fun HomeScreen(app: PanopticonApplication) {
    val recordingStatus by app.appState.recordingStatus.collectAsStateWithLifecycle()
    val cameraHealthy by app.appState.cameraHealthy.collectAsStateWithLifecycle()
    val motionActive by app.appState.motionActive.collectAsStateWithLifecycle()
    val config = app.appConfig.get()
    val usedBytes = app.segmentStore.totalBytes()
    val capBytes = config.storageCapBytes

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = config.deviceName,
            color = PanopticonColors.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            color = PanopticonColors.textDim,
            fontSize = 14.sp,
        )

        InfoCard(title = "Recording status") {
            val (label, color) = when {
                !cameraHealthy -> "UNAVAILABLE" to PanopticonColors.warn
                recordingStatus == RecordingStatus.RECORDING -> "REC" to PanopticonColors.rec
                recordingStatus == RecordingStatus.UNAVAILABLE -> "UNAVAILABLE" to PanopticonColors.warn
                recordingStatus == RecordingStatus.STOPPED -> "STOPPED" to PanopticonColors.textDim
                else -> "ARMED" to PanopticonColors.accent
            }
            Text(text = label, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                text = when {
                    recordingStatus == RecordingStatus.RECORDING ->
                        "Motion gate open - writing rotating ~10s clips."
                    recordingStatus == RecordingStatus.STOPPED ->
                        "Recording explicitly stopped - the camera is free for calibration / live preview. Re-arm from the Calibrate tab."
                    motionActive ->
                        "Motion detected - starting to record."
                    else ->
                        "Armed. Watching for motion (sensitivity: ${config.motionSensitivity}); records only while motion is present, plus a short tail."
                },
                color = PanopticonColors.textFaint,
                fontSize = 12.sp,
            )
        }

        InfoCard(title = "Storage") {
            val usedGb = usedBytes / 1_000_000_000.0
            val capGb = capBytes / 1_000_000_000.0
            Text(
                text = "%.2f GB / %.2f GB used".format(usedGb, capGb),
                color = PanopticonColors.text,
                fontSize = 16.sp,
            )
            Text(
                text = "${app.segmentStore.count()} segments on disk",
                color = PanopticonColors.textFaint,
                fontSize = 12.sp,
            )
        }

        InfoCard(title = "HTTP API") {
            Text(text = "Listening on port 8080", color = PanopticonColors.text, fontSize = 14.sp)
            Text(
                text = "Use the Connect tab to pair a controller.",
                color = PanopticonColors.textFaint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PanopticonColors.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, color = PanopticonColors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}
