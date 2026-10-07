package com.haessentz.videoeditor.data

import kotlinx.serialization.Serializable

/** A timed piece of text (transcript segment or subtitle cue). Times in ms. */
@Serializable
data class Seg(val startMs: Long, val endMs: Long, val text: String)

@Serializable
data class Range(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

@Serializable
data class SubStyle(
    /** Text size as percent of the video height. */
    val sizePct: Float = 4.2f,
    val textColor: Long = 0xFFFFFFFF,
    val outlineColor: Long = 0xFF000000,
    /** Outline width relative to text size (0 = none). */
    val outlineWidth: Float = 0.14f,
    /** Background box colour; alpha 0 = no box. */
    val boxColor: Long = 0x00000000,
    /** Vertical centre of the subtitle block, 0 = top, 1 = bottom. */
    val posY: Float = 0.72f,
    val bold: Boolean = true,
    val shadow: Boolean = true,
    /** Maximum words per subtitle when splitting from the transcript. */
    val maxWords: Int = 5,
)

@Serializable
data class ShortClip(
    val id: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    /** Horizontal crop position for 9:16, 0 = left edge, 1 = right edge. */
    val cropX: Float = 0.5f,
    /** Subtitle cues, times relative to [startMs]. */
    val cues: List<Seg> = emptyList(),
    val subtitles: Boolean = true,
    val style: SubStyle = SubStyle(),
)

@Serializable
data class OutputFile(val name: String, val uri: String, val createdAt: Long)

@Serializable
data class Hit(val ms: Long, val label: String)

@Serializable
data class LogEntry(val fromUser: Boolean, val text: String, val hits: List<Hit> = emptyList())

@Serializable
data class Project(
    val id: String,
    val name: String,
    val videoUri: String,
    val durationMs: Long,
    /** Display width/height (after rotation). */
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val transcript: List<Seg> = emptyList(),
    val transcribed: Boolean = false,
    val cutList: List<Range> = emptyList(),
    val shorts: List<ShortClip> = emptyList(),
    val outputs: List<OutputFile> = emptyList(),
    val log: List<LogEntry> = emptyList(),
)
