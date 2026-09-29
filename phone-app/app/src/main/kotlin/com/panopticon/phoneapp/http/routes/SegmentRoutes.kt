package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.clips.SegmentEntry
import com.panopticon.phoneapp.clips.SegmentStore
import com.panopticon.phoneapp.http.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class SegmentDto(
    val filename: String,
    val url: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val endMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

@Serializable
data class SegmentsResponse(val segments: List<SegmentDto>)

@Serializable
data class SegmentsMissingRequest(val filenames: List<String>)

/** Indices into the request's `filenames` whose `/file` would `404`. */
@Serializable
data class SegmentsMissingResponse(val missing: List<Int>)

/** Upper bound on one `POST /api/segments/missing` batch - callers chunk anything bigger. */
private const val MAX_MISSING_BATCH = 1000

@Serializable
data class DeleteResponse(val deleted: Boolean)

private fun SegmentEntry.toDto() = SegmentDto(
    filename = filename,
    url = "/api/segments/$filename/file",
    createdAtMs = createdAtMs,
    durationMs = durationMs,
    endMs = endMs,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
)

/**
 * Segment sync routes. `/file` uses `call.respondFile`, which - with the `PartialContent` plugin
 * installed on the server - transparently supports byte-`Range` requests (needed for scrubbing a
 * segment mid-download and for resuming an interrupted sync).
 *
 * A segment is one recorded file; the controller groups contiguous segments into user-facing
 * "clips". That grouping is not part of this API - the phone only knows segments.
 */
fun Route.segmentRoutes(segmentStore: SegmentStore) {
    // Authenticated by the global installAuth() intercept.
    run {
        get("/api/segments") {
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val segments = segmentStore.listSince(since).map { it.toDto() }
            call.respond(SegmentsResponse(segments))
        }

        // Batch existence check: which of these segments are gone? The controller asks this about
        // the segment tombstones it keeps for clips the user deleted, and drops the ones the phone
        // no longer has - one request per batch rather than one per segment. Exactly the /file
        // route's test, so "missing" means "/file would 404".
        post("/api/segments/missing") {
            val req = call.receive<SegmentsMissingRequest>()
            if (req.filenames.size > MAX_MISSING_BATCH) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("at most $MAX_MISSING_BATCH filenames per request"))
                return@post
            }
            val missing = req.filenames.indices.filter { segmentStore.fileFor(req.filenames[it]) == null }
            call.respond(SegmentsMissingResponse(missing))
        }

        get("/api/segments/{filename}/file") {
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("missing filename"))
            val file = segmentStore.fileFor(filename)
            if (file == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("segment not found (evicted or never existed)"))
                return@get
            }
            call.respondFile(file)
        }

        get("/api/segments/{filename}/thumbnail") {
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("missing filename"))
            val thumb = segmentStore.thumbnailFor(filename)
            if (thumb == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("segment or thumbnail not available"))
                return@get
            }
            call.respondFile(thumb)
        }

        delete("/api/segments/{filename}") {
            val filename = call.parameters["filename"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorBody("missing filename"))
            val deleted = segmentStore.delete(filename)
            call.respond(DeleteResponse(deleted))
        }
    }
}
