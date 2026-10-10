package com.haessentz.videoeditor.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** [langs]: languages this model is good for ("he", "ru"). */
data class ModelInfo(val id: String, val title: String, val desc: String, val url: String, val sizeMb: Int, val langs: Set<String> = setOf("he"))

/** Languages the app can transcribe. */
object Langs {
    val all = listOf("he", "ru")
    fun name(code: String) = when (code) { "ru" -> "רוסית"; else -> "עברית" }
    fun flag(code: String) = when (code) { "ru" -> "🇷🇺"; else -> "🇮🇱" }
}

object ModelManager {
    private const val REL = "https://github.com/williash7/video-editor/releases/download/models-v1/"

    val models = listOf(
        ModelInfo(
            "ivrit-q5", "עברית – מהיר (ivrit.ai מכווץ) ⭐",
            "אותו מודל עברית, מכווץ. בערך פי 2 מהיר יותר, וההבדל בדיוק קטן מאוד. מומלץ.",
            REL + "ivrit-turbo-q5_0.bin", 547
        ),
        ModelInfo(
            "ivrit-q8", "עברית – מאוזן (ivrit.ai)",
            "כמעט זהה בדיוק למודל המלא, ומהיר ממנו.",
            REL + "ivrit-turbo-q8_0.bin", 874
        ),
        ModelInfo(
            "ivrit-turbo", "עברית – מלא (ivrit.ai)",
            "המודל המקורי. הכי מדויק, אבל הכי איטי (בערך פי 3 מאורך הסרטון).",
            "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/main/ggml-model.bin", 1620
        ),
        ModelInfo(
            "turbo-q5", "רב־לשוני – מהיר ⭐ לרוסית",
            "Whisper turbo מכווץ. מצוין לרוסית. לעברית עדיף מודל ivrit.ai.",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", 574, setOf("he", "ru")
        ),
        ModelInfo(
            "turbo-q8", "רב־לשוני – מאוזן",
            "Whisper turbo בדיוק גבוה יותר, קצת יותר איטי מהמהיר.",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin", 874, setOf("he", "ru")
        ),
        ModelInfo(
            "small-q5", "קטן – לבדיקות בלבד",
            "מהיר מאוד אבל לא מדויק. טוב רק כדי לבדוק שהכול עובד.",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190, setOf("he", "ru")
        ),
    )
    const val CUSTOM_ID = "custom"

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, id: String) = File(dir(ctx), "$id.bin")
    fun isReady(ctx: Context, id: String) = file(ctx, id).let { it.exists() && it.length() > 1_000_000 }
    fun title(id: String) = models.firstOrNull { it.id == id }?.title ?: if (id == CUSTOM_ID) "מודל שיובא מהטלפון" else id

    suspend fun download(ctx: Context, info: ModelInfo, onProgress: (Float, String) -> Unit) {
        val target = file(ctx, info.id)
        val part = File(target.path + ".part")
        var attempt = 0
        while (true) {
            try {
                downloadOnce(info.url, part, onProgress)
                break
            } catch (e: java.io.IOException) {
                attempt++
                if (attempt >= 5) throw e
                kotlinx.coroutines.delay(3000L * attempt)
            }
        }
        if (!part.renameTo(target)) throw java.io.IOException("שמירת הקובץ נכשלה")
    }

    private suspend fun downloadOnce(url: String, part: File, onProgress: (Float, String) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        val code = conn.responseCode
        val append = code == 206
        if (code != 200 && code != 206) throw java.io.IOException("שגיאת הורדה $code")
        val total = (if (append) have else 0L) + conn.contentLengthLong.coerceAtLeast(0)
        var done = if (append) have else 0L
        conn.inputStream.use { input ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                var last = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 2_000_000) {
                        last = done
                        onProgress(if (total > 0) done.toFloat() / total else -1f, "${done / 1_000_000} / ${total / 1_000_000} MB")
                    }
                }
            }
        }
    }

    fun import(ctx: Context, uri: Uri, onProgress: (Float) -> Unit) {
        val target = file(ctx, CUSTOM_ID)
        val tmp = File(target.path + ".tmp")
        val size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (size > 0) onProgress(done.toFloat() / size)
                }
            }
        }
        tmp.renameTo(target)
    }
}
