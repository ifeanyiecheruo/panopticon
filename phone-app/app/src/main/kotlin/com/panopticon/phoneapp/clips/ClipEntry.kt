package com.panopticon.phoneapp.clips

import kotlinx.serialization.Serializable

/** Mirrors the clip object shape in phone-http-api.md's `GET /api/clips`. */
@Serializable
data class ClipEntry(
    val filename: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val endMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)
