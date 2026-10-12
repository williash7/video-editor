package com.haessentz.videoeditor.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A weekly lesson series: which weekday it is taught on, and its Spotify season number. */
data class SeriesInfo(val name: String, val weekday: Int, val season: Int, val dayName: String)

object Series {
    val all = listOf(
        SeriesInfo("סדר היום של יהודי", Calendar.SUNDAY, 1, "ראשון"),
        SeriesInfo("פרשת שבוע", Calendar.MONDAY, 2, "שני"),
        SeriesInfo("אגרת התשובה", Calendar.TUESDAY, 3, "שלישי"),
        SeriesInfo("עין יעקב", Calendar.WEDNESDAY, 4, "רביעי"),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name }

    /** The series taught on the weekday of [timeMs] (Israel time), or null (Thu–Sat). */
    fun forDate(timeMs: Long): SeriesInfo? {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem")).apply { timeInMillis = timeMs }
        return all.firstOrNull { it.weekday == c.get(Calendar.DAY_OF_WEEK) }
    }

    /** "…hook… | עין יעקב שיעור 3" — the ending every title must have. */
    fun suffix(series: String, no: Int) = if (series.isBlank() || no <= 0) "" else "$series שיעור $no"

    /** Makes sure the title ends with " | <series> שיעור <n>" (replacing an older ending if there is one). */
    fun withSuffix(title: String, series: String, no: Int): String {
        val suf = suffix(series, no)
        if (suf.isEmpty()) return title.trim()
        val hook = hookOf(title)
        return if (hook.isEmpty()) suf else "$hook | $suf"
    }

    /** The part of the title before " | <series> …" (used as the big text on the thumbnail). */
    fun hookOf(title: String): String {
        val t = title.trim()
        val i = all.map { t.lastIndexOf("| " + it.name) }.filter { it >= 0 }.maxOrNull()
        return (if (i != null) t.substring(0, i) else t).trim().trimEnd('|').trim()
    }

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

    fun dateText(ms: Long): String =
        SimpleDateFormat("d.M.yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Jerusalem") }.format(ms)
}
