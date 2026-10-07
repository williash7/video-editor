package com.haessentz.videoeditor.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import com.haessentz.videoeditor.data.Seg
import com.haessentz.videoeditor.data.SubStyle

/** Draws one subtitle onto a canvas of size w x h. Shared by the live preview and the export. */
object SubtitleRenderer {
    fun draw(canvas: Canvas, text: String, style: SubStyle, w: Int, h: Int) {
        val t = text.trim()
        if (t.isEmpty() || w <= 0 || h <= 0) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = style.sizePct / 100f * h
            typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            color = style.textColor.toInt()
        }
        val maxW = (w * 0.86f).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()
        val textH = layout.height.toFloat()
        val margin = h * 0.03f
        val top = (style.posY * h - textH / 2f).coerceIn(margin, (h - textH - margin).coerceAtLeast(margin))
        canvas.save()
        canvas.translate((w - maxW) / 2f, top)

        val boxAlpha = ((style.boxColor ushr 24) and 0xFF).toInt()
        if (boxAlpha > 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.boxColor.toInt() }
            val padX = paint.textSize * 0.35f
            val padY = paint.textSize * 0.12f
            val r = paint.textSize * 0.25f
            for (i in 0 until layout.lineCount) {
                val rect = RectF(
                    layout.getLineLeft(i) - padX, layout.getLineTop(i) - padY,
                    layout.getLineRight(i) + padX, layout.getLineBottom(i) + padY
                )
                canvas.drawRoundRect(rect, r, r, bp)
            }
        }

        if (style.outlineWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = style.outlineWidth * paint.textSize
            paint.color = style.outlineColor.toInt()
            if (style.shadow) paint.setShadowLayer(paint.textSize * 0.08f, 0f, paint.textSize * 0.05f, Color.argb(160, 0, 0, 0))
            layout.draw(canvas)
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL
            paint.color = style.textColor.toInt()
            layout.draw(canvas)
        } else {
            if (style.shadow) paint.setShadowLayer(paint.textSize * 0.08f, 0f, paint.textSize * 0.05f, Color.argb(200, 0, 0, 0))
            layout.draw(canvas)
        }
        canvas.restore()
    }
}

/**
 * Media3 overlay that burns subtitle cues into the exported video.
 * Cue times are relative to the clip start.
 */
@UnstableApi
class SubtitleOverlay(
    private val cues: List<Seg>,
    private val style: SubStyle,
    private val w: Int,
    private val h: Int,
    private val clipStartUs: Long,
) : BitmapOverlay() {
    private val empty: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private var lastIdx = -2
    private var lastBitmap: Bitmap = empty
    private var offsetUs: Long = -1

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (offsetUs < 0) {
            // Some pipelines report absolute source times, others clip-relative ones. Detect on the first frame.
            offsetUs = if (clipStartUs > 1_000_000 && presentationTimeUs >= clipStartUs - 500_000) clipStartUs else 0L
        }
        val tMs = (presentationTimeUs - offsetUs) / 1000
        val idx = cues.indexOfFirst { tMs >= it.startMs && tMs < it.endMs }
        if (idx != lastIdx) {
            lastIdx = idx
            lastBitmap = if (idx < 0) empty else render(cues[idx].text)
        }
        return lastBitmap
    }

    private fun render(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        SubtitleRenderer.draw(Canvas(bmp), text, style, w, h)
        return bmp
    }
}
