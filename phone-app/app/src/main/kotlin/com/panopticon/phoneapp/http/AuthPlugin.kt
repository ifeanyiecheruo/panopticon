package com.panopticon.phoneapp.http

import com.panopticon.phoneapp.pairing.ControllerRegistry
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ErrorBody(val error: String)

/**
 * Bearer-token auth gate, per docs/design/http-api.md: "every route below" requires
 * `Authorization: Bearer <per-controller-token>` except `POST /api/pair` (which is how a token
 * is obtained in the first place). Missing/unknown/revoked token -> 401.
 *
 * Implemented as a raw pipeline interceptor (rather than Ktor's `Authentication` plugin's bearer
 * provider) so it can call `finish()` to reliably short-circuit before any route handler runs -
 * simple and has no extra dependency surface.
 */
fun Application.installAuth(registry: ControllerRegistry) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val method = call.request.httpMethod
        if (path == "/api/pair" && method == HttpMethod.Post) return@intercept

        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank() || registry.findByToken(token) == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized"))
            finish()
        }
    }
}
