package com.marco.mlplayer.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File

/** Decodifica un file audio in PCM float mono a 16 kHz, il formato che serve a Whisper. */
object PcmDecoder {
    private const val TARGET = 16_000
    private const val MAX_SECONDS = 15 * 60

    class TooLong : Exception("Il brano supera i 15 minuti")

    private class FloatBuf {
        var a = FloatArray(1 shl 20)
        var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray() = a.copyOf(n)
    }

    fun decode(ctx: Context, uri: Uri?, file: File?, onProgress: (Int) -> Unit, cancelled: () -> Boolean): FloatArray {
        val ex = MediaExtractor()
        try {
            if (file != null) ex.setDataSource(file.path) else ex.setDataSource(ctx, uri!!, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) error("Nessuna traccia audio")
            ex.selectTrack(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs > MAX_SECONDS * 1_000_000L) throw TooLong()

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val out = FloatBuf()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var step = rate.toDouble() / TARGET
            var acc = 0.0
            var cnt = 0
            var next = step          // confine (in campioni di ingresso) del prossimo campione d'uscita
            var pos = 0.0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            try {
                while (!outDone) {
                    if (cancelled()) throw InterruptedException()
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = codec.outputFormat
                            rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            step = rate.toDouble() / TARGET
                            next = pos + step
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            val sb = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            val frames = info.size / 2 / ch
                            for (f in 0 until frames) {
                                var s = 0f
                                for (c in 0 until ch) s += sb.get(f * ch + c)
                                acc += s / ch / 32768f
                                cnt++
                                pos += 1.0
                                if (pos >= next) {
                                    out.add((acc / cnt).toFloat())
                                    acc = 0.0; cnt = 0; next += step
                                }
                            }
                            if (durUs > 0) onProgress((info.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 100))
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                            if (out.n > MAX_SECONDS * TARGET) throw TooLong()
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return out.toArray()
        } finally {
            ex.release()
        }
    }
}
