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
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
    var activeHe by remember { mutableStateOf(Prefs.modelFor("he")) }
    var activeRu by remember { mutableStateOf(Prefs.modelFor("ru")) }
    var threads by remember { mutableIntStateOf(Prefs.threads) }
    var refresh by remember { mutableIntStateOf(0) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Jobs.importModel(ctx, uri)
    }
    // re-read file states whenever jobs change
    val ready = remember(jobs, refresh) {
        (ModelManager.models.map { it.id } + ModelManager.CUSTOM_ID).associateWith { ModelManager.isReady(ctx, it) }
    }
    fun fix(lang: String, cur: String): String {
        if (ready[cur] == true) return cur
        val first = ModelManager.models.firstOrNull { lang in it.langs && ready[it.id] == true }?.id
            ?: ModelManager.CUSTOM_ID.takeIf { ready[it] == true }
        if (first != null && first != cur) Prefs.setModelFor(lang, first)
        return first ?: cur
    }
    activeHe = fix("he", activeHe)
    activeRu = fix("ru", activeRu)

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
            for (lang in com.haessentz.videoeditor.media.Langs.all) {
                item(key = "head-$lang") {
                    Text("${com.haessentz.videoeditor.media.Langs.flag(lang)} תמלול ב${com.haessentz.videoeditor.media.Langs.name(lang)}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp))
                }
                val list = ModelManager.models.filter { lang in it.langs && !(lang == "he" && it.id == "small-q5") }
                items(list, key = { "$lang-${it.id}" }) { m ->
                    val job = jobs.firstOrNull { it.kind == "model:${m.id}" && it.active }
                    val sel = if (lang == "ru") activeRu else activeHe
                    ModelCard(
                        m, ready[m.id] == true, sel == m.id, job?.progress, job?.detail,
                        onSelect = { Prefs.setModelFor(lang, m.id); if (lang == "ru") activeRu = m.id else activeHe = m.id },
                        onDownload = { Jobs.downloadModel(ctx, m.id) },
                        onCancel = { job?.let { JobManager.cancel(it.id) } },
                        onDelete = { ModelManager.file(ctx, m.id).delete(); refresh++ }
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ready[ModelManager.CUSTOM_ID] == true) {
                                RadioButton(selected = activeHe == ModelManager.CUSTOM_ID, onClick = {
                                    activeHe = ModelManager.CUSTOM_ID; Prefs.activeModel = ModelManager.CUSTOM_ID
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
            item { SeriesCounters() }
            item { RefImagesCard() }
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

/** The last published lesson number in each weekly series; new projects continue from it. */
@Composable
private fun SeriesCounters() {
    val ctx = LocalContext.current
    val activity = ctx as android.app.Activity
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var counts by remember { mutableStateOf(com.haessentz.videoeditor.data.Series.all.associate { it.name to Prefs.lastLessonNo(it.name) }) }
    var status by remember { mutableStateOf<String?>(null) }
    fun read(token: String) {
        status = "קורא את רשימת הסרטונים מהערוץ…"
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.haessentz.videoeditor.data.Series.lastNumbersFromTitles(com.haessentz.videoeditor.media.YouTubeReader.uploadTitles(token)) }
            }
            r.onSuccess { found ->
                found.forEach { (k, v) -> Prefs.setLastLessonNo(k, v) }
                counts = com.haessentz.videoeditor.data.Series.all.associate { it.name to Prefs.lastLessonNo(it.name) }
                status = if (found.isEmpty()) "לא מצאתי בערוץ כותרות עם שם סדרה ומספר. אפשר לכוון ידנית."
                    else "עודכן מהערוץ ✓ " + found.entries.joinToString(" · ") { "${it.key} ${it.value}" }
            }.onFailure { status = "הקריאה מהערוץ נכשלה: ${it.message}" }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val t = com.haessentz.videoeditor.media.YouTubeAuth.fromIntent(ctx, res.data)
        if (t != null) read(t) else status = "ההתחברות לגוגל לא הושלמה."
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("סדרות השיעורים", fontWeight = FontWeight.Bold)
            Text("לכל סדרה: מספר השיעור האחרון שכבר פורסם. סרטון חדש מקבל אוטומטית את הסדרה לפי יום ההקלטה, ואת המספר הבא.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                com.haessentz.videoeditor.media.YouTubeAuth.authorize(activity, Prefs.accountFor("he"),
                    launch = { sender -> consent.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) },
                    onToken = { t -> read(t) }, onError = { m -> status = m },
                    scope = com.haessentz.videoeditor.media.YouTubeAuth.READ_SCOPE)
            }, Modifier.padding(vertical = 6.dp)) { Text("קרא את המספרים מהערוץ ביוטיוב") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            com.haessentz.videoeditor.data.Series.all.filter { it.numbered }.forEach { si ->
                val n = counts[si.name] ?: 0
                fun setN(v: Int) { Prefs.setLastLessonNo(si.name, v); counts = counts + (si.name to v.coerceAtLeast(0)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(si.name)
                        Text("יום ${si.dayName} · עונה ${si.season} בספוטיפיי", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { setN((n - 1).coerceAtLeast(0)) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text("$n", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { setN(n + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

/** Photos of the rabbi that are sent automatically with every image prompt. */
@Composable
private fun RefImagesCard() {
    val ctx = LocalContext.current
    var refs by remember { mutableStateOf(Prefs.refImages(ctx)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val dir = java.io.File(ctx.filesDir, "ref").apply { mkdirs() }
        uris.take(4).forEach { u ->
            runCatching {
                val f = java.io.File(dir, "ref_${System.currentTimeMillis()}.jpg")
                val bmp = ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@runCatching
                val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
                val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                java.io.FileOutputStream(f).use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            }
        }
        refs = Prefs.refImages(ctx)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("תמונות ייחוס של הרב", fontWeight = FontWeight.Bold)
            Text("התמונות האלה נשלחות אוטומטית יחד עם פרומפט התמונה למחולל, כדי שהדמות תהיה מזוהה. מומלץ 1–3 תמונות ברורות של הפנים.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                refs.forEach { f ->
                    val bmp = remember(f.path) { android.graphics.BitmapFactory.decodeFile(f.path)?.let { it.asImageBitmap() } }
                    Box {
                        if (bmp != null) androidx.compose.foundation.Image(bmp, null,
                            Modifier.size(72.dp).clip(MaterialTheme.shapes.small), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Text("✕", Modifier.align(Alignment.TopEnd).background(Color(0x99000000), MaterialTheme.shapes.small)
                            .clickable { f.delete(); refs = Prefs.refImages(ctx) }.padding(horizontal = 6.dp), color = Color.White)
                    }
                }
            }
            OutlinedButton(onClick = { pick.launch("image/*") }) { Text(if (refs.isEmpty()) "בחר תמונות מהגלריה" else "הוסף תמונה") }
        }
    }
}
