package com.panopticon.phoneapp.clips

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

private const val TAG = "SegmentStore"

/**
 * Owns the on-disk segment directory + a small JSON index of [SegmentEntry] metadata
 * (createdAtMs, duration, dimensions) so `GET /api/segments` doesn't need to probe every file
 * with MediaMetadataRetriever on every request.
 *
 * A "segment" is a single recorded file - what the phone used to call a "clip". The controller
 * groups contiguous segments into user-facing clips; the phone has no notion of that.
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

    @Synchronized
    private fun loadIndex(): MutableMap<String, SegmentEntry> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        return try {
            json.decodeFromString<Map<String, SegmentEntry>>(raw).toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    private fun saveIndex(map: Map<String, SegmentEntry>) {
        prefs.edit().putString(KEY, json.encodeToString(map)).apply()
    }

    /** Repairs the index against what's actually on disk - recovers from a crash mid-write. */
    @Synchronized
    fun reconcile() {
        val index = loadIndex()
        val filesOnDisk = segmentsDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.associateBy { it.name } ?: emptyMap()

        // Drop index entries whose file no longer exists.
        val stale = index.keys.filter { it !in filesOnDisk.keys }
        stale.forEach { index.remove(it) }

        // Add index entries for files that exist but aren't tracked (e.g. crash mid-recording
        // left a finished-looking file behind before the index write landed).
        for ((name, file) in filesOnDisk) {
            if (index.containsKey(name)) continue
            val probed = probe(file) ?: continue
            index[name] = probed
        }
        saveIndex(index)
        Log.i(TAG, "reconcile: ${index.size} segments indexed, ${stale.size} stale entries dropped")
    }

    @Synchronized
    fun addSegment(file: File, createdAtMs: Long, durationMs: Long, width: Int, height: Int) {
        val index = loadIndex()
        index[file.name] = SegmentEntry(
            filename = file.name,
            createdAtMs = createdAtMs,
            durationMs = durationMs,
            endMs = createdAtMs + durationMs,
            sizeBytes = file.length(),
            width = width,
            height = height,
        )
        saveIndex(index)
    }

    @Synchronized
    fun listSince(sinceMs: Long): List<SegmentEntry> =
        loadIndex().values.filter { it.createdAtMs >= sinceMs }.sortedBy { it.createdAtMs }

    fun fileFor(filename: String): File? {
        val f = File(segmentsDir, sanitize(filename))
        return if (f.exists() && f.parentFile == segmentsDir) f else null
    }

    @Synchronized
    fun delete(filename: String): Boolean {
        val safe = sanitize(filename)
        val index = loadIndex()
        val existed = index.remove(safe) != null
        saveIndex(index)
        File(segmentsDir, safe).delete()
        File(thumbsDir, thumbName(safe)).delete()
        return existed
    }

    @Synchronized
    fun totalBytes(): Long = loadIndex().values.sumOf { it.sizeBytes }

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
