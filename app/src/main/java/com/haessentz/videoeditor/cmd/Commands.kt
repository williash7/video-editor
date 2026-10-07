package com.haessentz.videoeditor.cmd

import com.haessentz.videoeditor.data.Range

sealed class Cmd {
    data class Cut(val ranges: List<Range>) : Cmd()
    data class Keep(val ranges: List<Range>) : Cmd()
    data class Remove(val ranges: List<Range>, val exportNow: Boolean) : Cmd()
    data class Short(val ranges: List<Range>, val subtitles: Boolean) : Cmd()
    data class Silence(val minMs: Long) : Cmd()
    data class Split(val partMs: Long) : Cmd()
    data class Search(val query: String) : Cmd()
    data object ExportCutList : Cmd()
    data object ClearCutList : Cmd()
    data object ShowCutList : Cmd()
    data object Transcribe : Cmd()
    data object SubtitlesSrt : Cmd()
    data object BurnSubtitles : Cmd()
    data object Help : Cmd()
    data class Error(val message: String) : Cmd()
}

/**
 * Understands simple Hebrew commands, fully offline. Examples:
 *  "תחתוך מ-1:20 עד 3:45"            -> Cut
 *  "תוציא 10:00-12:30, 25:00-26:10"  -> Remove (adds to the cut list)
 *  "שורט 12:30-13:30"                -> Short
 *  "הסר שתיקות מעל 2 שניות"          -> Silence
 *  "פצל לקטעים של 10 דקות"           -> Split
 *  "חפש בעל שם טוב"                  -> Search
 */
object CommandParser {

    private enum class TU { H, M, S }

    private data class Tok(val ms: Long, val hasColon: Boolean, val unit: TU?, val rawValue: Double)

    private val timeRe = Regex("""\d+(?::\d{1,2}){0,2}(?:[.,]\d+)?""")

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }
    private val splitRe = Regex("""[\s,.;:!?"'()\-]+""")
    /** Whole-word match, so that "הסר" does not match "הסרטון". */
    private fun word(t: String, vararg words: String): Boolean {
        val ws = t.split(splitRe)
        return words.any { k -> ws.any { it == k || it == "ו$k" } }
    }

    fun parse(input: String): Cmd {
        val t = input.trim()
            .replace('–', '-').replace('—', '-').replace('־', '-')
            .replace("״", "\"").replace("''", "\"")
            .lowercase()
        if (t.isEmpty()) return Cmd.Error("כתוב פקודה. אפשר לכתוב \"עזרה\" לרשימת הפקודות.")

        // search comes first: the query itself may contain any word
        val searchKey = listOf("איפה אמרתי", "תחפש", "חפש", "תמצא", "מצא").firstOrNull { t.startsWith(it) || t.contains(" $it ") }
        if (searchKey != null) {
            val q = t.substringAfter(searchKey).trim().trim('"', '\'', ':', ' ').removePrefix("את ").trim()
            return if (q.isEmpty()) Cmd.Error("מה לחפש? לדוגמה: חפש בעל שם טוב") else Cmd.Search(q)
        }

        if (has(t, "עזרה", "פקודות", "help") || t == "?") return Cmd.Help
        if (has(t, "תמלל", "תמלול")) return Cmd.Transcribe
        if (has(t, "srt") || (has(t, "כתוביות") && has(t, "קובץ", "שמור", "ייצא", "תייצא"))) {
            return if (has(t, "צרוב", "תצרוב", "על הסרטון")) Cmd.BurnSubtitles else Cmd.SubtitlesSrt
        }
        if (has(t, "צרוב", "תצרוב")) return Cmd.BurnSubtitles

        val toks = tokens(t)

        if (has(t, "שתיק", "שקט")) {
            val sec = toks.firstOrNull()?.let { if (it.hasColon) it.ms / 1000.0 else it.rawValue } ?: 1.0
            return Cmd.Silence((sec * 1000).toLong().coerceIn(300, 30_000))
        }

        if (has(t, "פצל", "תפצל", "לפצל", "תחלק", "חלק ל")) {
            val tok = toks.firstOrNull() ?: return Cmd.Error("לכמה זמן כל חלק? לדוגמה: פצל לקטעים של 10 דקות")
            val ms = when {
                tok.hasColon -> tok.ms
                tok.unit == null -> (tok.rawValue * 60_000).toLong() // default: minutes
                else -> toMs(tok, tok.unit)
            }
            if (ms < 5_000) return Cmd.Error("חלק קצר מדי.")
            return Cmd.Split(ms)
        }

        val isShort = has(t, "שורט", "short", "רילס", "reels", "סרטון קצר")
        val isRemove = has(t, "תוציא", "הוצא", "להוציא", "תמחק", "למחוק", "תסיר", "להסיר", "תוריד", "להוריד", "תעיף") || word(t, "מחק", "הסר", "הורד")
        val isExport = has(t, "ייצא", "תייצא", "לייצא", "תבצע") || word(t, "בצע", "יצא")
        val isClear = has(t, "נקה", "תנקה", "תאפס") || word(t, "אפס")
        val isKeep = has(t, "השאר", "תשאיר", "תחבר", "לחבר", "רק את") || word(t, "חבר")
        val isList = has(t, "רשימ", "הצג")

        if (toks.isEmpty()) {
            return when {
                isClear -> Cmd.ClearCutList
                isExport -> Cmd.ExportCutList
                isList -> Cmd.ShowCutList
                isShort -> Cmd.Error("מאיזה זמן עד איזה זמן? לדוגמה: שורט 12:30-13:30")
                else -> Cmd.Error("לא הבנתי. כתוב \"עזרה\" כדי לראות דוגמאות.")
            }
        }

        val ranges = pairs(toks) ?: return Cmd.Error("חסר זמן סיום. כל קטע צריך התחלה וסוף, לדוגמה: 1:20-3:45")
        if (ranges.any { it.endMs <= it.startMs }) return Cmd.Error("זמן הסיום חייב להיות אחרי זמן ההתחלה.")

        return when {
            isShort -> Cmd.Short(ranges, subtitles = !has(t, "בלי כתוביות", "ללא כתוביות"))
            isRemove -> Cmd.Remove(ranges, exportNow = isExport)
            isKeep -> Cmd.Keep(ranges)
            else -> Cmd.Cut(ranges)
        }
    }

    private fun pairs(toks: List<Tok>): List<Range>? {
        if (toks.size % 2 != 0) return null
        return toks.chunked(2).map { (a, b) ->
            // "מ-5 עד 7 דקות": a unit written once applies to both numbers
            val ua = a.unit ?: if (!a.hasColon && !b.hasColon) b.unit else null
            val ub = b.unit ?: if (!a.hasColon && !b.hasColon) a.unit else null
            Range(toMs(a, ua), toMs(b, ub))
        }
    }

    private fun toMs(t: Tok, u: TU?): Long = if (t.hasColon) t.ms else when (u) {
        TU.H -> (t.rawValue * 3_600_000).toLong()
        TU.M -> (t.rawValue * 60_000).toLong()
        else -> (t.rawValue * 1000).toLong()
    }

    private fun tokens(t: String): List<Tok> {
        return timeRe.findAll(t).map { m ->
            val s = m.value.replace(',', '.')
            val before = t.substring(maxOf(0, m.range.first - 7), m.range.first)
            val after = t.substring(m.range.last + 1, minOf(t.length, m.range.last + 8))
            val unit = unitOf(after.trimStart().take(5)) ?: unitOf(before.takeLast(7))
            if (s.contains(':')) {
                val parts = s.split(':')
                val secPart = parts.last().toDouble()
                val ms = when (parts.size) {
                    2 -> parts[0].toLong() * 60_000 + (secPart * 1000).toLong()
                    else -> parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 + (secPart * 1000).toLong()
                }
                Tok(ms, true, unit, 0.0)
            } else {
                val v = s.toDoubleOrNull() ?: 0.0
                Tok((v * 1000).toLong(), false, unit, v)
            }
        }.toList()
    }

    private fun unitOf(s: String): TU? = when {
        s.contains("דק") || s.contains("min") -> TU.M
        s.contains("שע") -> TU.H
        s.contains("שנ") || s.contains("sec") -> TU.S
        else -> null
    }

    val HELP = """
פקודות לדוגמה:

✂️ חיתוך קטע (קובץ חדש):
• תחתוך מ-1:20 עד 3:45
• חתוך משנייה 80 עד 225
• חתוך 1:00-2:00, 5:00-6:00  (כל קטע קובץ נפרד)

🧹 הוצאת קטעים מיותרים:
• תוציא 10:00-12:30 ו-25:00-26:10  (נכנס לרשימת החיתוך)
• רשימה  /  נקה רשימה
• ייצא  (מייצא את הסרטון בלי הקטעים שברשימה)
• הסר שתיקות  /  הסר שתיקות מעל 2 שניות

🔗 חיבור קטעים:
• השאר 1:00-5:00, 10:00-12:00  (מחבר לסרטון אחד)

📱 שורטים:
• שורט 12:30-13:30  (אנכי, עם כתוביות אם יש תמלול)
• שורט 12:30-13:30 בלי כתוביות
• פצל לקטעים של 10 דקות

📝 תמלול וכתוביות:
• תמלל
• חפש בעל שם טוב
• שמור כתוביות  (קובץ SRT)
• צרוב כתוביות  (כל הסרטון עם כתוביות)

זמנים: 1:20 = דקה ו־20 שניות, 1:02:03 = שעה, 2 דקות ו־3 שניות, 80 = 80 שניות, "דקה 5" = 5 דקות.
""".trim()
}
