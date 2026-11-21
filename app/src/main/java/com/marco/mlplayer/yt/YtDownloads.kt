package com.marco.mlplayer.yt

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.marco.mlplayer.MLPlayerApp
import com.marco.mlplayer.audio.DownloadService
import com.marco.mlplayer.data.Track
import com.marco.mlplayer.tags.TagFiles
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.AsyncFileWriter
import com.marco.mlplayer.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brani scaricati nella cartella scelta dall'utente, raggruppati per playlist, album o artista
 * (una sottocartella per gruppo). L'elenco sta in filesDir/yt_downloads.json; la riproduzione
 * usa il file al posto del flusso di rete.
 */
object YtDownloads {
    private const val TAG = "Download"
    const val SINGLES = "singles"
    private val UNSAFE_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val SPACES = Regex("\\s+")

    /** Un gruppo di download: playlist, album, artista oppure i brani singoli. */
    class Group(val id: String, val kind: String, val title: String, val thumb: String)
    class Entry(val item: Yt.Item, val uri: String, val group: String, val path: String = "")
    private class Fetched(val uri: String, val path: String)
    private class Job(val item: Yt.Item, val group: String)

    /** Più recenti in alto. */
    val entries = mutableStateListOf<Entry>()
    val groups = mutableStateListOf<Group>()

    /** videoId -> avanzamento 0..100 (-1 = in coda). */
    val progress = mutableStateMapOf<String, Int>()

    /** videoId -> gruppo, per i download in coda o in corso. */
    private val jobGroup = mutableStateMapOf<String, String>()

    private lateinit var app: MLPlayerApp
    private val queue = ArrayDeque<Job>()
    private var current: Job? = null
    @Volatile private var cancelId: String? = null
    private var working = false
    private var batchTotal = 0
    private var batchDone = 0
    private val ui = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean(false)

    /** Chiamato (sul thread principale) quando cambia lo stato: lo usa la notifica. */
    var onChange: (() -> Unit)? = null

    class Snapshot(val total: Int, val done: Int, val title: String, val pct: Int)
    val active get() = working
    fun snapshot() = Snapshot(batchTotal, batchDone, current?.item?.title.orEmpty(), current?.let { progress[it.item.videoId] }?.coerceAtLeast(0) ?: 0)

    private val http = Net.withReadTimeout(readTimeoutSec = 30)

    private val indexFile get() = File(app.filesDir, "yt_downloads.json")
    private val writer by lazy { AsyncFileWriter(indexFile, "yt-downloads") }

    private fun parseGroup(o: JSONObject) = Group(o.getString("id"), o.optString("kind"), o.optString("title"), o.optString("thumb"))

    /** Una voce dell'indice; [uri] se dato sostituisce quello scritto nel file (ripristino da copia di riserva). */
    private fun parseEntry(o: JSONObject, uri: String = o.optString("uri")): Entry? {
        val item = Yt.Item.fromJson(o) ?: return null
        return if (uri.isBlank()) null else Entry(item, uri, o.optString("group", SINGLES), o.optString("path"))
    }

    fun init(app: MLPlayerApp) {
        this.app = app
        runCatching {
            if (!indexFile.exists()) return
            val text = indexFile.readText().trim()
            // formato vecchio: solo l'elenco dei brani; nuovo: oggetto con "entries" e "groups"
            val root = if (text.startsWith("[")) null else JSONObject(text)
            val arr = root?.optJSONArray("entries") ?: if (root == null) JSONArray(text) else JSONArray()
            root?.optJSONArray("groups")?.let { g -> for (i in 0 until g.length()) groups.add(parseGroup(g.getJSONObject(i))) }
            for (i in 0 until arr.length()) parseEntry(arr.getJSONObject(i))?.let { entries.add(it) }
        }.onFailure { AppLog.w(TAG, "Elenco download non leggibile", it) }
    }

    fun exportJson(): String {
        val a = JSONArray()
        entries.forEach { a.put(it.item.toJson().put("uri", it.uri).put("group", it.group).put("path", it.path)) }
        val g = JSONArray()
        groups.forEach { g.put(JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title).put("thumb", it.thumb)) }
        return JSONObject().put("entries", a).put("groups", g).toString()
    }

    /** Scrive l'indice fuori dal thread principale (in coda, in modo atomico) e aggiorna la copia di riserva. */
    private fun save() {
        writer.write(exportJson()) { AppLog.w(TAG, "Elenco download non salvato", it) }
        YtBackup.schedule()
    }

    /** Ritrova i file elencati nella copia di riserva dentro [root] (dopo una reinstallazione). Restituisce quanti. */
    suspend fun restoreFrom(root: DocumentFile, text: String): Int {
        val o = JSONObject(text)
        val dirs = HashMap<String, Map<String, DocumentFile>>()
        fun filesIn(dir: String): Map<String, DocumentFile> = dirs.getOrPut(dir) {
            val d = if (dir.isEmpty()) root else root.findFile(dir)?.takeIf { it.isDirectory }
            d?.listFiles()?.mapNotNull { f -> f.name?.let { it to f } }?.toMap() ?: emptyMap()
        }
        val gs = ArrayList<Group>()
        o.optJSONArray("groups")?.let { g -> for (i in 0 until g.length()) gs.add(parseGroup(g.getJSONObject(i))) }
        val found = ArrayList<Entry>()
        val arr = o.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            val path = x.optString("path")
            if (path.isBlank()) continue
            val dir = path.substringBeforeLast('/', "")
            val doc = filesIn(dir)[path.substringAfterLast('/')] ?: continue
            parseEntry(x, doc.uri.toString())?.let { found.add(it) }
        }
        return withContext(Dispatchers.Main) {
            gs.forEach { g -> if (groups.none { it.id == g.id }) groups.add(g) }
            var n = 0
            found.forEach { e ->
                if (entries.none { it.item.videoId == e.item.videoId && it.group == e.group }) { entries.add(e); n++ }
            }
            // gruppo mancante nell'indice: i brani vanno tra i singoli
            if (entries.any { e -> groups.none { it.id == e.group } } && groups.none { it.id == SINGLES }) groups.add(Group(SINGLES, "Songs", "Brani singoli", ""))
            if (n > 0) save()
            n
        }
    }

    fun isDownloaded(videoId: String) = entries.any { it.item.videoId == videoId }
    fun isBusy(videoId: String) = progress.containsKey(videoId)
    fun groupBusy(groupId: String) = jobGroup.containsValue(groupId)
    fun hasFolder() = app.settings.downloadDir.isNotBlank()
    fun itemsOf(groupId: String) = entries.filter { it.group == groupId }.map { it.item }
    fun groupOf(id: String) = groups.firstOrNull { it.id == id }

    /** Indirizzo del file scaricato, se c'è ancora; chiamato dal lettore all'apertura del brano. */
    fun fileUri(videoId: String): Uri? {
        val e = entries.firstOrNull { it.item.videoId == videoId } ?: return null
        val uri = Uri.parse(e.uri)
        val ok = runCatching { DocumentFile.fromSingleUri(app, uri)?.exists() == true }.getOrDefault(false)
        if (!ok) {
            AppLog.w(TAG, "File mancante, torno al flusso di rete: ${e.item.title}")
            app.appScope.launch { entries.removeAll { it.uri == e.uri }; save() }
            return null
        }
        return uri
    }

    private fun changed() {
        if (!notifyPending.compareAndSet(false, true)) return
        ui.postDelayed({ notifyPending.set(false); onChange?.invoke() }, 400)
    }

    private var pendingItems: List<Yt.Item>? = null
    private var pendingGroup: Group? = null

    /** Dopo la scelta della cartella: avvia il download rimasto in attesa (o lo scarta se la scelta è stata annullata). */
    fun resumePending(folderChosen: Boolean) {
        val items = pendingItems ?: return
        val g = pendingGroup
        pendingItems = null; pendingGroup = null
        if (folderChosen && hasFolder()) download(items, g)
    }

    /** Scarica [items] nel gruppo [group] (playlist/album/artista); senza gruppo vanno tra i brani singoli. */
    fun download(items: List<Yt.Item>, group: Group? = null) {
        if (!hasFolder()) {
            // nessuna cartella scelta: la chiedo ora e il download parte appena la scelgo
            pendingItems = items
            pendingGroup = group
            app.env.message = "Scegli la cartella dove salvare i download"
            app.env.actions?.pickDownloadFolder()
            return
        }
        val g = group ?: Group(SINGLES, "Songs", "Brani singoli", "")
        if (groups.none { it.id == g.id }) groups.add(0, g)
        var added = 0
        for (item in items) {
            if (entries.any { e -> e.item.videoId == item.videoId && e.group == g.id } || isBusy(item.videoId)) continue
            val other = entries.firstOrNull { e -> e.item.videoId == item.videoId }
            if (other != null) {              // già scaricato altrove: lo ricollego, senza riscaricarlo
                entries.add(0, Entry(item, other.uri, g.id, other.path))
                continue
            }
            progress[item.videoId] = -1
            jobGroup[item.videoId] = g.id
            queue.addLast(Job(item, g.id))
            batchTotal++
            added++
        }
        save()
        if (added > 0) app.env.message = if (added == 1) "Download avviato" else "$added brani in coda per il download"
        pump()
    }

    /** Ferma un download (in coda o in corso). */
    fun cancel(videoId: String) {
        queue.removeAll { it.item.videoId == videoId }.also { if (it) batchTotal-- }
        if (current?.item?.videoId == videoId) cancelId = videoId
        else { progress.remove(videoId); jobGroup.remove(videoId) }
        changed()
    }

    fun cancelGroup(groupId: String) {
        val mine = queue.filter { it.group == groupId }
        mine.forEach { progress.remove(it.item.videoId); jobGroup.remove(it.item.videoId) }
        queue.removeAll(mine.toSet()); batchTotal -= mine.size
        current?.takeIf { it.group == groupId }?.let { cancelId = it.item.videoId }
        changed()
    }

    fun cancelAll() {
        queue.forEach { progress.remove(it.item.videoId); jobGroup.remove(it.item.videoId) }
        batchTotal -= queue.size
        queue.clear()
        current?.let { cancelId = it.item.videoId }
        changed()
    }

    /** Dimentica l'elenco dei download (da chiamare sul thread principale, a coda ferma): i file audio restano nella cartella. */
    fun forgetAll() {
        entries.clear()
        groups.clear()
        save()
    }

    /** Toglie un brano da un gruppo; il file si cancella solo se nessun altro gruppo lo usa. */
    fun remove(videoId: String, groupId: String? = null) {
        val mine = entries.filter { it.item.videoId == videoId && (groupId == null || it.group == groupId) }
        mine.forEach { dropEntry(it) }
        save()
    }

    private fun dropEntry(e: Entry) {
        entries.remove(e)
        if (entries.none { it.uri == e.uri }) {
            app.appScope.launch(Dispatchers.IO) {
                runCatching { DocumentFile.fromSingleUri(app, Uri.parse(e.uri))?.delete() }
            }
        }
    }

    /** Elimina un'intera playlist/album/artista scaricato: file e cartella. */
    fun deleteGroup(groupId: String) {
        cancelGroup(groupId)
        val g = groupOf(groupId)
        entries.filter { it.group == groupId }.forEach { dropEntry(it) }
        groups.removeAll { it.id == groupId }
        save()
        if (g != null && g.id != SINGLES) {
            val root = app.settings.downloadDir
            app.appScope.launch(Dispatchers.IO) {
                delay(800)
                runCatching {
                    val dir = DocumentFile.fromTreeUri(app, Uri.parse(root))?.findFile(safeName(g.title))
                    if (dir != null && dir.isDirectory && dir.listFiles().isEmpty()) dir.delete()
                }
            }
        }
    }

    private fun pump() {
        if (working) return
        working = true
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        app.appScope.launch {
            var failed = 0
            while (queue.isNotEmpty()) {
                val job = queue.removeFirst()
                current = job
                cancelId = null
                changed()
                val r = withContext(Dispatchers.IO) { runCatching { fetch(job) } }
                current = null
                progress.remove(job.item.videoId); jobGroup.remove(job.item.videoId)
                batchDone++
                r.onSuccess { f -> entries.add(0, Entry(job.item, f.uri, job.group, f.path)); save() }
                    .onFailure {
                        if (it is Cancelled) AppLog.i(TAG, "Download fermato: ${job.item.title}")
                        else { failed++; AppLog.e(TAG, "Download non riuscito: ${job.item.title}", it) }
                    }
            }
            working = false
            batchTotal = 0; batchDone = 0
            changed()
            ui.postDelayed({ onChange?.invoke() }, 500)
            if (failed > 0) app.env.message = "Download non riusciti: $failed"
            else if (entries.isNotEmpty()) app.env.message = "Download completati"
        }
    }

    /** Titolo, artista, album e copertina dentro il file, così li vedono anche gli altri lettori. */
    private fun tagFile(uri: String, item: Yt.Item, g: Group?) {
        val cover = runCatching {
            http.newCall(Request.Builder().url(item.cover).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()?.takeIf { it.size > 1000 }
        val album = if (g?.kind == "Albums") g.title else ""
        runCatching { TagFiles.tagDownloaded(app, uri, item.title, item.artist, album, cover) }
            .onFailure { AppLog.w(TAG, "Tag non scritti: ${item.title}", it) }
    }

    /** Il brano come file scaricato (indirizzo del file, formato m4a), se i tag ci si possono scrivere. */
    fun fileTrack(t: Track): Track? {
        if (!t.uri.startsWith("yt:")) return null
        val e = entries.firstOrNull { it.item.videoId == t.uri.removePrefix("yt:") } ?: return null
        val name = e.path.ifBlank { runCatching { DocumentFile.fromSingleUri(app, Uri.parse(e.uri))?.name }.getOrNull().orEmpty() }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (!TagFiles.canEdit(ext)) return null
        return t.copy(uri = e.uri, ext = ext)
    }

    /** Dopo una modifica dei tag: titolo e artista dell'elenco seguono quelli scritti nel file. */
    suspend fun refreshMeta(uris: Collection<String>) {
        val updates = withContext(Dispatchers.IO) {
            entries.filter { it.uri in uris }.mapNotNull { e ->
                val d = TagFiles.read(app, Yt.toTrack(e.item).copy(uri = e.uri, ext = "m4a")) ?: return@mapNotNull null
                if (d.title.isBlank() && d.artist.isBlank()) null
                else e to e.item.copy(title = d.title.ifBlank { e.item.title }, artist = d.artist.ifBlank { e.item.artist })
            }
        }
        if (updates.isEmpty()) return
        for ((e, item) in updates) {
            val i = entries.indexOf(e)
            if (i >= 0) entries[i] = Entry(item, e.uri, e.group, e.path)
        }
        save()
    }

    private class Cancelled : IOException("Annullato")

    fun safeName(s: String) = s.replace(UNSAFE_CHARS, " ").replace(SPACES, " ").trim().take(120)

    /** Scarica a blocchi con Range (come fa il sito, evita i rallentamenti) e restituisce l'uri del file. */
    private fun fetch(job: Job): Fetched {
        val item = job.item
        val root = DocumentFile.fromTreeUri(app, Uri.parse(app.settings.downloadDir))?.takeIf { it.canWrite() }
            ?: throw IOException("Cartella dei download non accessibile")
        val g = groupOf(job.group)
        val dir = if (g == null || g.id == SINGLES) root else {
            val name = safeName(g.title).ifBlank { g.id }
            root.findFile(name)?.takeIf { it.isDirectory } ?: root.createDirectory(name) ?: throw IOException("Impossibile creare la cartella")
        }
        val res = Yt.resolve(item.videoId, preferMp4 = true)
        val webm = res.mime.contains("webm")
        val ext = if (webm) "webm" else "m4a"
        val mime = if (webm) "audio/webm" else "audio/mp4"
        val base = safeName("${item.artist} - ${item.title}").ifBlank { item.videoId }
        val doc = dir.createFile(mime, "$base.$ext") ?: throw IOException("Impossibile creare il file")
        try {
            var pos = 0L
            var total = -1L
            val buf = ByteArray(64 * 1024)
            app.contentResolver.openOutputStream(doc.uri, "w")!!.use { out ->
                while (total < 0 || pos < total) {
                    val end = pos + 4L * 1024 * 1024 - 1
                    val req = Request.Builder().url(res.url)
                        .header("Range", "bytes=$pos-$end")
                        .header("User-Agent", Net.BROWSER_UA)
                        .build()
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        if (total < 0) {
                            total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                ?: r.body?.contentLength()?.takeIf { it > 0 } ?: throw IOException("Dimensione sconosciuta")
                        }
                        val input = (r.body ?: throw IOException("Risposta vuota")).byteStream()
                        var got = 0L
                        while (true) {
                            if (cancelId == item.videoId) throw Cancelled()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n; pos += n
                            val pct = (pos * 100 / total).toInt().coerceIn(0, 99)
                            if (progress[item.videoId] != pct) {   // aggiorna solo quando la percentuale cambia
                                progress[item.videoId] = pct
                                changed()
                            }
                        }
                        if (got <= 0) throw IOException("Flusso interrotto")
                    }
                }
            }
            AppLog.i(TAG, "Scaricato: ${item.title} (${pos / 1024} KB)")
            if (!webm) tagFile(doc.uri.toString(), item, g)
            val rel = (if (dir == root) "" else "${dir.name}/") + (doc.name ?: "$base.$ext")
            return Fetched(doc.uri.toString(), rel)
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
    }
}
