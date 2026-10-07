@file:OptIn(ExperimentalMaterial3Api::class)

package com.haessentz.videoeditor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.Nav
import com.haessentz.videoeditor.data.Prefs
import com.haessentz.videoeditor.media.ModelInfo
import com.haessentz.videoeditor.media.ModelManager
import com.haessentz.videoeditor.media.WhisperLib
import com.haessentz.videoeditor.work.JobManager
import com.haessentz.videoeditor.work.Jobs

@UnstableApi
@Composable
fun ModelsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val jobs by JobManager.jobs.collectAsState()
    var active by remember { mutableStateOf(Prefs.activeModel) }
    var threads by remember { mutableIntStateOf(Prefs.threads) }
    var refresh by remember { mutableIntStateOf(0) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Jobs.importModel(ctx, uri)
    }
    // re-read file states whenever jobs change
    val ready = remember(jobs, refresh) {
        (ModelManager.models.map { it.id } + ModelManager.CUSTOM_ID).associateWith { ModelManager.isReady(ctx, it) }
    }
    if (ready[active] != true) {
        val firstReady = ready.entries.firstOrNull { it.value }?.key
        if (firstReady != null && firstReady != active) { active = firstReady; Prefs.activeModel = firstReady }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("הגדרות תמלול") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } }
            )
        },
        bottomBar = { JobsBar() }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "המודל יורד פעם אחת לטלפון. אחרי זה התמלול עובד בלי אינטרנט ובלי לשלוח כלום לשום מקום. מומלץ להוריד ב־Wi‑Fi.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items(ModelManager.models, key = { it.id }) { m ->
                val job = jobs.firstOrNull { it.kind == "model:${m.id}" && it.active }
                ModelCard(
                    m, ready[m.id] == true, active == m.id, job?.progress, job?.detail,
                    onSelect = { active = m.id; Prefs.activeModel = m.id },
                    onDownload = { Jobs.downloadModel(ctx, m.id) },
                    onCancel = { job?.let { JobManager.cancel(it.id) } },
                    onDelete = { ModelManager.file(ctx, m.id).delete(); refresh++ }
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ready[ModelManager.CUSTOM_ID] == true) {
                                RadioButton(selected = active == ModelManager.CUSTOM_ID, onClick = {
                                    active = ModelManager.CUSTOM_ID; Prefs.activeModel = ModelManager.CUSTOM_ID
                                })
                            }
                            Text("מודל מקובץ בטלפון", fontWeight = FontWeight.Bold)
                        }
                        Text("אם יש לך קובץ מודל של whisper.cpp ‏(.bin), אפשר לייבא אותו.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("ייבוא קובץ…") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("מספר ליבות לתמלול: $threads", fontWeight = FontWeight.Bold)
                        Text("יותר ליבות = מהיר יותר, אבל הטלפון מתחמם יותר. ברירת מחדל: ${Prefs.defaultThreads}.",
                            style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = threads.toFloat(), onValueChange = { threads = it.toInt(); Prefs.threads = threads },
                            valueRange = 2f..10f, steps = 7
                        )
                    }
                }
            }
            item {
                val info = remember { runCatching { WhisperLib.systemInfo() }.getOrElse { "—" } }
                Text("מידע טכני: $info", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ModelCard(
    m: ModelInfo, ready: Boolean, selected: Boolean, progress: Float?, detail: String?,
    onSelect: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ready) RadioButton(selected = selected, onClick = onSelect)
                Column(Modifier.weight(1f)) {
                    Text(m.title, fontWeight = FontWeight.Bold)
                    Text("${m.sizeMb} MB", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(m.desc, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            when {
                progress != null -> {
                    if (progress >= 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(detail ?: "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onCancel) { Text("בטל") }
                    }
                }
                ready -> Row {
                    Text(if (selected) "בשימוש ✓" else "מוכן", Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = onDelete) { Text("מחק") }
                }
                else -> Row {
                    Button(onClick = onDownload) { Text("הורד") }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
