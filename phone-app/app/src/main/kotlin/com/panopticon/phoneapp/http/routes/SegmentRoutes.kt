package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.clips.SegmentEntry
import com.panopticon.phoneapp.clips.SegmentStore
import com.panopticon.phoneapp.http.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
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
