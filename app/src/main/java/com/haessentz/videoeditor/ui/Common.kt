package com.haessentz.videoeditor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haessentz.videoeditor.work.fmtDuration
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haessentz.videoeditor.work.JobManager
import com.haessentz.videoeditor.work.JobStatus

/** Bottom bar showing the running job (and recent failures). */
@Composable
fun JobsBar(projectId: String? = null) {
    val jobs by JobManager.jobs.collectAsState()
    val relevant = jobs.filter { projectId == null || it.projectId == null || it.projectId == projectId }
    val active = relevant.firstOrNull { it.status == JobStatus.RUNNING } ?: relevant.firstOrNull { it.status == JobStatus.QUEUED }
    val failed = relevant.lastOrNull { it.status == JobStatus.FAILED }
    if (active == null && failed == null) return
    // tick every second so the elapsed time keeps moving
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active?.id) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (active != null) {
                val queued = relevant.count { it.active } - 1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(active.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            active.detail.ifEmpty { if (active.status == JobStatus.QUEUED) "ממתין בתור…" else "עובד…" } +
                                    if (queued > 0) " · עוד $queued בתור" else "",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (active.status == JobStatus.RUNNING) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("⏱ ${fmtDuration(active.elapsedMs(now))}", fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge)
                            active.remainingMs(now)?.let {
                                Text("נשאר ~${fmtDuration(it)}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    TextButton(onClick = { JobManager.cancel(active.id) }) { Text("בטל") }
                }
                Spacer(Modifier.height(4.dp))
                if (active.progress >= 0f) LinearProgressIndicator(progress = { active.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (failed != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = if (active != null) 6.dp else 0.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("נכשל: ${failed.title} (${fmtDuration(failed.elapsedMs())}) — ${failed.detail}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { JobManager.dismiss(failed.id) }) { Icon(Icons.Default.Close, "סגור") }
                }
            }
        }
    }
}

fun openVideo(ctx: Context, uri: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
}

fun shareVideo(ctx: Context, uri: String) {
    val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "שתף").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Asks Android not to stop the app in the background (Samsung is aggressive about this). */
@androidx.compose.runtime.Composable
fun BatteryCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var ok by remember { androidx.compose.runtime.mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) ok = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    if (ok) return
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().padding(8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("כדי שהתמלול ימשיך גם כשיוצאים מהאפליקציה", fontWeight = FontWeight.Bold)
            Text("סמסונג עוצרת אפליקציות ברקע כדי לחסוך סוללה. אשר לאפליקציה לעבוד ברקע ללא הגבלה.",
                style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Button(onClick = {
                runCatching {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("אפשר עבודה ברקע") }
        }
    }
}
