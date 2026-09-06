package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.camera.LivePipeline
import com.panopticon.phoneapp.http.ErrorBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class LiveStartResponse(val started: Boolean, val viewerCount: Int)

@Serializable
data class LiveStopResponse(val stopped: Boolean, val viewerCount: Int)

private val M3U8 = ContentType("application", "vnd.apple.mpegurl")
private val MP2T = ContentType("video", "mp2t")
private val SEGMENT_NAME = Regex("""live-(\d+)\.ts""")

/**
 * Live view (plain HLS). See phone-http-api.md's "Live view" section.
 *
 *  - `POST /api/live/start` idempotently begins broadcasting; `409` unless the phone is in LIVE
 *    mode (enter it first via `POST /api/mode {"mode":"live"}`, which is itself a `409` from
 *    `record`).
 *  - `DELETE /api/live/stop` drops back to armed-idle (usually unnecessary - a 15s no-request
 *    watchdog on [LivePipeline] does it).
 *  - `GET /live/live.m3u8` / `GET /live/live-<n>.ts` serve the rolling playlist and its segments
 *    straight from [LivePipeline]'s relay. Both are still behind the normal bearer token (the
 *    prototype's separate GET-only scoped token is deferred - the controller proxies these
 *    server-side with the full token today). Every GET also "touches" the watchdog.
 *
 * [live] is looked up per request because the pipeline only exists while the phone is in LIVE
 * mode - `PanopticonService` creates it on the mode switch and releases it on the way out.
 */
fun Route.liveRoutes(live: () -> LivePipeline?) {
    // Authenticated by the global installAuth() intercept.
    run {
        post("/api/live/start") {
            val pipeline = live() ?: return@post call.respond(
                HttpStatusCode.Conflict,
                ErrorBody("not in live mode: POST /api/mode {\"mode\":\"live\"} first"),
            )
            val count = pipeline.startBroadcasting()
            if (count <= 0) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody("live camera unavailable"))
            } else {
                call.respond(LiveStartResponse(started = true, viewerCount = count))
            }
        }

        delete("/api/live/stop") {
            live()?.stopBroadcasting()
            call.respond(LiveStopResponse(stopped = true, viewerCount = 0))
        }

        get("/live/live.m3u8") {
            val pipeline = live() ?: return@get call.respond(
                HttpStatusCode.Conflict,
                ErrorBody("not in live mode"),
            )
            pipeline.touch()
            val playlist = pipeline.currentPlaylist()
            if (playlist == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("live not started"))
            } else {
                call.respondText(playlist, M3U8)
            }
        }

        get("/live/{segment}") {
            val name = call.parameters["segment"].orEmpty()
            val seq = SEGMENT_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("not a live segment"))
            val pipeline = live() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("not in live mode"))
            pipeline.touch()
            val bytes = pipeline.readSegment(seq)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("segment rolled out of the window"))
            } else {
                call.respondBytes(bytes, MP2T)
            }
        }
    }
}
