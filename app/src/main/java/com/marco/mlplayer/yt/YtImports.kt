package com.marco.mlplayer.yt

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.AsyncFileWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Playlist importate da Spotify, Deezer e YouTube, con i brani già abbinati: si aprono anche senza rete. */
class YtImports(context: Context) {
    /**
     * [keys]: per ogni brano dell'origine il video scelto ("" = non trovato), serve agli aggiornamenti;
     * [total]: i brani dell'origine; [checkedAt]: l'ultima volta che è stata importata o controllata (ms).
     */
    class Imp(
        val entry: Yt.Entry,
        val tracks: List<Yt.Item>,
        val keys: Map<String, String> = emptyMap(),
        val total: Int = tracks.size,
        val checkedAt: Long = 0,
    )

    private val file = File(context.filesDir, "yt_imports.json")
    private val writer = AsyncFileWriter(file, "yt-imports")

    /** Le importate, le più recenti in alto. */
    var all by mutableStateOf(emptyList<Imp>())
        private set

    init {
        runCatching { if (file.exists()) all = parse(JSONObject(file.readText())) }
            .onFailure { AppLog.e("Importa", "Lettura delle playlist importate non riuscita", it) }
    }

    /** Ultimo controllo di una qualunque playlist (ms), 0 se mai. */
    val lastCheck: Long get() = all.maxOfOrNull { it.checkedAt } ?: 0

    fun find(importId: String): Imp? = all.firstOrNull { it.entry.browseId == importId }

    /** Salva una playlist importata nuova, o ne sostituisce una già presente mantenendo la sua posizione. */
    @Synchronized
    fun put(imp: Imp) {
        val i = all.indexOfFirst { it.entry.browseId == imp.entry.browseId }
        all = if (i < 0) listOf(imp) + all else all.toMutableList().also { it[i] = imp }
        save()
    }

    @Synchronized
    fun remove(importId: String) {
        all = all.filter { it.entry.browseId != importId }
        save()
    }

    fun exportJson(): String =
        JSONObject().put("items", JSONArray(all.map { i ->
            JSONObject()
                .put("entry", i.entry.toJson())
                .put("tracks", JSONArray(i.tracks.map { it.toJson() }))
                .put("keys", JSONObject(i.keys))
                .put("total", i.total)
                .put("at", i.checkedAt)
        })).toString()

    /** Aggiunge quelle di un file di riserva che mancano; restituisce quante. */
    @Synchronized
    fun mergeFrom(text: String): Int {
        val new = parse(JSONObject(text)).filter { n -> all.none { it.entry.browseId == n.entry.browseId } }
        if (new.isEmpty()) return 0
        all = all + new
        save()
        return new.size
    }

    private fun parse(o: JSONObject): List<Imp> {
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val x = a.optJSONObject(i) ?: return@mapNotNull null
            val entry = x.optJSONObject("entry")?.let { e -> Yt.Entry.fromJson(e) } ?: return@mapNotNull null
            val t = x.optJSONArray("tracks")
            val tracks = if (t == null) emptyList() else (0 until t.length()).mapNotNull { j -> t.optJSONObject(j)?.let { s -> Yt.Item.fromJson(s) } }
            val k = x.optJSONObject("keys")
            val keys = if (k == null) emptyMap() else k.keys().asSequence().associateWith { key -> k.optString(key) }
            Imp(entry, tracks, keys, x.optInt("total", tracks.size), x.optLong("at"))
        }
    }

    private fun save() {
        writer.write(exportJson()) { AppLog.e("Importa", "Salvataggio non riuscito", it) }
        YtBackup.schedule()
    }
}
