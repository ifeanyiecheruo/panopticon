package com.panopticon.phoneapp.clips

/**
 * Display-time grouping of segments into "clips" for the phone Gallery. This
 * mirrors the controller's own grouping (internal/dbstore + internal/syncer) so
 * both galleries show the same thing; the phone never persists it.
 */

/** Max gap (nextSegment.createdAtMs - runningEndMs) for two segments to be the
 * same clip. Loose for now because segment rotation currently drops ~1-2s on
 * every ~10s file; drops to ~500 once gapless rotation lands. Keep in sync with
 * the controller's dbstore.GroupingGapMs. */
const val GAP_MS = 3_000L

/** A contiguous run of segments - one Gallery row. */
data class Clip(val segments: List<SegmentEntry>) {
    val startedAtMs: Long get() = segments.first().createdAtMs
    val endedAtMs: Long get() = segments.maxOf { it.endMs }
    val durationMs: Long get() = endedAtMs - startedAtMs
    val sizeBytes: Long get() = segments.sumOf { it.sizeBytes }
    val count: Int get() = segments.size

    /** The segment a "play" tap opens (playback starts at the top of the run). */
    val firstSegment: SegmentEntry get() = segments.first()
}

/**
 * Groups [segments] (any order) into clips: a segment joins the current clip
 * when it starts within [gapMs] of that clip's running end, otherwise it opens a
 * new one. Returned newest-clip-first, each clip's segments oldest-first.
 */
fun groupIntoClips(segments: List<SegmentEntry>, gapMs: Long = GAP_MS): List<Clip> {
    if (segments.isEmpty()) return emptyList()
    val ordered = segments.sortedBy { it.createdAtMs }
    val runs = mutableListOf<MutableList<SegmentEntry>>()
    var runEnd = Long.MIN_VALUE
    for (seg in ordered) {
        if (runs.isEmpty() || seg.createdAtMs - runEnd > gapMs) {
            runs.add(mutableListOf(seg))
            runEnd = seg.endMs
        } else {
            runs.last().add(seg)
            runEnd = maxOf(runEnd, seg.endMs)
        }
    }
    return runs.map { Clip(it) }.sortedByDescending { it.startedAtMs }
}
