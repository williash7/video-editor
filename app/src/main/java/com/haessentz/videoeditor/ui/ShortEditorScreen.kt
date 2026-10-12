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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import com.haessentz.videoeditor.data.withPieces
import com.haessentz.videoeditor.data.outLengthMs
import com.haessentz.videoeditor.data.removedRel
import com.haessentz.videoeditor.data.keep
import com.haessentz.videoeditor.data.Range
import com.haessentz.videoeditor.data.SubStyle
import com.haessentz.videoeditor.data.fmtMs
import com.haessentz.videoeditor.media.SubtitleRenderer
import com.haessentz.videoeditor.work.Jobs
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt
import android.net.Uri
import androidx.compose.runtime.rememberCoroutineScope
import com.haessentz.videoeditor.media.AutoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // centre on the speaker automatically the first time the short is opened
    LaunchedEffect(sid) {
        if (!s.framed) {
            val x = withContext(Dispatchers.IO) { AutoFrame.cropFor(ctx, Uri.parse(p.videoUri), s.startMs, s.endMs, p.width, p.height) }
            save { it.copy(cropX = x ?: it.cropX, framed = true) }
        }
    }
    // keep playback inside the short
    val rel = player.positionMs - s.startMs
    LaunchedEffect(player.positionMs) {
        if (player.playing && player.positionMs >= s.endMs) player.seek(s.startMs)
        // jump over skipped sentences and parts removed from the long video, like the export does
        if (player.playing) {
            val relNow = player.positionMs - s.startMs
            s.removedRel(p.cutList).firstOrNull { relNow >= it.startMs && relNow < it.endMs - 120 }
                ?.let { player.seek(s.startMs + it.endMs) }
        }
    }
    var dragCrop by remember(sid) { mutableStateOf<Float?>(null) }
    var drag by remember(sid) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    fun window(a: Long, b: Long) = (a - 45_000).coerceAtLeast(0) to (b + 45_000).coerceAtMost(p.durationMs)
    var win by remember(sid) { mutableStateOf(window(s.startMs, s.endMs)) }
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
                ShortPreview(p, s, player, rel, Modifier.height(300.dp), dragCrop,
                    onCropDrag = { v -> dragCrop = v },
                    onCropDragEnd = { dragCrop?.let { v -> save { it.copy(cropX = v, framed = true) } }; dragCrop = null })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val removedNow = s.removedRel(p.cutList)
                    Text("${fmtMs(com.haessentz.videoeditor.data.AiImport.mapTime(rel.coerceAtLeast(0), removedNow), true)} / ${fmtMs(s.outLengthMs(p.cutList), true)}", fontWeight = FontWeight.Bold)
                    if (s.pieces.size > 1) Text("${s.pieces.size} קטעים מחוברים", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Row {
                        IconButton(onClick = {
                            if (player.positionMs < s.startMs || player.positionMs >= s.endMs) player.seek(s.startMs) else player.toggle()
                        }, Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)) {
                            if (player.playing) Text("❚❚") else Icon(Icons.Default.PlayArrow, "נגן")
                        }
                    }
                    Text("גבולות השורט", style = MaterialTheme.typography.labelMedium)
                    TrimRow("התחלה", s.startMs,
                        onNudge = { d -> save { it.withRange((it.startMs + d).coerceIn(0, it.endMs - 1000), it.endMs, p) }; player.seek(s.startMs + d, play = false) },
                        onSetHere = { save { it.withRange(player.positionMs.coerceIn(0, it.endMs - 1000), it.endMs, p) } })
                    TrimRow("סוף", s.endMs,
                        onNudge = { d -> save { it.withRange(it.startMs, (it.endMs + d).coerceIn(it.startMs + 1000, p.durationMs), p) }; player.seek((s.endMs + d - 3000).coerceAtLeast(s.startMs), play = true) },
                        onSetHere = { save { it.withRange(it.startMs, player.positionMs.coerceIn(it.startMs + 1000, p.durationMs), p) } })
                    if (s.about.isNotBlank()) Text(s.about, style = MaterialTheme.typography.labelSmall)
                }
            }
            // drag the two handles to move the start and end; the window around the short is +-45 seconds
            // time runs left to right, also in the Hebrew layout
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val cur = drag ?: (s.startMs.toFloat()..s.endMs.toFloat())
                androidx.compose.material3.RangeSlider(
                    value = cur,
                    onValueChange = { v ->
                        val prev = drag ?: cur
                        drag = v
                        if (v.start != prev.start) player.seek(v.start.toLong(), play = false)
                        else player.seek(v.endInclusive.toLong(), play = false)
                    },
                    onValueChangeFinished = {
                        drag?.let { v ->
                            val a = v.start.toLong(); val b = v.endInclusive.toLong().coerceAtLeast(a + 1000)
                            save { it.withRange(a, b, p) }
                            win = window(a, b)
                        }
                        drag = null
                    },
                    valueRange = win.first.toFloat()..win.second.toFloat(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(fmtMs(win.first), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text("${fmtMs(cur.start.toLong(), true)} – ${fmtMs(cur.endInclusive.toLong(), true)}",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(fmtMs(win.second), style = MaterialTheme.typography.labelSmall)
                }
            }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("משפטים") })
                Tab(tab == 1, { tab = 1 }, text = { Text("כתוביות") })
                Tab(tab == 2, { tab = 2 }, text = { Text("עיצוב") })
                Tab(tab == 3, { tab = 3 }, text = { Text("מסגור") })
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> SentencesEditor(p, s, player, ::save)
                    1 -> CuesEditor(p, s, player, rel, ::save)
                    2 -> StyleEditor(s, ::save, regenerate = { words ->
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
    if (pieces.size > 1) {
        // move the outer edges of the first and last pieces; drop pieces that fall outside
        val ps = keep().filter { it.endMs > start && it.startMs < end }.toMutableList()
        if (ps.isEmpty()) return copy(pieces = emptyList()).withRange(start, end, p)
        ps[0] = Range(start, ps[0].endMs.coerceAtLeast(start + 300))
        ps[ps.lastIndex] = Range(ps.last().startMs.coerceAtMost(end - 300), end)
        return withPieces(ps, p.transcript)
    }
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

/**
 * The short as a list of sentences: tick the ones that belong in the short, untick filler.
 * Nearby sentences (before and after) are listed too, so the short can be extended.
 * Below: each joined piece with fine start/end nudges.
 */
@Composable
private fun SentencesEditor(p: Project, s: ShortClip, player: PlayerState, save: ((ShortClip) -> ShortClip) -> Unit) {
    if (p.transcript.isEmpty()) {
        Text("אין תמלול לסרטון הזה, אז אי אפשר לבחור משפטים. אפשר לכוון את ההתחלה והסוף למעלה.", Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val keep = s.keep()
    val ctxMs = 25_000L
    val idxs = p.transcript.indices.filter { p.transcript[it].endMs > s.startMs - ctxMs && p.transcript[it].startMs < s.endMs + ctxMs }
    fun included(i: Int): Boolean {
        val sg = p.transcript[i]; val mid = (sg.startMs + sg.endMs) / 2
        return keep.any { mid >= it.startMs && mid < it.endMs }
    }
    fun rebuild(sel: Set<Int>) {
        if (sel.isEmpty()) return
        val sorted = sel.sorted()
        val ranges = ArrayList<Range>()
        var a = sorted.first(); var b = a
        for (i in sorted.drop(1)) { if (i == b + 1) b = i else { ranges.add(Range(p.transcript[a].startMs, p.transcript[b].endMs)); a = i; b = i } }
        ranges.add(Range(p.transcript[a].startMs, p.transcript[b].endMs))
        val padded = ranges.map { Range((it.startMs - 100).coerceAtLeast(0), (it.endMs + 150).coerceAtMost(p.durationMs)) }
        save { it.withPieces(padded, p.transcript) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        item {
            Text("סמן את המשפטים שנכנסים לשורט. בטל סימון של משפטים ומילים מיותרים באמצע — השורט יחבר את השאר. " +
                    "שים לב: שינוי כאן בונה מחדש את הכתוביות.",
                Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
        }
        items(idxs.size) { k ->
            val i = idxs[k]
            val sg = p.transcript[i]
            val inc = included(i)
            val outside = sg.endMs <= s.startMs || sg.startMs >= s.endMs
            val playingNow = player.positionMs >= sg.startMs && player.positionMs < sg.endMs
            Row(
                Modifier.fillMaxWidth()
                    .background(if (playingNow) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Checkbox(inc, { v ->
                    val sel = idxs.filter { included(it) }.toMutableSet()
                    if (v) sel.add(i) else sel.remove(i)
                    rebuild(sel)
                })
                Column(Modifier.weight(1f).clickable { player.seek(sg.startMs) }) {
                    Text(fmtMs(sg.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(sg.text, style = MaterialTheme.typography.bodySmall,
                        color = if (inc) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (outside) 0.5f else 0.75f),
                        textDecoration = if (!inc && !outside) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                }
            }
        }
        if (keep.size > 1) {
            item {
                Text("הקטעים בשורט (כיוון עדין):", Modifier.padding(top = 10.dp, start = 4.dp), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge)
            }
            items(keep.size) { j ->
                val pc = keep[j]
                fun upd(f: (Range) -> Range) = save { it.withPieces(keep.mapIndexed { x, r -> if (x == j) f(r) else r }, p.transcript) }
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("קטע ${j + 1}: ${fmtMs(pc.startMs, true)} – ${fmtMs(pc.endMs, true)}", Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { player.seek(pc.startMs) }, Modifier.size(32.dp)) { Icon(Icons.Default.PlayArrow, "נגן") }
                            IconButton(onClick = { save { it.withPieces(keep.filterIndexed { x, _ -> x != j }, p.transcript) } }, Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "מחק")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SmallBtn("התחלה ‎-0.3") { upd { Range((it.startMs - 300).coerceAtLeast(0), it.endMs) } }
                            SmallBtn("התחלה ‎+0.3") { upd { Range((it.startMs + 300).coerceAtMost(it.endMs - 300), it.endMs) } }
                            SmallBtn("סוף ‎-0.3") { upd { Range(it.startMs, (it.endMs - 300).coerceAtLeast(it.startMs + 300)) } }
                            SmallBtn("סוף ‎+0.3") { upd { Range(it.startMs, it.endMs + 300) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrimRow(label: String, value: Long, onNudge: (Long) -> Unit, onSetHere: () -> Unit) {
    Column {
        Text("$label: ${fmtMs(value, true)}", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallBtn("‎-1") { onNudge(-1000) }
            SmallBtn("‎-0.3") { onNudge(-300) }
            SmallBtn("‎+0.3") { onNudge(300) }
            SmallBtn("‎+1") { onNudge(1000) }
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
private fun ShortPreview(
    p: Project, s: ShortClip, player: PlayerState, relMs: Long, modifier: Modifier,
    cropOverride: Float? = null, onCropDrag: (Float) -> Unit = {}, onCropDragEnd: () -> Unit = {},
) {
    val cue = if (s.subtitles) s.cues.firstOrNull { relMs >= it.startMs && relMs < it.endMs } else null
    val cropX = cropOverride ?: s.cropX
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(modifier.aspectRatio(9f / 16f).background(Color.Black)) {
        val boxW = maxWidth
        val boxH = maxHeight
        val srcAspect = p.width.toFloat() / p.height.coerceAtLeast(1)
        val target = 9f / 16f
        if (srcAspect > target) {
            val vidW = boxH * srcAspect
            val rangePx = with(density) { (vidW - boxW).toPx() }.coerceAtLeast(1f)
            val latest = androidx.compose.runtime.rememberUpdatedState(cropX)
            val dragMod = Modifier.pointerInput(s.id) {
                var x = latest.value
                detectHorizontalDragGestures(
                    onDragStart = { x = latest.value },
                    onDragEnd = { onCropDragEnd() },
                    onDragCancel = { onCropDragEnd() },
                ) { change, dx ->
                    change.consume()
                    // drag the picture like a photo: dragging right shows more of the left side
                    x = (x - dx / rangePx).coerceIn(0f, 1f)
                    onCropDrag(x)
                }
            }
            val dx = -(vidW - boxW) * cropX
            Box(Modifier.fillMaxSize().clipToBounds()) {
                VideoView(player, Modifier.requiredSize(vidW, boxH).absoluteOffset(x = dx + (vidW - boxW) / 2), controls = false, texture = true)
            }
            // a transparent layer on top catches the drag (the video view itself would swallow touches)
            Box(Modifier.fillMaxSize().then(dragMod))
            Text("↔ גרור", Modifier.align(Alignment.TopCenter).padding(4.dp).background(Color(0x66000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        } else {
            VideoView(player, Modifier.fillMaxSize(), controls = false, zoom = true, texture = true)
        }
        if (cue != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c -> SubtitleRenderer.draw(c.nativeCanvas, cue.text, s.style, size.width.toInt(), size.height.toInt()) }
            }
        }
        val absMs = s.startMs + relMs
        val note = if (p.burnNotes) p.notes.firstOrNull { absMs >= it.ms && absMs < it.ms + com.haessentz.videoeditor.data.noteDurationMs(it.text) } else null
        if (note != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c -> com.haessentz.videoeditor.media.NoteRenderer.draw(c.nativeCanvas, note.text, size.width.toInt(), size.height.toInt()) }
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var detecting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (p.cutList.any { it.endMs > s.startMs && it.startMs < s.endMs }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("להוציא מהשורט קטעים שנמחקו מהסרטון", Modifier.weight(1f))
                Switch(checked = s.followCuts, onCheckedChange = { v -> save { it.copy(followCuts = v) } })
            }
        }
        if (vertical) {
            Text("הסרטון כבר אנכי, אין צורך במסגור.")
        } else {
            Text("איזה חלק מהתמונה יופיע בשורט (שמאל ↔ ימין):", style = MaterialTheme.typography.labelLarge)
            Slider(s.cropX, { v -> save { it.copy(cropX = v, framed = true) } }, valueRange = 0f..1f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallBtn("שמאל") { save { it.copy(cropX = 0f, framed = true) } }
                SmallBtn("מרכז") { save { it.copy(cropX = 0.5f, framed = true) } }
                SmallBtn("ימין") { save { it.copy(cropX = 1f, framed = true) } }
                SmallBtn("◀") { save { it.copy(cropX = (it.cropX - 0.04f).coerceIn(0f, 1f), framed = true) } }
                SmallBtn("▶") { save { it.copy(cropX = (it.cropX + 0.04f).coerceIn(0f, 1f), framed = true) } }
                SmallBtn(if (detecting) "מחפש…" else "על הפנים ✨") {
                    if (!detecting) {
                        detecting = true
                        scope.launch {
                            val x = withContext(Dispatchers.IO) {
                                AutoFrame.cropFor(ctx, Uri.parse(p.videoUri), s.startMs, s.endMs, p.width, p.height)
                            }
                            detecting = false
                            if (x != null) save { it.copy(cropX = x, framed = true) } else note = "לא זיהיתי פנים בקטע הזה. אפשר לכוון ידנית."
                        }
                    }
                }
            }
            Text("אפשר גם לגרור את התמונה בתצוגה למעלה ימינה ושמאלה. התצוגה מראה בדיוק מה ייכנס לשורט.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
