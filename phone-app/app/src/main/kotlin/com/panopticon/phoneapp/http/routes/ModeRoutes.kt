package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.http.ErrorBody
import com.panopticon.phoneapp.state.AppMode
import com.panopticon.phoneapp.state.AppState
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class ModeBody(val mode: String)

/**
 * GET/POST /api/mode.
 *
 * RECORD is sticky and takes precedence over every other camera-using feature.
 * Transitions:
 *  - `standby`  - always allowed. This is the *explicit stop* that frees the
 *                 camera for calibration / live preview.
 *  - `record`   - always allowed (re-arm).
 *  - `live`     - allowed only from STANDBY (or LIVE); from RECORD it's a 409,
 *                 the caller must stop recording first. `live` itself is still a
 *                 stub - no real HLS encoder/relay behind it yet.
 *
 * While the thermal guard has the phone shut down, `record`/`standby` are recorded and applied
 * on cooldown, and `live` is a 503 (docs/design/decisions/0014-thermal-safety-shutdown.md).
 */
fun Route.modeRoutes(appState: AppState, onModeChanged: (AppMode) -> Unit) {
    // Authenticated by the global installAuth() intercept.
    run {
        get("/api/mode") {
            call.respond(ModeBody(appState.mode.value.name.lowercase()))
        }

        post("/api/mode") {
            val body = call.receive<ModeBody>()
            val requested = when (body.mode.lowercase()) {
                "record" -> AppMode.RECORD
                "live" -> AppMode.LIVE
                "standby" -> AppMode.STANDBY
                else -> null
            }
            if (requested == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("mode must be 'record', 'live', or 'standby'"))
                return@post
            }

            // Overheated: `record` and `standby` are accepted and take effect once the phone cools
            // (record is the unattended resting mode - asking for it while hot is a reasonable
            // thing to queue). `live` is interactive and would come back to a viewer long gone,
            // so it is refused outright.
            if (requested == AppMode.LIVE && appState.overheated) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorBody("phone is overheated; live preview is unavailable until it cools"),
                )
                return@post
            }

            val current = appState.mode.value
            if (requested == AppMode.LIVE && current == AppMode.RECORD) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorBody("stop recording first: POST /api/mode {\"mode\":\"standby\"}"),
                )
                return@post
            }

            if (requested != current) {
                appState.setMode(requested)
                onModeChanged(requested)
            }
            call.respond(ModeBody(requested.name.lowercase()))
        }
    }
}
