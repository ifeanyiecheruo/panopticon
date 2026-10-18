package com.panopticon.phoneapp.motion

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Offline replay of real footage through the **shipping** detector code on the desktop JVM, for
 * tuning and for before/after comparisons. Skipped unless pointed at something:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*MotionReplayTest*' \
 *         -Pmotion.replay=<file-or-dir>[;<file-or-dir>...] \
 *         [-Pmotion.replay.snapshots=true] [-Pmotion.replay.labels=<labels.txt>]
 *
 * Inputs:
 * - debug traces from `DebugMotionTraceReceiver`: `PMTR2` (luma at the analysis rate - exactly
 *   what the phone's detector saw) or the older `PMTR1` (RGB, usually 80x60 at 10fps, from the
 *   previous detector; replayable but coarser than the phone now analyses);
 * - any video ffmpeg can decode (recorded clips), resampled to 160x120 luma at 15fps. Encoded
 *   and already gated by whatever detector recorded them, so they can show false triggers
 *   but never what was missed.
 *
 * Each source is replayed independently (fresh detectors) through the new detector at every
 * sensitivity and through [LegacyChangeDetector] (the one it replaced), and reported as
 * recording *events* the way the recorder sees them: motion, held for [TRAILER_MS] after it
 * stops. Labels, if given, are lines `<source file name> <start s> <end s> [label]` marking where
 * something real happens; the report then gives recall and detection latency per label.
 *
 * The report goes to `app/build/motion-replay/report.txt`; snapshots (the frame at each event
 * start, 4x, confirmed tracks boxed red and candidates yellow) to `app/build/motion-replay/`.
 */
class MotionReplayTest {

    /** [frames] yields each frame with its timestamp in ms from the start of the source. */
    private class Source(val name: String, val width: Int, val height: Int, val fps: Double, val frames: Sequence<Pair<ByteArray, Long>>, val flipRows: Boolean)

    private class Event(val startMs: Long, var lastMotionMs: Long)

    @Test
    fun replay() {
        val spec = System.getProperty("motion.replay").orEmpty()
        assumeTrue("set -Pmotion.replay to replay footage", spec.isNotBlank())
        val snapshots = System.getProperty("motion.replay.snapshots") == "true"
        val labels = System.getProperty("motion.replay.labels").orEmpty().takeIf { it.isNotBlank() }?.let { readLabels(File(it)) }.orEmpty()
        val out = File("build/motion-replay").apply { mkdirs() }
        val files = spec.split(';').filter { it.isNotBlank() }.flatMap { p ->
            val f = File(p)
            if (f.isDirectory) f.walkTopDown().filter { it.isFile && isInput(it) }.sortedBy { it.name }.toList() else listOf(f)
        }
        val sensitivities = listOf("high", "medium", "low")
        // Where confirmed tracks are, frame by frame (file, ms, x, y, size) - "high" only.
        val trackLog = if (System.getProperty("motion.replay.tracks") == "true") StringBuilder() else null
        val report = StringBuilder()
        val totals = HashMap<String, LongArray>() // key -> [events, recordedMs, durationMs]
        var nanos = 0L
        var analysed = 0L

        report.appendLine("source | frames | duration s | " + sensitivities.joinToString(" | ") { "new $it ev/rec s" } + " | " +
            sensitivities.joinToString(" | ") { "old $it ev/rec s" })
        for (file in files) {
            val src = open(file) ?: continue
            val news = sensitivities.associateWith { MotionDetector(it) }
            val olds = sensitivities.associateWith { LegacyChangeDetector(it, referenceLagFrames = src.fps.toInt(), noiseWindowFrames = (src.fps * 5).toInt(), noiseMinSamples = src.fps.toInt()) }
            val events = HashMap<String, MutableList<Event>>()
            var t = 0
            var durationMs = 0L
            for ((frame, nowMs) in src.frames) {
                durationMs = nowMs
                for ((s, d) in news) {
                    val t0 = System.nanoTime()
                    val r = d.accept(frame, src.width, src.height, src.width, nowMs)
                    if (s == "medium") { nanos += System.nanoTime() - t0; analysed++ }
                    val opened = note(events.getOrPut("new $s") { ArrayList() }, r.motion, nowMs)
                    if (r.motion && s == "high" && trackLog != null) {
                        for (tr in d.debugTracker.tracks) if (tr.confirmed) trackLog.appendLine("${file.name} $nowMs ${"%.0f".format(Locale.US, tr.x)} ${"%.0f".format(Locale.US, if (src.flipRows) src.height - 1 - tr.y else tr.y)} ${"%.0f".format(Locale.US, tr.meanSupport)}")
                    }
                    if (opened && snapshots) snapshot(out, src, frame, "${file.name}_${s}_${nowMs}ms", d)
                }
                for ((s, d) in olds) note(events.getOrPut("old $s") { ArrayList() }, d.accept(frame, src.width, src.height, src.width).motion, nowMs)
                t++
            }
            fun cell(key: String): String {
                val ev = events[key].orEmpty()
                val rec = recordedMs(ev, durationMs)
                val acc = totals.getOrPut(key) { LongArray(3) }
                acc[0] = acc[0] + ev.size
                acc[1] = acc[1] + rec
                acc[2] = acc[2] + durationMs
                return "${ev.size}/${"%.1f".format(Locale.US, rec / 1000.0)}"
            }
            report.appendLine("${file.name} | $t | ${"%.1f".format(Locale.US, durationMs / 1000.0)} | " +
                sensitivities.joinToString(" | ") { cell("new $it") } + " | " + sensitivities.joinToString(" | ") { cell("old $it") })
            for ((key, ev) in events.toSortedMap()) {
                if (ev.isNotEmpty()) report.appendLine("  $key events at s: " + ev.joinToString(" ") { "%.1f".format(Locale.US, it.startMs / 1000.0) })
            }
            for (l in labels.filter { it.file == file.name }) {
                for (s in sensitivities) {
                    val hit = events["new $s"].orEmpty().firstOrNull { it.lastMotionMs >= l.startMs && it.startMs <= l.endMs }
                    val latency = hit?.let { maxOf(0L, it.startMs - l.startMs) }
                    report.appendLine("  label ${l.name} ${l.startMs / 1000.0}-${l.endMs / 1000.0}s new $s: " +
                        (if (latency == null) "MISSED" else "caught, +${latency}ms"))
                }
            }
        }
        report.appendLine()
        report.appendLine("TOTAL (events / recorded share of footage):")
        for ((k, v) in totals.toSortedMap()) {
            report.appendLine("  $k: ${v[0]} events, ${"%.1f".format(Locale.US, 100.0 * v[1] / maxOf(1L, v[2]))}% recorded")
        }
        if (analysed > 0) report.appendLine("new detector (medium): ${"%.3f".format(Locale.US, nanos / 1e6 / analysed)} ms/frame on this JVM over $analysed frames")
        File(out, "report.txt").writeText(report.toString())
        trackLog?.let { File(out, "tracks.txt").writeText(it.toString()) }
        println(report)
    }

    /** Feeds one verdict into the event list (motion + [TRAILER_MS]); returns true if it opened an event. */
    private fun note(ev: MutableList<Event>, motion: Boolean, nowMs: Long): Boolean {
        if (!motion) return false
        val last = ev.lastOrNull()
        if (last != null && nowMs - last.lastMotionMs <= TRAILER_MS) { last.lastMotionMs = nowMs; return false }
        ev.add(Event(nowMs, nowMs))
        return true
    }

    private fun recordedMs(ev: List<Event>, durationMs: Long): Long =
        ev.sumOf { minOf(durationMs, it.lastMotionMs + TRAILER_MS) - it.startMs }

    private fun isInput(f: File): Boolean = f.extension.lowercase() in setOf("bin", "mp4", "mkv", "mov", "avi", "webm")

    private fun open(f: File): Source? = when (f.extension.lowercase()) {
        "bin" -> openTrace(f)
        else -> openVideo(f)
    }

    private fun openTrace(f: File): Source? {
        val inp = DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 20))
        val magic = ByteArray(5).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
        val w = inp.readInt(); val h = inp.readInt(); val ch = inp.readInt()
        val v2 = when (magic) { "PMTR2" -> true; "PMTR1" -> false; else -> { inp.close(); return null } }
        // Timestamps drive confirmation and coasting, so frames replay at the times they were
        // captured: the analysis rate varies with the camera's (11-15fps on the Pixel 6).
        var startMs = -1L
        val frames = sequence {
            val raw = ByteArray(w * h * ch)
            try {
                while (true) {
                    val ms = inp.readLong(); inp.readLong(); inp.readByte()
                    if (v2) repeat(4) { inp.readShort() } else inp.readFloat()
                    inp.readFully(raw)
                    if (startMs < 0) startMs = ms
                    yield((if (ch == 1) raw.copyOf() else ByteArray(w * h) { i ->
                        val r = raw[i * ch].toInt() and 0xFF; val g = raw[i * ch + 1].toInt() and 0xFF; val b = raw[i * ch + 2].toInt() and 0xFF
                        ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
                    }) to ms - startMs)
                }
            } catch (_: EOFException) {
            } finally {
                inp.close()
            }
        }
        val fps = if (v2) 15.0 else 10.0
        return Source(f.name, w, h, fps, frames, flipRows = true)
    }

    private fun openVideo(f: File): Source {
        val w = 160; val h = 120
        val p = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", f.absolutePath, "-vf", "fps=15,scale=$w:$h:flags=area",
            "-pix_fmt", "gray", "-f", "rawvideo", "-",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val inp = BufferedInputStream(p.inputStream, 1 shl 20)
        val frames = sequence {
            var n = 0L
            try {
                while (true) {
                    val buf = ByteArray(w * h)
                    var off = 0
                    while (off < buf.size) {
                        val n = inp.read(buf, off, buf.size - off)
                        if (n < 0) return@sequence
                        off += n
                    }
                    yield(buf to n * 1000L / 15)
                    n++
                }
            } finally {
                inp.close(); p.destroy()
            }
        }
        return Source(f.name, w, h, 15.0, frames, flipRows = false)
    }

    private fun snapshot(dir: File, src: Source, luma: ByteArray, name: String, d: MotionDetector) {
        val s = 4
        val w = src.width * s
        val h = src.height * s
        val rgb = IntArray(w * h)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val v = luma[(if (src.flipRows) src.height - 1 - y else y) * src.width + x].toInt() and 0xFF
            val c = (v shl 16) or (v shl 8) or v
            for (j in 0 until s) for (i in 0 until s) rgb[(y * s + j) * w + x * s + i] = c
        }
        for (t in d.debugTracker.tracks) {
            val color = if (t.confirmed) 0xFF0000 else 0xFFFF00
            val cy = if (src.flipRows) src.height - 1 - t.y else t.y
            val r = maxOf(4f, kotlin.math.sqrt(t.meanSupport)) * s
            val x0 = (t.x * s - r).toInt().coerceIn(0, w - 1); val x1 = (t.x * s + r).toInt().coerceIn(0, w - 1)
            val y0 = (cy * s - r).toInt().coerceIn(0, h - 1); val y1 = (cy * s + r).toInt().coerceIn(0, h - 1)
            for (x in x0..x1) { rgb[y0 * w + x] = color; rgb[y1 * w + x] = color }
            for (y in y0..y1) { rgb[y * w + x0] = color; rgb[y * w + x1] = color }
        }
        File(dir, name.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".png").writeBytes(png(rgb, w, h))
    }

    /** Minimal RGB PNG encoder - the Android unit-test classpath has no ImageIO. */
    private fun png(rgb: IntArray, w: Int, h: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            val row = ByteArray(1 + 3 * w)
            for (y in 0 until h) {
                row[0] = 0
                for (x in 0 until w) {
                    val c = rgb[y * w + x]
                    row[1 + 3 * x] = (c shr 16).toByte(); row[2 + 3 * x] = (c shr 8).toByte(); row[3 + 3 * x] = c.toByte()
                }
                z.write(row)
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val len = data.size
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            val td = type.toByteArray(Charsets.US_ASCII) + data
            out.write(td)
            val crc = CRC32().apply { update(td) }.value
            out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        }
        val ihdr = ByteArray(13)
        for (k in 0 until 4) { ihdr[k] = (w ushr (24 - 8 * k)).toByte(); ihdr[4 + k] = (h ushr (24 - 8 * k)).toByte() }
        ihdr[8] = 8; ihdr[9] = 2 // 8-bit RGB
        chunk("IHDR", ihdr)
        chunk("IDAT", raw.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private class Label(val file: String, val startMs: Long, val endMs: Long, val name: String)

    private fun readLabels(f: File): List<Label> = f.readLines().mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 3 || p[0].startsWith("#")) null
        else Label(p[0], (p[1].toDouble() * 1000).toLong(), (p[2].toDouble() * 1000).toLong(), p.getOrElse(3) { "event" })
    }

    private companion object {
        const val TRAILER_MS = 5_000L
    }
}
