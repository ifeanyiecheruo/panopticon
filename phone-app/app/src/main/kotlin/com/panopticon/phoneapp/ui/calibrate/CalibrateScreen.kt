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
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.calibration.CalibrationResult
import com.panopticon.phoneapp.calibration.CalibrationRunner
import com.panopticon.phoneapp.calibration.CalibrationStatus
import com.panopticon.phoneapp.ui.theme.PanopticonColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device Calibrate screen. Talks to [CalibrationRunner] directly (same
 * process - no HTTP), mirroring what a controller would drive over the
 * calibration routes. Shows the live per-step probe view + per-capability
 * pass/fail breakdown that HANDOFF-controller-ux.md notes the phone's own
 * screen has (the controller only summarises "N/M checks").
 */
@Composable
fun CalibrateScreen(app: PanopticonApplication) {
    val runner = app.calibrationRunner
    var status by remember { mutableStateOf<CalibrationStatus?>(runner.status(null)) }
    var result by remember { mutableStateOf<CalibrationResult?>(currentResult(runner)) }
    var runId by remember { mutableStateOf(status?.runId?.takeIf { status?.status == "running" }) }

    // Poll while a sweep is running; stop once it settles.
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
            delay(300)
        }
    }

    val running = status?.status == "running"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Calibration", color = PanopticonColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Sweeps every camera the device reports, comparing declared Camera2 capabilities " +
                "against what they actually do. Results are keyed by device model and shared with paired controllers.",
            color = PanopticonColors.textDim,
            fontSize = 13.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    when (val outcome = runner.start()) {
                        is CalibrationRunner.StartOutcome.Started -> {
                            runId = outcome.response.runId
                            status = runner.status(runId)
                        }
                        is CalibrationRunner.StartOutcome.AlreadyRunning -> {
                            runId = outcome.runId
                        }
                        is CalibrationRunner.StartOutcome.NoCameras -> Unit
                    }
                },
                enabled = !running,
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
            }
        }

        status?.let { s -> ProgressCard(s) }
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
            "Step ${s.stepsCompleted}/${s.stepsTotal}" +
                (s.currentStep?.let { " - $it" } ?: "") +
                (if (s.progressWithinStep.total > 0) " - check ${s.progressWithinStep.index}/${s.progressWithinStep.total}" else ""),
            color = PanopticonColors.textDim,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ResultCard(r: CalibrationResult) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.US) }
    CardBox(title = "Last result - ${fmt.format(Date(r.runAtMs))}") {
        val total = r.cameras.values.sumOf { c -> c.steps.values.sumOf { it.checksTotal } }
        val passed = r.cameras.values.sumOf { c -> c.steps.values.sumOf { it.checksPassed } }
        Text("$passed / $total checks passed across ${r.cameras.size} camera(s)", color = PanopticonColors.text, fontSize = 14.sp)

        r.cameras.forEach { (cameraId, cam) ->
            Text(
                "Camera $cameraId - ${cam.deviceIdentity.facing}" +
                    (cam.deviceIdentity.focalLengthMm?.let { " - %.1fmm".format(it) } ?: ""),
                color = PanopticonColors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
            cam.steps.forEach { (stepName, step) ->
                Text(
                    "  $stepName: ${step.checksPassed}/${step.checksTotal}",
                    color = PanopticonColors.textDim,
                    fontSize = 12.sp,
                )
                step.checks.forEach { check ->
                    Text(
                        "    ${if (check.ok) "OK " else "!! "}${check.name}: ${check.declared}",
                        color = if (check.ok) PanopticonColors.textFaint else PanopticonColors.warn,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
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
