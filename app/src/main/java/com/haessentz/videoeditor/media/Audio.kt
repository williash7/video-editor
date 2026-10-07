package com.haessentz.videoeditor.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.haessentz.videoeditor.data.Range
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.log10
import kotlin.math.sqrt

/** Decodes the audio track of a video into 16 kHz mono 16-bit PCM. */
object AudioExtractor {
    const val RATE = 16000

    fun extract(ctx: Context, uri: Uri, out: File, onProgress: (Float) -> Unit, cancelled: () -> Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; fmt = f; break
            }
        }
        if (track < 0 || fmt == null) {
            ex.release()
            throw IllegalStateException("לא נמצא ערוץ שמע בסרטון")
        }
        ex.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val tmp = File(out.path + ".tmp")
        val os = BufferedOutputStream(FileOutputStream(tmp), 1 shl 20)
        val outBytes = ByteArray(16384)
        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

        var acc = 0.0
        var cnt = 0
        var phase = 0L
        fun emit(v: Float) {
            acc += v; cnt++
            phase += RATE
            if (phase >= inRate) {
                phase -= inRate
                val s = (acc / cnt).coerceIn(-1.0, 1.0)
                outBuf.putShort((s * 32767).toInt().toShort())
                acc = 0.0; cnt = 0
                if (!outBuf.hasRemaining()) {
                    os.write(outBytes, 0, outBuf.position()); outBuf.clear()
                }
            }
        }

        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        var lastReport = 0L
        try {
            while (!outDone) {
                if (cancelled()) throw CancellationException("cancelled")
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val ch = channels.coerceAtLeast(1)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            for (fr in 0 until frames) {
                                var s = 0f
                                for (c in 0 until ch) s += fb.get()
                                emit(s / ch)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / ch
                            for (fr in 0 until frames) {
                                var s = 0f
                                for (c in 0 until ch) s += sb.get() / 32768f
                                emit(s / ch)
                            }
                        }
                    }
                    if (durUs > 0 && info.presentationTimeUs - lastReport > 2_000_000) {
                        lastReport = info.presentationTimeUs
                        onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (outBuf.position() > 0) os.write(outBytes, 0, outBuf.position())
            os.close()
            tmp.renameTo(out)
        } finally {
            runCatching { os.close() }
            runCatching { codec.stop() }
            codec.release()
            ex.release()
            if (tmp.exists()) tmp.delete()
        }
    }
}

/** Finds silent parts in a 16 kHz PCM file. */
object SilenceDetector {
    private const val WIN = 800 // 50 ms

    fun detect(pcm: File, minSilenceMs: Long, padMs: Long = 200): List<Range> {
        val dbs = ArrayList<Float>()
        val bytes = ByteArray(WIN * 2)
        BufferedInputStream(FileInputStream(pcm), 1 shl 20).use { input ->
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                var read = 0
                while (read < bytes.size) {
                    val r = input.read(bytes, read, bytes.size - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < 2) break
                bb.clear()
                val n = read / 2
                var sum = 0.0
                for (i in 0 until n) {
                    val v = bb.getShort() / 32768.0
                    sum += v * v
                }
                val rms = sqrt(sum / n)
                dbs.add((20 * log10(rms + 1e-9)).toFloat())
                if (read < bytes.size) break
            }
        }
        if (dbs.isEmpty()) return emptyList()
        val sorted = dbs.sorted()
        val floor = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]
        val thr = (floor + 10f).coerceIn(-50f, -28f)
        val winMs = 50L
        val out = ArrayList<Range>()
        var runStart = -1
        for (i in 0..dbs.size) {
            val silent = i < dbs.size && dbs[i] < thr
            if (silent && runStart < 0) runStart = i
            if (!silent && runStart >= 0) {
                val s = runStart * winMs
                val e = i * winMs
                if (e - s >= minSilenceMs) {
                    val rs = s + padMs
                    val re = e - padMs
                    if (re > rs) out.add(Range(rs, re))
                }
                runStart = -1
            }
        }
        return out
    }
}
