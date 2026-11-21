package com.marco.mlplayer.yt

import org.json.JSONArray

/** Gli artisti scelti come preferiti (Benvenuto e Impostazioni), salvati in una preferenza come elenco JSON. */
object FavArtists {
    fun parse(json: String): List<Yt.Entry> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { Yt.Entry.fromJson(a.getJSONObject(it)) }.filter { it.kind == Yt.Kind.Artists }
    }.getOrDefault(emptyList())

    fun has(json: String, id: String) = parse(json).any { it.browseId == id }

    /** Aggiunge l'artista se non c'è, altrimenti lo toglie. */
    fun toggle(json: String, e: Yt.Entry): String {
        val cur = parse(json)
        val next = if (cur.any { it.browseId == e.browseId }) cur.filter { it.browseId != e.browseId } else cur + e
        return JSONArray().apply { next.forEach { put(it.toJson()) } }.toString()
    }
}
