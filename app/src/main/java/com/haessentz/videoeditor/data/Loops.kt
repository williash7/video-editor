package com.haessentz.videoeditor.data

/**
 * Finds places where the transcription "got stuck in a loop": the same word or phrase repeated
 * over and over instead of what was actually said (a known Whisper failure on long recordings).
 */
object Loops {
    private val wordRe = Regex("""[\p{L}\p{N}'-]+""")

    private fun words(t: String): List<String> = wordRe.findAll(t.lowercase()).map { it.value.trim('-', '\'') }.filter { it.isNotEmpty() }.toList()

    /** True if this single line is mostly one word or one short phrase repeated. */
    fun lineLooks(t: String): Boolean {
        val w = words(t)
        if (w.size < 3) return false
        val top = w.groupingBy { it }.eachCount().maxOf { it.value }
        if (top >= 3 && top * 10 >= w.size * 4) return true
        // a 2- or 3-word phrase repeated 3+ times ("это книга, это книга, это книга")
        for (n in 2..3) {
            if (w.size < n * 3) continue
            val grams = (0..w.size - n).map { w.subList(it, it + n).joinToString(" ") }
            if (grams.groupingBy { it }.eachCount().maxOf { it.value } >= 3) return true
        }
        return false
    }

    /** Indices of looped lines: a line that loops by itself, or the 3rd+ identical line in a row. */
    fun badIndices(tr: List<Seg>): Set<Int> {
        val bad = HashSet<Int>()
        tr.forEachIndexed { i, s -> if (lineLooks(s.text)) bad.add(i) }
        var run = 0
        for (i in 1 until tr.size) {
            val same = words(tr[i].text) == words(tr[i - 1].text) && words(tr[i].text).isNotEmpty()
            run = if (same) run + 1 else 0
            if (run >= 2) for (j in i - run..i) bad.add(j)
        }
        return bad
    }

    /**
     * Time ranges to re-transcribe. Nearby looped lines are merged, and each range is widened by one
     * good line on each side, so the new pass starts and ends on a whole sentence.
     */
    fun badRanges(tr: List<Seg>): List<Range> {
        val bad = badIndices(tr).sorted()
        if (bad.isEmpty()) return emptyList()
        val groups = ArrayList<IntRange>()
        var a = bad.first(); var b = a
        for (i in bad.drop(1)) {
            if (i - b <= 3) b = i else { groups.add(a..b); a = i; b = i }
        }
        groups.add(a..b)
        val out = ArrayList<Range>()
        for (g in groups) {
            val from = (g.first - 1).coerceAtLeast(0)
            val to = (g.last + 1).coerceAtMost(tr.size - 1)
            val r = Range(tr[from].startMs, tr[to].endMs)
            val last = out.lastOrNull()
            if (last != null && r.startMs <= last.endMs) out[out.size - 1] = Range(last.startMs, maxOf(last.endMs, r.endMs)) else out.add(r)
        }
        return out
    }

    /** Replaces every line inside [r] with [fresh] (only the fresh lines that fall inside the range). */
    fun replace(tr: List<Seg>, r: Range, fresh: List<Seg>): List<Seg> {
        val keep = tr.filter { it.endMs <= r.startMs || it.startMs >= r.endMs }
        val add = fresh.filter { it.startMs >= r.startMs - 500 && it.startMs < r.endMs }
            .map { it.copy(startMs = it.startMs.coerceAtLeast(r.startMs), endMs = it.endMs.coerceAtMost(r.endMs)) }
        return (keep + add).sortedBy { it.startMs }
    }
}
