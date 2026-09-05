package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.calibration.CalibrationCancelled
import com.panopticon.phoneapp.calibration.CalibrationRunner
import com.panopticon.phoneapp.http.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Device-wide calibration routes (phone-http-api.md "Calibration"). One sweep
 * at a time, keyed by `runId`; the last completed result is persisted so
 * `GET /api/calibration/result` (no `runId`) answers straight from disk
 * without re-running anything - that's what lets a controller ingest it
 * opportunistically right after pairing.
 */
fun Route.calibrationRoutes(runner: CalibrationRunner) {
    // Authenticated by the global installAuth() intercept.
    run {
        post("/api/calibration/start") {
            when (val outcome = runner.start()) {
                is CalibrationRunner.StartOutcome.Started ->
                    call.respond(outcome.response)
                is CalibrationRunner.StartOutcome.AlreadyRunning ->
                    call.respond(HttpStatusCode.Conflict, ErrorBody("calibration already running (runId=${outcome.runId})"))
                is CalibrationRunner.StartOutcome.CameraBusy ->
                    call.respond(HttpStatusCode.Conflict, ErrorBody(outcome.message))
                is CalibrationRunner.StartOutcome.NoCameras ->
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody(outcome.message))
            }
        }

        get("/api/calibration/status") {
            val runId = call.request.queryParameters["runId"]
            val status = runner.status(runId)
            if (status == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("no such calibration run"))
            } else {
                call.respond(status)
            }
        }

        delete("/api/calibration/{runId}") {
            val runId = call.parameters["runId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorBody("missing runId"))
            if (runner.cancel(runId)) {
                call.respond(CalibrationCancelled(true))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorBody("no running calibration with that runId"))
            }
        }

        get("/api/calibration/result") {
            val runId = call.request.queryParameters["runId"]
            when (val outcome = runner.result(runId)) {
                is CalibrationRunner.ResultOutcome.Completed ->
                    call.respond(outcome.result)
                is CalibrationRunner.ResultOutcome.NotCompleted ->
                    call.respond(HttpStatusCode.Conflict, ErrorBody("run not completed yet"))
                is CalibrationRunner.ResultOutcome.Unknown ->
                    call.respond(HttpStatusCode.NotFound, ErrorBody("no completed calibration result"))
            }
        }
    }
}
