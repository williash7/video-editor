package com.haessentz.videoeditor.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

object YouTubeAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/youtube.upload"

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    /** Interactive: may need to show Google's consent screen via [launch]. */
    fun authorize(activity: Activity, launch: (android.content.IntentSender) -> Unit, onToken: (String) -> Unit, onError: (String) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { res ->
                val pi = res.pendingIntent
                if (res.hasResolution() && pi != null) launch(pi.intentSender)
                else res.accessToken?.let(onToken) ?: onError("לא התקבלה הרשאה")
            }
            .addOnFailureListener { e -> onError(explain(e)) }
    }

    fun fromIntent(ctx: Context, data: Intent?): String? = runCatching {
        val r: AuthorizationResult = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)
        r.accessToken
    }.getOrNull()

    /** Background refresh (works once the user has already agreed). Must not run on the main thread. */
    fun silentToken(ctx: Context): String {
        val res = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()))
        if (res.hasResolution()) throw YouTubeException("צריך להתחבר מחדש לגוגל — לחץ שוב על העלאה")
        return res.accessToken ?: throw YouTubeException("לא התקבלה הרשאה מגוגל")
    }

    fun explain(e: Exception): String {
        val m = e.message ?: e.toString()
        return when {
            m.contains("10:") || m.contains("DEVELOPER_ERROR") ->
                "ההגדרה ב־Google Cloud עדיין לא הושלמה (חסר מזהה OAuth לאנדרואיד עם טביעת האצבע של האפליקציה)."
            m.contains("12501") || m.contains("anceled") -> "ההתחברות בוטלה."
            else -> "שגיאת התחברות לגוגל: $m"
        }
    }
}

class YouTubeException(message: String, val code: Int = 0) : IOException(message)

object YouTubeUploader {
    private const val CHUNK = 16 * 256 * 1024 // 4 MB, multiple of 256 KB

    data class Meta(val title: String, val description: String, val privacy: String, val tags: List<String> = emptyList())

    /**
     * Resumable upload. Survives network drops and expired tokens.
     * Returns the YouTube video id.
     */
    suspend fun upload(
        ctx: Context,
        video: Uri,
        meta: Meta,
        firstToken: String,
        onProgress: (Float, String) -> Unit,
    ): String {
        var token = firstToken
        val pfd = ctx.contentResolver.openFileDescriptor(video, "r") ?: throw IOException("לא הצלחתי לפתוח את הקובץ")
        pfd.use {
            val total = it.statSize
            if (total <= 0) throw IOException("גודל הקובץ לא ידוע")
            val channel = FileInputStream(it.fileDescriptor).channel

            val body = JSONObject().apply {
                put("snippet", JSONObject().apply {
                    put("title", meta.title.take(100).ifBlank { "סרטון" })
                    put("description", meta.description.take(5000).replace("<", "‹").replace(">", "›"))
                    put("categoryId", "27")
                    put("defaultLanguage", "he")
                    put("defaultAudioLanguage", "he")
                    if (meta.tags.isNotEmpty()) put("tags", org.json.JSONArray(meta.tags))
                })
                put("status", JSONObject().apply {
                    put("privacyStatus", meta.privacy)
                    put("selfDeclaredMadeForKids", false)
                })
            }.toString().toByteArray(Charsets.UTF_8)

            // 1. open a session
            var session: String? = null
            var attempt = 0
            while (session == null) {
                currentCoroutineContext().ensureActive()
                val c = (URL("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    instanceFollowRedirects = false
                    connectTimeout = 30_000; readTimeout = 60_000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("X-Upload-Content-Length", total.toString())
                    setRequestProperty("X-Upload-Content-Type", "video/mp4")
                    setFixedLengthStreamingMode(body.size)
                }
                try {
                    c.outputStream.use { o -> o.write(body) }
                    val code = c.responseCode
                    when {
                        code == 200 || code == 201 -> session = c.getHeaderField("Location") ?: throw IOException("יוטיוב לא החזיר כתובת העלאה")
                        code == 401 -> { token = YouTubeAuth.silentToken(ctx); attempt++ }
                        code >= 500 -> { attempt++; delay(3000L * attempt) }
                        else -> throw apiError(c, code)
                    }
                } catch (e: YouTubeException) { throw e } catch (e: IOException) {
                    attempt++
                    if (attempt > 8) throw e
                    delay(3000L * attempt)
                } finally { c.disconnect() }
                if (attempt > 8) throw IOException("יוטיוב לא זמין כרגע, נסה שוב מאוחר יותר")
            }

            // 2. send the file in chunks
            val buf = ByteArray(CHUNK)
            var offset = 0L
            var failures = 0
            val started = System.currentTimeMillis()
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = minOf(CHUNK.toLong(), total - offset).toInt()
                channel.position(offset)
                val bb = ByteBuffer.wrap(buf, 0, n)
                while (bb.hasRemaining()) if (channel.read(bb) < 0) break
                val c = (URL(session).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    instanceFollowRedirects = false
                    connectTimeout = 30_000; readTimeout = 120_000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "video/mp4")
                    setRequestProperty("Content-Range", "bytes $offset-${offset + n - 1}/$total")
                    setFixedLengthStreamingMode(n)
                }
                try {
                    c.outputStream.use { o -> o.write(buf, 0, n) }
                    val code = c.responseCode
                    when (code) {
                        200, 201 -> {
                            val json = JSONObject(c.inputStream.bufferedReader().readText())
                            onProgress(1f, "הועלה")
                            return json.getString("id")
                        }
                        308 -> {
                            offset = nextOffset(c.getHeaderField("Range"))
                            failures = 0
                            val secs = (System.currentTimeMillis() - started) / 1000.0
                            val mbps = if (secs > 0) offset / 1_000_000.0 / secs else 0.0
                            onProgress(offset.toFloat() / total,
                                "${offset / 1_000_000} / ${total / 1_000_000} MB · ${String.format(java.util.Locale.US, "%.1f", mbps)} MB/s")
                        }
                        401 -> { token = YouTubeAuth.silentToken(ctx); offset = queryOffset(session, total, token) }
                        in 500..599 -> { failures++; delay(minOf(60_000L, 2000L * failures)); offset = queryOffset(session, total, token) }
                        else -> throw apiError(c, code)
                    }
                } catch (e: YouTubeException) { throw e } catch (e: IOException) {
                    failures++
                    if (failures > 20) throw IOException("החיבור נקטע יותר מדי פעמים. נסה שוב כשיש רשת יציבה.")
                    onProgress(offset.toFloat() / total, "החיבור נקטע, מנסה שוב…")
                    delay(minOf(60_000L, 2000L * failures))
                    offset = runCatching { queryOffset(session, total, token) }.getOrDefault(offset)
                } finally { c.disconnect() }
            }
        }
    }

    private fun nextOffset(range: String?): Long =
        range?.substringAfter("-", "")?.toLongOrNull()?.plus(1) ?: 0L

    private fun queryOffset(session: String, total: Long, token: String): Long {
        val c = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Range", "bytes */$total")
            setFixedLengthStreamingMode(0)
        }
        try {
            c.outputStream.close()
            return if (c.responseCode == 308) nextOffset(c.getHeaderField("Range")) else 0L
        } finally { c.disconnect() }
    }

    private fun apiError(c: HttpURLConnection, code: Int): YouTubeException {
        val text = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: ""
        val reason = runCatching {
            JSONObject(text).getJSONObject("error").getJSONArray("errors").getJSONObject(0).getString("reason")
        }.getOrNull() ?: ""
        val msg = when {
            reason == "quotaExceeded" -> "נגמרה המכסה היומית של יוטיוב. אפשר להעלות שוב מחר."
            reason == "uploadLimitExceeded" -> "הגעת למגבלת ההעלאות היומית של הערוץ."
            code == 403 && text.contains("accessNotConfigured") -> "צריך להפעיל את YouTube Data API v3 בפרויקט ב־Google Cloud."
            code == 403 -> "אין הרשאה להעלות ($reason). בדוק שהחשבון שבחרת הוא של הערוץ."
            code == 400 -> "יוטיוב דחה את הפרטים: $reason ${text.take(200)}"
            else -> "שגיאת יוטיוב $code $reason"
        }
        return YouTubeException(msg, code)
    }

    /** Sets a custom thumbnail (needs a verified channel). Returns an error message or null on success. */
    fun setThumbnail(ctx: Context, videoId: String, jpeg: File, token: String): String? {
        val bytes = jpeg.readBytes()
        val c = (URL("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=$videoId")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "image/jpeg")
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            if (code in 200..299) null
            else if (code == 403) "התמונה הממוזערת לא הועלתה: הערוץ צריך אימות טלפון ביוטיוב כדי לאפשר תמונות מותאמות."
            else "התמונה הממוזערת לא הועלתה (שגיאה $code)."
        } catch (e: IOException) {
            "התמונה הממוזערת לא הועלתה: ${e.message}"
        } finally { c.disconnect() }
    }
}

/** Builds 1280x720 thumbnails from a video frame or an image, with optional title text. */
object Thumbs {
    const val W = 1280
    const val H = 720

    fun baseFile(dir: File) = File(dir, "thumb_base.jpg")
    fun finalFile(dir: File) = File(dir, "thumb.jpg")

    fun fromFrame(ctx: Context, video: Uri, timeMs: Long, dir: File) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, video)
            val frame = r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw IOException("לא הצלחתי לקחת תמונה מהסרטון")
            save(cover(frame), baseFile(dir))
        } finally { r.release() }
    }

    fun fromImage(ctx: Context, image: Uri, dir: File) {
        val bmp = ctx.contentResolver.openInputStream(image)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("לא הצלחתי לפתוח את התמונה")
        save(cover(bmp), baseFile(dir))
    }

    /** Draws the text on the base image and writes the final thumbnail. */
    fun compose(dir: File, text: String, top: Boolean): File? {
        val base = baseFile(dir)
        if (!base.exists()) return null
        val bmp = BitmapFactory.decodeFile(base.path)?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val t = text.trim()
        if (t.isNotEmpty()) {
            val canvas = Canvas(bmp)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = H * 0.12f
                typeface = Typeface.DEFAULT_BOLD
            }
            val maxW = (W * 0.9f).toInt()
            val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            val y = if (top) H * 0.06f else H - layout.height - H * 0.07f
            // soft dark band behind the text for readability
            val band = Paint().apply { color = Color.argb(110, 0, 0, 0) }
            canvas.drawRect(Rect(0, (y - H * 0.03f).toInt(), W, (y + layout.height + H * 0.03f).toInt()), band)
            canvas.save()
            canvas.translate((W - maxW) / 2f, y)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = paint.textSize * 0.14f
            paint.strokeJoin = Paint.Join.ROUND; paint.color = Color.BLACK
            layout.draw(canvas)
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(255, 226, 0)
            layout.draw(canvas)
            canvas.restore()
        }
        val out = finalFile(dir)
        save(bmp, out)
        return out
    }

    private fun cover(src: Bitmap): Bitmap {
        val scale = maxOf(W.toFloat() / src.width, H.toFloat() / src.height)
        val sw = (W / scale).toInt().coerceAtMost(src.width)
        val sh = (H / scale).toInt().coerceAtMost(src.height)
        val sx = (src.width - sw) / 2
        val sy = (src.height - sh) / 2
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun save(bmp: Bitmap, f: File) {
        var q = 92
        while (true) {
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }
            if (f.length() < 1_900_000 || q <= 50) break
            q -= 10
        }
    }
}
