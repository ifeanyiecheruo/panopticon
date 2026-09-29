package com.panopticon.phoneapp.clips

/**
 * Which segments the ring buffer drops, and in what order - the policy behind
 * [SegmentStore.evictAsync], kept free of files and Android so it unit-tests on the JVM.
 *
 * Oldest first, and a segment goes if any of these still holds with it kept:
 * - it is older than [maxAgeMs] (`ringBufferMaxAgeMs` in /api/config);
 * - the segments together exceed [capBytes] (`storageCapBytes`);
 * - the volume has less than the headroom asked for - [bytesToFree] is how far short it is.
 *
 * The last rule is what keeps the phone recording when something else fills the disk: without
 * it, a full volume makes opening the next segment fail, and the camera stops (2026-09-29: 43GB
 * of segments against an 8GB cap, because nothing enforced the cap at all). Newest footage wins,
 * as a ring buffer should - the controller is expected to have synced the old.
 */
fun planEviction(
    entries: Collection<SegmentEntry>,
    capBytes: Long,
    maxAgeMs: Long,
    bytesToFree: Long,
    nowMs: Long,
): List<String> {
    val oldestFirst = entries.sortedBy { it.createdAtMs }
    var total = oldestFirst.sumOf { it.sizeBytes }
    var freed = 0L
    val out = ArrayList<String>()
    for (e in oldestFirst) {
        val tooOld = maxAgeMs > 0 && nowMs - e.endMs > maxAgeMs
        val overCap = capBytes > 0 && total > capBytes
        val shortOfSpace = freed < bytesToFree
        if (!tooOld && !overCap && !shortOfSpace) break
        out += e.filename
        total -= e.sizeBytes
        freed += e.sizeBytes
    }
    return out
}
