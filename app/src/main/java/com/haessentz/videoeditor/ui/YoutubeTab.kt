@file:OptIn(ExperimentalLayoutApi::class)

package com.haessentz.videoeditor.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.data.AiImport
import com.haessentz.videoeditor.data.Chapter
import com.haessentz.videoeditor.data.Project
import com.haessentz.videoeditor.data.ProjectStore
import com.haessentz.videoeditor.data.Range
import com.haessentz.videoeditor.data.YoutubeMeta
import com.haessentz.videoeditor.data.fmtMs
import com.haessentz.videoeditor.media.Thumbs
import com.haessentz.videoeditor.media.YouTubeAuth
import com.haessentz.videoeditor.work.JobManager
import com.haessentz.videoeditor.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Candidate(val label: String, val uri: String, val removed: List<Range>, val durationMs: Long)

private fun candidates(p: Project): List<Candidate> {
    val list = p.outputs.filter { it.kind != "short" }.map { o ->
        val dur = if (o.kind == "edited") p.durationMs - o.removed.sumOf { it.lengthMs } else p.durationMs
        val label = when (o.kind) {
            "edited" -> "ערוך: ${o.name} (${fmtMs(dur)})"
            else -> o.name
        }
        Candidate(label, o.uri, o.removed, dur)
    }
    return list + Candidate("הסרטון המקורי (${fmtMs(p.durationMs)})", p.videoUri, emptyList(), p.durationMs)
}

@UnstableApi
@Composable
fun YoutubeTab(p: Project, player: PlayerState) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val scope = rememberCoroutineScope()
    val yt = p.youtube
    fun save(f: (YoutubeMeta) -> YoutubeMeta) = ProjectStore.update(p.id) { it.copy(youtube = f(it.youtube)) }

    val cands = candidates(p)
    val chosen = cands.firstOrNull { it.uri == yt.videoUri }
        ?: cands.firstOrNull { c -> p.outputs.any { it.uri == c.uri && it.kind == "edited" } }
        ?: cands.last()
    val check = AiImport.finalChapters(yt.chapters, chosen.removed, chosen.durationMs)
    val finalDesc = AiImport.buildDescription(yt.description, check.lines)

    var error by remember { mutableStateOf<String?>(null) }
    var thumbVersion by remember { mutableIntStateOf(0) }
    var showDesc by remember { mutableStateOf(false) }
    val jobs by JobManager.jobs.collectAsState()
    val uploading = jobs.any { it.projectId == p.id && it.kind == "upload" && it.active }

    // Google sign-in: after the consent screen, run the pending upload with the token
    var pending by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val token = YouTubeAuth.fromIntent(ctx, res.data)
        if (token != null) pending?.invoke(token) else error = "ההתחברות לגוגל לא הושלמה."
        pending = null
    }
    fun withToken(action: (String) -> Unit) {
        pending = action
        YouTubeAuth.authorize(activity,
            launch = { sender -> consent.launch(IntentSenderRequest.Builder(sender).build()) },
            onToken = { t -> pending = null; action(t) },
            onError = { m -> pending = null; error = m })
    }

    val dir = ProjectStore.dir(p.id)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Thumbs.fromImage(ctx, uri, dir); Thumbs.compose(dir, yt.thumbText, yt.thumbTextTop) }.isSuccess
            }
            if (ok) { save { it.copy(hasThumb = true) }; thumbVersion++ } else error = "לא הצלחתי לטעון את התמונה."
        }
    }
    val thumb = remember(thumbVersion, yt.hasThumb) {
        Thumbs.finalFile(dir).takeIf { yt.hasThumb && it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ---- file
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("איזה קובץ להעלות", fontWeight = FontWeight.Bold)
                    cands.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { save { it.copy(videoUri = c.uri) } }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(c.uri == chosen.uri, { save { it.copy(videoUri = c.uri) } })
                            Text(c.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (p.cutList.isNotEmpty() && p.outputs.none { it.kind == "edited" }) {
                        Text("יש רשימת חיתוך אבל עוד לא ייצאת סרטון ערוך. ייצא אותו קודם (לשונית רשימת חיתוך).",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // ---- title + description
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(yt.title, { v -> save { it.copy(title = v) } }, Modifier.fillMaxWidth(),
                        label = { Text("כותרת (${yt.title.length}/100)") }, isError = yt.title.length > 100)
                    OutlinedTextField(yt.description, { v -> save { it.copy(description = v) } }, Modifier.fillMaxWidth(),
                        label = { Text("תיאור") }, minLines = 3, maxLines = 10, textStyle = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // ---- chapters
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("פרקים", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = {
                            save { it.copy(chapters = (it.chapters + Chapter(player.positionMs, "פרק חדש")).sortedBy { c -> c.ms }) }
                        }) { Icon(Icons.Default.Add, null); Text(" פרק כאן (${fmtMs(player.positionMs)})") }
                    }
                    Text("הזמנים לפי הסרטון המקורי. החץ מראה את הזמן בקובץ שנבחר להעלאה.", style = MaterialTheme.typography.labelSmall)
                    yt.chapters.forEachIndexed { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { player.seek(c.ms) }) { Icon(Icons.Default.PlayArrow, "נגן") }
                            Text("${fmtMs(c.ms)} ← ${fmtMs(AiImport.mapTime(c.ms, chosen.removed))}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(c.title, { v -> save { y -> y.copy(chapters = y.chapters.mapIndexed { j, x -> if (j == i) x.copy(title = v) else x }) } },
                                Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { save { y -> y.copy(chapters = y.chapters.filterIndexed { j, _ -> j != i }) } }) {
                                Icon(Icons.Default.Delete, "מחק")
                            }
                        }
                    }
                    check.warnings.forEach { w -> Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showDesc = true }) { Text("תצוגה מקדימה של התיאור הסופי") }
                }
            }
        }
        // ---- thumbnail
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("תמונה ממוזערת", fontWeight = FontWeight.Bold)
                    if (thumb != null) {
                        Image(thumb, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
                    } else {
                        Text("אין תמונה. יוטיוב יבחר תמונה בעצמו.", style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val at = player.positionMs
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { Thumbs.fromFrame(ctx, Uri.parse(p.videoUri), at, dir); Thumbs.compose(dir, yt.thumbText, yt.thumbTextTop) }.isSuccess
                                }
                                if (ok) { save { it.copy(hasThumb = true) }; thumbVersion++ } else error = "לא הצלחתי לקחת תמונה מהסרטון."
                            }
                        }) { Text("מהרגע הנוכחי (${fmtMs(player.positionMs)})") }
                        OutlinedButton(onClick = { pickImage.launch("image/*") }) { Text("מהגלריה") }
                        if (yt.hasThumb) TextButton(onClick = { save { it.copy(hasThumb = false) } }) { Text("הסר") }
                    }
                    if (yt.hasThumb) {
                        var text by remember(p.id) { mutableStateOf(yt.thumbText) }
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("טקסט על התמונה (לא חובה)") }, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(!yt.thumbTextTop, { save { it.copy(thumbTextTop = false) } }, label = { Text("למטה") })
                            FilterChip(yt.thumbTextTop, { save { it.copy(thumbTextTop = true) } }, label = { Text("למעלה") })
                            Button(onClick = {
                                val top = p.youtube.thumbTextTop
                                save { it.copy(thumbText = text) }
                                scope.launch {
                                    withContext(Dispatchers.IO) { Thumbs.compose(dir, text, top) }
                                    thumbVersion++
                                }
                            }) { Text("החל") }
                        }
                    }
                }
            }
        }
        // ---- privacy + upload
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("פרטיות", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("private" to "פרטי", "unlisted" to "לא רשום", "public" to "ציבורי").forEach { (k, l) ->
                            FilterChip(yt.privacy == k, { save { it.copy(privacy = k) } }, label = { Text(l) })
                        }
                    }
                    Text("שים לב: עד שגוגל מאשרת את האפליקציה, יוטיוב שומר כל העלאה כפרטית. מפרסמים בלחיצה ב־YouTube Studio.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Button(
                        enabled = !uploading && yt.title.isNotBlank() && yt.title.length <= 100,
                        onClick = {
                            val plan = Jobs.UploadPlan(chosen.uri, chosen.label, chosen.removed, chosen.durationMs)
                            withToken { token -> Jobs.uploadVideo(ctx, p.id, plan, token) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (uploading) "מעלה… (ההתקדמות בפס למטה)" else "העלה ליוטיוב") }
                    if (yt.title.isBlank()) Text("צריך כותרת כדי להעלות.", style = MaterialTheme.typography.bodySmall)
                    yt.lastVideoId?.let { id ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { open(ctx, "https://youtu.be/$id") }) { Text("פתח ביוטיוב") }
                            OutlinedButton(onClick = { open(ctx, "https://studio.youtube.com/video/$id/edit") }) { Text("YouTube Studio") }
                        }
                    }
                }
            }
        }
        // ---- shorts
        if (p.shorts.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("שורטים", fontWeight = FontWeight.Bold)
                        p.shorts.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.title.ifBlank { s.name }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                when {
                                    s.youtubeId != null -> TextButton(onClick = { open(ctx, "https://youtube.com/shorts/${s.youtubeId}") }) { Text("הועלה ✓") }
                                    s.outputUri == null -> Text("קודם ייצא", style = MaterialTheme.typography.labelSmall)
                                    else -> TextButton(enabled = !uploading, onClick = {
                                        withToken { token -> Jobs.uploadShort(ctx, p.id, s.id, token) }
                                    }) { Text("העלה") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDesc) {
        AlertDialog(onDismissRequest = { showDesc = false },
            title = { Text("התיאור שיעלה ליוטיוב") },
            text = { LazyColumn { item { Text(finalDesc.ifBlank { "(ריק)" }, style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = { showDesc = false }) { Text("סגור") } })
    }
    error?.let { m ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("אישור") } })
    }
}

private fun open(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
