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

    /** Same request for a lesson in Russian: headers stay in Hebrew (so the app can read them), content in Russian. */
    val DEFAULT_PROMPT_RU = """
להלן תמלול של סרטון ברוסית, עם זמן בתחילת כל שורה.
אנא החזר תשובה בדיוק בתבנית הבאה. את הכותרות המסומנות ב־### השאר בדיוק כפי שהן כתובות כאן (בעברית), אבל את כל התוכן — כותרת, תיאור, שמות השורטים ושמות הפרקים — כתוב ברוסית תקנית. בלי טקסט נוסף לפני או אחרי:

### מחיקה
(קטעים מיותרים למחיקה: היסוסים, הפסקות ארוכות, חזרות, טעויות, דיבור שלא שייך לנושא. שורה לכל קטע)
מ:שש-מ:שש | סיבה קצרה

### שורטים
(3 עד 5 קטעים חזקים של 30 עד 60 שניות, שמובנים גם בלי הקשר)
מ:שש-מ:שש | כותרת לשורט ברוסית

### כותרת
כותרת אחת מושכת לסרטון, ברוסית

### תיאור
תיאור של 2 עד 4 משפטים, ברוסית

### פרקים
0:00 שם הפרק ברוסית
מ:שש שם הפרק ברוסית

כללים: כל הזמנים בפורמט דקות:שניות (או שעות:דקות:שניות), לפי הזמנים בתמלול המקורי. בפרקים: לפחות 3 פרקים, הראשון ב־0:00.

התמלול:
""".trim()

    // ------------------------------------------------------------ transcript correction

    fun fixPrompt(lang: String): String {
        val l = if (lang == "ru") "רוסית" else "עברית"
        val extra = if (lang == "ru")
            "הדובר אינו דובר רוסית ילידי, לכן תקן גם דקדוק, סיומות, סדר מילים וניסוח, כך שכל שורה תהיה ברוסית תקנית וטבעית. שמור על המונחים היהודיים (למשל Тора, Ребе, хасидут) כפי שהם."
        else "תקן שגיאות זיהוי, מילים שנשמעו לא נכון ופיסוק."
        return """
להלן תמלול אוטומטי של שיעור ב$l. כל שורה מתחילה במספר בסוגריים מרובעים, למשל [12].
$extra
אל תשנה את התוכן ואל תוסיף דברים שלא נאמרו.
חשוב מאוד: השאר כל מספר בדיוק כפי שהוא, שורה אחת לכל מספר, באותו סדר. אל תאחד ואל תפצל שורות, ואל תדלג על שורות.
החזר רק את השורות המתוקנות, בלי הסברים לפני או אחרי.

התמלול:
""".trim()
    }

    fun fixLines(transcript: List<Seg>): String =
        transcript.mapIndexed { i, s -> "[${i + 1}] ${s.text.trim()}" }.joinToString("\n")

    private val fixRe = Regex("""\[(\d{1,5})\]""")

    /** Parses "[n] text" pieces (works even if line breaks were lost). Returns index (0-based) -> text. */
    fun parseFix(reply: String): Map<Int, String> {
        val ms = fixRe.findAll(reply).toList()
        val out = LinkedHashMap<Int, String>()
        ms.forEachIndexed { i, m ->
            val end = if (i + 1 < ms.size) ms[i + 1].range.first else reply.length
            val t = reply.substring(m.range.last + 1, end).replace("**", "").trim()
            val n = m.groupValues[1].toInt() - 1
            if (t.isNotEmpty() && n >= 0 && n !in out) out[n] = t
        }
        return out
    }

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

    private fun clean(line: String): String = line.replace("**", "").replace("__", "").replace("`", "").trim()

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
            h.contains("удал") || h.contains("вырез") -> Sec.DELETE
            h.contains("шорт") -> Sec.SHORTS
            h.contains("глав") || h.contains("тайм") -> Sec.CHAPTERS
            h.contains("заголов") || h.contains("назван") -> Sec.TITLE
            h.contains("описан") -> Sec.DESC
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

    private val rangeRe = Regex("""(?<![\d:])(\d{1,2}:\d{2}(?::\d{2})?)\s*[-–—־]\s*(\d{1,2}:\d{2}(?::\d{2})?)(?![\d:])""")
    private val trailingNumRe = Regex("""\s*(?:[-*•▪◦]|\d+[.)])\s*$""")

    /** Clean a label taken from between two times: separators at the start, list numbering at the end. */
    private fun tidy(s: String): String =
        s.trim().trimStart('|', '-', ':', '.', ',', ' ', '–', '—').replace(trailingNumRe, "").trim().trimEnd('|', ',', ' ').trim()

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
            var h = header(rawLine)
            var lineText = rawLine
            if (h == null) {
                // "מחיקה 0:00-0:03 | ..." — keyword glued to the first item
                val first = clean(rawLine).trimStart('#', ' ').substringBefore(' ')
                val rest = clean(rawLine).trimStart('#', ' ').substringAfter(' ', "")
                val hk = header(first)
                if (hk != null && hk.first != Sec.TITLE && hk.first != Sec.DESC && timeRe.containsMatchIn(rest)) {
                    sec = hk.first
                    lineText = rest
                }
            }
            if (h != null) {
                sec = h.first
                if (h.second.isNotEmpty()) {
                    if (sec == Sec.TITLE) title = h.second.trim('"', '\'', ' ')
                    if (sec == Sec.DESC) desc.append(h.second).append('\n')
                }
                continue
            }
            val line = clean(lineText)
            val noBullet = line.replace(bulletRe, "")
            if (noBullet.startsWith("(") && noBullet.endsWith(")")) continue // instruction echo
            val times = timeRe.findAll(noBullet).toList()
            when (sec) {
                Sec.DELETE, Sec.SHORTS -> {
                    // several items may arrive on one line when line breaks were lost in copying
                    val ms = rangeRe.findAll(noBullet).toList()
                    ms.forEachIndexed { i, m ->
                        val a = parseTime(m.groupValues[1])
                        val b = parseTime(m.groupValues[2])
                        val end = if (i + 1 < ms.size) ms[i + 1].range.first else noBullet.length
                        val label = tidy(noBullet.substring(m.range.last + 1, end))
                        if (b > a) {
                            val item = Item(Range(a, b), label)
                            if (sec == Sec.DELETE) deletes.add(item) else shorts.add(item)
                        }
                    }
                }
                Sec.CHAPTERS -> if (times.isNotEmpty() && times[0].range.first <= 3) {
                    times.forEachIndexed { i, m ->
                        val end = if (i + 1 < times.size) times[i + 1].range.first else noBullet.length
                        val name = tidy(noBullet.substring(m.range.last + 1, end))
                        if (name.isNotEmpty()) chapters.add(Chapter(parseTime(m.value), name))
                    }
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
