package com.haessentz.videoeditor.work

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.data.Cues
import com.haessentz.videoeditor.data.OutputFile
import com.haessentz.videoeditor.data.Prefs
import com.haessentz.videoeditor.data.Project
import com.haessentz.videoeditor.data.ProjectStore
import com.haessentz.videoeditor.data.Range
import com.haessentz.videoeditor.data.Ranges
import com.haessentz.videoeditor.data.Seg
import com.haessentz.videoeditor.data.fileTime
import com.haessentz.videoeditor.media.AudioExtractor
import com.haessentz.videoeditor.media.MediaSaver
import com.haessentz.videoeditor.media.ModelManager
import com.haessentz.videoeditor.media.SilenceDetector
import com.haessentz.videoeditor.media.VideoExporter
import com.haessentz.videoeditor.media.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** High-level operations. Each one is queued as a background job. */
@UnstableApi
object Jobs {

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)

    fun pcmFile(p: Project) = File(ProjectStore.dir(p.id), "audio.pcm")

    private suspend fun ensurePcm(ctx: Context, p: Project, js: JobScope, from: Float, to: Float): File {
        val pcm = pcmFile(p)
        if (pcm.exists() && pcm.length() > 0) return pcm
        js.progress(from, "מחלץ את השמע…")
        withContext(Dispatchers.IO) {
            val ctxJob = currentCoroutineContext()
            AudioExtractor.extract(ctx, Uri.parse(p.videoUri), pcm,
                onProgress = { f -> js.progress(from + (to - from) * f, "מחלץ את השמע… ${(f * 100).toInt()}%") },
                cancelled = { !ctxJob.isActive })
        }
        return pcm
    }

    // ---------------------------------------------------------------- transcription

    fun transcribe(ctx: Context, pid: String): String? {
        val p = ProjectStore.get(pid) ?: return null
        val modelId = Prefs.activeModel
        return JobManager.enqueue(ctx, "תמלול: ${p.name}", pid, kind = "transcribe") { js ->
            if (!ModelManager.isReady(ctx, modelId)) throw IllegalStateException("לא הורד מודל תמלול. היכנס להגדרות ⚙ והורד מודל.")
            val pcm = ensurePcm(ctx, p, js, 0f, 0.08f)
            js.progress(0.08f, "טוען את מודל התמלול…")
            ProjectStore.update(pid) { it.copy(transcript = emptyList(), transcribed = false) }
            withContext(Dispatchers.IO) {
                WhisperLib.setAbort(false)
                js.onCancel { WhisperLib.setAbort(true) }
                val ctxPtr = WhisperLib.initContext(ModelManager.file(ctx, modelId).absolutePath)
                if (ctxPtr == 0L) throw IllegalStateException("טעינת המודל נכשלה. אולי הקובץ פגום — נסה למחוק ולהוריד שוב.")
                val segs = ArrayList<Seg>()
                var lastSave = System.currentTimeMillis()
                val duration = p.durationMs.coerceAtLeast(1)
                try {
                    val cb = object : WhisperLib.Callback {
                        override fun onSegment(t0: Long, t1: Long, text: ByteArray) {
                            val t = String(text, Charsets.UTF_8).trim()
                            if (t.isEmpty()) return
                            segs.add(Seg(t0, t1, t))
                            val f = (t1.toFloat() / duration).coerceIn(0f, 1f)
                            js.progress(0.1f + 0.9f * f, "מתמלל… ${com.haessentz.videoeditor.data.fmtMs(t1)} מתוך ${com.haessentz.videoeditor.data.fmtMs(duration)}")
                            val now = System.currentTimeMillis()
                            if (now - lastSave > 4000) {
                                lastSave = now
                                val copy = ArrayList(segs)
                                ProjectStore.update(pid, save = false) { it.copy(transcript = copy) }
                            }
                        }

                        override fun onProgress(progress: Int) {}
                    }
                    val r = WhisperLib.transcribe(ctxPtr, pcm.absolutePath, "he", Prefs.threads, 60, cb)
                    currentCoroutineContext().ensureActive()
                    if (r != 0) throw IllegalStateException("התמלול נכשל (קוד $r)")
                } finally {
                    WhisperLib.freeContext(ctxPtr)
                    val final = ArrayList(segs)
                    ProjectStore.update(pid) { it.copy(transcript = final) }
                }
                ProjectStore.update(pid) { it.copy(transcribed = true) }
                addLog(pid, "התמלול הסתיים: ${segs.size} משפטים. עכשיו אפשר לחפש, ליצור שורטים עם כתוביות ועוד.")
            }
        }
    }

    // ---------------------------------------------------------------- exports

    private suspend fun runExport(
        ctx: Context, p: Project, js: JobScope, ranges: List<Range>,
        effects: List<androidx.media3.common.Effect>, name: String,
        from: Float = 0f, to: Float = 1f,
    ): OutputFile {
        val tmp = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        VideoExporter.export(ctx, Uri.parse(p.videoUri), ranges, effects, tmp) { f ->
            js.progress(from + (to - from) * f, "מייצא… ${(f * 100).toInt()}%")
        }
        js.progress(to, "שומר לגלריה…")
        val display = safeName(name) + ".mp4"
        val uri = withContext(Dispatchers.IO) { MediaSaver.saveVideo(ctx, tmp, display) }
        val out = OutputFile(display, uri.toString(), System.currentTimeMillis())
        ProjectStore.update(p.id) { it.copy(outputs = listOf(out) + it.outputs) }
        return out
    }

    /** Each range becomes its own clip (join = false) or all ranges become one video (join = true). */
    fun exportRanges(ctx: Context, pid: String, ranges: List<Range>, join: Boolean, label: String): String? {
        val p = ProjectStore.get(pid) ?: return null
        val rs = Ranges.normalize(ranges, p.durationMs)
        if (rs.isEmpty()) return null
        val title = if (join) "מחבר ${rs.size} קטעים" else if (rs.size == 1) "חותך קטע" else "חותך ${rs.size} קטעים"
        return JobManager.enqueue(ctx, title, pid, kind = "export") { js ->
            if (join) {
                val o = runExport(ctx, p, js, rs, emptyList(), "${p.name}_$label")
                addLog(pid, "מוכן ✓ ${o.name} נשמר בגלריה (תיקיית VideoEditor).")
            } else {
                rs.forEachIndexed { i, r ->
                    val o = runExport(ctx, p, js, listOf(r), emptyList(),
                        "${p.name}_${fileTime(r.startMs)}_${fileTime(r.endMs)}",
                        i.toFloat() / rs.size, (i + 1).toFloat() / rs.size)
                    addLog(pid, "מוכן ✓ ${o.name}")
                }
            }
        }
    }

    /** Exports the video without the parts in the cut list. */
    fun exportCutList(ctx: Context, pid: String): String? {
        val p = ProjectStore.get(pid) ?: return null
        val keep = Ranges.complement(p.cutList, p.durationMs)
        if (keep.isEmpty()) return null
        return exportRanges(ctx, pid, keep, join = true, label = "ערוך")
    }

    fun removeSilence(ctx: Context, pid: String, minSilenceMs: Long): String? {
        val p = ProjectStore.get(pid) ?: return null
        return JobManager.enqueue(ctx, "הסרת שתיקות", pid, kind = "export") { js ->
            val pcm = ensurePcm(ctx, p, js, 0f, 0.15f)
            js.progress(0.16f, "מחפש שתיקות…")
            val silent = withContext(Dispatchers.IO) { SilenceDetector.detect(pcm, minSilenceMs) }
            if (silent.isEmpty()) {
                addLog(pid, "לא נמצאו שתיקות ארוכות מ־${minSilenceMs / 1000.0} שניות.")
                return@enqueue
            }
            val keep = Ranges.complement(silent, p.durationMs)
            val removed = p.durationMs - keep.sumOf { it.lengthMs }
            addLog(pid, "נמצאו ${silent.size} שתיקות (סה״כ ${removed / 1000} שניות). מייצא…")
            val o = runExport(ctx, p, js, keep, emptyList(), "${p.name}_בלי_שתיקות", 0.2f, 1f)
            addLog(pid, "מוכן ✓ ${o.name}")
        }
    }

    /** Splits the whole video into parts of about [partMs], snapping to sentence ends when a transcript exists. */
    fun split(ctx: Context, pid: String, partMs: Long): String? {
        val p = ProjectStore.get(pid) ?: return null
        val cuts = ArrayList<Long>()
        var t = partMs
        while (t < p.durationMs - partMs / 4) {
            val near = p.transcript.map { it.endMs }.filter { kotlin.math.abs(it - t) < 30_000 }.minByOrNull { kotlin.math.abs(it - t) }
            cuts.add(near ?: t)
            t = (near ?: t) + partMs
        }
        val points = listOf(0L) + cuts + p.durationMs
        val ranges = points.zipWithNext { a, b -> Range(a, b) }.filter { it.lengthMs > 1000 }
        return JobManager.enqueue(ctx, "פיצול ל־${ranges.size} חלקים", pid, kind = "export") { js ->
            ranges.forEachIndexed { i, r ->
                val o = runExport(ctx, p, js, listOf(r), emptyList(), "${p.name}_חלק_${i + 1}",
                    i.toFloat() / ranges.size, (i + 1).toFloat() / ranges.size)
                addLog(pid, "מוכן ✓ ${o.name}")
            }
        }
    }

    fun exportShort(ctx: Context, pid: String, shortId: String): String? {
        val p = ProjectStore.get(pid) ?: return null
        val s = p.shorts.firstOrNull { it.id == shortId } ?: return null
        return JobManager.enqueue(ctx, "שורט: ${s.name}", pid, kind = "export") { js ->
            val cues = if (s.subtitles) s.cues else null
            val effects = VideoExporter.shortEffects(p.width, p.height, s.cropX, cues, s.style, s.startMs)
            val o = runExport(ctx, p, js, listOf(Range(s.startMs, s.endMs)), effects, "${p.name}_${s.name}")
            addLog(pid, "השורט מוכן ✓ ${o.name}")
        }
    }

    fun exportWithSubtitles(ctx: Context, pid: String): String? {
        val p = ProjectStore.get(pid) ?: return null
        if (p.transcript.isEmpty()) return null
        return JobManager.enqueue(ctx, "סרטון עם כתוביות", pid, kind = "export") { js ->
            val style = com.haessentz.videoeditor.data.SubStyle(posY = 0.85f, sizePct = 4.5f, maxWords = 8)
            val cues = Cues.forClip(p.transcript, 0, p.durationMs, style.maxWords)
            val effects = VideoExporter.subtitleEffects(p.width, p.height, cues, style, 0)
            val o = runExport(ctx, p, js, listOf(Range(0, p.durationMs)), effects, "${p.name}_כתוביות")
            addLog(pid, "מוכן ✓ ${o.name}")
        }
    }

    fun downloadModel(ctx: Context, modelId: String): String? {
        val info = ModelManager.models.firstOrNull { it.id == modelId } ?: return null
        return JobManager.enqueue(ctx, "הורדת מודל: ${info.title}", null, kind = "model:$modelId") { js ->
            withContext(Dispatchers.IO) {
                ModelManager.download(ctx, info) { f, d -> js.progress(f, d) }
            }
            if (!ModelManager.isReady(ctx, Prefs.activeModel)) Prefs.activeModel = modelId
        }
    }

    fun importModel(ctx: Context, uri: Uri): String =
        JobManager.enqueue(ctx, "ייבוא מודל", null, kind = "model:${ModelManager.CUSTOM_ID}") { js ->
            withContext(Dispatchers.IO) { ModelManager.import(ctx, uri) { f -> js.progress(f, "מעתיק…") } }
            Prefs.activeModel = ModelManager.CUSTOM_ID
        }

    fun addLog(pid: String, text: String, hits: List<com.haessentz.videoeditor.data.Hit> = emptyList()) {
        ProjectStore.update(pid) { it.copy(log = (it.log + com.haessentz.videoeditor.data.LogEntry(false, text, hits)).takeLast(200)) }
    }
}
