package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.camera.CameraHealthRegistry
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /api/camera/health` - per-camera pipeline health counters.
 *
 * Diagnostics, not product: nothing in the controller or the phone UI reads this yet, and the
 * body's shape is deliberately not a stable contract (see
 * [com.panopticon.phoneapp.camera.CameraHealthRegistry]). It exists so the recording pipeline's
 * restart cycle can be characterised from a phone that has been running for hours, without
 * needing a USB cable and `logcat` attached at the moment it happens - which, at one restart
 * every two or three minutes, is the only practical way to catch a pattern rather than an
 * anecdote.
 *
 * Read-only and authenticated by the global `installAuth()` intercept like every other route.
 */
fun Route.healthRoutes(health: CameraHealthRegistry) {
    get("/api/camera/health") {
        call.respond(health.snapshot())
    }
}
