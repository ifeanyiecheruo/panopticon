package com.panopticon.phoneapp.http

import android.content.Context
import android.util.Log
import com.panopticon.phoneapp.CameraConfigChange
import com.panopticon.phoneapp.calibration.CalibrationRunner
import com.panopticon.phoneapp.camera.CameraCatalog
import com.panopticon.phoneapp.camera.LivePipeline
import com.panopticon.phoneapp.clips.SegmentStore
import com.panopticon.phoneapp.http.routes.calibrationRoutes
import com.panopticon.phoneapp.http.routes.cameraRoutes
import com.panopticon.phoneapp.http.routes.segmentRoutes
import com.panopticon.phoneapp.http.routes.deviceRoutes
import com.panopticon.phoneapp.http.routes.liveRoutes
import com.panopticon.phoneapp.http.routes.modeRoutes
import com.panopticon.phoneapp.http.routes.pairingRoutes
import com.panopticon.phoneapp.pairing.ControllerRegistry
import com.panopticon.phoneapp.pairing.InviteManager
import com.panopticon.phoneapp.state.AppConfig
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.state.AppState
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.net.BindException

private const val TAG = "PanopticonHttpServer"
const val SERVER_PORT = 8080

/**
 * Embedded Ktor/Netty HTTP server implementing the subset of phone-http-api.md this vertical
 * slice covers. Bind-retry-on-crash-loop and explicit CORS header exposure are carried forward
 * from the old prototype's QUIRKS.md (see docs/quirks/android-service.md, and the CORS note in
 * docs/quirks/live-hls.md, for what was reconfirmed vs. carried forward without re-verification here).
 */
class PanopticonHttpServer(
    private val androidContext: Context,
    private val registry: ControllerRegistry,
    private val invites: InviteManager,
    private val appConfig: AppConfig,
    private val appState: AppState,
    private val segmentStore: SegmentStore,
    private val calibrationRunner: CalibrationRunner,
    private val cameraCatalog: CameraCatalog,
    private val onModeChanged: (AppMode) -> Unit,
    private val onCameraConfigChanged: (CameraConfigChange) -> Unit,
    private val liveProvider: () -> LivePipeline?,
) {
    private var engine: ApplicationEngine? = null

    /**
     * Old prototype's confirmed quirk: after this process crashes, Android's service
     * auto-restart can race the OS actually freeing the crashed process's listening port,
     * producing a `BindException` that crashes the restarted process too - a repeating crash
     * loop. Retry bind up to 20 attempts / ~30s total rather than a short budget.
     */
    fun start() {
        if (engine != null) return
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 20) {
            attempt++
            try {
                val server = embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0") {
                    configureServer()
                }
                server.start(wait = false)
                engine = server
                Log.i(TAG, "HTTP server listening on port $SERVER_PORT (attempt $attempt)")
                return
            } catch (e: Exception) {
                val isBindIssue = e is BindException || (e.cause is BindException)
                lastError = e
                if (!isBindIssue) throw e
                Log.w(TAG, "bind failed (attempt $attempt/20), retrying in 1.5s", e)
                Thread.sleep(1500)
            }
        }
        throw lastError ?: IllegalStateException("failed to bind HTTP server")
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        engine = null
    }

    private fun io.ktor.server.application.Application.configureServer() {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Log.e(TAG, "unhandled route exception", cause)
                call.respond(HttpStatusCode.InternalServerError, ErrorBody(cause.message ?: "internal error"))
            }
        }

        // Old prototype's confirmed quirk: a byte-range response that succeeds is still
        // unusable to the requester if Content-Range/Content-Length aren't explicitly exposed
        // via CORS - the fetch succeeds but the caller can't read the confirming headers back.
        // Relevant here for any controller doing ranged clip downloads across origins.
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            exposeHeader(HttpHeaders.ContentRange)
            exposeHeader(HttpHeaders.ContentLength)
        }

        install(PartialContent)

        installAuth(registry)

        routing {
            pairingRoutes(registry, invites, appConfig, androidContext)
            deviceRoutes(androidContext, appConfig, appState, segmentStore)
            modeRoutes(appState, onModeChanged)
            segmentRoutes(segmentStore)
            calibrationRoutes(calibrationRunner)
            cameraRoutes(androidContext, cameraCatalog, appConfig, onCameraConfigChanged)
            liveRoutes(liveProvider)
        }
    }
}
