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

    /**
     * Transcribes the project. Progress is saved to disk as it goes, so if the app is closed or killed
     * the next run continues from the last saved sentence. [fresh] = start over from the beginning.
     */
    fun transcribe(ctx: Context, pid: String, fresh: Boolean = false): String? {
        val p = ProjectStore.get(pid) ?: return null
        if (JobManager.jobs.value.any { it.projectId == pid && it.kind == "transcribe" && it.active }) return null
        val lang = p.language
        val modelId = Prefs.modelFor(lang)
        // mark at once, so a kill even before the job starts still resumes later
        ProjectStore.update(pid) {
            if (fresh || it.transcribed) it.copy(transcript = emptyList(), transcribed = false, transcribing = true)
            else it.copy(transcribing = true)
        }
        return JobManager.enqueue(ctx, "תמלול: ${p.name}", pid, kind = "transcribe") { js ->
            try {
                if (!ModelManager.isReady(ctx, modelId)) throw IllegalStateException("לא הורד מודל תמלול ל${com.haessentz.videoeditor.media.Langs.name(lang)}. היכנס להגדרות ⚙ והורד מודל.")
                val pcm = ensurePcm(ctx, p, js, 0f, 0.08f)
                js.progress(0.08f, "טוען את מודל התמלול…")
                val existing = ProjectStore.get(pid)?.transcript ?: emptyList()
                val resumeMs = existing.lastOrNull()?.endMs ?: 0L
                if (resumeMs > 0) addLog(pid, "ממשיך את התמלול מ־${com.haessentz.videoeditor.data.fmtMs(resumeMs)} (מה שכבר תומלל נשמר).")
                withContext(Dispatchers.IO) {
                    WhisperLib.setAbort(false)
                    js.onCancel { WhisperLib.setAbort(true) }
                    val ctxPtr = WhisperLib.initContext(ModelManager.file(ctx, modelId).absolutePath)
                    if (ctxPtr == 0L) throw IllegalStateException("טעינת המודל נכשלה. אולי הקובץ פגום — נסה למחוק ולהוריד שוב.")
                    val duration = p.durationMs.coerceAtLeast(1)
                    val startF = 0.1f + 0.9f * (resumeMs.toFloat() / duration).coerceIn(0f, 1f)
                    js.phase(startF, "המודל נטען. מתמלל… (המשפטים הבאים יופיעו תוך דקה־שתיים)")
                    val segs = ArrayList<Seg>(existing)
                    var lastSave = System.currentTimeMillis()
                    try {
                        val cb = object : WhisperLib.Callback {
                            override fun onSegment(t0: Long, t1: Long, text: ByteArray) {
                                val t = String(text, Charsets.UTF_8).trim()
                                if (t.isEmpty() || t1 <= resumeMs) return
                                segs.add(Seg(t0, t1, t))
                                val f = (t1.toFloat() / duration).coerceIn(0f, 1f)
                                js.progress(0.1f + 0.9f * f, "מתמלל… ${com.haessentz.videoeditor.data.fmtMs(t1)} מתוך ${com.haessentz.videoeditor.data.fmtMs(duration)}")
                                val now = System.currentTimeMillis()
                                val copy = ArrayList(segs)
                                // save to disk every few seconds so nothing is lost if the app is closed
                                val toDisk = now - lastSave > 8000
                                if (toDisk) lastSave = now
                                ProjectStore.update(pid, save = toDisk) { it.copy(transcript = copy) }
                            }

                            override fun onProgress(progress: Int) {}
                        }
                        val r = WhisperLib.transcribe(ctxPtr, pcm.absolutePath, lang, Prefs.threads, 60, resumeMs, cb)
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
            } finally {
                // reached on finish, error or "בטל" — but not when Android kills the app, so that case resumes
                ProjectStore.update(pid) { it.copy(transcribing = false) }
            }
        }
    }

    /** Called when the app opens: continues any transcription that was cut off by the app being closed. */
    fun resumeInterrupted(ctx: Context) {
        ProjectStore.projects.value.filter { it.transcribing && !it.transcribed }.forEach { transcribe(ctx, it.id) }
    }

    // ---------------------------------------------------------------- exports

    private suspend fun runExport(
        ctx: Context, p: Project, js: JobScope, ranges: List<Range>,
        effects: List<androidx.media3.common.Effect>, name: String,
        from: Float = 0f, to: Float = 1f, kind: String = "clip", removed: List<Range> = emptyList(),
    ): OutputFile {
        val tmp = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        VideoExporter.export(ctx, Uri.parse(p.videoUri), ranges, effects, tmp) { f ->
            js.progress(from + (to - from) * f, "מייצא… ${(f * 100).toInt()}%")
        }
        js.progress(to, "שומר לגלריה…")
        val display = safeName(name) + ".mp4"
        val uri = withContext(Dispatchers.IO) { MediaSaver.saveVideo(ctx, tmp, display) }
        val out = OutputFile(display, uri.toString(), System.currentTimeMillis(), kind, removed)
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
                val removed = Ranges.complement(rs, p.durationMs, minKeepMs = 1)
                val o = runExport(ctx, p, js, rs, emptyList(), "${p.name}_$label", kind = "edited", removed = removed)
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
            val o = runExport(ctx, p, js, keep, emptyList(), "${p.name}_בלי_שתיקות", 0.2f, 1f,
                kind = "edited", removed = Ranges.complement(keep, p.durationMs, minKeepMs = 1))
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
                    i.toFloat() / ranges.size, (i + 1).toFloat() / ranges.size, kind = "part")
                addLog(pid, "מוכן ✓ ${o.name}")
            }
        }
    }

    fun exportShort(ctx: Context, pid: String, shortId: String): String? {
        val p = ProjectStore.get(pid) ?: return null
        val s = p.shorts.firstOrNull { it.id == shortId } ?: return null
        return JobManager.enqueue(ctx, "שורט: ${s.name}", pid, kind = "export") { js ->
            var cropX = s.cropX
            if (!s.framed) {
                js.progress(-1f, "מאתר את הדובר במסגרת…")
                val found = withContext(Dispatchers.IO) {
                    com.haessentz.videoeditor.media.AutoFrame.cropFor(ctx, Uri.parse(p.videoUri), s.startMs, s.endMs, p.width, p.height)
                }
                if (found != null) cropX = found
                ProjectStore.update(pid) { pr -> pr.copy(shorts = pr.shorts.map { if (it.id == shortId) it.copy(cropX = cropX, framed = true) else it }) }
            }
            val cues = if (s.subtitles) s.cues else null
            val effects = VideoExporter.shortEffects(p.width, p.height, cropX, cues, s.style, s.startMs)
            val o = runExport(ctx, p, js, listOf(Range(s.startMs, s.endMs)), effects, "${p.name}_${s.name}", kind = "short")
            ProjectStore.update(pid) { pr -> pr.copy(shorts = pr.shorts.map { if (it.id == shortId) it.copy(outputUri = o.uri) else it }) }
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
            val o = runExport(ctx, p, js, listOf(Range(0, p.durationMs)), effects, "${p.name}_כתוביות", kind = "full")
            addLog(pid, "מוכן ✓ ${o.name}")
        }
    }

    // ---------------------------------------------------------------- YouTube

    data class UploadPlan(
        val fileUri: String,
        val fileName: String,
        val removed: List<Range>,
        val fileDurationMs: Long,
    )

    fun accountOf(p: Project): String = p.youtube.account.ifBlank { Prefs.accountFor(p.language) }

    fun uploadVideo(ctx: Context, pid: String, plan: UploadPlan, token: String): String? {
        val p = ProjectStore.get(pid) ?: return null
        val yt = p.youtube
        val account = accountOf(p)
        return JobManager.enqueue(ctx, "העלאה ליוטיוב: ${yt.title.ifBlank { p.name }}", pid, kind = "upload") { js ->
            val check = com.haessentz.videoeditor.data.AiImport.finalChapters(yt.chapters, plan.removed, plan.fileDurationMs)
            val desc = com.haessentz.videoeditor.data.AiImport.buildDescription(yt.description, check.lines)
            js.progress(0f, "מתחיל העלאה…")
            val id = com.haessentz.videoeditor.media.YouTubeUploader.upload(
                ctx, Uri.parse(plan.fileUri),
                com.haessentz.videoeditor.media.YouTubeUploader.Meta(yt.title.ifBlank { p.name }, desc, yt.privacy),
                token, account
            ) { f, d -> js.progress(f, d) }
            ProjectStore.update(pid) { pr ->
                pr.copy(youtube = pr.youtube.copy(lastVideoId = id),
                    outputs = pr.outputs.map { if (it.uri == plan.fileUri) it.copy(youtubeId = id) else it })
            }
            addLog(pid, "הועלה ליוטיוב ✓ https://youtu.be/$id" +
                    (if (yt.privacy == "private") "\n(הסרטון פרטי — אפשר לפרסם אותו מ־YouTube Studio)" else ""))
            val thumbDir = ProjectStore.dir(pid)
            val thumb = com.haessentz.videoeditor.media.Thumbs.finalFile(thumbDir)
            if (yt.hasThumb && thumb.exists()) {
                js.progress(1f, "מעלה תמונה ממוזערת…")
                val err = withContext(Dispatchers.IO) {
                    val t = runCatching { com.haessentz.videoeditor.media.YouTubeAuth.silentToken(ctx, account) }.getOrDefault(token)
                    com.haessentz.videoeditor.media.YouTubeUploader.setThumbnail(ctx, id, thumb, t)
                }
                if (err != null) addLog(pid, err)
            }
        }
    }

    /**
     * Uploads one short. The file and the main video's link are read when the job runs,
     * so this can be queued right after an export or after the main video's upload.
     */
    fun uploadShort(ctx: Context, pid: String, shortId: String, token: String): String? {
        val p0 = ProjectStore.get(pid) ?: return null
        val s0 = p0.shorts.firstOrNull { it.id == shortId } ?: return null
        val title = s0.title.ifBlank { s0.name }
        return JobManager.enqueue(ctx, "העלאת שורט: $title", pid, kind = "upload") { js ->
            val p = ProjectStore.get(pid) ?: return@enqueue
            val s = p.shorts.firstOrNull { it.id == shortId } ?: return@enqueue
            if (s.youtubeId != null) return@enqueue
            val uri = s.outputUri ?: throw IllegalStateException("השורט \"$title\" עוד לא יוצא, אז אין מה להעלות.")
            val mainId = p.youtube.lastVideoId
            val desc = buildString {
                append(title)
                if (mainId != null) {
                    append("\n\n▶ השיעור המלא: https://youtu.be/").append(mainId)
                    if (p.youtube.title.isNotBlank()) append("\n").append(p.youtube.title)
                } else if (p.youtube.title.isNotBlank()) {
                    append("\n\nמתוך השיעור: ").append(p.youtube.title)
                }
                append("\n\n#shorts")
            }
            val id = com.haessentz.videoeditor.media.YouTubeUploader.upload(
                ctx, Uri.parse(uri),
                com.haessentz.videoeditor.media.YouTubeUploader.Meta("$title #shorts".take(100), desc, p.youtube.privacy),
                token, accountOf(p)
            ) { f, d -> js.progress(f, d) }
            ProjectStore.update(pid) { pr -> pr.copy(shorts = pr.shorts.map { if (it.id == shortId) it.copy(youtubeId = id) else it }) }
            addLog(pid, "השורט הועלה ✓ https://youtube.com/shorts/$id")
        }
    }

    /** Exports any short that isn't exported yet, then uploads every short that isn't on YouTube yet. */
    fun uploadAllShorts(ctx: Context, pid: String, token: String): Int {
        val p = ProjectStore.get(pid) ?: return 0
        val todo = p.shorts.filter { it.youtubeId == null }
        todo.forEach { s ->
            if (s.outputUri == null) exportShort(ctx, pid, s.id)
            uploadShort(ctx, pid, s.id, token)
        }
        return todo.size
    }

    fun downloadModel(ctx: Context, modelId: String): String? {
        val info = ModelManager.models.firstOrNull { it.id == modelId } ?: return null
        return JobManager.enqueue(ctx, "הורדת מודל: ${info.title}", null, kind = "model:$modelId") { js ->
            withContext(Dispatchers.IO) {
                ModelManager.download(ctx, info) { f, d -> js.progress(f, d) }
            }
            info.langs.forEach { l -> if (!ModelManager.isReady(ctx, Prefs.modelFor(l))) Prefs.setModelFor(l, modelId) }
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
