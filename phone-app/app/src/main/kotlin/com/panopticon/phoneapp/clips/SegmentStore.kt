package com.panopticon.phoneapp.clips

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SegmentStore"

/**
 * Owns the on-disk segment directory + an index of [SegmentEntry] metadata
 * (createdAtMs, duration, dimensions) so `GET /api/segments` doesn't need to probe every file
 * with MediaMetadataRetriever on every request.
 *
 * A "segment" is a single recorded file - what the phone used to call a "clip". The controller
 * groups contiguous segments into user-facing clips; the phone has no notion of that.
 *
 * ## Why the index is held in memory
 *
 * The index is persisted as one JSON blob in SharedPreferences. After weeks of motion-gated
 * recording that blob holds thousands of entries, and the original design re-parsed it on every
 * read and re-serialised + rewrote the whole thing on every single add/delete. Recording adds an
 * entry every ~10s; deleting one clip deletes N entries. On a weak device that per-op JSON
 * round-trip stacks into an ANR (and OOM, from the repeated big allocations) once the user
 * deletes a few clips back to back.
 *
 * So the parsed map is loaded once and kept as the authoritative in-memory copy. Reads hit it
 * directly; mutations update it and schedule a single coalesced background flush of the whole
 * blob. Losing the last few unflushed mutations on a hard kill is harmless: [reconcile] on the
 * next launch drops index entries whose file is gone and re-probes files that aren't indexed, so
 * the index converges to what's actually on disk regardless.
 *
 * Uses app-specific external storage (`getExternalFilesDir`) - no storage permission needed on
 * modern Android, and it's automatically cleaned up on uninstall.
 *
 * The on-disk directory (`"clips"`) and the SharedPreferences name/key
 * (`"panopticon_clips"` / `"clips_index_json"`) deliberately keep their old literals: renaming
 * them would orphan every already-recorded file and the existing index on an app update.
 */
class SegmentStore(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val prefs = appContext.getSharedPreferences("panopticon_clips", Context.MODE_PRIVATE)

    val segmentsDir: File = File(appContext.getExternalFilesDir(null), "clips").apply { mkdirs() }
    val thumbsDir: File = File(appContext.getExternalFilesDir(null), "thumbnails").apply { mkdirs() }

    // Authoritative in-memory index. Loaded lazily on first access, mutated in place thereafter.
    private var cache: MutableMap<String, SegmentEntry>? = null

    private val flushExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SegmentIndexFlush") }
    private val flushPending = AtomicBoolean(false)

    @Synchronized
    private fun index(): MutableMap<String, SegmentEntry> {
        cache?.let { return it }
        val raw = prefs.getString(KEY, null)
        val loaded: MutableMap<String, SegmentEntry> = if (raw == null) {
            mutableMapOf()
        } else {
            try {
                json.decodeFromString<Map<String, SegmentEntry>>(raw).toMutableMap()
            } catch (e: Exception) {
                Log.w(TAG, "index parse failed, starting empty", e)
                mutableMapOf()
            }
        }
        cache = loaded
        return loaded
    }

    /** Schedules one coalesced write of the whole index to prefs on the flush thread. Mutations
     *  that land while a flush is in flight simply arm the next one. */
    private fun markDirty() {
        if (flushPending.compareAndSet(false, true)) {
            flushExecutor.execute {
                flushPending.set(false)
                val snapshot = synchronized(this) { HashMap(index()) }
                prefs.edit().putString(KEY, json.encodeToString(snapshot)).commit()
            }
        }
    }

    /** Repairs the index against what's actually on disk - recovers from unflushed mutations and
     *  from a crash mid-recording. Called once on process start. */
    @Synchronized
    fun reconcile() {
        val index = index()
        val filesOnDisk = segmentsDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.associateBy { it.name } ?: emptyMap()

        // Drop index entries whose file no longer exists (e.g. a delete that never got flushed).
        val stale = index.keys.filter { it !in filesOnDisk.keys }
        stale.forEach { index.remove(it) }

        // Add index entries for files that exist but aren't tracked (a finished file whose index
        // write never landed).
        for ((name, file) in filesOnDisk) {
            if (index.containsKey(name)) continue
            val probed = probe(file) ?: continue
            index[name] = probed
        }
        markDirty()
        Log.i(TAG, "reconcile: ${index.size} segments indexed, ${stale.size} stale entries dropped")
    }

    @Synchronized
    fun addSegment(file: File, createdAtMs: Long, durationMs: Long, width: Int, height: Int) {
        index()[file.name] = SegmentEntry(
            filename = file.name,
            createdAtMs = createdAtMs,
            durationMs = durationMs,
            endMs = createdAtMs + durationMs,
            sizeBytes = file.length(),
            width = width,
            height = height,
        )
        markDirty()
    }

    @Synchronized
    fun listSince(sinceMs: Long): List<SegmentEntry> =
        index().values.filter { it.createdAtMs >= sinceMs }.sortedBy { it.createdAtMs }

    fun fileFor(filename: String): File? {
        val f = File(segmentsDir, sanitize(filename))
        return if (f.exists() && f.parentFile == segmentsDir) f else null
    }

    @Synchronized
    fun delete(filename: String): Boolean = deleteAll(listOf(filename)) > 0

    /**
     * Deletes one or many segments: N in-memory map removals + N file deletes + ONE coalesced
     * index flush, regardless of N. (Deleting a clip = deleting all its segments, so N is
     * routinely large.) Returns how many index entries were actually removed.
     */
    @Synchronized
    fun deleteAll(filenames: Collection<String>): Int {
        if (filenames.isEmpty()) return 0
        val safe = filenames.map { sanitize(it) }
        val index = index()
        var removed = 0
        for (name in safe) {
            if (index.remove(name) != null) removed++
        }
        markDirty()
        for (name in safe) {
            File(segmentsDir, name).delete()
            File(thumbsDir, thumbName(name)).delete()
        }
        return removed
    }

    @Synchronized
    fun totalBytes(): Long = index().values.sumOf { it.sizeBytes }

    @Synchronized
    fun count(): Int = index().size

    /** Extracts (and caches) a single JPEG frame from a segment for the Gallery filmstrip. */
    fun thumbnailFor(filename: String): File? {
        val safe = sanitize(filename)
        val segmentFile = fileFor(safe) ?: return null
        val thumbFile = File(thumbsDir, thumbName(safe))
        if (thumbFile.exists()) return thumbFile

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(segmentFile.absolutePath)
            val frame: Bitmap = retriever.getFrameAtTime(0) ?: return null
            FileOutputStream(thumbFile).use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            thumbFile
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail extraction failed for $safe", e)
            null
        } finally {
            retriever.release()
        }
    }

    private fun probe(file: File): SegmentEntry? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val createdAtMs = file.lastModified() - durationMs
            SegmentEntry(
                filename = file.name,
                createdAtMs = createdAtMs,
                durationMs = durationMs,
                endMs = createdAtMs + durationMs,
                sizeBytes = file.length(),
                width = width,
                height = height,
            )
        } catch (e: Exception) {
            Log.w(TAG, "probe failed for ${file.name}", e)
            null
        } finally {
            retriever.release()
        }
    }

    private fun thumbName(segmentFilename: String) = segmentFilename.removeSuffix(".mp4") + ".jpg"

    /** Strips any path components - filenames come from the URL path, never trust them raw. */
    private fun sanitize(filename: String): String = File(filename).name

    companion object {
        private const val KEY = "clips_index_json"
    }
}
