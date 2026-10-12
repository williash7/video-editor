package com.haessentz.videoeditor.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * A weekly lesson series. [name] is the key and the tag on the thumbnail; [titleName] ends the YouTube title
 * ("… | סדר היום 5"); [numbered] = lessons carry a running number.
 */
data class SeriesInfo(
    val name: String, val weekday: Int, val season: Int, val dayName: String,
    val titleName: String = name, val numbered: Boolean = true,
)

object Series {
    val all = listOf(
        SeriesInfo("סדר היום של יהודי", Calendar.SUNDAY, 1, "ראשון", titleName = "סדר היום"),
        SeriesInfo("פרשת שבוע", Calendar.MONDAY, 2, "שני", numbered = false),
        SeriesInfo("אגרת התשובה", Calendar.TUESDAY, 3, "שלישי"),
        SeriesInfo("עין יעקב", Calendar.WEDNESDAY, 4, "רביעי"),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name }

    /** The series taught on the weekday of [timeMs] (Israel time), or null (Thu–Sat). */
    fun forDate(timeMs: Long): SeriesInfo? {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem")).apply { timeInMillis = timeMs }
        return all.firstOrNull { it.weekday == c.get(Calendar.DAY_OF_WEEK) }
    }

    /** The channel's last published numbers as of Oct 2026 (used until the user sets or reads them). */
    val knownLast = mapOf("סדר היום של יהודי" to 5, "אגרת התשובה" to 6, "עין יעקב" to 4)

    /** "…hook… | עין יעקב 4" — the ending every title in a numbered series must have. */
    fun suffix(series: String, no: Int): String {
        val si = byName(series) ?: return ""
        return if (!si.numbered || no <= 0) "" else "${si.titleName} $no"
    }

    /** Makes sure the title ends with " | <series> שיעור <n>" (replacing an older ending if there is one). */
    fun withSuffix(title: String, series: String, no: Int): String {
        val suf = suffix(series, no)
        if (suf.isEmpty()) return title.trim()
        val hook = hookOf(title)
        return if (hook.isEmpty()) suf else "$hook | $suf"
    }

    private val tailRe by lazy {
        val names = all.flatMap { listOf(it.name, it.titleName) } + listOf("איגרת התשובה", "פרשת")
        Regex("""\|\s*(?:""" + names.joinToString("|") { Regex.escape(it) } + """).*$""")
    }

    /** The part of the title before " | <series> …" (used as the big text on the thumbnail). */
    fun hookOf(title: String): String = title.trim().replace(tailRe, "").trim().trimEnd('|').trim()

    /** Recording date from the video's metadata or its file name (e.g. 102027_20261008), else now. */
    fun recordingDate(metaDate: String?, fileName: String): Long {
        if (!metaDate.isNullOrBlank()) {
            for (f in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
                runCatching {
                    val sdf = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val d = sdf.parse(metaDate)
                    if (d != null && d.time > 946684800000L) return d.time
                }
            }
        }
        Regex("""(20\d{2})(\d{2})(\d{2})""").find(fileName)?.let { m ->
            val (y, mo, d) = m.destructured
            val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"))
            c.set(y.toInt(), mo.toInt() - 1, d.toInt(), 12, 0, 0)
            return c.timeInMillis
        }
        return System.currentTimeMillis()
    }

    /**
     * The highest lesson number per series found in video titles, e.g.
     * "… | עין יעקב שיעור 3", "… | איגרת התשובה 4", "עין יעקב חלק 1", "סדר היום 2".
     */
    fun lastNumbersFromTitles(titles: List<String>): Map<String, Int> {
        val aliases = mapOf(
            "סדר היום של יהודי" to listOf("סדר היום של יהודי", "סדר היום"),
            "פרשת שבוע" to listOf("פרשת שבוע", "פרשת השבוע"),
            "אגרת התשובה" to listOf("אגרת התשובה", "איגרת התשובה"),
            "עין יעקב" to listOf("עין יעקב"),
        )
        val out = HashMap<String, Int>()
        for (t in titles) {
            if (t.contains("#shorts", ignoreCase = true)) continue
            for ((name, names) in aliases) {
                for (a in names) {
                    val m = Regex(Regex.escape(a) + "[\\s\\-–|:]*(?:שיעור|חלק|פרק)?[\\s#]*(\\d{1,3})").find(t) ?: continue
                    val n = m.groupValues[1].toInt()
                    if (n > (out[name] ?: 0)) out[name] = n
                    break
                }
            }
        }
        return out
    }

    fun dateText(ms: Long): String =
        SimpleDateFormat("d.M.yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Jerusalem") }.format(ms)
}
