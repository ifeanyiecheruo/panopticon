package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.camera.LiveHlsRelay
import com.panopticon.phoneapp.camera.LivePipeline
import com.panopticon.phoneapp.http.ErrorBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class LiveStartResponse(val started: Boolean, val viewerCount: Int)

/** 503 body when the camera is still coming up: retry after [retryAfterMs]. */
@Serializable
data class LiveRetryBody(val error: String, val retryAfterMs: Int)

private const val LIVE_RETRY_AFTER_MS = 2_000

@Serializable
data class LiveStopResponse(val stopped: Boolean, val viewerCount: Int)

private val M3U8 = ContentType("application", "vnd.apple.mpegurl")
private val MP2T = ContentType("video", "mp2t")
private val SEGMENT_NAME = Regex("""live-(\d+)\.ts""")

/** Comfortably longer than the advertised PART-HOLD-BACK (3x part duration, see
 * [LiveHlsRelay]) so a blocked long-poll never gets cut off by this server before the client's
 * own expected wait. */
private val BLOCKING_RELOAD_TIMEOUT_MS = (LiveHlsRelay.DEFAULT_PART_TARGET_DURATION_US / 1000) * 4

private val BYTE_RANGE_HEADER = Regex("""bytes=(\d+)-(\d+)""")

/** Parses a single-range `Range: bytes=<start>-<end>` request header (the only form this
 * server's own BYTERANGE playlist hints ever produce) - returns null for anything else (absent,
 * multi-range, suffix-range, malformed), which callers treat as "not a part/byte-range
 * request". */
private fun parseByteRange(header: String): IntRange? {
    val match = BYTE_RANGE_HEADER.matchEntire(header.trim()) ?: return null
    val (start, end) = match.destructured
    val startInt = start.toIntOrNull() ?: return null
    val endInt = end.toIntOrNull() ?: return null
    if (startInt > endInt) return null
    return startInt..endInt
}

/**
 * Live view (LL-HLS). See docs/design/http-api.md's "Live view" section.
 *
 *  - `POST /api/live/start` idempotently begins broadcasting; `409` unless the phone is in LIVE
 *    mode (enter it first via `POST /api/mode {"mode":"live"}`, which is itself a `409` from
 *    `record`).
 *  - `DELETE /api/live/stop` drops back to armed-idle (usually unnecessary - a 15s no-request
 *    watchdog on [LivePipeline] does it).
 *  - `GET /live/live.m3u8` serves the rolling LL-HLS playlist straight from [LivePipeline]'s
 *    relay. A request carrying `_HLS_msn` (optionally `_HLS_part`) is a blocking reload - it
 *    holds the response open until that part (or whole segment) actually exists, instead of
 *    polling on a fixed interval, up to [BLOCKING_RELOAD_TIMEOUT_MS].
 *  - `GET /live/live-<n>.ts` serves a finalized segment file, or - for the segment still being
 *    encoded - a `Range` fetch against whatever's been muxed so far (LL-HLS part/preload-hint
 *    byte ranges into the in-progress segment).
 *  - Both are still behind the normal bearer token (the prototype's separate GET-only scoped
 *    token is deferred - the controller proxies these server-side with the full token today).
 *    Every GET also "touches" the watchdog.
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
            when (val count = pipeline.startBroadcasting()) {
                LivePipeline.STILL_ARMING -> {
                    // Camera is coming up (cold front-camera start): tell the caller to retry.
                    call.response.header(HttpHeaders.RetryAfter, "2")
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        LiveRetryBody("camera still starting", LIVE_RETRY_AFTER_MS),
                    )
                }
                0 -> call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorBody("live camera unavailable"),
                )
                else -> call.respond(LiveStartResponse(started = true, viewerCount = count))
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
            // LL-HLS blocking reload: a client that already has a copy of the playlist appends
            // `_HLS_msn`/`_HLS_part` (the next msn/part it doesn't have yet) instead of polling on
            // a fixed interval - this holds the response open until that part (or whole segment,
            // if `_HLS_part` is absent) actually exists, so a reload returns the instant new data
            // is ready rather than up to one poll-interval late. A plain reload (no `_HLS_msn`)
            // responds immediately, unchanged.
            val msn = call.request.queryParameters["_HLS_msn"]?.toIntOrNull()
            if (msn != null) {
                val part = call.request.queryParameters["_HLS_part"]?.toIntOrNull()
                val deferred = pipeline.relayAwaitAtLeast(msn, part)
                if (deferred != null) withTimeoutOrNull(BLOCKING_RELOAD_TIMEOUT_MS) { deferred.await() }
            }
            val playlist = pipeline.currentPlaylist()
            if (playlist == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("live not started"))
            } else {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(playlist, M3U8)
            }
        }

        get("/live/{segment}") {
            val name = call.parameters["segment"].orEmpty()
            val seq = SEGMENT_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("not a live segment"))
            val pipeline = live() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("not in live mode"))
            pipeline.touch()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val bytes = pipeline.readSegment(seq)
            if (bytes != null) {
                call.respondBytes(bytes, MP2T)
                return@get
            }
            // Not finalized yet - an LL-HLS part/preload-hint fetch against the segment currently
            // being built, referencing a BYTERANGE this server itself advertised in the
            // in-progress segment's #EXT-X-PART/#EXT-X-PRELOAD-HINT entries (see
            // LiveHlsRelay.writePlaylist). Once this same segment finalizes, the exact same
            // URI+range request instead hits the branch above and gets byte-identical content.
            val range = call.request.header(HttpHeaders.Range)?.let(::parseByteRange)
            if (range == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("segment rolled out of the window"))
                return@get
            }
            val partBytes = pipeline.relayReadInProgressRange(seq, range.first, range.last + 1)
            if (partBytes == null) {
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, ErrorBody("requested part not available yet"))
                return@get
            }
            call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/*")
            call.respondBytes(partBytes, contentType = MP2T, status = HttpStatusCode.PartialContent)
        }
    }
}
