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
 * GET/POST /api/mode. For this vertical slice, switching to "live" is a stub: it flips the mode
 * flag (and the recording pipeline pauses, since RECORD/LIVE are still mutually exclusive per the
 * architecture doc) but there is no real HLS encoder/relay behind it yet - out of scope here.
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
                else -> null
            }
            if (requested == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("mode must be 'record' or 'live'"))
                return@post
            }
            if (requested != appState.mode.value) {
                appState.setMode(requested)
                onModeChanged(requested)
            }
            call.respond(ModeBody(requested.name.lowercase()))
        }
    }
}
