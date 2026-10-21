package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.camera.CameraGlPipeline
import com.panopticon.phoneapp.http.ErrorBody
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.state.AppState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A still younger than this is shared rather than grabbed again, so any number of controllers
 *  polling one phone cost at most one grab a second between them. */
private const val SNAPSHOT_MAX_AGE_MS = 1_000L

/** One frame normally completes a grab in well under 100ms; this only bounds a camera that has
 *  stopped delivering frames (a pipeline restart), which is reported as retryable. */
private const val SNAPSHOT_WAIT_MS = 3_000L

private const val SNAPSHOT_RETRY_AFTER_MS = 2_000

/** Epoch ms the still's frame was rendered at, so a viewer can say how old the picture is. */
const val CAPTURED_AT_HEADER = "X-Captured-At-Ms"

/**
 * `GET /api/camera/snapshot` - a JPEG still of what RECORD is encoding, produced on demand
 * (docs/design/http-api.md "Stills while recording", docs/design/decisions/0015-recording-stills.md).
 *
 * The live view's stand-in while recording: the phone can't run the HLS encoder alongside the
 * recording one, but it can read back one frame every few seconds for whoever is looking. Only
 * RECORD serves it - LIVE has the real video, STANDBY has no camera open.
 *
 * [record] is looked up per request because the pipeline only exists while recording.
 */
fun Route.snapshotRoutes(record: () -> CameraGlPipeline?, appState: AppState) {
    // Authenticated by the global installAuth() intercept.
    get("/api/camera/snapshot") {
        if (appState.overheated) {
            return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorBody("phone is overheated; stills are unavailable until it cools"),
            )
        }
        if (appState.mode.value != AppMode.RECORD) {
            return@get call.respond(
                HttpStatusCode.Conflict,
                ErrorBody("not recording: stills are served in record mode only (use /live/* in live mode)"),
            )
        }
        val pending = record()?.requestSnapshot(SNAPSHOT_MAX_AGE_MS)
            ?: return@get call.retryLater("camera still starting")
        // A grab fails outright when the pipeline is torn down under it (a mode switch or a
        // restart), and times out when frames have stopped; to the caller both are "try again".
        val snap = try {
            withTimeoutOrNull(SNAPSHOT_WAIT_MS) { pending.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@get call.retryLater("camera not delivering frames")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(CAPTURED_AT_HEADER, snap.capturedAtMs.toString())
        call.respondBytes(snap.jpeg, ContentType.Image.JPEG)
    }
}

/** The same retryable `503` shape `POST /api/live/start` uses while its camera arms. */
private suspend fun ApplicationCall.retryLater(reason: String) {
    response.header(HttpHeaders.RetryAfter, (SNAPSHOT_RETRY_AFTER_MS / 1000).toString())
    respond(HttpStatusCode.ServiceUnavailable, LiveRetryBody(reason, SNAPSHOT_RETRY_AFTER_MS))
}
