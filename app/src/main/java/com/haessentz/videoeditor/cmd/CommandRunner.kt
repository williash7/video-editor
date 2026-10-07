package com.haessentz.videoeditor.cmd

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.haessentz.videoeditor.data.Cues
import com.haessentz.videoeditor.data.Hit
import com.haessentz.videoeditor.data.LogEntry
import com.haessentz.videoeditor.data.ProjectStore
import com.haessentz.videoeditor.data.Range
import com.haessentz.videoeditor.data.Ranges
import com.haessentz.videoeditor.data.ShortClip
import com.haessentz.videoeditor.data.SubStyle
import com.haessentz.videoeditor.data.fmtMs
import com.haessentz.videoeditor.media.MediaSaver
import com.haessentz.videoeditor.work.Jobs
import java.util.UUID

/** Executes a typed command against a project and writes the conversation into the project log. */
@UnstableApi
object CommandRunner {

    fun run(ctx: Context, pid: String, input: String) {
        val p = ProjectStore.get(pid) ?: return
        ProjectStore.update(pid) { it.copy(log = (it.log + LogEntry(true, input.trim())).takeLast(200)) }
        val (reply, hits) = execute(ctx, pid, CommandParser.parse(input), p.durationMs)
        if (reply.isNotEmpty()) Jobs.addLog(pid, reply, hits)
    }

    private fun r(s: String) = s to emptyList<Hit>()

    private fun fmt(r: Range) = "${fmtMs(r.startMs)}–${fmtMs(r.endMs)}"

    private fun validate(ranges: List<Range>, dur: Long): String? {
        val bad = ranges.firstOrNull { it.startMs >= dur }
        return if (bad != null) "הזמן ${fmtMs(bad.startMs)} אחרי סוף הסרטון (${fmtMs(dur)})." else null
    }

    private fun execute(ctx: Context, pid: String, cmd: Cmd, dur: Long): Pair<String, List<Hit>> {
        val p = ProjectStore.get(pid)!!
        return when (cmd) {
            is Cmd.Error -> r(cmd.message)
            Cmd.Help -> r(CommandParser.HELP)

            is Cmd.Cut -> {
                validate(cmd.ranges, dur)?.let { return r(it) }
                Jobs.exportRanges(ctx, pid, cmd.ranges, join = false, label = "קטע")
                r(if (cmd.ranges.size == 1) "חותך ${fmt(cmd.ranges[0])}… הקובץ יישמר בגלריה."
                else "חותך ${cmd.ranges.size} קטעים, כל אחד לקובץ נפרד: ${cmd.ranges.joinToString(", ") { fmt(it) }}")
            }

            is Cmd.Keep -> {
                validate(cmd.ranges, dur)?.let { return r(it) }
                Jobs.exportRanges(ctx, pid, cmd.ranges, join = true, label = "מחובר")
                r("מחבר ${cmd.ranges.size} קטעים לסרטון אחד…")
            }

            is Cmd.Remove -> {
                validate(cmd.ranges, dur)?.let { return r(it) }
                val updated = ProjectStore.update(pid) { it.copy(cutList = Ranges.normalize(it.cutList + cmd.ranges, it.durationMs)) }!!
                val removed = updated.cutList.sumOf { it.lengthMs }
                if (cmd.exportNow) {
                    Jobs.exportCutList(ctx, pid)
                    r("הוספתי לרשימה ומייצא עכשיו. מוציא ${fmtMs(removed)} מתוך ${fmtMs(dur)}.")
                } else {
                    r("נוסף לרשימת החיתוך: ${cmd.ranges.joinToString(", ") { fmt(it) }}\n" +
                            "ברשימה עכשיו ${updated.cutList.size} קטעים (${fmtMs(removed)}). הסרטון יהיה באורך ${fmtMs(dur - removed)}.\n" +
                            "כתוב \"ייצא\" כשסיימת.")
                }
            }

            Cmd.ShowCutList -> {
                if (p.cutList.isEmpty()) r("רשימת החיתוך ריקה.")
                else ("ברשימת החיתוך:\n" + p.cutList.mapIndexed { i, x -> "${i + 1}. ${fmt(x)}" }.joinToString("\n")) to
                        p.cutList.map { Hit(it.startMs, fmtMs(it.startMs)) }
            }

            Cmd.ClearCutList -> {
                ProjectStore.update(pid) { it.copy(cutList = emptyList()) }
                r("רשימת החיתוך נוקתה.")
            }

            Cmd.ExportCutList -> {
                if (p.cutList.isEmpty()) r("רשימת החיתוך ריקה. קודם כתוב למשל: תוציא 10:00-12:30")
                else {
                    Jobs.exportCutList(ctx, pid)
                    r("מייצא את הסרטון בלי ${p.cutList.size} הקטעים שברשימה…")
                }
            }

            is Cmd.Silence -> {
                Jobs.removeSilence(ctx, pid, cmd.minMs)
                r("מחפש שתיקות ארוכות מ־${"%.1f".format(cmd.minMs / 1000.0)} שניות ומוציא אותן…")
            }

            is Cmd.Split -> {
                val n = (dur / cmd.partMs).coerceAtLeast(1)
                Jobs.split(ctx, pid, cmd.partMs)
                r("מפצל לחלקים של בערך ${fmtMs(cmd.partMs)} (בערך $n חלקים)" +
                        if (p.transcript.isNotEmpty()) ", בסוף משפט." else ".")
            }

            is Cmd.Short -> {
                validate(cmd.ranges, dur)?.let { return r(it) }
                val noTranscript = p.transcript.isEmpty()
                val style = SubStyle()
                val created = cmd.ranges.mapIndexed { i, rg ->
                    ShortClip(
                        id = UUID.randomUUID().toString(),
                        name = "שורט ${p.shorts.size + i + 1}",
                        startMs = rg.startMs, endMs = rg.endMs.coerceAtMost(dur),
                        cues = Cues.forClip(p.transcript, rg.startMs, rg.endMs.coerceAtMost(dur), style.maxWords),
                        subtitles = cmd.subtitles && !noTranscript,
                        style = style,
                    )
                }
                ProjectStore.update(pid) { it.copy(shorts = it.shorts + created) }
                created.forEach { Jobs.exportShort(ctx, pid, it.id) }
                val note = when {
                    !cmd.subtitles -> ""
                    noTranscript -> "\nאין עדיין תמלול, אז השורט ייצא בלי כתוביות. כדי לקבל כתוביות: כתוב \"תמלל\", ואחר כך ייצא שוב מלשונית שורטים."
                    else -> "\nאפשר לערוך את הכתוביות והעיצוב בלשונית שורטים ולייצא שוב."
                }
                r("יוצר ${created.size} שורט: ${cmd.ranges.joinToString(", ") { fmt(it) }}$note")
            }

            is Cmd.Search -> {
                if (p.transcript.isEmpty()) return r("אין עדיין תמלול. כתוב \"תמלל\" קודם.")
                val q = cmd.query
                val found = p.transcript.filter { it.text.lowercase().contains(q) }
                if (found.isEmpty()) r("לא נמצא \"$q\" בתמלול.")
                else {
                    val shown = found.take(30)
                    val text = "נמצא ${found.size} פעמים" + (if (found.size > 30) " (מציג 30 ראשונים)" else "") + ":\n" +
                            shown.joinToString("\n") { "${fmtMs(it.startMs)} — ${it.text}" }
                    text to shown.map { Hit(it.startMs, fmtMs(it.startMs)) }
                }
            }

            Cmd.Transcribe -> {
                Jobs.transcribe(ctx, pid)
                r("מתחיל תמלול. בסרטון של שעה זה יכול לקחת חצי שעה ויותר — כדאי לחבר למטען. אפשר לצאת מהאפליקציה בינתיים.")
            }

            Cmd.SubtitlesSrt -> {
                if (p.transcript.isEmpty()) return r("אין עדיין תמלול. כתוב \"תמלל\" קודם.")
                val srt = Cues.toSrt(p.transcript.flatMap { Cues.split(it, 10) })
                runCatching { MediaSaver.saveText(ctx, srt, "${p.name}.srt") }
                    .fold({ r("קובץ הכתוביות נשמר בתיקיית ההורדות: Download/VideoEditor/${p.name}.srt") },
                        { r("שמירת הקובץ נכשלה: ${it.message}") })
            }

            Cmd.BurnSubtitles -> {
                if (p.transcript.isEmpty()) return r("אין עדיין תמלול. כתוב \"תמלל\" קודם.")
                Jobs.exportWithSubtitles(ctx, pid)
                r("מייצא את כל הסרטון עם כתוביות צרובות. זה ייקח זמן…")
            }
        }
    }
}
