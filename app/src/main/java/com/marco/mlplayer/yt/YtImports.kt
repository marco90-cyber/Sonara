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

/** Playlist importate da Spotify, Deezer e YouTube, con i brani già abbinati: si aprono anche senza rete per l'elenco. */
class YtImports(context: Context) {
    class Imp(val entry: Yt.Entry, val tracks: List<Yt.Item>)

    private val file = File(context.filesDir, "yt_imports.json")
    private val writer = AsyncFileWriter(file, "yt-imports")

    /** Le importate, le più recenti in alto. */
    var all by mutableStateOf(emptyList<Imp>())
        private set

    init {
        runCatching { if (file.exists()) all = parse(JSONObject(file.readText())) }
            .onFailure { AppLog.e("Importa", "Lettura delle playlist importate non riuscita", it) }
    }

    fun find(importId: String): Imp? = all.firstOrNull { it.entry.browseId == importId }

    /** Salva (o aggiorna) una playlist importata. */
    fun add(entry: Yt.Entry, tracks: List<Yt.Item>) {
        all = listOf(Imp(entry, tracks)) + all.filter { it.entry.browseId != entry.browseId }
        save()
    }

    fun remove(importId: String) {
        all = all.filter { it.entry.browseId != importId }
        save()
    }

    fun exportJson(): String =
        JSONObject().put("items", JSONArray(all.map { i ->
            JSONObject().put("entry", i.entry.toJson()).put("tracks", JSONArray(i.tracks.map { it.toJson() }))
        })).toString()

    /** Aggiunge quelle di un file di riserva che mancano; restituisce quante. */
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
            Imp(entry, if (t == null) emptyList() else (0 until t.length()).mapNotNull { j -> t.optJSONObject(j)?.let { s -> Yt.Item.fromJson(s) } })
        }
    }

    private fun save() {
        writer.write(exportJson()) { AppLog.e("Importa", "Salvataggio non riuscito", it) }
        YtBackup.schedule()
    }
}
