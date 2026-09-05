package com.panopticon.phoneapp.ui.calibrate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.calibration.CalibrationResult
import com.panopticon.phoneapp.calibration.CalibrationRunner
import com.panopticon.phoneapp.calibration.CalibrationStatus
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.ui.theme.PanopticonColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device Calibrate screen. Talks to [CalibrationRunner] directly (same
 * process - no HTTP), mirroring what a controller would drive over the
 * calibration routes. A real sweep needs exclusive camera access, so it's only
 * available from STANDBY - recording has to be explicitly stopped first.
 */
@Composable
fun CalibrateScreen(app: PanopticonApplication) {
    val runner = app.calibrationRunner
    val mode by app.appState.mode.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<CalibrationStatus?>(runner.status(null)) }
    var result by remember { mutableStateOf<CalibrationResult?>(currentResult(runner)) }
    var runId by remember { mutableStateOf(status?.runId?.takeIf { status?.status == "running" }) }

    LaunchedEffect(runId) {
        if (runId == null) return@LaunchedEffect
        while (true) {
            val s = runner.status(runId)
            status = s
            if (s == null || s.status != "running") {
                result = currentResult(runner)
                runId = null
                break
            }
            delay(400)
        }
    }

    val running = status?.status == "running"
    val recording = mode == AppMode.RECORD

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Calibration", color = PanopticonColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Empirically probes every camera at every resolution: effective field of view per " +
                "zoom level, where optical zoom hands off to digital, whether a zoom rect's " +
                "position is honoured, and image sharpness. Results are keyed by device model " +
                "and shared with paired controllers.",
            color = PanopticonColors.textDim,
            fontSize = 13.sp,
        )

        if (recording) {
            CardBox(title = "Recording is active") {
                Text(
                    "Calibration (and live preview) need exclusive use of the camera. Stop " +
                        "recording to run a sweep; it stays stopped until you start it again.",
                    color = PanopticonColors.textDim,
                    fontSize = 12.sp,
                )
                Button(
                    onClick = { app.requestMode(AppMode.STANDBY) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PanopticonColors.rec,
                        contentColor = PanopticonColors.text,
                    ),
                ) { Text("Stop recording") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    when (val outcome = runner.start()) {
                        is CalibrationRunner.StartOutcome.Started -> {
                            runId = outcome.response.runId
                            status = runner.status(runId)
                        }
                        is CalibrationRunner.StartOutcome.AlreadyRunning -> runId = outcome.runId
                        else -> Unit
                    }
                },
                enabled = !running && !recording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PanopticonColors.accent,
                    contentColor = PanopticonColors.accentInk,
                ),
            ) {
                Text(if (result == null) "Run calibration" else "Re-run")
            }
            if (running) {
                OutlinedButton(onClick = { status?.runId?.let { runner.cancel(it) } }) {
                    Text("Cancel", color = PanopticonColors.text)
                }
            } else if (mode == AppMode.STANDBY) {
                OutlinedButton(onClick = { app.requestMode(AppMode.RECORD) }) {
                    Text("Resume recording", color = PanopticonColors.text)
                }
            }
        }

        status?.let { s -> if (s.status == "running") ProgressCard(s) }
        result?.let { r -> ResultCard(r) }
        if (result == null && !running) {
            Text(
                "No calibration has completed on this phone yet.",
                color = PanopticonColors.textFaint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ProgressCard(s: CalibrationStatus) {
    CardBox(title = "Run ${s.runId} - ${s.status}") {
        val camFrac = if (s.camerasTotal > 0) s.camerasCompleted.toFloat() / s.camerasTotal else 0f
        LinearProgressIndicator(
            progress = { camFrac.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = PanopticonColors.accent,
            trackColor = PanopticonColors.surface2,
        )
        Text(
            "Camera ${s.camerasCompleted}/${s.camerasTotal}" +
                (s.currentCameraId?.let { " (id $it)" } ?: ""),
            color = PanopticonColors.text,
            fontSize = 13.sp,
        )
        Text(
            "Resolution ${s.stepsCompleted}/${s.stepsTotal}" +
                (s.currentStep?.let { " - $it" } ?: "") +
                (if (s.progressWithinStep.total > 0) " - zoom ${s.progressWithinStep.index}/${s.progressWithinStep.total}" else ""),
            color = PanopticonColors.textDim,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ResultCard(r: CalibrationResult) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.US) }
    CardBox(title = "Last result - ${fmt.format(Date(r.runAtMs))}") {
        Text(
            "${r.deviceIdentity.manufacturer} ${r.deviceIdentity.model} - ${r.cameras.size} camera(s)",
            color = PanopticonColors.text,
            fontSize = 13.sp,
        )
        r.cameras.forEach { (cameraId, cam) ->
            val id = cam.deviceIdentity
            Text(
                "Camera $cameraId - ${id.facing}" +
                    (if (id.isLogicalMultiCam) " - logical (${id.physicalIds.size} physical)" else "") +
                    " - active ${id.activeArrayWidth}x${id.activeArrayHeight}",
                color = PanopticonColors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Line("optical zoom", "%.2fx .. %.2fx".format(cam.opticalRange.lo, cam.opticalRange.hi))
            Line("digital zoom", "%.2fx .. %.2fx".format(cam.digitalRange.lo, cam.digitalRange.hi))
            Line(
                "crossover",
                cam.crossoverRatio?.let { "%.2fx (%s)".format(it, cam.crossoverMethod) } ?: cam.crossoverMethod,
            )
            Line("zoom-rect position honoured", if (cam.positionHonored) "yes" else "NO ${cam.positionFailRatios.joinToString(prefix = "@", transform = { "%.1fx".format(it) })}")
            Line("quality collapse", cam.qualityCollapseRatio?.let { "from %.2fx".format(it) } ?: "not seen")
            cam.perResolution.forEach { (res, zm) ->
                val honored = zm.samples.count { it.ratioHonored }
                Line("  $res", "$honored/${zm.samples.size} zooms honoured")
            }
        }
    }
}

@Composable
private fun Line(k: String, v: String) {
    Text("$k: $v", color = PanopticonColors.textFaint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
private fun CardBox(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PanopticonColors.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = PanopticonColors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

private fun currentResult(runner: CalibrationRunner): CalibrationResult? =
    when (val outcome = runner.result(null)) {
        is CalibrationRunner.ResultOutcome.Completed -> outcome.result
        else -> null
    }
