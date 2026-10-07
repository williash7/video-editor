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
    /** Title for YouTube (e.g. suggested by the AI). */
    val title: String = "",
    /** Gallery uri of the last export of this short. */
    val outputUri: String? = null,
    val youtubeId: String? = null,
)

@Serializable
data class OutputFile(
    val name: String,
    val uri: String,
    val createdAt: Long,
    /** "edited", "short", "clip", "part", "full" */
    val kind: String = "",
    /** Parts of the original removed in this file (for re-timing chapters). */
    val removed: List<Range> = emptyList(),
    val youtubeId: String? = null,
)

@Serializable
data class Chapter(val ms: Long, val title: String)

@Serializable
data class YoutubeMeta(
    val title: String = "",
    val description: String = "",
    /** Chapter times on the ORIGINAL video timeline. */
    val chapters: List<Chapter> = emptyList(),
    val privacy: String = "private",
    /** Uri of the file to upload; null = choose automatically. */
    val videoUri: String? = null,
    val thumbText: String = "",
    val thumbTextTop: Boolean = false,
    val hasThumb: Boolean = false,
    val lastVideoId: String? = null,
)

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
    val youtube: YoutubeMeta = YoutubeMeta(),
    val aiReply: String = "",
)
