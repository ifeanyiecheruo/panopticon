package com.panopticon.phoneapp.ui.gallery

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.clips.ClipEntry
import com.panopticon.phoneapp.ui.theme.PanopticonColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bare-bones clip list: play launches the system video viewer via ACTION_VIEW (fastest path for
 * this slice, per the task's own guidance - no in-app ExoPlayer/VideoView here). Delete calls
 * straight into ClipStore, same as the HTTP DELETE route would.
 */
@Composable
fun GalleryScreen(app: PanopticonApplication) {
    val context = LocalContext.current
    var clips by remember { mutableStateOf(app.clipStore.listSince(0).sortedByDescending { it.createdAtMs }) }

    fun refresh() {
        clips = app.clipStore.listSince(0).sortedByDescending { it.createdAtMs }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Gallery", color = PanopticonColors.text, fontSize = 20.sp)
        Text(text = "${clips.size} clips", color = PanopticonColors.textDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))

        if (clips.isEmpty()) {
            Text(
                text = "No clips yet - the recording pipeline rotates a new ~10s clip continuously once the service is running.",
                color = PanopticonColors.textFaint,
                fontSize = 13.sp,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(clips, key = { it.filename }) { clip ->
                ClipRow(
                    clip = clip,
                    onPlay = {
                        val file = app.clipStore.fileFor(clip.filename) ?: return@ClipRow
                        val uri = FileProvider.getUriForFile(context, "com.panopticon.phoneapp.fileprovider", file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    onDelete = {
                        app.clipStore.delete(clip.filename)
                        refresh()
                    },
                )
            }
        }
    }
}

@Composable
private fun ClipRow(clip: ClipEntry, onPlay: () -> Unit, onDelete: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.US) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanopticonColors.surface, RoundedCornerShape(10.dp))
            .clickable { onPlay() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(text = timeFormat.format(Date(clip.createdAtMs)), color = PanopticonColors.text, fontSize = 15.sp)
            Text(
                text = "${clip.durationMs / 1000}s - ${clip.width}x${clip.height} - ${clip.sizeBytes / 1024} KB",
                color = PanopticonColors.textFaint,
                fontSize = 12.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = PanopticonColors.accent)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = PanopticonColors.rec)
            }
        }
    }
}
