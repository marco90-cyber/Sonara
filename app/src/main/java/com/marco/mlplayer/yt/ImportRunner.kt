package com.marco.mlplayer.yt

import android.content.Intent
import androidx.core.content.ContextCompat
import com.marco.mlplayer.MLPlayerApp
import com.marco.mlplayer.audio.ImportNotify
import com.marco.mlplayer.audio.ImportService
import com.marco.mlplayer.util.AppLog
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Esegue le importazioni e gli aggiornamenti delle playlist uno alla volta, anche con l'app chiusa:
 * [launch] tiene vivo il processo con [ImportService] (notifica con l'avanzamento), [runSync] serve ai lavori periodici.
 */
object ImportRunner {
    private const val TAG = "Importa"

    class Snapshot(val name: String, val done: Int, val total: Int, val queued: Int)

    /** Cosa è cambiato in una playlist aggiornata. */
    class Change(val title: String, val added: Int, val removed: Int, val reordered: Boolean) {
        val any get() = added > 0 || removed > 0 || reordered
    }

    /** Nessun brano trovato su YouTube Music (la playlist non viene toccata). */
    class NoTracks(message: String) : IOException(message)

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "import").apply { isDaemon = true } }
    private val pending = AtomicInteger(0)
    @Volatile private var cancelled = false
    @Volatile private var name = ""
    @Volatile private var done = 0
    @Volatile private var total = 0
    @Volatile private var lastNotifyMs = 0L

    /** La notifica del servizio si aggiorna qui. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Almeno un'activity è visibile: serve a decidere se avvisare con una notifica a lavoro finito. */
    @Volatile var visible = false

    val active get() = pending.get() > 0

    fun snapshot() = Snapshot(name, done, total, pending.get())

    fun cancelAll() { cancelled = true }

    private fun report(n: String, d: Int, t: Int) {
        if (cancelled) throw CancellationException("Importazione interrotta")
        name = n; done = d; total = t
        val now = System.currentTimeMillis()
        if (d >= t || now - lastNotifyMs >= 700) { lastNotifyMs = now; onChange?.invoke() }
    }

    /** Mette [block] in coda (un lavoro alla volta) e tiene vivo il processo con una notifica. */
    fun launch(app: MLPlayerApp, block: () -> Unit) {
        if (pending.getAndIncrement() == 0) cancelled = false
        runCatching { ContextCompat.startForegroundService(app, Intent(app, ImportService::class.java)) }
            .onFailure { AppLog.w(TAG, "Servizio in primo piano non avviato: ${it.message}") }
        exec.execute { finish { block() } }
    }

    /** Esegue [block] qui, adesso (per i lavori periodici, che il sistema tiene vivi da sé). */
    fun runSync(block: () -> Unit) {
        if (pending.getAndIncrement() == 0) cancelled = false
        finish(block)
    }

    private fun finish(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            AppLog.e(TAG, "Lavoro di importazione interrotto", e)
        } finally {
            if (pending.decrementAndGet() == 0) { cancelled = false; name = ""; done = 0; total = 0 }
            onChange?.invoke()
        }
    }

    /** Importa una playlist nuova e la salva. Bloccante: da chiamare dentro [launch] o [runSync]. */
    fun importPlaylist(
        app: MLPlayerApp,
        ref: PlaylistImport.Ref,
        listener: (PlaylistImport.Meta, List<Yt.Item>, Int, Int) -> Unit = { _, _, _, _ -> },
    ): YtImports.Imp {
        val r = PlaylistImport.import(ref) { meta, found, d, t ->
            report(meta.name, d, t)
            listener(meta, found, d, t)
        }
        if (r.tracks.isEmpty()) throw NoTracks("Non ho trovato nessun brano di questa playlist su YouTube Music.")
        val entry = Yt.Entry(Yt.Kind.Playlists, ref.importId, r.meta.name, PlaylistImport.subtitle(ref.source, r.tracks.size, r.total), r.meta.cover)
        val imp = YtImports.Imp(entry, r.tracks, r.keys, r.total, System.currentTimeMillis())
        app.ytImports.put(imp)
        if (!visible) ImportNotify.imported(app, entry.title, entry.subtitle)
        return imp
    }

    /**
     * Rilegge una playlist importata: i brani già abbinati restano, quelli nuovi vengono cercati, quelli spariti tolti.
     * Se la lettura fallisce non cambia nulla. Bloccante: da chiamare dentro [launch] o [runSync].
     */
    fun refreshPlaylist(
        app: MLPlayerApp,
        imp: YtImports.Imp,
        listener: (PlaylistImport.Meta, List<Yt.Item>, Int, Int) -> Unit = { _, _, _, _ -> },
    ): Change {
        val ref = PlaylistImport.refOf(imp.entry.browseId) ?: throw IOException("Playlist non riconosciuta")
        val r = PlaylistImport.import(ref, imp.keys, imp.tracks.associateBy { it.videoId }) { meta, found, d, t ->
            report(meta.name, d, t)
            listener(meta, found, d, t)
        }
        if (r.tracks.isEmpty()) throw NoTracks("Nessun brano trovato: tengo la copia che avevo.")
        val oldIds = imp.tracks.map { it.videoId }
        val newIds = r.tracks.map { it.videoId }
        val change = Change(
            r.meta.name.ifBlank { imp.entry.title },
            newIds.count { it !in oldIds.toSet() },
            oldIds.count { it !in newIds.toSet() },
            newIds.toSet() == oldIds.toSet() && newIds != oldIds,
        )
        val entry = imp.entry.copy(
            title = change.title,
            subtitle = PlaylistImport.subtitle(ref.source, r.tracks.size, r.total),
            thumb = r.meta.cover.ifBlank { imp.entry.thumb },
        )
        app.ytImports.put(YtImports.Imp(entry, r.tracks, r.keys.ifEmpty { imp.keys }, r.total, System.currentTimeMillis()))
        AppLog.i(TAG, "Aggiornata \"${entry.title}\": +${change.added} −${change.removed}")
        return change
    }

    /** Aggiorna tutte le importate, una dopo l'altra; quelle che non si riesce a leggere restano come sono. */
    fun refreshAll(app: MLPlayerApp): List<Change> {
        val changes = ArrayList<Change>()
        for (imp in app.ytImports.all.toList()) {
            if (cancelled) break
            if (PlaylistImport.refOf(imp.entry.browseId) == null) continue
            try {
                changes += refreshPlaylist(app, imp)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                AppLog.w(TAG, "Aggiornamento di \"${imp.entry.title}\" non riuscito: ${e.message}")
            }
        }
        return changes
    }
}
