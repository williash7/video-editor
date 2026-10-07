package com.haessentz.videoeditor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import android.net.Uri

/** Finds where the speaker's face is, so the 9:16 crop can be centred on it. Fully offline. */
object AutoFrame {

    /**
     * Returns cropX (0 = left edge, 1 = right edge) that centres the faces found in the clip,
     * or null if no face was found or the video is already vertical.
     */
    fun cropFor(ctx: Context, video: Uri, startMs: Long, endMs: Long, srcW: Int, srcH: Int): Float? {
        val srcAspect = srcW.toFloat() / srcH.coerceAtLeast(1)
        val target = 9f / 16f
        if (srcAspect <= target + 0.01f) return null
        val keep = target / srcAspect // fraction of the width that stays visible

        val r = MediaMetadataRetriever()
        val xs = ArrayList<Float>()
        try {
            r.setDataSource(ctx, video)
            val samples = 7
            for (i in 0 until samples) {
                val t = startMs + (endMs - startMs) * (i + 0.5f) / samples
                val frame = r.getScaledFrameAtTime((t * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                    ?: continue
                faceX(frame)?.let { xs.add(it) }
                frame.recycle()
            }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
        if (xs.isEmpty()) return null
        val fx = xs.sorted()[xs.size / 2] // median: ignores a stray detection
        val left = (fx - keep / 2f).coerceIn(0f, 1f - keep)
        return if (1f - keep <= 0f) 0.5f else left / (1f - keep)
    }

    /** Horizontal centre of the biggest face as a fraction of the width, or null. */
    private fun faceX(src: Bitmap): Float? {
        val w = src.width - (src.width % 2) // FaceDetector needs an even width
        if (w < 2) return null
        val bmp = Bitmap.createBitmap(src, 0, 0, w, src.height).copy(Bitmap.Config.RGB_565, false) ?: return null
        val faces = arrayOfNulls<FaceDetector.Face>(4)
        val n = FaceDetector(bmp.width, bmp.height, faces.size).findFaces(bmp, faces)
        var best: FaceDetector.Face? = null
        for (i in 0 until n) {
            val f = faces[i] ?: continue
            if (f.confidence() < 0.3f) continue
            if (best == null || f.eyesDistance() > best.eyesDistance()) best = f
        }
        val p = PointF()
        val result = best?.let { it.getMidPoint(p); p.x / bmp.width }
        bmp.recycle()
        return result
    }
}
