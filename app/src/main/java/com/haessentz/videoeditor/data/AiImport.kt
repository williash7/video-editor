package com.haessentz.videoeditor.data

/** Round trip with an external AI: build the request text, and parse the reply back. */
object AiImport {

    val DEFAULT_PROMPT = """
להלן תמלול של סרטון, עם זמן בתחילת כל שורה.
אנא החזר תשובה בדיוק בתבנית הבאה, עם הכותרות המסומנות ב־###, בלי טקסט נוסף לפני או אחרי:

### מחיקה
(קטעים מיותרים למחיקה: היסוסים, הפסקות ארוכות, חזרות, טעויות, דיבור שלא שייך לנושא. שורה לכל קטע)
מ:שש-מ:שש | סיבה קצרה

### שורטים
(3 עד 5 קטעים חזקים של 30 עד 60 שניות, שמובנים גם בלי הקשר)
מ:שש-מ:שש | כותרת לשורט

### כותרת
כותרת אחת מושכת לסרטון

### תיאור
תיאור של 2 עד 4 משפטים

### פרקים
0:00 פתיחה
מ:שש שם הפרק

כללים: כל הזמנים בפורמט דקות:שניות (או שעות:דקות:שניות), לפי הזמנים בתמלול המקורי. בפרקים: לפחות 3 פרקים, הראשון ב־0:00.

התמלול:
""".trim()

    /** Transcript as "[m:ss] text" lines, merging short segments into readable lines. */
    fun transcriptText(transcript: List<Seg>): String {
        val sb = StringBuilder()
        var lineStart = -1L
        val line = StringBuilder()
        for (s in transcript) {
            if (lineStart < 0) lineStart = s.startMs
            if (line.isNotEmpty()) line.append(' ')
            line.append(s.text.trim())
            val ends = s.text.trimEnd().lastOrNull()?.let { it == '.' || it == '?' || it == '!' } ?: false
            if (line.length > 110 || (ends && line.length > 40)) {
                sb.append('[').append(fmtMs(lineStart)).append("] ").append(line).append('\n')
                line.setLength(0); lineStart = -1
            }
        }
        if (line.isNotEmpty()) sb.append('[').append(fmtMs(lineStart)).append("] ").append(line).append('\n')
        return sb.toString()
    }

    data class Item(val range: Range, val label: String)

    data class Result(
        val deletes: List<Item>,
        val shorts: List<Item>,
        val title: String,
        val description: String,
        val chapters: List<Chapter>,
    ) {
        val isEmpty get() = deletes.isEmpty() && shorts.isEmpty() && title.isBlank() && description.isBlank() && chapters.isEmpty()
    }

    private enum class Sec { NONE, DELETE, SHORTS, TITLE, DESC, CHAPTERS }

    private val timeRe = Regex("""(?<![\d:])(\d{1,2}:\d{2}(?::\d{2})?)(?![\d:])""")
    private val bulletRe = Regex("""^\s*(?:[-*•▪◦>]+|\d+[.)])\s*""")

    private fun parseTime(s: String): Long {
        val p = s.split(':').map { it.toLong() }
        return if (p.size == 3) p[0] * 3_600_000 + p[1] * 60_000 + p[2] * 1000 else p[0] * 60_000 + p[1] * 1000
    }

    private fun clean(line: String): String = line.replace("**", "").replace("__", "").replace("`", "")
        .replace('–', '-').replace('—', '-').replace('־', '-').trim()

    private fun header(line: String): Pair<Sec, String>? {
        val raw = clean(line)
        val isMarked = raw.startsWith("#")
        val t = raw.trimStart('#', ' ').trim()
        val head = t.substringBefore(':').trim()
        val rest = if (t.contains(':')) t.substringAfter(':').trim() else ""
        // a header is short and either marked with # or ends with ':' or is just the keyword
        if (!isMarked && head.length > 25) return null
        if (!isMarked && timeRe.containsMatchIn(head)) return null
        val shortHead = head.split(' ').filter { it.isNotEmpty() }.size <= 3
        if (!isMarked && !(t.endsWith(":") || (t.contains(':') && shortHead) || t.split(' ').size <= 2)) return null
        val h = head.lowercase()
        val sec = when {
            h.contains("מחיק") || h.contains("למחוק") || h.contains("להסיר") || h.contains("הסרה") || h.contains("מיותר") || h.contains("להוציא") -> Sec.DELETE
            h.contains("שורט") || h.contains("short") || h.contains("רילס") -> Sec.SHORTS
            h.contains("פרק") || h.contains("chapter") -> Sec.CHAPTERS
            h.contains("כותרת") || h == "title" -> Sec.TITLE
            h.contains("תיאור") || h.contains("description") -> Sec.DESC
            else -> return null
        }
        if (!isMarked && rest.isNotEmpty() && sec != Sec.TITLE && sec != Sec.DESC) return null
        return sec to (if (sec == Sec.TITLE || sec == Sec.DESC) rest else "")
    }

    private fun labelAfter(line: String, lastTimeEnd: Int): String =
        line.substring(lastTimeEnd).trim().trimStart('|', '-', ':', '.', ',', ' ', '–').trim()

    fun parse(text: String): Result {
        val deletes = ArrayList<Item>()
        val shorts = ArrayList<Item>()
        val chapters = ArrayList<Chapter>()
        var title = ""
        val desc = StringBuilder()
        var sec = Sec.NONE
        for (rawLine in text.lines()) {
            if (rawLine.isBlank()) {
                if (sec == Sec.DESC && desc.isNotEmpty()) desc.append('\n')
                continue
            }
            val h = header(rawLine)
            if (h != null) {
                sec = h.first
                if (h.second.isNotEmpty()) {
                    if (sec == Sec.TITLE) title = h.second.trim('"', '\'', ' ')
                    if (sec == Sec.DESC) desc.append(h.second).append('\n')
                }
                continue
            }
            val line = clean(rawLine)
            val noBullet = line.replace(bulletRe, "")
            if (noBullet.startsWith("(") && noBullet.endsWith(")")) continue // instruction echo
            val times = timeRe.findAll(noBullet).toList()
            when (sec) {
                Sec.DELETE, Sec.SHORTS -> if (times.size >= 2) {
                    val a = parseTime(times[0].value)
                    val b = parseTime(times[1].value)
                    if (b > a) {
                        val item = Item(Range(a, b), labelAfter(noBullet, times[1].range.last + 1))
                        if (sec == Sec.DELETE) deletes.add(item) else shorts.add(item)
                    }
                }
                Sec.CHAPTERS -> if (times.isNotEmpty() && times[0].range.first <= 3) {
                    val t = parseTime(times[0].value)
                    val name = labelAfter(noBullet, times[0].range.last + 1)
                    if (name.isNotEmpty()) chapters.add(Chapter(t, name))
                }
                Sec.TITLE -> if (title.isEmpty()) title = noBullet.trim('"', '\'', ' ')
                Sec.DESC -> desc.append(line).append('\n')
                Sec.NONE -> {}
            }
        }
        return Result(deletes, shorts, title, desc.toString().trim(), chapters.sortedBy { it.ms })
    }

    // ------------------------------------------------------------ chapter timing

    /** Maps a time on the original video to the time in a file where [removed] parts were cut out. */
    fun mapTime(t: Long, removed: List<Range>): Long {
        var shift = 0L
        for (r in removed.sortedBy { it.startMs }) {
            when {
                t >= r.endMs -> shift += r.lengthMs
                t > r.startMs -> return r.startMs - shift
                else -> break
            }
        }
        return t - shift
    }

    data class ChapterCheck(val lines: List<Pair<Long, String>>, val warnings: List<String>)

    /** Chapters re-timed for the chosen file, with YouTube's rules applied. */
    fun finalChapters(chapters: List<Chapter>, removed: List<Range>, fileDurationMs: Long): ChapterCheck {
        val warnings = ArrayList<String>()
        val mapped = chapters.map { mapTime(it.ms, removed) to it.title.trim() }
            .filter { it.second.isNotEmpty() && it.first < fileDurationMs }
            .sortedBy { it.first }
        val out = ArrayList<Pair<Long, String>>()
        for (c in mapped) {
            val last = out.lastOrNull()
            if (last != null && c.first - last.first < 10_000) {
                warnings.add("הפרק \"${c.second}\" קרוב מדי לקודם (פחות מ־10 שניות) ולכן הושמט.")
                continue
            }
            out.add(c)
        }
        if (out.isNotEmpty() && out[0].first != 0L) {
            if (out[0].first < 10_000) out[0] = 0L to out[0].second
            else out.add(0, 0L to "פתיחה")
        }
        if (out.isNotEmpty() && out.size < 3) warnings.add("יוטיוב מציג פרקים רק כשיש לפחות 3.")
        return ChapterCheck(out, warnings)
    }

    fun buildDescription(description: String, chapters: List<Pair<Long, String>>): String {
        if (chapters.isEmpty()) return description.trim()
        val ch = chapters.joinToString("\n") { "${fmtMs(it.first)} ${it.second}" }
        return (description.trim() + "\n\n" + ch).trim()
    }
}
