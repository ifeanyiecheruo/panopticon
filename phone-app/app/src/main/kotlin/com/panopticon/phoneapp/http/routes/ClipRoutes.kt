package com.panopticon.phoneapp.http.routes

import com.panopticon.phoneapp.clips.ClipEntry
import com.panopticon.phoneapp.clips.ClipStore
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
data class ClipDto(
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
data class ClipsResponse(val clips: List<ClipDto>)

@Serializable
data class DeleteResponse(val deleted: Boolean)

private fun ClipEntry.toDto() = ClipDto(
    filename = filename,
    url = "/api/clips/$filename/file",
    createdAtMs = createdAtMs,
    durationMs = durationMs,
    endMs = endMs,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
)

/**
 * Clip sync routes. `/file` uses `call.respondFile`, which - with the `PartialContent` plugin
 * installed on the server - transparently supports byte-`Range` requests (needed for scrubbing a
 * clip mid-download and for resuming an interrupted sync).
 */
fun Route.clipRoutes(clipStore: ClipStore) {
    // Authenticated by the global installAuth() intercept.
    run {
        get("/api/clips") {
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val clips = clipStore.listSince(since).map { it.toDto() }
            call.respond(ClipsResponse(clips))
        }

        get("/api/clips/{filename}/file") {
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("missing filename"))
            val file = clipStore.fileFor(filename)
            if (file == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("clip not found (evicted or never existed)"))
                return@get
            }
            call.respondFile(file)
        }

        get("/api/clips/{filename}/thumbnail") {
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("missing filename"))
            val thumb = clipStore.thumbnailFor(filename)
            if (thumb == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("clip or thumbnail not available"))
                return@get
            }
            call.respondFile(thumb)
        }

        delete("/api/clips/{filename}") {
            val filename = call.parameters["filename"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorBody("missing filename"))
            val deleted = clipStore.delete(filename)
            call.respond(DeleteResponse(deleted))
        }
    }
}
