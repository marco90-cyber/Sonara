package com.marco.mlplayer.yt

import com.marco.mlplayer.MLPlayerApp
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.AsyncFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * «Correzione brani»: con l'impostazione attiva ogni brano che entra in coda (da playlist, Home, consigliati…)
 * viene controllato in background; se la durata non torna con quella ufficiale e si trova un audio sicuro
 * (stesso titolo, durata entro 3 s, traccia e non video) la sorgente cambia da sola.
 *
 * Non tocca mai i brani già cambiati a mano o ripristinati, e non cambia il brano che sta suonando da più di qualche secondo
 * (lo trova corretto la volta dopo). Ogni brano controllato si ricorda per 14 giorni.
 */
object AutoFix {
    private const val TAG = "Correzione"
    private const val TTL_MS = 14L * 24 * 3600 * 1000
    private const val MAX_REMEMBERED = 5000

    @Volatile var enabled: () -> Boolean = { false }

    private var app: MLPlayerApp? = null
    private var writer: AsyncFileWriter? = null
    private val checked = ConcurrentHashMap<String, Long>()
    private val ignored: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val queue = Channel<Yt.Item>(Channel.UNLIMITED)
    private var saveJob: Job? = null

    fun init(app: MLPlayerApp) {
        this.app = app
        val file = File(app.filesDir, "yt_autofix.json")
        writer = AsyncFileWriter(file, "yt-autofix")
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.optJSONObject("checked")?.let { c -> c.keys().forEach { k -> checked[k] = c.optLong(k) } }
                o.optJSONArray("ignored")?.let { a -> for (i in 0 until a.length()) ignored.add(a.getString(i)) }
            }
        }.onFailure { AppLog.w(TAG, "Lettura non riuscita: ${it.message}") }
        // due operai: i brani si controllano nell'ordine in cui entrano in coda, senza fare troppe richieste insieme
        repeat(2) {
            app.appScope.launch(Dispatchers.IO) {
                for (item in queue) {
                    try {
                        process(item)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "${item.title}: controllo non riuscito (${e.javaClass.simpleName})")
                    } finally {
                        pending.remove(item.videoId)
                    }
                    delay(200)
                }
            }
        }
    }

    /** Mette il brano in fila per il controllo (se l'impostazione è attiva e non è già stato visto di recente). */
    fun consider(item: Yt.Item) {
        if (app == null || !enabled()) return
        val id = item.videoId
        if (id in ignored || YtOverrides.has(id)) return
        if ((checked[id] ?: 0L) > System.currentTimeMillis() - TTL_MS) return
        if (!pending.add(id)) return
        queue.trySend(item)
    }

    /** L'utente ha ripristinato la sorgente originale: la correzione automatica non lo tocca più. */
    fun ignore(videoId: String) {
        ignored.add(videoId)
        save()
    }

    private suspend fun process(item: Yt.Item) {
        val id = item.videoId
        if (!enabled() || YtOverrides.has(id) || id in ignored) return
        if (item.durationSec <= 0) { mark(id); return }
        val lookup = DurationRef.lookupFull(item.title, item.artist)
        if (!lookup.reachable) return // niente rete: si riproverà alla prossima occasione
        val ref = lookup.ref
        if (ref == null || !DurationMatch.isOff(item.durationSec, ref.seconds)) { mark(id); return }
        val candidates = try {
            Yt.searchSources("${item.title} ${DurationMatch.mainArtist(item.artist)}")
        } catch (e: Exception) {
            return
        }
        val pick = DurationMatch.best(item.title, item.artist, ref.seconds, candidates)
        if (pick != null && pick.sure) apply(item, pick.source.item, ref)
        mark(id)
    }

    private suspend fun apply(orig: Yt.Item, source: Yt.Item, ref: DurationRef.Ref) = withContext(Dispatchers.Main) {
        val app = app ?: return@withContext
        if (!enabled() || YtOverrides.has(orig.videoId)) return@withContext
        YtOverrides.set(orig, source, false)
        val uri = "yt:${orig.videoId}"
        val engine = app.engine
        val playingNow = engine.currentTrack?.uri == uri
        // se il brano suona già da un po' non lo interrompo: la correzione vale dalla prossima volta
        if (!(playingNow && engine.positionMs > 8_000)) {
            engine.replaceTracks(mapOf(uri to Yt.toTrack(orig)), if (playingNow) setOf(uri) else emptySet(), restart = true)
        }
        AppLog.i(TAG, "${orig.title}: audio sostituito (${orig.durationSec}s → ${source.durationSec}s, riferimento ${ref.seconds}s ${ref.source})")
    }

    private fun mark(id: String) {
        checked[id] = System.currentTimeMillis()
        if (checked.size > MAX_REMEMBERED) {
            checked.entries.sortedBy { it.value }.take(500).forEach { checked.remove(it.key) }
        }
        save()
    }

    /** Scrive il file al massimo ogni 5 secondi, anche se i brani controllati sono tanti. */
    private fun save() {
        val app = app ?: return
        if (saveJob?.isActive == true) return
        saveJob = app.appScope.launch(Dispatchers.IO) {
            delay(5000)
            val o = JSONObject()
                .put("checked", JSONObject().apply { checked.forEach { (k, v) -> put(k, v) } })
                .put("ignored", JSONArray().apply { ignored.forEach { put(it) } })
            writer?.write(o.toString()) { AppLog.w(TAG, "Salvataggio non riuscito: ${it.message}") }
        }
    }
}
