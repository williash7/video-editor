package com.haessentz.videoeditor.data

import java.util.Locale

fun fmtMs(ms: Long, tenths: Boolean = false): String {
    val t = ms.coerceAtLeast(0)
    val h = t / 3_600_000
    val m = (t / 60_000) % 60
    val s = (t / 1000) % 60
    val base = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
    return if (tenths) "$base.${(t % 1000) / 100}" else base
}

/** For file names: 1:02:03 -> 1-02-03 */
fun fileTime(ms: Long) = fmtMs(ms).replace(':', '-')

object Ranges {
    /** Sort, clamp and merge overlapping ranges. */
    fun normalize(list: List<Range>, durationMs: Long): List<Range> {
        val sorted = list.map { Range(it.startMs.coerceIn(0, durationMs), it.endMs.coerceIn(0, durationMs)) }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        val out = ArrayList<Range>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.startMs <= last.endMs) out[out.size - 1] = Range(last.startMs, maxOf(last.endMs, r.endMs))
            else out.add(r)
        }
        return out
    }

    /** The parts of [0, duration] not covered by [removed]. */
    fun complement(removed: List<Range>, durationMs: Long, minKeepMs: Long = 150): List<Range> {
        val norm = normalize(removed, durationMs)
        val keep = ArrayList<Range>()
        var cur = 0L
        for (r in norm) {
            if (r.startMs - cur >= minKeepMs) keep.add(Range(cur, r.startMs))
            cur = r.endMs
        }
        if (durationMs - cur >= minKeepMs) keep.add(Range(cur, durationMs))
        return keep
    }
}

object Cues {
    private val ws = Regex("\\s+")

    /** Split one segment into chunks of at most [maxWords] words, timing proportional to characters. */
    fun split(seg: Seg, maxWords: Int): List<Seg> {
        val words = seg.text.trim().split(ws).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        if (maxWords <= 0 || words.size <= maxWords) return listOf(seg.copy(text = words.joinToString(" ")))
        val chunks = words.chunked(maxWords).map { it.joinToString(" ") }
        val totalChars = chunks.sumOf { it.length }.coerceAtLeast(1)
        val dur = seg.endMs - seg.startMs
        var t = seg.startMs
        return chunks.mapIndexed { i, c ->
            val end = if (i == chunks.lastIndex) seg.endMs else t + dur * c.length / totalChars
            val s = Seg(t, end, c)
            t = end
            s
        }
    }

    /** Build cues for a clip [start, end] from a transcript, times relative to start. */
    fun forClip(transcript: List<Seg>, startMs: Long, endMs: Long, maxWords: Int): List<Seg> {
        return transcript.asSequence()
            .filter { it.endMs > startMs && it.startMs < endMs }
            .flatMap { split(it, maxWords).asSequence() }
            .filter { (it.startMs + it.endMs) / 2 in startMs until endMs }
            .map { Seg((it.startMs - startMs).coerceAtLeast(0), (it.endMs - startMs).coerceAtMost(endMs - startMs), it.text) }
            .filter { it.endMs > it.startMs }
            .toList()
    }

    fun toSrt(cues: List<Seg>): String {
        fun t(ms: Long): String {
            val h = ms / 3_600_000; val m = (ms / 60_000) % 60; val s = (ms / 1000) % 60; val r = ms % 1000
            return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, r)
        }
        val sb = StringBuilder()
        cues.forEachIndexed { i, c ->
            sb.append(i + 1).append('\n').append(t(c.startMs)).append(" --> ").append(t(c.endMs)).append('\n')
                .append(c.text.trim()).append("\n\n")
        }
        return sb.toString()
    }
}
