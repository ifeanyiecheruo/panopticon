package com.panopticon.phoneapp.clips

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ClipStore"

/**
 * Owns the on-disk clip directory + a small JSON index of [ClipEntry] metadata (createdAtMs,
 * duration, dimensions) so `GET /api/clips` doesn't need to probe every file with
 * MediaMetadataRetriever on every request.
 *
 * Uses app-specific external storage (`getExternalFilesDir`) - no storage permission needed on
 * modern Android, and it's automatically cleaned up on uninstall.
 */
class ClipStore(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val prefs = appContext.getSharedPreferences("panopticon_clips", Context.MODE_PRIVATE)

    val clipsDir: File = File(appContext.getExternalFilesDir(null), "clips").apply { mkdirs() }
    val thumbsDir: File = File(appContext.getExternalFilesDir(null), "thumbnails").apply { mkdirs() }

    @Synchronized
    private fun loadIndex(): MutableMap<String, ClipEntry> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        return try {
            json.decodeFromString<Map<String, ClipEntry>>(raw).toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    private fun saveIndex(map: Map<String, ClipEntry>) {
        prefs.edit().putString(KEY, json.encodeToString(map)).apply()
    }

    /** Repairs the index against what's actually on disk - recovers from a crash mid-write. */
    @Synchronized
    fun reconcile() {
        val index = loadIndex()
        val filesOnDisk = clipsDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.associateBy { it.name } ?: emptyMap()

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
        Log.i(TAG, "reconcile: ${index.size} clips indexed, ${stale.size} stale entries dropped")
    }

    @Synchronized
    fun addClip(file: File, createdAtMs: Long, durationMs: Long, width: Int, height: Int) {
        val index = loadIndex()
        index[file.name] = ClipEntry(
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
    fun listSince(sinceMs: Long): List<ClipEntry> =
        loadIndex().values.filter { it.createdAtMs >= sinceMs }.sortedBy { it.createdAtMs }

    fun fileFor(filename: String): File? {
        val f = File(clipsDir, sanitize(filename))
        return if (f.exists() && f.parentFile == clipsDir) f else null
    }

    @Synchronized
    fun delete(filename: String): Boolean {
        val safe = sanitize(filename)
        val index = loadIndex()
        val existed = index.remove(safe) != null
        saveIndex(index)
        File(clipsDir, safe).delete()
        File(thumbsDir, thumbName(safe)).delete()
        return existed
    }

    @Synchronized
    fun totalBytes(): Long = loadIndex().values.sumOf { it.sizeBytes }

    /** Extracts (and caches) a single JPEG frame from a clip for the Gallery filmstrip. */
    fun thumbnailFor(filename: String): File? {
        val safe = sanitize(filename)
        val clipFile = fileFor(safe) ?: return null
        val thumbFile = File(thumbsDir, thumbName(safe))
        if (thumbFile.exists()) return thumbFile

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(clipFile.absolutePath)
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

    private fun probe(file: File): ClipEntry? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val createdAtMs = file.lastModified() - durationMs
            ClipEntry(
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

    private fun thumbName(clipFilename: String) = clipFilename.removeSuffix(".mp4") + ".jpg"

    /** Strips any path components - filenames come from the URL path, never trust them raw. */
    private fun sanitize(filename: String): String = File(filename).name

    companion object {
        private const val KEY = "clips_index_json"
    }
}
