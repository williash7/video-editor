@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.haessentz.videoeditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import com.haessentz.videoeditor.data.outLengthMs
import com.haessentz.videoeditor.data.keep
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
    val ctx = LocalContext.current
    // the newest edited export that still exists on the phone
    val edited = remember(p.outputs) {
        p.outputs.filter { it.kind == "edited" }.firstOrNull { o ->
            runCatching { ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(o.uri), "r")?.use { true } ?: false }.getOrDefault(false)
        }
    }
    var showEdited by remember(edited?.uri) { mutableStateOf(edited != null) }
    val source = if (showEdited && edited != null) edited else null
    val player = rememberPlayer(source?.uri ?: p.videoUri, source?.removed ?: emptyList())
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("⌨ פקודות", "📝 תמלול", "✨ בינה", "✂ חיתוך", "📱 שורטים", "▶ יוטיוב", "📁 קבצים")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text("${com.haessentz.videoeditor.media.Langs.flag(p.language)} ${fmtMs(p.durationMs)}" +
                                (if (p.transcribed) " · מתומלל" else "") + (if (p.shorts.isNotEmpty()) " · ${p.shorts.size} שורטים" else ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } }
            )
        },
        bottomBar = { JobsBar(pid) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            Box(Modifier.fillMaxWidth().height(210.dp).background(Color.Black)) {
                VideoView(player, Modifier.fillMaxSize())
                // preview of the on-screen corrections (an edited export already has them burned in)
                if (source == null) {
                    val note = p.notes.firstOrNull { player.positionMs >= it.ms && player.positionMs < it.ms + com.haessentz.videoeditor.data.noteDurationMs(it.text) }
                    if (note != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c -> com.haessentz.videoeditor.media.NoteRenderer.draw(c.nativeCanvas, note.text, size.width.toInt(), size.height.toInt()) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (source != null) "ערוך: ${fmtMs(player.fileMs, tenths = true)} / ${fmtMs(p.durationMs - source.removed.sumOf { it.lengthMs })} · במקור ${fmtMs(player.positionMs)}"
                    else "מיקום: ${fmtMs(player.positionMs, tenths = true)} / ${fmtMs(p.durationMs)}",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium
                )
                if (edited != null) {
                    androidx.compose.material3.FilterChip(showEdited, { showEdited = true }, label = { Text("ערוך") })
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.FilterChip(!showEdited, { showEdited = false }, label = { Text("מקורי") })
                }
            }
            TimelineBar(p, player)
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.surface,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(t, style = MaterialTheme.typography.labelLarge) })
                }
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
                                if (e.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                if (e.fromUser) RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp) else RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                            )
                            .then(if (e.fromUser) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
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
    val modelId = Prefs.modelFor(p.language)
    val modelReady = ModelManager.isReady(ctx, modelId)

    if (p.transcript.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (running) {
                Text("מתמלל… ההתקדמות מופיעה בפס למטה. המשפטים יופיעו כאן תוך כדי.")
            } else {
                Text("הסרטון עוד לא תומלל. התמלול נעשה על הטלפון בלבד, בלי אינטרנט.")
                Text("באיזו שפה מדברים בסרטון?", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.haessentz.videoeditor.media.Langs.all.forEach { l ->
                        androidx.compose.material3.FilterChip(
                            selected = p.language == l,
                            onClick = { Prefs.lastLanguage = l; ProjectStore.update(p.id) { it.copy(language = l) } },
                            label = { Text("${com.haessentz.videoeditor.media.Langs.flag(l)} ${com.haessentz.videoeditor.media.Langs.name(l)}") })
                    }
                }
                Text("מודל: ${ModelManager.title(modelId)}${if (modelReady) "" else " (לא הורד)"}", style = MaterialTheme.typography.bodySmall)
                if (modelReady) Button(onClick = { Jobs.transcribe(ctx, p.id) }) { Text("תמלל עכשיו") }
                else Button(onClick = { nav.go(Screen.Models) }) { Text("להורדת מודל") }
                Text("טיפ: כדאי לחבר למטען. מה שמתומלל נשמר תוך כדי, גם אם יוצאים מהאפליקציה.", style = MaterialTheme.typography.bodySmall)
                BatteryCard()
            }
        }
        return
    }

    val idxs = remember(p.transcript, filter) {
        p.transcript.indices.filter { filter.isBlank() || p.transcript[it].text.contains(filter.trim(), ignoreCase = true) }
    }
    val current = p.transcript.indexOfFirst { player.positionMs >= it.startMs && player.positionMs < it.endMs }
    val loopBad = remember(p.transcript) { com.haessentz.videoeditor.data.Loops.badIndices(p.transcript) }
    val loopRanges = remember(p.transcript) { com.haessentz.videoeditor.data.Loops.badRanges(p.transcript) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), placeholder = { Text("חיפוש בתמלול") }, singleLine = true)
            if (!running) TextButton(onClick = { CommandRunner.run(ctx, p.id, "שמור כתוביות") }) { Text("SRT") }
        }
        if (running) Text("עדיין מתמלל… מה שכבר תומלל נשמר, גם אם יוצאים מהאפליקציה.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        BatteryCard()
        if (!running && loopRanges.isNotEmpty()) {
            androidx.compose.material3.Card(
                Modifier.fillMaxWidth().padding(8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚠ בתמלול יש ${loopRanges.size} קטעים שבהם התמלול נתקע וחזר על אותן מילים " +
                        "(${fmtMs(loopRanges.sumOf { it.lengthMs })} בסך הכול). הם מסומנים באדום.", fontWeight = FontWeight.Bold)
                    Text(loopRanges.joinToString(" · ") { "${fmtMs(it.startMs)}–${fmtMs(it.endMs)}" }, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { Jobs.repairTranscript(ctx, p.id) }) { Text("תמלל מחדש רק את הקטעים האלה") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            items(idxs, key = { it }) { i ->
                val s = p.transcript[i]
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            when {
                                i == current -> MaterialTheme.colorScheme.primaryContainer
                                i in loopBad -> MaterialTheme.colorScheme.errorContainer
                                else -> Color.Transparent
                            }, RoundedCornerShape(8.dp))
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
                Button(enabled = p.cutList.isNotEmpty() || (p.burnNotes && p.notes.isNotEmpty()), onClick = { CommandRunner.run(ctx, p.id, "ייצא") }) { Text("ייצא סרטון ערוך") }
                OutlinedButton(enabled = p.cutList.isNotEmpty(), onClick = { CommandRunner.run(ctx, p.id, "נקה רשימה") }) { Text("נקה") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { CommandRunner.run(ctx, p.id, "הסר שתיקות") }) { Text("הסר שתיקות אוטומטית") }
        }
        item { NotesSection(p, player) }
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
                    val outLen = s.outLengthMs(p.cutList)
                    Text("${fmtMs(s.startMs)} – ${fmtMs(s.endMs)} · ${outLen / 1000} שניות" +
                            (if (s.pieces.size > 1) " · ${s.pieces.size} קטעים מחוברים" else "") +
                            " · " + (if (s.subtitles) "${s.cues.size} כתוביות" else "בלי כתוביות"),
                        style = MaterialTheme.typography.bodySmall)
                    if (s.about.isNotBlank()) Text(s.about, style = MaterialTheme.typography.bodySmall)
                    val words = s.keep().joinToString(" … ") { shortText(p, it) }
                    if (words.isNotBlank()) Text("״" + words.take(180) + (if (words.length > 180) "…" else "") + "״",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { nav.go(Screen.ShortEditor(p.id, s.id)) }) {
                            Icon(Icons.Default.Edit, null); Text(" עריכה")
                        }
                        Button(onClick = { Jobs.exportShort(ctx, p.id, s.id) }) { Text("ייצא") }
                        IconButton(onClick = { player.seek(s.startMs) }) { Icon(Icons.Default.PlayArrow, "נגן בסרטון") }
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

/**
 * A bar under the player for the whole (original) video: removed parts in red, shorts in the main colour,
 * and the current position. Tap or drag to jump.
 */
@Composable
fun TimelineBar(p: Project, player: PlayerState) {
    val dur = p.durationMs.coerceAtLeast(1).toFloat()
    val cutColor = MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    val shortColor = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val head = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(22.dp)
                .pointerInput(p.id) {
                    detectTapGestures { o -> player.seek((o.x / size.width * dur).toLong(), play = false) }
                }
                .pointerInput(p.id) {
                    detectHorizontalDragGestures { change, _ ->
                        player.seek((change.position.x / size.width * dur).toLong().coerceIn(0, p.durationMs), play = false)
                    }
                }
        ) {
            val w = size.width; val h = size.height
            fun x(ms: Long) = ms / dur * w
            drawRect(bg, topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.3f), size = androidx.compose.ui.geometry.Size(w, h * 0.4f))
            p.cutList.forEach { r ->
                drawRect(cutColor, topLeft = androidx.compose.ui.geometry.Offset(x(r.startMs), h * 0.3f),
                    size = androidx.compose.ui.geometry.Size((x(r.endMs) - x(r.startMs)).coerceAtLeast(2f), h * 0.4f))
            }
            p.shorts.forEach { sh ->
                sh.keep().forEach { pc ->
                    drawRect(shortColor, topLeft = androidx.compose.ui.geometry.Offset(x(pc.startMs), h * 0.05f),
                        size = androidx.compose.ui.geometry.Size((x(pc.endMs) - x(pc.startMs)).coerceAtLeast(3f), h * 0.2f))
                }
            }
            val px = x(player.positionMs)
            drawRect(head, topLeft = androidx.compose.ui.geometry.Offset(px - 1.5f, 0f), size = androidx.compose.ui.geometry.Size(3f, h))
        }
        if (p.shorts.isNotEmpty() || p.cutList.isNotEmpty())
            Text("למעלה: שורטים · באמצע באדום: קטעים שנמחקו", style = MaterialTheme.typography.labelSmall)
    }
}

/** Factual corrections: an asterisk with the fix appears at the top of the screen when the mistake is said. */
@Composable
private fun NotesSection(p: Project, player: PlayerState) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✱ תיקונים עובדתיים על המסך", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(p.burnNotes, { v -> ProjectStore.update(p.id) { it.copy(burnNotes = v) } })
            }
            Text("כשנאמר משהו לא מדויק, יופיע למעלה במסך כוכבית עם התיקון. הבינה מציעה אותם, ואפשר להוסיף גם ידנית.",
                style = MaterialTheme.typography.bodySmall)
            p.notes.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seek((n.ms - 2000).coerceAtLeast(0)) }) { Icon(Icons.Default.PlayArrow, "נגן") }
                    Column(Modifier.weight(1f).clickable { editing = i }) {
                        Text(fmtMs(n.ms), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(n.text, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { ProjectStore.update(p.id) { it.copy(notes = it.notes.filterIndexed { j, _ -> j != i }) } }) {
                        Icon(Icons.Default.Delete, "מחק")
                    }
                }
            }
            FilledTonalButton(onClick = {
                ProjectStore.update(p.id) { it.copy(notes = (it.notes + com.haessentz.videoeditor.data.FactNote(player.positionMs, "")).sortedBy { n -> n.ms }) }
                editing = p.notes.count { it.ms <= player.positionMs }
            }) { Text("+ תיקון ב־${fmtMs(player.positionMs)}") }
            if (p.notes.isNotEmpty() && p.burnNotes)
                Text("התיקונים ייכנסו לסרטון הערוך ולשורטים בייצוא הבא.", style = MaterialTheme.typography.labelSmall)
        }
    }
    editing?.let { i ->
        val n = p.notes.getOrNull(i) ?: run { editing = null; return }
        var text by remember(i) { mutableStateOf(n.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("תיקון ב־${fmtMs(n.ms)}") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("למשל: המקור הוא תניא פרק כ\"ו") }) },
            confirmButton = {
                TextButton(onClick = {
                    ProjectStore.update(p.id) { pr ->
                        pr.copy(notes = pr.notes.mapIndexed { j, x -> if (j == i) x.copy(text = text.trim()) else x }.filter { it.text.isNotBlank() })
                    }
                    editing = null
                }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = {
                ProjectStore.update(p.id) { pr -> pr.copy(notes = pr.notes.filter { it.text.isNotBlank() }) }
                editing = null
            }) { Text("ביטול") } }
        )
    }
}
