package com.panopticon.phoneapp.clips

import kotlinx.serialization.Serializable

/** Mirrors the segment object shape in docs/design/http-api.md's `GET /api/segments`.
 * A segment is one recorded file; grouping contiguous segments into "clips" is a
 * controller/UX concern, not something the phone tracks. */
@Serializable
data class SegmentEntry(
    val filename: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val endMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)
