package com.haessentz.videoeditor.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.haessentz.videoeditor.data.Range
import com.haessentz.videoeditor.data.Seg
import com.haessentz.videoeditor.data.SubStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
object VideoExporter {

    /** Exports [ranges] of [src] joined into one file. */
    suspend fun export(
        ctx: Context,
        src: Uri,
        ranges: List<Range>,
        videoEffects: List<Effect>,
        out: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val items = ranges.map { r ->
            val mediaItem = MediaItem.Builder()
                .setUri(src)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(r.startMs)
                        .setEndPositionMs(r.endMs)
                        .build()
                )
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(listOf(), videoEffects))
                .build()
        }
        val composition = Composition.Builder(listOf(EditedMediaItemSequence(items))).build()
        val simpleTrim = ranges.size == 1 && videoEffects.isEmpty()
        out.parentFile?.mkdirs()
        out.delete()

        suspendCancellableCoroutine<Unit> { cont ->
            val builder = Transformer.Builder(ctx)
            if (simpleTrim) builder.experimentalSetTrimOptimizationEnabled(true)
            val transformer = builder.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            }).build()
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 500)
                }
            }
            cont.invokeOnCancellation { handler.post { transformer.cancel() } }
            transformer.start(composition, out.absolutePath)
            handler.post(poll)
        }
    }

    /** Effects for a vertical 9:16 short with optional burned-in subtitles. */
    fun shortEffects(srcW: Int, srcH: Int, cropX: Float, cues: List<Seg>?, style: SubStyle, clipStartMs: Long): List<Effect> {
        val effects = ArrayList<Effect>()
        val outW = 1080
        val outH = 1920
        val srcAspect = srcW.toFloat() / srcH.coerceAtLeast(1)
        val target = outW.toFloat() / outH
        if (srcAspect > target + 0.01f) {
            val f = target / srcAspect // fraction of width kept
            val c = -1f + f + cropX.coerceIn(0f, 1f) * (2f - 2f * f)
            effects.add(Crop(c - f, c + f, -1f, 1f))
        }
        effects.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        if (!cues.isNullOrEmpty()) {
            val overlay: TextureOverlay = SubtitleOverlay(cues, style, outW, outH, clipStartMs * 1000)
            effects.add(OverlayEffect(ImmutableList.of(overlay)))
        }
        return effects
    }

    /** Effects for burning subtitles onto a normal (non-cropped) clip. */
    fun subtitleEffects(srcW: Int, srcH: Int, cues: List<Seg>, style: SubStyle, clipStartMs: Long): List<Effect> {
        // Fix the output size so the overlay bitmap matches it exactly.
        val scale = if (maxOf(srcW, srcH) > 1920) 1920f / maxOf(srcW, srcH) else 1f
        val w = ((srcW * scale).toInt() / 2) * 2
        val h = ((srcH * scale).toInt() / 2) * 2
        val overlay: TextureOverlay = SubtitleOverlay(cues, style, w, h, clipStartMs * 1000)
        return listOf(
            Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT),
            OverlayEffect(ImmutableList.of(overlay)),
        )
    }
}

object MediaSaver {
    /** Moves a finished video into the gallery (Movies/VideoEditor). */
    fun saveVideo(ctx: Context, file: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("לא הצלחתי לשמור לגלריה")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        file.delete()
        return uri
    }

    /** Saves a text file (e.g. SRT) to Download/VideoEditor. */
    fun saveText(ctx: Context, text: String, displayName: String, mime: String = "application/x-subrip"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoEditor")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("לא הצלחתי לשמור קובץ")
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return uri
    }
}
