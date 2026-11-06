package com.panopticon.phoneapp.clips

import com.panopticon.phoneapp.calibration.RectNorm
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
    /** The area of interest when the segment began, in the recorded frame's own `0..1`
     *  coordinates (top-down); null when there was none. Served by
     *  `GET /api/segments/{filename}/aoi`, not in the listing. */
    val aoi: RectNorm? = null,
)
