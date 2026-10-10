@file:OptIn(ExperimentalLayoutApi::class)

package com.haessentz.videoeditor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.data.AiImport
import com.haessentz.videoeditor.data.Chapter
import com.haessentz.videoeditor.data.Cues
import com.haessentz.videoeditor.data.LogEntry
import com.haessentz.videoeditor.data.Prefs
import com.haessentz.videoeditor.data.Project
import com.haessentz.videoeditor.data.ProjectStore
import com.haessentz.videoeditor.data.Range
import com.haessentz.videoeditor.data.Ranges
import com.haessentz.videoeditor.data.ShortClip
import com.haessentz.videoeditor.data.SubStyle
import com.haessentz.videoeditor.data.fmtMs
import com.haessentz.videoeditor.data.Seg
import com.haessentz.videoeditor.work.Jobs
import com.haessentz.videoeditor.media.MediaSaver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID

private fun clipboard(ctx: Context) = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

@UnstableApi
@Composable
fun AiTab(p: Project, player: PlayerState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var editPrompt by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val openReply = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val t = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (t != null) ProjectStore.update(p.id) { it.copy(aiReply = t) }
        }
    }

    if (p.transcript.isEmpty()) {
        Text("קודם צריך לתמלל את הסרטון (לשונית תמלול). אחרי זה אפשר להעתיק את התמלול לבינה.", Modifier.padding(20.dp))
        return
    }
    val result = remember(p.aiReply) { AiImport.parse(p.aiReply) }
    var mode by remember { mutableStateOf(0) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(mode == 0, { mode = 0 }, label = { Text("עריכה ופרסום") })
                androidx.compose.material3.FilterChip(mode == 1, { mode = 1 }, label = { Text("תיקון תמלול") })
            }
        }
        if (mode == 1) {
            item { FixSection(p, player) }
            return@LazyColumn
        }
        // ---------------- step 1
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("שלב 1: שולחים לבינה", fontWeight = FontWeight.Bold)
                    Text("התמלול עם הזמנים יוצא כקובץ, יחד עם הוראה שמבקשת מהבינה לענות בתבנית שהאפליקציה מבינה. קובץ עובר שלם גם בסרטון ארוך.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { shareAsFile(ctx, p) }) { Text("שלח קובץ לבינה") }
                        FilledTonalButton(onClick = {
                            saved = runCatching {
                                MediaSaver.saveText(ctx, requestText(p), fileName(p), "text/plain")
                                "נשמר בתיקיית ההורדות: Download/VideoEditor/${fileName(p)}"
                            }.getOrElse { "השמירה נכשלה: ${it.message}" }
                        }) { Text("שמור קובץ") }
                        OutlinedButton(onClick = {
                            clipboard(ctx).setPrimaryClip(ClipData.newPlainText("תמלול", requestText(p)))
                            copied = true
                        }) { Text(if (copied) "הועתק ✓" else "העתק") }
                        TextButton(onClick = { editPrompt = true }) { Text("ערוך הוראה") }
                    }
                    saved?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Text("${p.transcript.size} משפטים · כ־${requestText(p).length / 1000} אלף תווים", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // ---------------- step 2
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("שלב 2: מדביקים את התשובה", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val t = clipboard(ctx).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                            ProjectStore.update(p.id) { it.copy(aiReply = t) }
                        }) { Text("הדבק מהלוח") }
                        FilledTonalButton(onClick = { openReply.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("טען מקובץ") }
                        if (p.aiReply.isNotEmpty()) OutlinedButton(onClick = { ProjectStore.update(p.id) { it.copy(aiReply = "") } }) { Text("נקה") }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = p.aiReply,
                        onValueChange = { v -> ProjectStore.update(p.id) { it.copy(aiReply = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("או הדבק כאן ידנית") },
                        minLines = 3, maxLines = 8,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    if (p.aiReply.isNotBlank() && result.isEmpty) {
                        Text("לא זיהיתי בתשובה קטעים, כותרת או פרקים. ודא שהבינה ענתה בתבנית עם הכותרות ### מחיקה, ### שורטים וכו'.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // ---------------- step 3
        if (!result.isEmpty) {
            item { Review(p, result, player, onDone) }
        }
    }

    if (editPrompt) {
        var text by remember { mutableStateOf(Prefs.aiPromptFor(p.language)) }
        AlertDialog(
            onDismissRequest = { editPrompt = false },
            title = { Text("ההוראה לבינה") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 6, maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { Prefs.setAiPromptFor(p.language, text); editPrompt = false }) { Text("שמור") } },
            dismissButton = {
                TextButton(onClick = { Prefs.setAiPromptFor(p.language, null); editPrompt = false }) { Text("חזור לברירת המחדל") }
            }
        )
    }
}

private fun fileName(p: Project) = p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60) + " - תמלול.txt"

/** Writes the request to a file and opens the share sheet, so the whole transcript arrives as an attachment. */
private fun shareAsFile(ctx: Context, p: Project) {
    val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = java.io.File(dir, fileName(p))
    f.writeText(requestText(p), Charsets.UTF_8)
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, f.name)
        .putExtra(Intent.EXTRA_TEXT, "בצע את ההוראות שבראש הקובץ המצורף.")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "שלח לבינה").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun requestText(p: Project) = Prefs.aiPromptFor(p.language) + "\n\n" + AiImport.transcriptText(p.transcript)

private fun fixText(p: Project) = AiImport.fixPrompt(p.language) + "\n\n" + AiImport.fixLines(p.transcript)

private fun shareText(ctx: Context, name: String, text: String) {
    val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = java.io.File(dir, name)
    f.writeText(text, Charsets.UTF_8)
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, f.name)
        .putExtra(Intent.EXTRA_TEXT, "בצע את ההוראות שבראש הקובץ המצורף.")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "שלח לבינה").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Rebuilds every short's subtitles from the (corrected) transcript. */
private fun rebuildShortCues(pr: Project, transcript: List<com.haessentz.videoeditor.data.Seg>) =
    pr.shorts.map { it.copy(cues = Cues.forClip(transcript, it.startMs, it.endMs, it.style.maxWords)) }

/** Second mode of the AI tab: send the transcript for correction, paste back, preview and apply. */
@Composable
fun FixSection(p: Project, player: PlayerState) {
    val ctx = LocalContext.current
    var reply by remember(p.id) { mutableStateOf("") }
    var updateShorts by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf<String?>(null) }
    val openReply = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrNull()?.let { reply = it }
    }
    val fixes = remember(reply) { AiImport.parseFix(reply) }
    val changes = remember(fixes, p.transcript) {
        fixes.filter { (i, t) -> i < p.transcript.size && t != p.transcript[i].text.trim() }
    }
    val fname = p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60) + " - לתיקון.txt"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("שלב 1: שולחים לתיקון", fontWeight = FontWeight.Bold)
                Text(if (p.language == "ru") "הבינה תתקן את הרוסית — דקדוק, סיומות וניסוח — בלי לשנות את התוכן. התיקון יופיע בתמלול ובכתוביות."
                    else "הבינה תתקן שגיאות זיהוי ופיסוק בלי לשנות את התוכן.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shareText(ctx, fname, fixText(p)) }) { Text("שלח קובץ לבינה") }
                    FilledTonalButton(onClick = {
                        done = runCatching { MediaSaver.saveText(ctx, fixText(p), fname, "text/plain"); "נשמר בתיקיית ההורדות: Download/VideoEditor/$fname" }
                            .getOrElse { "השמירה נכשלה: ${it.message}" }
                    }) { Text("שמור קובץ") }
                }
                Text("${p.transcript.size} שורות. אם התשובה נקטעת באמצע, בקש מהבינה \"המשך\" והדבק גם את ההמשך.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("שלב 2: מדביקים את התשובה", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val t = clipboard(ctx).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                        reply = t
                    }) { Text("הדבק מהלוח") }
                    FilledTonalButton(onClick = {
                        val t = clipboard(ctx).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                        reply = reply + "\n" + t
                    }) { Text("הוסף המשך") }
                    OutlinedButton(onClick = { openReply.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("טען מקובץ") }
                }
                if (reply.isNotBlank()) {
                    Text("זוהו ${fixes.size} שורות מתוך ${p.transcript.size} · ${changes.size} שונו",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (fixes.isEmpty()) Text("לא זיהיתי שורות עם מספרים בסוגריים, כמו [12]. ודא שהבינה שמרה על המספרים.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (changes.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("שלב 3: בדיקה", fontWeight = FontWeight.Bold)
                    changes.entries.take(40).forEach { (i, t) ->
                        val seg = p.transcript[i]
                        Column(Modifier.fillMaxWidth().clickable { player.seek(seg.startMs) }) {
                            Text("▶ ${fmtMs(seg.startMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(seg.text, style = MaterialTheme.typography.bodySmall,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                            Text(t, style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                    if (changes.size > 40) Text("ועוד ${changes.size - 40} שינויים…", style = MaterialTheme.typography.labelSmall)
                    if (p.shorts.isNotEmpty()) CheckLine("לעדכן גם את הכתוביות בשורטים (תיקונים ידניים בכתוביות יוחלפו)", updateShorts) { updateShorts = it }
                    Button(onClick = {
                        ProjectStore.update(p.id) { pr ->
                            val fixed = pr.transcript.mapIndexed { i, sg -> changes[i]?.let { sg.copy(text = it) } ?: sg }
                            pr.copy(
                                transcriptBeforeFix = pr.transcriptBeforeFix.ifEmpty { pr.transcript },
                                transcript = fixed,
                                shorts = if (updateShorts) rebuildShortCues(pr, fixed) else pr.shorts,
                                log = (pr.log + LogEntry(false, "התמלול תוקן: ${changes.size} שורות עודכנו.")).takeLast(200),
                            )
                        }
                        reply = ""
                        done = "התיקונים הוחלו ✓"
                    }, Modifier.fillMaxWidth()) { Text("החל ${changes.size} תיקונים") }
                }
            }
        }
        done?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (p.transcriptBeforeFix.isNotEmpty()) {
            OutlinedButton(onClick = {
                ProjectStore.update(p.id) { pr ->
                    pr.copy(transcript = pr.transcriptBeforeFix, transcriptBeforeFix = emptyList(),
                        shorts = rebuildShortCues(pr, pr.transcriptBeforeFix))
                }
                done = "חזרת לתמלול המקורי."
            }) { Text("חזור לתמלול המקורי (לפני התיקון)") }
        }
    }
}

@UnstableApi
@Composable
private fun Review(p: Project, r: AiImport.Result, player: PlayerState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val delSel = remember(r) { mutableStateListOf(*Array(r.deletes.size) { true }) }
    val shortSel = remember(r) { mutableStateListOf(*Array(r.shorts.size) { true }) }
    val chapSel = remember(r) { mutableStateListOf(*Array(r.chapters.size) { true }) }
    val shortTitles = remember(r) { mutableStateListOf(*r.shorts.map { it.label }.toTypedArray()) }
    val chapNames = remember(r) { mutableStateListOf(*r.chapters.map { it.title }.toTypedArray()) }
    var title by remember(r) { mutableStateOf(r.title) }
    var desc by remember(r) { mutableStateOf(r.description) }
    var exportEdited by remember { mutableStateOf(true) }
    var exportShorts by remember { mutableStateOf(true) }

    val chosenDeletes = r.deletes.filterIndexed { i, _ -> delSel[i] }.map { it.range }
    val removed = Ranges.normalize(chosenDeletes, p.durationMs)
    val removedMs = removed.sumOf { it.lengthMs }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("שלב 3: בדיקה", fontWeight = FontWeight.Bold)
            Text("▶ מנגן בדיוק את הקטע. אפשר לבטל סימון של מה שלא מתאים.", style = MaterialTheme.typography.bodySmall)

            if (r.deletes.isNotEmpty()) {
                Section("למחיקה (${r.deletes.size})")
                r.deletes.forEachIndexed { i, it ->
                    ItemRow(delSel[i], { delSel[i] = it }, "${fmtMs(it.range.startMs)}–${fmtMs(it.range.endMs)}",
                        "(${it.range.lengthMs / 1000} שנ׳) ${it.label}") { player.seek((it.range.startMs - 2000).coerceAtLeast(0)) }
                }
                Text("יימחקו ${fmtMs(removedMs)} · הסרטון יהיה באורך ${fmtMs(p.durationMs - removedMs)}",
                    style = MaterialTheme.typography.labelMedium)
            }

            if (r.shorts.isNotEmpty()) {
                Section("שורטים (${r.shorts.size})")
                r.shorts.forEachIndexed { i, it ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(shortSel[i], { v -> shortSel[i] = v })
                        Column(Modifier.weight(1f)) {
                            Text("${fmtMs(it.range.startMs)}–${fmtMs(it.range.endMs)} (${it.range.lengthMs / 1000} שנ׳)",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            OutlinedTextField(shortTitles[i], { v -> shortTitles[i] = v }, Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall, singleLine = true)
                        }
                        PlayBtn { player.seek(it.range.startMs) }
                    }
                }
            }

            Section("כותרת")
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = false, maxLines = 3)
            Section("תיאור")
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 8,
                textStyle = MaterialTheme.typography.bodySmall)

            if (r.chapters.isNotEmpty()) {
                Section("פרקים (${r.chapters.size})")
                Text("הזמן הראשון = בסרטון המקורי, החץ = הזמן אחרי המחיקות.", style = MaterialTheme.typography.labelSmall)
                r.chapters.forEachIndexed { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(chapSel[i], { v -> chapSel[i] = v })
                        Text("${fmtMs(c.ms)} ← ${fmtMs(AiImport.mapTime(c.ms, removed))}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(6.dp))
                        OutlinedTextField(chapNames[i], { v -> chapNames[i] = v }, Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true)
                        PlayBtn { player.seek(c.ms) }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            if (r.deletes.isNotEmpty()) CheckLine("לייצא מיד את הסרטון הערוך", exportEdited) { exportEdited = it }
            if (r.shorts.isNotEmpty()) CheckLine("לייצא מיד את השורטים", exportShorts) { exportShorts = it }
            Button(onClick = {
                val style = SubStyle()
                val newShorts = r.shorts.mapIndexedNotNull { i, it ->
                    if (!shortSel[i]) return@mapIndexedNotNull null
                    val end = it.range.endMs.coerceAtMost(p.durationMs)
                    val t = shortTitles[i].trim()
                    ShortClip(
                        id = UUID.randomUUID().toString(),
                        name = t.ifBlank { "שורט" }.take(40),
                        startMs = it.range.startMs, endMs = end,
                        cues = Cues.forClip(p.transcript, it.range.startMs, end, style.maxWords),
                        subtitles = true, style = style, title = t,
                    )
                }
                val chapters = r.chapters.mapIndexedNotNull { i, c -> if (chapSel[i]) Chapter(c.ms, chapNames[i].trim()) else null }
                val doEdited = exportEdited && removed.isNotEmpty()
                ProjectStore.update(p.id) { pr ->
                    pr.copy(
                        cutList = if (r.deletes.isNotEmpty()) removed else pr.cutList,
                        shorts = pr.shorts + newShorts,
                        youtube = pr.youtube.copy(
                            title = title.trim().ifBlank { pr.youtube.title },
                            description = desc.trim().ifBlank { pr.youtube.description },
                            chapters = if (r.chapters.isNotEmpty()) chapters else pr.youtube.chapters,
                            videoUri = null,
                        ),
                        log = (pr.log + LogEntry(false, buildString {
                            append("יובאו מהבינה: ")
                            append("${removed.size} קטעים למחיקה, ${newShorts.size} שורטים, ${chapters.size} פרקים")
                            if (title.isNotBlank()) append(", כותרת ותיאור")
                            append(".")
                        })).takeLast(200),
                    )
                }
                if (doEdited) Jobs.exportCutList(ctx, p.id)
                if (exportShorts) newShorts.forEach { Jobs.exportShort(ctx, p.id, it.id) }
                onDone()
            }, Modifier.fillMaxWidth()) { Text("בצע") }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun PlayBtn(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.PlayArrow, "נגן") }
}

@Composable
private fun ItemRow(checked: Boolean, onChecked: (Boolean) -> Unit, time: String, label: String, onPlay: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChecked)
        Column(Modifier.weight(1f).clickable(onClick = onPlay)) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.bodySmall)
        }
        PlayBtn(onPlay)
    }
}

@Composable
fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}
