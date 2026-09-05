package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.http.ErrorBody
import com.panopticon.phoneapp.pairing.ControllerRegistry
import com.panopticon.phoneapp.pairing.InviteManager
import com.panopticon.phoneapp.state.AppConfig
import com.panopticon.phoneapp.state.DeviceIdentity
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class PairRequest(val publicKey: String, val name: String, val kind: String)

@Serializable
data class PhoneSummary(val phoneId: String, val name: String)

@Serializable
data class PairResponse(val controllerId: String, val token: String, val phone: PhoneSummary)

@Serializable
data class UnpairResponse(val unpaired: Boolean)

/** POST /api/pair (unauthenticated - it's how a token is obtained) and DELETE /api/pair (self-unpair). */
fun Route.pairingRoutes(
    registry: ControllerRegistry,
    invites: InviteManager,
    config: AppConfig,
    android: android.content.Context,
) {
    post("/api/pair") {
        val code = call.request.queryParameters["invite"]
        if (code.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("missing invite query param"))
            return@post
        }
        when (invites.redeem(code)) {
            is InviteManager.RedeemResult.NotFound -> {
                call.respond(HttpStatusCode.NotFound, ErrorBody("unknown or already-used invite code"))
            }
            is InviteManager.RedeemResult.Expired -> {
                call.respond(HttpStatusCode.Gone, ErrorBody("invite expired or already used"))
            }
            is InviteManager.RedeemResult.Ok -> {
                val body = try {
                    call.receive<PairRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("invalid request body"))
                    return@post
                }
                val controller = registry.register(body.name, body.kind, body.publicKey)
                val phoneId = DeviceIdentity.getOrCreatePhoneId(android)
                call.respond(
                    PairResponse(
                        controllerId = controller.controllerId,
                        token = controller.token,
                        phone = PhoneSummary(phoneId = phoneId, name = config.get().deviceName),
                    ),
                )
            }
        }
    }

    // Authenticated by the global installAuth() intercept (every route except POST /api/pair).
    delete("/api/pair") {
        // The bearer token itself is the identity being revoked (self-unpair only) - no
        // controllerId param, so a controller can never unpair anyone but itself.
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val revoked = token?.let { registry.revokeByToken(it) } ?: false
        if (revoked) {
            call.respond(UnpairResponse(true))
        } else {
            call.respond(HttpStatusCode.Unauthorized, ErrorBody("token already invalid"))
        }
    }
}
