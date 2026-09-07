package com.panopticon.phoneapp.camera

import kotlin.math.min

/**
 * Pure helper for turning a physical camera's facing + focal length into a
 * short human label ("Wide", "Ultra-wide", "Tele", "Front"), the way the
 * `/api/cameras` list presents each camera to the controller.
 *
 * Framework-free so it unit-tests on the plain JVM (matches `ZoomMath` /
 * `MotionDetector`); `CameraCatalog` reads the raw `CameraCharacteristics` and
 * calls in here.
 *
 * The heuristic is relative, not absolute: phones don't agree on absolute
 * focal-length numbers, but within one facing direction the shortest lens is
 * the ultra-wide and the longest is the tele. A single-lens facing is just
 * "Wide" / "Front".
 */
object CameraLabels {

    /**
     * @param focalLengthMm       this camera's (first) reported focal length, or null/<=0 if unknown
     * @param facing              "back" | "front" | "external" | "unknown"
     * @param peerFocalLengthsMm  every reported focal length of *all* cameras with the same facing
     *                            (including this one), used to place this lens as wide/ultra-wide/tele
     */
    fun label(
        focalLengthMm: Float?,
        facing: String,
        peerFocalLengthsMm: List<Float>,
    ): String {
        val front = facing == "front"
        val base = if (front) "Front" else "Wide"

        val f = focalLengthMm?.takeIf { it > 0f } ?: return base
        val peers = peerFocalLengthsMm.filter { it > 0f }.sorted().distinct()
        if (peers.size < 2) return base

        val isShortest = f <= peers.first() * 1.001f
        val isLongest = f >= peers.last() * 0.999f
        // Ultra-wide: the shortest lens, and it's meaningfully shorter than the next one up.
        if (isShortest && peers[0] < peers[1] * 0.85f) {
            return if (front) "Front ultra-wide" else "Ultra-wide"
        }
        // Tele: only meaningful with 3+ lenses, and the longest must stand well clear of the pack.
        if (isLongest && peers.size >= 3 && peers.last() > peers[peers.size - 2] * 1.30f) {
            return if (front) "Front tele" else "Tele"
        }
        return base
    }

    /** A stable ordering key so the controller's chip row reads wide -> tele, back before front. */
    fun sortKey(facing: String, focalLengthMm: Float?): Long {
        val facingRank = when (facing) {
            "back" -> 0L
            "front" -> 1L
            "external" -> 2L
            else -> 3L
        }
        val f = ((focalLengthMm ?: 0f).coerceIn(0f, 999f) * 100f).toLong()
        return facingRank * 100_000L + min(f, 99_999L)
    }
}
