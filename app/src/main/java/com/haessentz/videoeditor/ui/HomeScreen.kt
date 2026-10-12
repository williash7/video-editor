@file:OptIn(ExperimentalMaterial3Api::class)

package com.haessentz.videoeditor.ui

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.Nav
import com.haessentz.videoeditor.Screen
import com.haessentz.videoeditor.data.Prefs
import com.haessentz.videoeditor.data.Project
import com.haessentz.videoeditor.data.ProjectStore
import com.haessentz.videoeditor.data.fmtMs
import com.haessentz.videoeditor.media.ModelManager
import com.haessentz.videoeditor.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@UnstableApi
@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val projects by ProjectStore.projects.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Project?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { createProject(ctx, uri) } }
            p.onSuccess { nav.go(Screen.Project(it.id)) }.onFailure { error = "לא הצלחתי לפתוח את הסרטון: ${it.message}" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("עורך וידאו", style = MaterialTheme.typography.titleLarge)
                        Text("העסענץ החסידי", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = { IconButton(onClick = { nav.go(Screen.Models) }) { Icon(Icons.Default.Settings, "הגדרות") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("video/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("סרטון חדש") }
            )
        },
        bottomBar = { JobsBar() }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!ModelManager.isReady(ctx, Prefs.modelFor("he")) && !ModelManager.isReady(ctx, Prefs.modelFor("ru"))) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("צעד ראשון: מודל תמלול", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("כדי לתמלל בלי אינטרנט צריך להוריד פעם אחת מודל לטלפון. חיתוך ושורטים עובדים גם בלעדיו.")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { nav.go(Screen.Models) }) { Text("להורדת מודל") }
                        }
                    }
                }
            }
            if (projects.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text("🎬", style = MaterialTheme.typography.headlineLarge)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("עוד אין פרויקטים", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("לחץ על \"סרטון חדש\" ובחר סרטון מהטלפון.\nהסרטון לא עולה לשום מקום — הכול נעשה על הטלפון.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            items(projects, key = { it.id }) { p ->
                ProjectCard(p, onOpen = { nav.go(Screen.Project(p.id)) }, onDelete = { toDelete = p })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    error?.let { msg ->
        AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton(onClick = { error = null }) { Text("אישור") } },
            text = { Text(msg) })
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("למחוק את הפרויקט?") },
            text = { Text("הפרויקט והתמלול יימחקו מהאפליקציה. הסרטון המקורי והקבצים שייצאת לגלריה לא יימחקו.") },
            confirmButton = { TextButton(onClick = { ProjectStore.delete(p.id); toDelete = null }) { Text("מחק") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("ביטול") } }
        )
    }
}

private fun createProject(ctx: Context, uri: Uri): Project {
    runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    var name = "סרטון"
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0)?.substringBeforeLast('.') ?: name
    }
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        if (dur <= 0) throw IllegalStateException("הקובץ לא נראה כמו סרטון תקין")
        val p = Project(
            id = UUID.randomUUID().toString(), name = name, videoUri = uri.toString(),
            durationMs = dur, width = w, height = h, createdAt = System.currentTimeMillis(),
            language = Prefs.lastLanguage,
            log = listOf(com.haessentz.videoeditor.data.LogEntry(false, "הסרטון נטען (${fmtMs(dur)}). כתוב פקודה, או \"עזרה\" לרשימת הפקודות."))
        )
        ProjectStore.put(p)
        return p
    } finally {
        r.release()
    }
}

@Composable
private fun ProjectCard(p: Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.id) {
        value = withContext(Dispatchers.IO) { coverFor(ctx, p) }
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(Color(0xFF2A241D))) {
                thumb?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                Text(fmtMs(p.durationMs), Modifier.align(Alignment.BottomStart).padding(5.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(com.haessentz.videoeditor.media.Langs.flag(p.language))
                    when {
                        p.transcribed -> Pill("מתומלל ✓", MaterialTheme.colorScheme.tertiaryContainer)
                        p.transcript.isNotEmpty() -> Pill("תמלול חלקי")
                        else -> Pill("לא מתומלל")
                    }
                }
                val extra = buildList {
                    if (p.shorts.isNotEmpty()) add("${p.shorts.size} שורטים")
                    if (p.outputs.isNotEmpty()) add("${p.outputs.size} קבצים")
                    if (p.youtube.lastVideoId != null) add("ביוטיוב ✓")
                }
                if (extra.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "מחק", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Text(text, Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall)
}

/** A frame from the video, cached in the project folder. */
private fun coverFor(ctx: Context, p: Project): androidx.compose.ui.graphics.ImageBitmap? {
    val f = java.io.File(ProjectStore.dir(p.id), "cover.jpg")
    if (!f.exists()) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(p.videoUri))
            val bmp = r.getScaledFrameAtTime((p.durationMs / 10).coerceAtMost(60_000) * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480) ?: return null
            java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (_: Exception) {
            return null
        } finally { runCatching { r.release() } }
    }
    return android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap()
}
