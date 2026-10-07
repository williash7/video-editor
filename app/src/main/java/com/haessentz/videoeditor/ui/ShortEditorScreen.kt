@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.haessentz.videoeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.Nav
import com.haessentz.videoeditor.data.Cues
import com.haessentz.videoeditor.data.Project
import com.haessentz.videoeditor.data.ProjectStore
import com.haessentz.videoeditor.data.Seg
import com.haessentz.videoeditor.data.ShortClip
import com.haessentz.videoeditor.data.SubStyle
import com.haessentz.videoeditor.data.fmtMs
import com.haessentz.videoeditor.media.SubtitleRenderer
import com.haessentz.videoeditor.work.Jobs
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

@UnstableApi
@Composable
fun ShortEditorScreen(pid: String, sid: String, nav: Nav) {
    val ctx = LocalContext.current
    val projects by ProjectStore.projects.collectAsState()
    val p = projects.firstOrNull { it.id == pid }
    val s = p?.shorts?.firstOrNull { it.id == sid }
    if (p == null || s == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    fun save(f: (ShortClip) -> ShortClip) {
        ProjectStore.update(pid) { pr -> pr.copy(shorts = pr.shorts.map { if (it.id == sid) f(it) else it }) }
    }

    val player = rememberPlayer(p.videoUri)
    LaunchedEffect(player) { player.seek(s.startMs, play = false) }
    // keep playback inside the short
    val rel = player.positionMs - s.startMs
    LaunchedEffect(player.positionMs) {
        if (player.playing && player.positionMs >= s.endMs) player.seek(s.startMs)
    }
    var tab by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.name, Modifier.clickable { renaming = true }) },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                actions = { Button(onClick = { Jobs.exportShort(ctx, pid, sid) }, Modifier.padding(end = 8.dp)) { Text("ייצא") } }
            )
        },
        bottomBar = { JobsBar(pid) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShortPreview(p, s, player, rel, Modifier.height(300.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${fmtMs(rel.coerceAtLeast(0), true)} / ${fmtMs(s.endMs - s.startMs, true)}", fontWeight = FontWeight.Bold)
                    Row {
                        IconButton(onClick = {
                            if (player.positionMs < s.startMs || player.positionMs >= s.endMs) player.seek(s.startMs) else player.toggle()
                        }, Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)) {
                            if (player.playing) Text("❚❚") else Icon(Icons.Default.PlayArrow, "נגן")
                        }
                    }
                    Text("גבולות השורט", style = MaterialTheme.typography.labelMedium)
                    TrimRow("התחלה", s.startMs, player.positionMs,
                        onNudge = { d -> save { it.withRange((it.startMs + d).coerceIn(0, it.endMs - 1000), it.endMs, p) } },
                        onSetHere = { save { it.withRange(player.positionMs.coerceIn(0, it.endMs - 1000), it.endMs, p) } })
                    TrimRow("סוף", s.endMs, player.positionMs,
                        onNudge = { d -> save { it.withRange(it.startMs, (it.endMs + d).coerceIn(it.startMs + 1000, p.durationMs), p) } },
                        onSetHere = { save { it.withRange(it.startMs, player.positionMs.coerceIn(it.startMs + 1000, p.durationMs), p) } })
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("כתוביות") })
                Tab(tab == 1, { tab = 1 }, text = { Text("עיצוב") })
                Tab(tab == 2, { tab = 2 }, text = { Text("מסגור") })
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> CuesEditor(p, s, player, rel, ::save)
                    1 -> StyleEditor(s, ::save, regenerate = { words ->
                        save { it.copy(cues = Cues.forClip(p.transcript, it.startMs, it.endMs, words), style = it.style.copy(maxWords = words)) }
                    }, hasTranscript = p.transcript.isNotEmpty())
                    else -> FrameEditor(p, s, ::save)
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("שם השורט") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { save { it.copy(name = name.ifBlank { it.name }) }; renaming = false }) { Text("שמור") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("ביטול") } }
        )
    }
}

/** Changing the range keeps hand-edited cues but drops those that fall outside, and shifts their times. */
private fun ShortClip.withRange(start: Long, end: Long, p: Project): ShortClip {
    val shift = startMs - start
    val moved = cues.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) }
        .filter { it.endMs > 0 && it.startMs < end - start }
        .map { it.copy(startMs = it.startMs.coerceAtLeast(0), endMs = it.endMs.coerceAtMost(end - start)) }
    // fill in transcript cues for newly uncovered time
    val extra = Cues.forClip(p.transcript, start, end, style.maxWords).filter { c ->
        moved.none { m -> c.startMs < m.endMs && c.endMs > m.startMs }
    }
    return copy(startMs = start, endMs = end, cues = (moved + extra).sortedBy { it.startMs })
}

@Composable
private fun TrimRow(label: String, value: Long, here: Long, onNudge: (Long) -> Unit, onSetHere: () -> Unit) {
    Column {
        Text("$label: ${fmtMs(value, true)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallBtn("‎-1s") { onNudge(-1000) }
            SmallBtn("‎+1s") { onNudge(1000) }
            SmallBtn("כאן") { onSetHere() }
        }
    }
}

@Composable
private fun SmallBtn(text: String, onClick: () -> Unit) {
    Box(
        Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp)
    ) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

/** 9:16 live preview: crops the video exactly like the export and draws subtitles with the same renderer. */
@UnstableApi
@Composable
private fun ShortPreview(p: Project, s: ShortClip, player: PlayerState, relMs: Long, modifier: Modifier) {
    val cue = if (s.subtitles) s.cues.firstOrNull { relMs >= it.startMs && relMs < it.endMs } else null
    BoxWithConstraints(modifier.aspectRatio(9f / 16f).background(Color.Black)) {
        val boxW = maxWidth
        val boxH = maxHeight
        val srcAspect = p.width.toFloat() / p.height.coerceAtLeast(1)
        val target = 9f / 16f
        if (srcAspect > target) {
            val vidW = boxH * srcAspect
            val dx = -(vidW - boxW) * s.cropX
            Box(Modifier.fillMaxSize().clipToBounds()) {
                VideoView(player, Modifier.requiredSize(vidW, boxH).absoluteOffset(x = dx + (vidW - boxW) / 2), controls = false, texture = true)
            }
        } else {
            VideoView(player, Modifier.fillMaxSize(), controls = false, zoom = true, texture = true)
        }
        if (cue != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c -> SubtitleRenderer.draw(c.nativeCanvas, cue.text, s.style, size.width.toInt(), size.height.toInt()) }
            }
        }
    }
}


// ------------------------------------------------------------------ cues

@Composable
private fun CuesEditor(p: Project, s: ShortClip, player: PlayerState, relMs: Long, save: ((ShortClip) -> ShortClip) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("להציג כתוביות", Modifier.weight(1f))
            Switch(checked = s.subtitles, onCheckedChange = { v -> save { it.copy(subtitles = v) } })
        }
        if (s.cues.isEmpty()) {
            Text(
                if (p.transcript.isEmpty()) "אין תמלול לסרטון הזה, אז אין כתוביות אוטומטיות. אפשר לתמלל מלשונית תמלול, או להוסיף כתוביות ידנית."
                else "אין כתוביות בקטע הזה.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall
            )
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(s.cues) { i, c ->
                val active = relMs >= c.startMs && relMs < c.endMs
                CueCard(c, active,
                    onSeek = { player.seek(s.startMs + c.startMs) },
                    onText = { t -> save { it.copy(cues = it.cues.mapIndexed { j, x -> if (j == i) x.copy(text = t) else x }) } },
                    onStartHere = { save { it.copy(cues = it.cues.mapIndexed { j, x -> if (j == i) x.copy(startMs = relMs.coerceIn(0, x.endMs - 100)) else x }) } },
                    onEndHere = { save { it.copy(cues = it.cues.mapIndexed { j, x -> if (j == i) x.copy(endMs = relMs.coerceAtLeast(x.startMs + 100)) else x }) } },
                    onNudge = { ds, de -> save { it.copy(cues = it.cues.mapIndexed { j, x ->
                        if (j == i) x.copy(startMs = (x.startMs + ds).coerceAtLeast(0), endMs = (x.endMs + de).coerceAtLeast(x.startMs + ds + 100)) else x }) } },
                    onSplit = { save { it.copy(cues = splitCue(it.cues, i, relMs)) } },
                    onMerge = if (i < s.cues.lastIndex) ({ save { it.copy(cues = mergeCue(it.cues, i)) } }) else null,
                    onDelete = { save { it.copy(cues = it.cues.filterIndexed { j, _ -> j != i }) } },
                )
            }
            item {
                OutlinedButton(onClick = {
                    val start = relMs.coerceIn(0, (s.endMs - s.startMs - 500).coerceAtLeast(0))
                    val next = s.cues.firstOrNull { it.startMs > start }?.startMs ?: (s.endMs - s.startMs)
                    val end = minOf(start + 2000, next).coerceAtLeast(start + 300)
                    save { it.copy(cues = (it.cues + Seg(start, end, "טקסט חדש")).sortedBy { c -> c.startMs }) }
                }) { Icon(Icons.Default.Add, null); Text(" הוסף כתובית בנקודה הנוכחית") }
            }
        }
    }
}

private fun splitCue(cues: List<Seg>, i: Int, atMs: Long): List<Seg> {
    val c = cues[i]
    val words = c.text.trim().split(Regex("\\s+"))
    if (words.size < 2) return cues
    val at = if (atMs > c.startMs + 100 && atMs < c.endMs - 100) atMs else (c.startMs + c.endMs) / 2
    val ratio = (at - c.startMs).toFloat() / (c.endMs - c.startMs)
    val k = (words.size * ratio).roundToInt().coerceIn(1, words.size - 1)
    val a = Seg(c.startMs, at, words.take(k).joinToString(" "))
    val b = Seg(at, c.endMs, words.drop(k).joinToString(" "))
    return cues.take(i) + a + b + cues.drop(i + 1)
}

private fun mergeCue(cues: List<Seg>, i: Int): List<Seg> {
    val a = cues[i]
    val b = cues[i + 1]
    return cues.take(i) + Seg(a.startMs, b.endMs, a.text.trim() + " " + b.text.trim()) + cues.drop(i + 2)
}

@Composable
private fun CueCard(
    c: Seg, active: Boolean, onSeek: () -> Unit, onText: (String) -> Unit, onStartHere: () -> Unit, onEndHere: () -> Unit,
    onNudge: (Long, Long) -> Unit, onSplit: () -> Unit, onMerge: (() -> Unit)?, onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().then(if (active) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▶ ${fmtMs(c.startMs, true)} → ${fmtMs(c.endMs, true)}", Modifier.clickable(onClick = onSeek).padding(4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "מחק") }
            }
            var text by remember(c.text) { mutableStateOf(c.text) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onText(it) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallBtn("התחלה כאן", onStartHere)
                SmallBtn("סוף כאן", onEndHere)
                SmallBtn("‎◀ 0.2") { onNudge(-200, -200) }
                SmallBtn("‎0.2 ▶") { onNudge(200, 200) }
                SmallBtn("פצל", onSplit)
                if (onMerge != null) SmallBtn("מזג עם הבא", onMerge)
            }
        }
    }
}

// ------------------------------------------------------------------ style

private val textColors = listOf(0xFFFFFFFF, 0xFFFFE600, 0xFF7CFC8A, 0xFF5CE1FF, 0xFFFFA24C, 0xFF000000)
private val boxColors = listOf(0x00000000L, 0xAA000000, 0xDDFFFFFF, 0xCC7A4A22)

@Composable
private fun StyleEditor(s: ShortClip, save: ((ShortClip) -> ShortClip) -> Unit, regenerate: (Int) -> Unit, hasTranscript: Boolean) {
    val st = s.style
    fun set(f: (SubStyle) -> SubStyle) = save { it.copy(style = f(it.style)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text("גודל טקסט", style = MaterialTheme.typography.labelLarge)
            Slider(st.sizePct, { v -> set { it.copy(sizePct = v) } }, valueRange = 2.5f..8f)
            Text("מיקום (למעלה ↔ למטה)", style = MaterialTheme.typography.labelLarge)
            Slider(st.posY, { v -> set { it.copy(posY = v) } }, valueRange = 0.08f..0.92f)
            Text("צבע טקסט", style = MaterialTheme.typography.labelLarge)
            ColorRow(textColors, st.textColor) { c -> set { it.copy(textColor = c) } }
            Text("עובי מסגרת לאותיות", style = MaterialTheme.typography.labelLarge)
            Slider(st.outlineWidth, { v -> set { it.copy(outlineWidth = v) } }, valueRange = 0f..0.3f)
            Text("רקע מאחורי הטקסט", style = MaterialTheme.typography.labelLarge)
            ColorRow(boxColors, st.boxColor) { c -> set { it.copy(boxColor = c) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(st.bold, { set { it.copy(bold = !it.bold) } }, label = { Text("מודגש") })
                Spacer(Modifier.width(8.dp))
                FilterChip(st.shadow, { set { it.copy(shadow = !it.shadow) } }, label = { Text("צל") })
            }
            Spacer(Modifier.height(8.dp))
            Text("סגנונות מוכנים", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresetChip("קלאסי") { set { SubStyle(maxWords = it.maxWords) } }
                PresetChip("צהוב בולט") { set { SubStyle(textColor = 0xFFFFE600, sizePct = 5.2f, outlineWidth = 0.18f, maxWords = it.maxWords) } }
                PresetChip("קופסה שחורה") { set { SubStyle(outlineWidth = 0f, boxColor = 0xAA000000, shadow = false, maxWords = it.maxWords) } }
                PresetChip("לבן על חום") { set { SubStyle(outlineWidth = 0f, boxColor = 0xCC7A4A22, shadow = false, maxWords = it.maxWords) } }
                PresetChip("גדול במרכז") { set { SubStyle(sizePct = 6.5f, posY = 0.5f, outlineWidth = 0.2f, maxWords = it.maxWords) } }
            }
            Spacer(Modifier.height(12.dp))
            Text("מילים בכל כתובית: ${st.maxWords}", style = MaterialTheme.typography.labelLarge)
            var words by remember { mutableIntStateOf(st.maxWords) }
            Slider(words.toFloat(), { words = it.roundToInt() }, valueRange = 1f..12f, steps = 10)
            if (hasTranscript) {
                OutlinedButton(onClick = { regenerate(words) }) { Text("בנה כתוביות מחדש מהתמלול ($words מילים)") }
                Text("שים לב: זה מחליף את התיקונים הידניים בכתוביות.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ColorRow(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            val alpha = ((c ushr 24) and 0xFF).toInt()
            Box(
                Modifier.size(34.dp)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .padding(3.dp)
                    .background(if (alpha == 0) Color.Transparent else Color(c.toInt()), CircleShape)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center
            ) { if (alpha == 0) Text("∅") }
        }
    }
}

// ------------------------------------------------------------------ framing

@Composable
private fun FrameEditor(p: Project, s: ShortClip, save: ((ShortClip) -> ShortClip) -> Unit) {
    val vertical = p.width.toFloat() / p.height.coerceAtLeast(1) <= 9f / 16f + 0.01f
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (vertical) {
            Text("הסרטון כבר אנכי, אין צורך במסגור.")
        } else {
            Text("איזה חלק מהתמונה יופיע בשורט (שמאל ↔ ימין):", style = MaterialTheme.typography.labelLarge)
            Slider(s.cropX, { v -> save { it.copy(cropX = v) } }, valueRange = 0f..1f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallBtn("שמאל") { save { it.copy(cropX = 0f) } }
                SmallBtn("מרכז") { save { it.copy(cropX = 0.5f) } }
                SmallBtn("ימין") { save { it.copy(cropX = 1f) } }
            }
            Text("התצוגה למעלה מראה בדיוק מה ייכנס לשורט.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
