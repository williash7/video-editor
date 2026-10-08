@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.haessentz.videoeditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.Nav
import com.haessentz.videoeditor.Screen
import com.haessentz.videoeditor.cmd.CommandParser
import com.haessentz.videoeditor.cmd.CommandRunner
import com.haessentz.videoeditor.data.Cues
import com.haessentz.videoeditor.data.Prefs
import com.haessentz.videoeditor.data.Project
import com.haessentz.videoeditor.data.ProjectStore
import com.haessentz.videoeditor.data.Range
import com.haessentz.videoeditor.data.Ranges
import com.haessentz.videoeditor.data.Seg
import com.haessentz.videoeditor.data.ShortClip
import com.haessentz.videoeditor.data.SubStyle
import com.haessentz.videoeditor.data.fmtMs
import com.haessentz.videoeditor.media.ModelManager
import com.haessentz.videoeditor.work.JobManager
import com.haessentz.videoeditor.work.Jobs
import java.util.UUID

@UnstableApi
@Composable
fun ProjectScreen(pid: String, nav: Nav) {
    val projects by ProjectStore.projects.collectAsState()
    val p = projects.firstOrNull { it.id == pid }
    if (p == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val player = rememberPlayer(p.videoUri)
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("פקודות", "תמלול", "בינה", "רשימת חיתוך", "שורטים", "יוטיוב", "קבצים")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } }
            )
        },
        bottomBar = { JobsBar(pid) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            Box(Modifier.fillMaxWidth().height(210.dp).background(Color.Black)) {
                VideoView(player, Modifier.fillMaxSize())
            }
            Text(
                "מיקום: ${fmtMs(player.positionMs, tenths = true)} / ${fmtMs(p.durationMs)}",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium
            )
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> CommandsTab(p, player)
                    1 -> TranscriptTab(p, player, nav)
                    2 -> AiTab(p, player, onDone = { tab = 5 })
                    3 -> CutListTab(p, player)
                    4 -> ShortsTab(p, player, nav)
                    5 -> YoutubeTab(p, player)
                    else -> FilesTab(p)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ commands

@UnstableApi
@Composable
private fun CommandsTab(p: Project, player: PlayerState) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(p.log.size) { if (p.log.isNotEmpty()) listState.animateScrollToItem(p.log.size - 1) }
    fun send(text: String) {
        if (text.isBlank()) return
        CommandRunner.run(ctx, p.id, text)
        input = ""
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(p.log.size) { i ->
                val e = p.log[i]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (e.fromUser) Arrangement.Start else Arrangement.End) {
                    Column(
                        Modifier.widthIn(max = 320.dp)
                            .background(
                                if (e.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(e.text, style = MaterialTheme.typography.bodyMedium)
                        if (e.hits.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                e.hits.forEach { h -> SuggestionChip(onClick = { player.seek(h.ms) }, label = { Text("▶ ${h.label}") }) }
                            }
                        }
                    }
                }
            }
        }
        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("עזרה", "תמלל", "הסר שתיקות", "רשימה", "ייצא").forEach { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }) }
            AssistChip(onClick = { input = "${input.trimEnd()} ${fmtMs(player.positionMs)}".trimStart() }, label = { Text("+ זמן נוכחי") })
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("למשל: תחתוך מ-1:20 עד 3:45") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                maxLines = 3
            )
            IconButton(onClick = { send(input) }) { Icon(Icons.AutoMirrored.Filled.Send, "שלח") }
        }
    }
}

// ------------------------------------------------------------------ transcript

@UnstableApi
@Composable
private fun TranscriptTab(p: Project, player: PlayerState, nav: Nav) {
    val ctx = LocalContext.current
    val jobs by JobManager.jobs.collectAsState()
    val running = jobs.any { it.projectId == p.id && it.kind == "transcribe" && it.active }
    var filter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }
    val modelReady = ModelManager.isReady(ctx, Prefs.activeModel)

    if (p.transcript.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (running) {
                Text("מתמלל… ההתקדמות מופיעה בפס למטה. המשפטים יופיעו כאן תוך כדי.")
            } else {
                Text("הסרטון עוד לא תומלל. התמלול נעשה על הטלפון בלבד, בלי אינטרנט.")
                Text("מודל: ${ModelManager.title(Prefs.activeModel)}${if (modelReady) "" else " (לא הורד)"}", style = MaterialTheme.typography.bodySmall)
                if (modelReady) Button(onClick = { Jobs.transcribe(ctx, p.id) }) { Text("תמלל עכשיו") }
                else Button(onClick = { nav.go(Screen.Models) }) { Text("להורדת מודל") }
                Text("טיפ: בסרטון של שעה זה לוקח בערך חצי שעה. כדאי לחבר למטען.", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    val idxs = remember(p.transcript, filter) {
        p.transcript.indices.filter { filter.isBlank() || p.transcript[it].text.contains(filter.trim(), ignoreCase = true) }
    }
    val current = p.transcript.indexOfFirst { player.positionMs >= it.startMs && player.positionMs < it.endMs }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), placeholder = { Text("חיפוש בתמלול") }, singleLine = true)
            if (!running) TextButton(onClick = { CommandRunner.run(ctx, p.id, "שמור כתוביות") }) { Text("SRT") }
        }
        if (running) Text("עדיין מתמלל… מה שכבר תומלל נשמר, גם אם יוצאים מהאפליקציה.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        BatteryCard()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            items(idxs, key = { it }) { i ->
                val s = p.transcript[i]
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (i == current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { player.seek(s.startMs) }, onLongClick = { if (!running) editing = i })
                        .padding(8.dp)
                ) {
                    Text(fmtMs(s.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 52.dp))
                    Text(s.text, Modifier.weight(1f))
                }
            }
            item {
                Text("לחיצה = קפיצה לזמן. לחיצה ארוכה = תיקון הטקסט.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                if (!running) {
                    if (!p.transcribed) Button(onClick = { Jobs.transcribe(ctx, p.id) }, Modifier.padding(8.dp)) { Text("המשך תמלול") }
                    OutlinedButton(onClick = { Jobs.transcribe(ctx, p.id, fresh = true) }, Modifier.padding(8.dp)) { Text("תמלל מחדש מההתחלה") }
                }
            }
        }
    }
    editing?.let { i ->
        var text by remember(i) { mutableStateOf(p.transcript[i].text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("תיקון משפט · ${fmtMs(p.transcript[i].startMs)}") },
            text = { OutlinedTextField(text, { text = it }) },
            confirmButton = {
                TextButton(onClick = {
                    ProjectStore.update(p.id) { pr -> pr.copy(transcript = pr.transcript.mapIndexed { j, s -> if (j == i) s.copy(text = text) else s }) }
                    editing = null
                }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("ביטול") } }
        )
    }
}

// ------------------------------------------------------------------ cut list

@UnstableApi
@Composable
private fun CutListTab(p: Project, player: PlayerState) {
    val ctx = LocalContext.current
    var markIn by remember { mutableStateOf<Long?>(null) }
    val removed = p.cutList.sumOf { it.lengthMs }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("קטעים להוצאה מהסרטון. אפשר להוסיף בפקודה (\"תוציא 10:00-12:30\") או לסמן כאן תוך כדי צפייה.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { markIn = player.positionMs }) {
                    Text(if (markIn == null) "סמן התחלה" else "התחלה: ${fmtMs(markIn!!, true)}")
                }
                FilledTonalButton(enabled = markIn != null, onClick = {
                    val a = markIn!!; val b = player.positionMs
                    if (b != a) ProjectStore.update(p.id) {
                        it.copy(cutList = Ranges.normalize(it.cutList + Range(minOf(a, b), maxOf(a, b)), it.durationMs))
                    }
                    markIn = null
                }) { Text("סמן סוף והוסף") }
            }
        }
        items(p.cutList.size) { i ->
            val r = p.cutList[i]
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.  ${fmtMs(r.startMs, true)} – ${fmtMs(r.endMs, true)}   (${fmtMs(r.lengthMs)})", Modifier.weight(1f))
                    IconButton(onClick = { player.seek((r.startMs - 2000).coerceAtLeast(0)) }) { Icon(Icons.Default.PlayArrow, "נגן") }
                    IconButton(onClick = { ProjectStore.update(p.id) { it.copy(cutList = it.cutList.filterIndexed { j, _ -> j != i }) } }) {
                        Icon(Icons.Default.Delete, "מחק")
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("מוציא ${fmtMs(removed)} · אורך אחרי עריכה: ${fmtMs(p.durationMs - removed)}", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = p.cutList.isNotEmpty(), onClick = { CommandRunner.run(ctx, p.id, "ייצא") }) { Text("ייצא סרטון ערוך") }
                OutlinedButton(enabled = p.cutList.isNotEmpty(), onClick = { CommandRunner.run(ctx, p.id, "נקה רשימה") }) { Text("נקה") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { CommandRunner.run(ctx, p.id, "הסר שתיקות") }) { Text("הסר שתיקות אוטומטית") }
        }
    }
}

// ------------------------------------------------------------------ shorts

@UnstableApi
@Composable
private fun ShortsTab(p: Project, player: PlayerState, nav: Nav) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("שורט = קטע אנכי 9:16 עם כתוביות. אפשר ליצור בפקודה (\"שורט 12:30-13:30\") או מכאן.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val start = player.positionMs
                val end = (start + 60_000).coerceAtMost(p.durationMs)
                val style = SubStyle()
                val s = ShortClip(
                    UUID.randomUUID().toString(), "שורט ${p.shorts.size + 1}", start, end,
                    cues = Cues.forClip(p.transcript, start, end, style.maxWords),
                    subtitles = p.transcript.isNotEmpty(), style = style
                )
                ProjectStore.update(p.id) { it.copy(shorts = it.shorts + s) }
                nav.go(Screen.ShortEditor(p.id, s.id))
            }) { Text("שורט חדש מהמיקום הנוכחי (${fmtMs(player.positionMs)})") }
        }
        items(p.shorts, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.name, fontWeight = FontWeight.Bold)
                    Text("${fmtMs(s.startMs)} – ${fmtMs(s.endMs)} · ${(s.endMs - s.startMs) / 1000} שניות · " +
                            if (s.subtitles) "${s.cues.size} כתוביות" else "בלי כתוביות", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { nav.go(Screen.ShortEditor(p.id, s.id)) }) {
                            Icon(Icons.Default.Edit, null); Text(" עריכה")
                        }
                        Button(onClick = { Jobs.exportShort(ctx, p.id, s.id) }) { Text("ייצא") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { ProjectStore.update(p.id) { it.copy(shorts = it.shorts.filter { x -> x.id != s.id }) } }) {
                            Icon(Icons.Default.Delete, "מחק")
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ files

@Composable
private fun FilesTab(p: Project) {
    val ctx = LocalContext.current
    if (p.outputs.isEmpty()) {
        Text("עוד לא נוצרו קבצים. כל קובץ שתייצא יישמר בגלריה, בתיקייה VideoEditor.", Modifier.padding(20.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(p.outputs, key = { it.uri }) { o ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { openVideo(ctx, o.uri) }) { Icon(Icons.Default.PlayArrow, "פתח") }
                    IconButton(onClick = { shareVideo(ctx, o.uri) }) { Icon(Icons.Default.Share, "שתף") }
                }
            }
        }
    }
}
