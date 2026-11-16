package com.marco.mlplayer.yt

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.marco.mlplayer.MLPlayerApp
import com.marco.mlplayer.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Brani scaricati nella cartella scelta dall'utente. L'elenco (con i dati dei brani) sta in
 * filesDir/yt_downloads.json; la riproduzione usa il file al posto del flusso di rete.
 */
object YtDownloads {
    private const val TAG = "Download"
    private const val CHUNK = 4L * 1024 * 1024

    class Entry(val item: Yt.Item, val uri: String)

    /** Più recenti in alto. */
    val entries = mutableStateListOf<Entry>()

    /** videoId -> avanzamento 0..100 (-1 = in coda). */
    val progress = mutableStateMapOf<String, Int>()
    var failed by mutableStateOf<String?>(null)

    private lateinit var app: MLPlayerApp
    private val queue = ArrayDeque<Yt.Item>()
    private var working = false
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val indexFile get() = File(app.filesDir, "yt_downloads.json")

    fun init(app: MLPlayerApp) {
        this.app = app
        runCatching {
            if (!indexFile.exists()) return
            val a = JSONArray(indexFile.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val item = Yt.Item(o.getString("id"), o.optString("title"), o.optString("artist"), o.optLong("dur"), o.optString("thumb"))
                entries.add(Entry(item, o.getString("uri")))
            }
        }.onFailure { AppLog.w(TAG, "Elenco download non leggibile", it) }
    }

    private fun save() {
        val a = JSONArray()
        entries.forEach {
            a.put(JSONObject().put("id", it.item.videoId).put("title", it.item.title).put("artist", it.item.artist)
                .put("dur", it.item.durationSec).put("thumb", it.item.thumb).put("uri", it.uri))
        }
        runCatching { indexFile.writeText(a.toString()) }
    }

    fun isDownloaded(videoId: String) = entries.any { it.item.videoId == videoId }
    fun isBusy(videoId: String) = progress.containsKey(videoId)
    fun hasFolder() = app.settings.downloadDir.isNotBlank()

    /** Indirizzo del file scaricato, se c'è ancora; chiamato dal lettore all'apertura del brano. */
    fun fileUri(videoId: String): Uri? {
        val e = entries.firstOrNull { it.item.videoId == videoId } ?: return null
        val uri = Uri.parse(e.uri)
        val ok = runCatching { DocumentFile.fromSingleUri(app, uri)?.exists() == true }.getOrDefault(false)
        if (!ok) {
            AppLog.w(TAG, "File mancante, torno al flusso di rete: ${e.item.title}")
            app.appScope.launch { entries.remove(e); save() }
            return null
        }
        return uri
    }

    fun download(items: List<Yt.Item>) {
        if (!hasFolder()) {
            app.env.message = "Scegli prima la cartella dei download nelle Impostazioni"
            return
        }
        var added = 0
        for (it in items) {
            if (isDownloaded(it.videoId) || isBusy(it.videoId)) continue
            progress[it.videoId] = -1
            queue.addLast(it)
            added++
        }
        if (added > 0) app.env.message = if (added == 1) "Download avviato" else "$added brani in coda per il download"
        pump()
    }

    fun remove(videoId: String) {
        val e = entries.firstOrNull { it.item.videoId == videoId } ?: return
        entries.remove(e)
        save()
        app.appScope.launch(Dispatchers.IO) {
            runCatching { DocumentFile.fromSingleUri(app, Uri.parse(e.uri))?.delete() }
        }
    }

    private fun pump() {
        if (working) return
        working = true
        app.appScope.launch {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val r = withContext(Dispatchers.IO) { runCatching { fetch(item) } }
                progress.remove(item.videoId)
                r.onSuccess { uri ->
                    entries.add(0, Entry(item, uri)); save()
                }.onFailure {
                    AppLog.e(TAG, "Download non riuscito: ${item.title}", it)
                    app.env.message = "Download non riuscito: ${item.title}"
                }
            }
            working = false
            if (entries.isNotEmpty()) app.env.message = "Download completati"
        }
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120)

    /** Scarica a blocchi con Range (come fa il sito, evita i rallentamenti) e restituisce l'uri del file. */
    private fun fetch(item: Yt.Item): String {
        val dirUri = app.settings.downloadDir
        val dir = DocumentFile.fromTreeUri(app, Uri.parse(dirUri))?.takeIf { it.canWrite() }
            ?: throw IOException("Cartella dei download non accessibile")
        val res = Yt.resolve(item.videoId)
        val webm = res.mime.contains("webm")
        val ext = if (webm) "webm" else "m4a"
        val mime = if (webm) "audio/webm" else "audio/mp4"
        val base = safeName("${item.artist} - ${item.title}").ifBlank { item.videoId }
        val doc = dir.createFile(mime, "$base.$ext") ?: throw IOException("Impossibile creare il file")
        try {
            var pos = 0L
            var total = -1L
            app.contentResolver.openOutputStream(doc.uri, "w")!!.use { out ->
                while (total < 0 || pos < total) {
                    val end = pos + CHUNK - 1
                    val req = Request.Builder().url(res.url)
                        .header("Range", "bytes=$pos-$end")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
                        .build()
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        if (total < 0) {
                            total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                ?: r.body?.contentLength()?.takeIf { it > 0 } ?: throw IOException("Dimensione sconosciuta")
                        }
                        val body = r.body ?: throw IOException("Risposta vuota")
                        val n = body.byteStream().copyTo(out)
                        if (n <= 0) throw IOException("Flusso interrotto")
                        pos += n
                        progress[item.videoId] = (pos * 100 / total).toInt().coerceIn(0, 99)
                    }
                }
            }
            AppLog.i(TAG, "Scaricato: ${item.title} (${pos / 1024} KB)")
            return doc.uri.toString()
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
    }
}
