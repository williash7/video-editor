package com.haessentz.videoeditor.data

/** Round trip with an external AI: build the request text, and parse the reply back. */
object AiImport {

    val DEFAULT_PROMPT = """
להלן תמלול של סרטון. בתחילת כל שורה מופיעים בסוגריים זמן ההתחלה וזמן הסוף שלה.
אנא החזר תשובה בדיוק בתבנית הבאה, עם הכותרות המסומנות ב־###, בלי טקסט נוסף לפני או אחרי:

### מחיקה
(קטעים מיותרים למחיקה: היסוסים, הפסקות ארוכות, חזרות, טעויות, דיבור שלא שייך לנושא. שורה לכל קטע)
מ:שש-מ:שש | סיבה קצרה

### שורטים
(3 עד 5 שורטים של 20 עד 60 שניות. מה נכנס לשורט — לפי סדר העדיפות:
1. סיפורים — סיפור עם התחלה, אמצע וסוף (מעשה בצדיק, סיפור אישי, משל), שנגמר בשורה התחתונה.
2. דיבור על החיים עצמם — מה זה אומר לאדם ביום־יום: בבית, בעבודה, ברגש, בהתמודדות; משהו שהצופה מרגיש "זה עליי".
3. משפטים חותכים ועוצמתיים — אמירה חדה, מפתיעה או מרגשת, שאפשר לצטט.
לא לשורט: ניתוח טכני של לשון הטקסט, פלפול ושקלא וטריא, קריאת מקורות בקול, הסברי רקע ארוכים, וקטעים שנשענים על מה שנאמר קודם ("כמו שאמרנו").
כל שורט מעביר נקודה אחת, נקייה, בלי מה שמסביב. מותר — ועדיף — לבנות שורט מכמה קטעים סמוכים ולדלג באמצע על משפטים ומילים מיותרים: הקדמות, חזרות, סטיות מהנושא, היסוסים ו"כאילו". מחברים את הקטעים בסימן + . כל קטע מתחיל בדיוק בזמן ההתחלה של שורה בתמלול ונגמר בדיוק בזמן הסוף של שורה — כפי שמופיעים בסוגריים. השורט פותח במשפט שמושך מהשנייה הראשונה ומובן בלי הקשר, ונגמר כשהרעיון נסגר. לא לכלול קטעים מרשימת המחיקה. בשדה "על מה" כתוב קודם את הסוג בסוגריים: [סיפור], [חיים] או [משפט חזק])
מ:שש-מ:שש + מ:שש-מ:שש + מ:שש-מ:שש | כותרת לשורט | על מה השורט, במשפט אחד

### תיקונים
(בדיקת עובדות: עבור על כל השיעור ובדוק כל מקור שצוטט (ספר, פרק, דף, פסוק), כל ציטוט, שם של אדם, תאריך, מספר, עובדה היסטורית ופסק הלכה שנאמרו. ציין רק טעות ודאית — אם אינך בטוח, אל תציין. לכל טעות: הזמן המדויק שבו נאמרה, ותיקון קצר וברור שיופיע על המסך ליד כוכבית. אם לא נמצאו טעויות — השאר את החלק הזה ריק)
מ:שש | התיקון בקצרה, למשל: המקור הוא תניא פרק כ"ו ולא כ"ה

### כותרת
כותרת אחת מושכת לסרטון

### תיאור
תיאור של 2 עד 4 משפטים

### פרקים
0:00 פתיחה
מ:שש שם הפרק

### תמונה
(כאן הפרומפט המלא ליצירת תמונת השיעור — לפי "הנחיות לתמונת השיעור" שלמעלה. כתוב רק את הפרומפט עצמו, בעברית, מוכן להדבקה במחולל תמונות)

כללים: כל הזמנים בפורמט דקות:שניות (או שעות:דקות:שניות), לפי הזמנים בתמלול המקורי. בפרקים: לפחות 3 פרקים, הראשון ב־0:00.

התמלול:
""".trim()

    /** Same request for a lesson in Russian: headers stay in Hebrew (so the app can read them), content in Russian. */
    val DEFAULT_PROMPT_RU = """
להלן תמלול של סרטון ברוסית. בתחילת כל שורה מופיעים בסוגריים זמן ההתחלה וזמן הסוף שלה.
אנא החזר תשובה בדיוק בתבנית הבאה. את הכותרות המסומנות ב־### השאר בדיוק כפי שהן כתובות כאן (בעברית), אבל את כל התוכן — כותרת, תיאור, שמות השורטים ושמות הפרקים — כתוב ברוסית תקנית. בלי טקסט נוסף לפני או אחרי:

### מחיקה
(קטעים מיותרים למחיקה: היסוסים, הפסקות ארוכות, חזרות, טעויות, דיבור שלא שייך לנושא. שורה לכל קטע)
מ:שש-מ:שש | סיבה קצרה

### שורטים
(3 עד 5 שורטים של 20 עד 60 שניות. מה נכנס לשורט — לפי סדר העדיפות:
1. סיפורים — סיפור עם התחלה, אמצע וסוף (מעשה בצדיק, סיפור אישי, משל), שנגמר בשורה התחתונה.
2. דיבור על החיים עצמם — מה זה אומר לאדם ביום־יום: בבית, בעבודה, ברגש, בהתמודדות; משהו שהצופה מרגיש "זה עליי".
3. משפטים חותכים ועוצמתיים — אמירה חדה, מפתיעה או מרגשת, שאפשר לצטט.
לא לשורט: ניתוח טכני של לשון הטקסט, פלפול ושקלא וטריא, קריאת מקורות בקול, הסברי רקע ארוכים, וקטעים שנשענים על מה שנאמר קודם ("כמו שאמרנו").
כל שורט מעביר נקודה אחת, נקייה, בלי מה שמסביב. מותר — ועדיף — לבנות שורט מכמה קטעים סמוכים ולדלג באמצע על משפטים ומילים מיותרים: הקדמות, חזרות, סטיות מהנושא, היסוסים ו"כאילו". מחברים את הקטעים בסימן + . כל קטע מתחיל בדיוק בזמן ההתחלה של שורה בתמלול ונגמר בדיוק בזמן הסוף של שורה — כפי שמופיעים בסוגריים. השורט פותח במשפט שמושך מהשנייה הראשונה ומובן בלי הקשר, ונגמר כשהרעיון נסגר. לא לכלול קטעים מרשימת המחיקה. בשדה "על מה" כתוב קודם את הסוג בסוגריים: [סיפור], [חיים] או [משפט חזק])
מ:שש-מ:שש + מ:שש-מ:שש + מ:שש-מ:שש | כותרת לשורט ברוסית | על מה השורט, במשפט אחד בעברית

### תיקונים
(בדיקת עובדות: עבור על כל השיעור ובדוק כל מקור שצוטט (ספר, פרק, דף, פסוק), כל ציטוט, שם של אדם, תאריך, מספר, עובדה היסטורית ופסק הלכה שנאמרו. ציין רק טעות ודאית — אם אינך בטוח, אל תציין. לכל טעות: הזמן המדויק שבו נאמרה, ותיקון קצר וברור ברוסית שיופיע על המסך ליד כוכבית. אם לא נמצאו טעויות — השאר את החלק הזה ריק)
מ:שש | התיקון בקצרה ברוסית

### כותרת
כותרת אחת מושכת לסרטון, ברוסית

### תיאור
תיאור של 2 עד 4 משפטים, ברוסית

### פרקים
0:00 שם הפרק ברוסית
מ:שש שם הפרק ברוסית

### תמונה
(כאן הפרומפט המלא ליצירת תמונת השיעור — לפי "הנחיות לתמונת השיעור" שלמעלה. כתוב רק את הפרומפט עצמו, בעברית, מוכן להדבקה במחולל תמונות)

כללים: כל הזמנים בפורמט דקות:שניות (או שעות:דקות:שניות), לפי הזמנים בתמלול המקורי. בפרקים: לפחות 3 פרקים, הראשון ב־0:00.

התמלול:
""".trim()

    /** The fact-check section, for prompts the user saved before it existed. */
    fun factCheckBlock(lang: String) = """
### תיקונים
${(check(lang))}
מ:שש | התיקון בקצרה${if (lang == "ru") " ברוסית" else ""}
""".trim()

    private fun check(lang: String) =
        "(בדיקת עובדות: עבור על כל השיעור ובדוק כל מקור שצוטט (ספר, פרק, דף, פסוק), כל ציטוט, שם של אדם, תאריך, מספר, עובדה היסטורית ופסק הלכה שנאמרו. ציין רק טעות ודאית — אם אינך בטוח, אל תציין. לכל טעות: הזמן המדויק שבו נאמרה, ותיקון קצר וברור" +
            (if (lang == "ru") " ברוסית" else "") + " שיופיע על המסך ליד כוכבית. אם לא נמצאו טעויות — השאר את החלק הזה ריק)"

    /** The user's rules for the lesson thumbnail; the AI writes an image prompt from them. */
    val THUMB_GUIDE = """
הנחיות לתמונת השיעור (טאמבנייל) — בחלק "### תמונה" בתשובה כתוב גם את הפרומפט המלא, מוכן להדבקה במחולל:

הוראות ליצירת תמונת השיעור (טאמבנייל)

לאחר קליטת תמלול השיעור, יש לזהות את הרעיון המרכזי, ה־Hook החזק ביותר או הדימוי הבולט ביותר, ומתוכו ליצור טאמבנייל מסקרן ומושך ליוטיוב.

תהליך העבודה
1. לזהות את השאלה המרכזית, הפאנץ׳ או הרעיון המפתיע של השיעור.
2. לפצח כותרת חזקה ורעיון ויזואלי מקורי שממחיש את העסקענץ של השיעור.
3. לא להסתפק בכתיבת פרומפט — לבצע בפועל את משימה 3.2: יצירת התמונה עצמה.
4. לבנות את התמונה סביב רעיון ויזואלי אחד ברור, בלי עומס פרטים.

סגנון התמונה
5. יחס 16:9, מותאם ליוטיוב.
6. סגנון ריאליסטי־סוריאליסטי, קולנועי, חד, דרמטי ומושך עין.
7. להימנע ממראה מצועצע, זוהר מוגזם, קלישאות ותמונות גנריות.
8. רצוי לשלב הפתעה, מקוריות ולעיתים קריצה קומית המתאימה לתוכן.
9. להעדיף רעיון מפתיע ובלתי צפוי על פני תמונה רגילה של רב וספרייה.

שמירה על דמות הרב
10. לשמור על המראה המזוהה לפי תמונת הייחוס: פנים, זקן, משקפיים, כובע שחור, חליפה שחורה וחולצה לבנה.
11. גם בשימוש בהשתקפות, דמות מקבילה או משל חזותי, יש לשמור על זיהוי ברור של הרב.

גבולות אמוניים וחזותיים — חובה
12. אין להמחיש בצורה גשמית את הקב״ה, השכינה, מלאכים, נשמות, כוחות אלוקיים או ישויות רוחניות. לא באמצעות דמויות, ידיים מן השמים, כנפיים, פנים, צלליות וכדומה.
13. יש להתרחק לחלוטין מדימויים וסמלים המזוהים עם נצרות, דתות המזרח או אמונות זרות: הילות קדושים, מלאכים מכונפים, סמלי פולחן, צ׳אקרות, מיסטיקה זרה וכדומה.
14. רעיונות רוחניים יש להמחיש באמצעות משלים גשמיים: אנשים, חפצים, פעולות וסיטואציות, בלי להציג את המשל כדמותו של המושג הרוחני.
15. הנאמנות לאמונה היהודית ולתוכן החסידי קודמת לכל שיקול עיצובי.

קומפוזיציה
16. מוקד מרכזי אחד, מעט אלמנטים וקומפוזיציה ברורה גם בגודל קטן.
17. התמונה צריכה להעביר את הרעיון בתוך שנייה ולעורר סקרנות.

טקסט על התמונה
18. כותרת ראשית — בדיוק לפי כותרת הסרטון, בצהוב בוהק עם מסגרת שחורה עבה.
19. שורת הסבר קצרה בלבן, רק אם נדרשת.
20. תגית הסדרה בתחתית, קטנה, ברורה ונקייה, למשל: „איגרת התשובה”.

עיקרון מנחה

הטאמבנייל אינו נועד רק להיות יפה, אלא להמחיש את העסקענץ של השיעור, לעורר סקרנות ולגרום לצופה ללחוץ.

יש לעבור במהירות מניתוח התמלול לפיצוח הכותרת והקונספט, ומשם ליצירת התמונה בפועל, תוך שמירה על כל הכללים לעיל.
""".trim()

    /** Adds the thumbnail guidance and the "### תמונה" section to any prompt (also an older saved one). */
    fun withThumbGuide(prompt: String): String {
        var out = prompt
        if (!out.contains("### תמונה")) {
            val i = out.lastIndexOf("התמלול:")
            val sec = "### תמונה\n(כאן הפרומפט המלא ליצירת תמונת השיעור — לפי \"הנחיות לתמונת השיעור\". כתוב רק את הפרומפט עצמו, בעברית)"
            out = if (i > 0) out.substring(0, i).trimEnd() + "\n\n" + sec + "\n\n" + out.substring(i) else out.trimEnd() + "\n\n" + sec
        }
        if (!out.contains("הנחיות לתמונת השיעור (טאמבנייל)")) {
            val i = out.lastIndexOf("התמלול:")
            out = if (i > 0) out.substring(0, i).trimEnd() + "\n\n" + THUMB_GUIDE + "\n\n" + out.substring(i) else out.trimEnd() + "\n\n" + THUMB_GUIDE
        }
        return out
    }

    /** Shorts guidance, for prompts the user saved before it existed. */
    private val SHORTS_GUIDE = """
הנחיות לבחירת שורטים — מה נכנס לשורט, לפי סדר העדיפות:
1. סיפורים — סיפור עם התחלה, אמצע וסוף (מעשה בצדיק, סיפור אישי, משל), שנגמר בשורה התחתונה.
2. דיבור על החיים עצמם — מה זה אומר לאדם ביום־יום; משהו שהצופה מרגיש "זה עליי".
3. משפטים חותכים ועוצמתיים — אמירה חדה, מפתיעה או מרגשת, שאפשר לצטט.
לא לשורט: ניתוח טכני של הטקסט, פלפול, קריאת מקורות, הסברי רקע ארוכים, וקטעים שנשענים על מה שנאמר קודם.
מותר ועדיף לחבר כמה קטעים סמוכים בסימן + ולדלג על משפטים מיותרים באמצע.
""".trim()

    fun withShortsGuide(prompt: String): String {
        if (prompt.contains("מה נכנס לשורט")) return prompt
        val i = prompt.lastIndexOf("התמלול:")
        return if (i > 0) prompt.substring(0, i).trimEnd() + "\n\n" + SHORTS_GUIDE + "\n\n" + prompt.substring(i)
        else prompt.trimEnd() + "\n\n" + SHORTS_GUIDE
    }

    /** Makes sure any prompt (also an older saved one) asks for the fact check. */
    fun withFactCheck(prompt: String, lang: String): String {
        if (prompt.contains("### תיקונים")) return prompt
        val i = prompt.lastIndexOf("התמלול:")
        return if (i > 0) prompt.substring(0, i).trimEnd() + "\n\n" + factCheckBlock(lang) + "\n\n" + prompt.substring(i)
        else prompt.trimEnd() + "\n\n" + factCheckBlock(lang)
    }

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
        var lineEnd = 0L
        val line = StringBuilder()
        fun flush() {
            if (line.isEmpty()) return
            sb.append('[').append(fmtMs(lineStart)).append('-').append(fmtMs(lineEnd)).append("] ").append(line).append('\n')
            line.setLength(0); lineStart = -1
        }
        for (s in transcript) {
            if (lineStart < 0) lineStart = s.startMs
            if (line.isNotEmpty()) line.append(' ')
            line.append(s.text.trim())
            lineEnd = s.endMs
            val ends = s.text.trimEnd().lastOrNull()?.let { it == '.' || it == '?' || it == '!' } ?: false
            if (line.length > 45 || (ends && line.length > 15)) flush()
        }
        flush()
        return sb.toString()
    }

    /** Moves a piece's edges to the nearest segment start/end (the transcript lines the AI saw). */
    fun snapPiece(r: Range, transcript: List<Seg>): Range {
        if (transcript.isEmpty()) return r
        val start = transcript.filter { kotlin.math.abs(it.startMs - r.startMs) <= 2500 }
            .minByOrNull { kotlin.math.abs(it.startMs - r.startMs) }?.startMs ?: r.startMs
        val end = transcript.filter { kotlin.math.abs(it.endMs - r.endMs) <= 2500 && it.endMs > start }
            .minByOrNull { kotlin.math.abs(it.endMs - r.endMs) }?.endMs ?: r.endMs
        return Range((start - 100).coerceAtLeast(0), maxOf(end + 150, start + 500))
    }

    /** Moves a range's edges to the nearest sentence start/end in the transcript (within a few seconds). */
    fun snapToSentences(r: Range, transcript: List<Seg>): Range {
        if (transcript.isEmpty()) return r
        fun endsSentence(s: Seg) = s.text.trimEnd().lastOrNull()?.let { it == '.' || it == '?' || it == '!' } ?: false
        // start: a segment that starts near r.start; prefer one right after a sentence end
        val starts = transcript.indices.filter { kotlin.math.abs(transcript[it].startMs - r.startMs) <= 4000 }
        val start = starts.minByOrNull { i ->
            val d = kotlin.math.abs(transcript[i].startMs - r.startMs)
            val bonus = if (i == 0 || endsSentence(transcript[i - 1])) 0 else 2500
            d + bonus
        }?.let { transcript[it].startMs } ?: r.startMs
        val ends = transcript.filter { kotlin.math.abs(it.endMs - r.endMs) <= 5000 && it.endMs > start + 5000 }
        val end = ends.minByOrNull { s -> kotlin.math.abs(s.endMs - r.endMs) + if (endsSentence(s)) 0 else 2500 }?.endMs ?: r.endMs
        // a little air after the last word
        return Range(start, (end + 300).coerceAtLeast(start + 1000))
    }

    /** [range] spans all [pieces]; a short may be several pieces joined. */
    data class Item(val range: Range, val label: String, val pieces: List<Range> = listOf(range))

    data class Result(
        val deletes: List<Item>,
        val shorts: List<Item>,
        val title: String,
        val description: String,
        val chapters: List<Chapter>,
        val notes: List<FactNote> = emptyList(),
        val imagePrompt: String = "",
    ) {
        val isEmpty get() = deletes.isEmpty() && shorts.isEmpty() && title.isBlank() && description.isBlank() && chapters.isEmpty() && notes.isEmpty() && imagePrompt.isBlank()
    }

    private enum class Sec { NONE, DELETE, SHORTS, TITLE, DESC, CHAPTERS, NOTES, IMAGE }

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
            h.contains("תמונה") || h.contains("טאמבנייל") || h.contains("thumbnail") || h.contains("изображ") || h.contains("картин") -> Sec.IMAGE
            h.contains("תיקונ") || h.contains("טעויות") || h.contains("исправ") || h.contains("поправ") || h.contains("ошиб") -> Sec.NOTES
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
        val notes = ArrayList<FactNote>()
        var title = ""
        val desc = StringBuilder()
        var sec = Sec.NONE
        val image = StringBuilder()
        for (rawLine in text.lines()) {
            // the image prompt is free text: inside it only a "###" line starts a new section
            if (sec == Sec.IMAGE && !rawLine.trimStart().startsWith("#")) {
                val t = rawLine.trim()
                if (!t.startsWith("```") && !t.startsWith("(כאן")) image.append(rawLine.trimEnd()).append('\n')
                continue
            }
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
                Sec.DELETE -> {
                    // several items may arrive on one line when line breaks were lost in copying
                    val ms = rangeRe.findAll(noBullet).toList()
                    ms.forEachIndexed { i, m ->
                        val a = parseTime(m.groupValues[1])
                        val b = parseTime(m.groupValues[2])
                        val end = if (i + 1 < ms.size) ms[i + 1].range.first else noBullet.length
                        val label = tidy(noBullet.substring(m.range.last + 1, end))
                        if (b > a) deletes.add(Item(Range(a, b), label))
                    }
                }
                Sec.SHORTS -> {
                    // ranges joined by "+" belong to the same short; a label between ranges starts a new short
                    val ms = rangeRe.findAll(noBullet).toList()
                    var cur = ArrayList<Range>()
                    ms.forEachIndexed { i, m ->
                        val a = parseTime(m.groupValues[1])
                        val b = parseTime(m.groupValues[2])
                        if (b > a) cur.add(Range(a, b))
                        val end = if (i + 1 < ms.size) ms[i + 1].range.first else noBullet.length
                        val gap = noBullet.substring(m.range.last + 1, end)
                        val joinOnly = i + 1 < ms.size && gap.trim().trim('+', ',', '&', ' ').isEmpty()
                        if (!joinOnly && cur.isNotEmpty()) {
                            val ps = cur.sortedBy { it.startMs }
                            shorts.add(Item(Range(ps.first().startMs, ps.maxOf { it.endMs }), tidy(gap), ps))
                            cur = ArrayList()
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
                Sec.NOTES -> if (times.isNotEmpty() && times[0].range.first <= 3) {
                    val t = parseTime(times[0].value)
                    // "m:ss-m:ss | text" is fine too: take the first time
                    val after = if (times.size > 1 && times[1].range.first - times[0].range.last <= 3) times[1].range.last + 1 else times[0].range.last + 1
                    val txt = tidy(noBullet.substring(after))
                    if (txt.isNotEmpty()) notes.add(FactNote(t, txt))
                }
                Sec.TITLE -> if (title.isEmpty()) title = noBullet.trim('"', '\'', ' ')
                Sec.DESC -> desc.append(line).append('\n')
                Sec.NONE, Sec.IMAGE -> {}
            }
        }
        return Result(deletes, shorts, title, desc.toString().trim(), chapters.sortedBy { it.ms }, notes.sortedBy { it.ms }, image.toString().trim())
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

/** How long a correction stays on screen. */
fun noteDurationMs(text: String): Long = (4500L + text.length * 60L).coerceIn(5000L, 11000L)

/** Notes as timed pieces for a clip [start, end] (times relative to start), optionally skipping removed parts. */
fun notesForClip(notes: List<FactNote>, start: Long, end: Long, removedRel: List<Range> = emptyList()): List<Seg> =
    notes.filter { it.ms in start until end }.map {
        val a = AiImport.mapTime(it.ms - start, removedRel)
        Seg(a, a + noteDurationMs(it.text), it.text)
    }
