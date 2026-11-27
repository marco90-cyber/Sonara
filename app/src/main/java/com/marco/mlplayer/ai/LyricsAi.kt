package com.marco.mlplayer.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import okhttp3.Request
import com.marco.mlplayer.audio.LyricsAiService
import com.marco.mlplayer.data.Track
import com.marco.mlplayer.lyrics.LLine
import com.marco.mlplayer.lyrics.LWord
import com.marco.mlplayer.lyrics.Lyrics
import com.marco.mlplayer.ui.AppEnv
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.Net
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Genera il testo di un brano che non ce l'ha: ascolta l'audio con Whisper (offline, sul telefono)
 * e ricava le righe con i tempi. Il lavoro continua anche se chiudi la finestra, finché l'app resta aperta.
 */
@UnstableApi
object LyricsAi {
    const val SOURCE = "Generato con AI"
    private const val TAG = "TestoAI"

    enum class Model(val label: String, val file: String, val mb: Int, val note: String) {
        Base("Base", "ggml-base-q5_1.bin", 60, "più veloce"),
        Small("Small", "ggml-small-q5_1.bin", 190, "più preciso col canto");

        val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
    }

    sealed class State {
        data object Idle : State()
        data class Working(val trackId: String, val stage: String, val percent: Int) : State()
        data class Failed(val trackId: String, val message: String) : State()
        data class Done(val trackId: String) : State()
    }

    private var current by mutableStateOf<State>(State.Idle)
    var state: State
        get() = current
        private set(v) { current = v; onChange?.invoke() }
    /** Chiamato a ogni cambio di stato: la notifica del servizio si aggiorna da qui. */
    @Volatile var onChange: (() -> Unit)? = null
    /** Titolo del brano in lavorazione, per la notifica. */
    @Volatile var title: String = ""
        private set
    private var job: Job? = null
    @Volatile private var stop = false

    val available: Boolean get() = WhisperJni.isAvailable
    val busy: Boolean get() = state is State.Working

    fun modelFile(ctx: Context, m: Model) = File(File(ctx.filesDir, "whisper").apply { mkdirs() }, m.file)
    fun hasModel(ctx: Context, m: Model) = modelFile(ctx, m).let { it.exists() && it.length() > m.mb * 400_000L }
    fun deleteModel(ctx: Context, m: Model) { modelFile(ctx, m).delete() }

    fun cancel() {
        stop = true
        WhisperJni.cancel()
        job?.cancel()
        state = State.Idle
    }

    fun dismiss() { if (state !is State.Working) state = State.Idle }

    fun start(env: AppEnv, t: Track, model: Model) {
        if (busy) return
        val app = env.app
        stop = false
        title = t.title
        state = State.Working(t.id, "Preparo", 0)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, LyricsAiService::class.java)) }
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonara:testo-ai")
        job = app.appScope.launch(Dispatchers.Default) {
            runCatching { wl.acquire(30 * 60 * 1000L) }
            var tmp: File? = null
            try {
                if (!WhisperJni.isAvailable) error("Questa funzione richiede un telefono a 64 bit (arm64)")
                val mf = modelFile(app, model)
                if (!hasModel(app, model)) {
                    download(model.url, mf, { state = State.Working(t.id, "Scarico il modello ${model.label}", it) })
                }
                // audio del brano: scaricato, file locale o flusso di YouTube
                state = State.Working(t.id, "Leggo l'audio", 0)
                val pcm = if (t.uri.startsWith("yt:")) {
                    val dl = YtDownloads.fileUri(t.uri.removePrefix("yt:"))
                    if (dl != null) PcmDecoder.decode(app, dl, null, { state = State.Working(t.id, "Leggo l'audio", it) }, { stop })
                    else {
                        tmp = File(app.cacheDir, "testo_ai_input.bin")
                        fetchStream(t.uri, tmp) { state = State.Working(t.id, "Scarico l'audio", it) }
                        PcmDecoder.decode(app, null, tmp, { state = State.Working(t.id, "Leggo l'audio", it) }, { stop })
                    }
                } else PcmDecoder.decode(app, Uri.parse(t.uri), null, { state = State.Working(t.id, "Leggo l'audio", it) }, { stop })
                if (stop) throw CancellationException()
                if (pcm.size < 16_000) error("Audio troppo corto")

                state = State.Working(t.id, "Ascolto e scrivo il testo", 0)
                val handle = WhisperJni.init(mf.path)
                if (handle == 0L) { mf.delete(); error("Modello non caricato: riprova, lo riscarico") }
                val raw = try {
                    WhisperJni.transcribe(handle, pcm, "auto", 0) { p -> state = State.Working(t.id, "Ascolto e scrivo il testo", p) }
                } finally { WhisperJni.free(handle) }
                if (stop) throw CancellationException()

                val lines = clean(raw)
                if (lines.isEmpty()) error("Non ho trovato parole cantate")
                env.lyrics.saveGenerated(t, Lyrics(lines, SOURCE))
                state = State.Done(t.id)
            } catch (e: CancellationException) {
                state = State.Idle
            } catch (e: Throwable) {
                AppLog.e(TAG, "Generazione del testo non riuscita", e)
                state = State.Failed(t.id, if (e is java.io.IOException) "Connessione interrotta, riprova" else e.message ?: "Non riuscito")
            } finally {
                tmp?.delete()
                runCatching { if (wl.isHeld) wl.release() }
            }
        }
    }

    // ---------------------------------------------------------------- risultato

    private val JUNK = Regex("""^[\[(*♪♫♬\s].*[])*♪♫♬\s]$|^[♪♫♬\s.]*$""")

    /** Righe "inizio<TAB>fine<TAB>testo<TAB>parole" → righe del testo con i tempi delle parole, senza le tipiche invenzioni di Whisper. */
    internal fun clean(raw: String): List<LLine> {
        val out = ArrayList<LLine>()
        var repeat = 0
        for (row in raw.lineSequence()) {
            val p = row.split('\t', limit = 4)
            if (p.size < 3) continue
            val start = p[0].toLongOrNull() ?: continue
            val end = (p[1].toLongOrNull() ?: start).coerceAtLeast(start)
            val text = p[2].trim()
            if (text.isBlank() || JUNK.matches(text)) continue
            val same = out.isNotEmpty() && out.last().text.equals(text, ignoreCase = true)
            repeat = if (same) repeat + 1 else 0
            if (repeat >= 4) continue   // ripetizione infinita: è un'allucinazione, non un ritornello
            out.add(LLine(start, end, text, words(p.getOrNull(3).orEmpty(), start, end)))
        }
        return out
    }

    /** "inizio:fine:parola" separati da 0x1F → parole con tempi coerenti (in ordine, dentro la riga, senza buchi piccoli). */
    private fun words(field: String, lineStart: Long, lineEnd: Long): List<LWord> {
        if (field.isBlank()) return emptyList()
        val raw = field.split('\u001f').filter { it.isNotBlank() }.mapNotNull {
            val q = it.split(':', limit = 3)
            if (q.size < 3 || q[2].isBlank()) null else Triple(q[0].toLongOrNull() ?: return@mapNotNull null, q[1].toLongOrNull() ?: return@mapNotNull null, q[2])
        }
        if (raw.isEmpty()) return emptyList()
        val maxEnd = maxOf(lineEnd, raw.last().second)
        val starts = LongArray(raw.size)
        var prev = lineStart
        for (i in raw.indices) {
            starts[i] = raw[i].first.coerceIn(prev, maxEnd)
            prev = starts[i]
        }
        return raw.indices.map { i ->
            val nextStart = if (i < raw.lastIndex) starts[i + 1] else maxEnd
            var e = raw[i].second.coerceIn(starts[i], maxEnd)
            // se tra una parola e la prossima c'è una pausa breve, la parola resta accesa fino alla successiva
            if (i < raw.lastIndex && nextStart - e < 400) e = nextStart
            if (e <= starts[i]) e = (starts[i] + 120).coerceAtMost(maxOf(nextStart, starts[i] + 1))
            LWord(starts[i], e, raw[i].third + if (i < raw.lastIndex) " " else "")
        }
    }

    // ---------------------------------------------------------------- rete

    /** Scarica con ripresa: se la connessione cade, riparte da dove era arrivato (fino a 6 tentativi). */
    private fun download(url: String, dest: File, onPct: (Int) -> Unit) {
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            try {
                val have = if (part.exists()) part.length() else 0L
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.instanceFollowRedirects = true
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code == 416) { part.delete(); continue }
                if (code !in 200..299) error("Download del modello non riuscito ($code)")
                val resumed = code == 206
                val base = if (resumed) have else 0L
                val total = c.contentLengthLong.let { if (it > 0) it + base else -1L }
                var done = base
                var last = -1
                c.inputStream.use { i ->
                    java.io.FileOutputStream(part, resumed).use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (stop) throw CancellationException()
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) { last = pct; onPct(pct) }
                            }
                        }
                    }
                }
                break
            } catch (e: java.io.IOException) {
                if (++attempt >= 6) throw e
                AppLog.w(TAG, "Download interrotto, riprovo ($attempt)", e)
                Thread.sleep(2_000L * attempt)
            }
        }
        if (!part.renameTo(dest)) error("Modello non salvato")
    }

    /**
     * Scarica l'audio del flusso a blocchi da 4 MB con Range, come fanno i download dell'app e il sito di YouTube:
     * una richiesta unica e lunga viene interrotta dal server. Ogni blocco si riprova (4 tentativi) dallo stesso punto.
     */
    private fun fetchStream(videoUri: String, dest: File, onPct: (Int) -> Unit) {
        val res = Yt.resolve(videoUri.removePrefix("yt:"), preferMp4 = true, quality = 2)   // il più leggero: Whisper lavora a 16 kHz mono
        var pos = 0L
        var total = -1L
        val buf = ByteArray(64 * 1024)
        dest.outputStream().use { out ->
            while (total < 0 || pos < total) {
                var attempt = 0
                while (true) {
                    if (stop) throw CancellationException()
                    try {
                        val req = Request.Builder().url(res.url)
                            .header("Range", "bytes=$pos-${pos + 4L * 1024 * 1024 - 1}")
                            .header("User-Agent", Net.BROWSER_UA)
                            .build()
                        Net.client.newCall(req).execute().use { r ->
                            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                            if (total < 0) {
                                total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                    ?: r.body?.contentLength()?.takeIf { it > 0 } ?: throw java.io.IOException("Dimensione sconosciuta")
                            }
                            val input = (r.body ?: throw java.io.IOException("Risposta vuota")).byteStream()
                            var got = 0L
                            while (true) {
                                if (stop) throw CancellationException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                pos += n
                                onPct((pos * 100 / total).toInt().coerceIn(0, 99))
                            }
                            if (got <= 0) throw java.io.IOException("Flusso interrotto")
                        }
                        break
                    } catch (e: java.io.IOException) {
                        if (++attempt >= 4) throw e
                        AppLog.w(TAG, "Blocco audio interrotto, riprovo ($attempt)", e)
                        Thread.sleep(1_500L * attempt)
                    }
                }
            }
        }
    }
}
