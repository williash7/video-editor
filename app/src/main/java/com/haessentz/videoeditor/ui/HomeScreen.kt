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
                title = { Text("עורך וידאו") },
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
            if (!ModelManager.isReady(ctx, Prefs.activeModel)) {
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
                    Text(
                        "עוד אין פרויקטים.\nלחץ על \"סרטון חדש\" ובחר סרטון מהטלפון. הסרטון לא עולה לשום מקום — הכול נעשה על הטלפון.",
                        Modifier.padding(vertical = 32.dp), style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            items(projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { nav.go(Screen.Project(p.id)) }) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold, maxLines = 2)
                            Spacer(Modifier.height(2.dp))
                            val status = buildList {
                                add(fmtMs(p.durationMs))
                                add(if (p.transcribed) "מתומלל ✓" else if (p.transcript.isNotEmpty()) "תמלול חלקי" else "לא מתומלל")
                                if (p.shorts.isNotEmpty()) add("${p.shorts.size} שורטים")
                                if (p.outputs.isNotEmpty()) add("${p.outputs.size} קבצים")
                            }.joinToString(" · ")
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { toDelete = p }) { Icon(Icons.Default.Delete, "מחק") }
                        Spacer(Modifier.width(4.dp))
                    }
                }
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
            log = listOf(com.haessentz.videoeditor.data.LogEntry(false, "הסרטון נטען (${fmtMs(dur)}). כתוב פקודה, או \"עזרה\" לרשימת הפקודות."))
        )
        ProjectStore.put(p)
        return p
    } finally {
        r.release()
    }
}
