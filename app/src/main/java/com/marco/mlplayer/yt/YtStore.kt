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

/** Preferiti, cronologia e ultime ricerche di Sonara (file JSON locale, nessun account richiesto). */
class YtStore(context: Context) {
    private val file = File(context.filesDir, "yt_store.json")
    private val writer = AsyncFileWriter(file, "yt-store")

    var favorites by mutableStateOf(emptyList<Yt.Item>())
        private set
    var history by mutableStateOf(emptyList<Yt.Item>())
        private set
    var searches by mutableStateOf(emptyList<String>())
        private set
    /** Playlist, album e artisti salvati con il segnalibro (i più recenti in alto). */
    var saved by mutableStateOf(emptyList<Yt.Entry>())
        private set

    /** Ascolti contati per brano (chiave: id video). */
    var plays by mutableStateOf(emptyMap<String, YtPlay>())
        private set

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                favorites = items(o.optJSONArray("favorites"))
                history = items(o.optJSONArray("history"))
                saved = entries(o.optJSONArray("saved"))
                plays = playsOf(o.optJSONArray("plays"))
                searches = o.optJSONArray("searches")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            }
        }.onFailure { AppLog.e("YouTube", "Lettura preferiti non riuscita", it) }
    }

    fun isSaved(browseId: String) = saved.any { it.browseId == browseId }

    /** Salva o toglie dalla libreria una playlist, un album o un artista. */
    fun toggleSaved(e: Yt.Entry): Boolean {
        val now = !isSaved(e.browseId)
        saved = if (now) listOf(e) + saved else saved.filter { it.browseId != e.browseId }
        save()
        return now
    }

    fun isFavorite(videoId: String) = favorites.any { it.videoId == videoId }

    fun toggleFavorite(item: Yt.Item): Boolean {
        val now = !isFavorite(item.videoId)
        favorites = if (now) listOf(item) + favorites else favorites.filter { it.videoId != item.videoId }
        save()
        return now
    }

    /** Un brano ascoltato: in cima alla cronologia, senza doppioni, al massimo 300. */
    fun addHistory(item: Yt.Item) {
        history = (listOf(item) + history.filter { it.videoId != item.videoId }).take(300)
        save()
    }

    /** Un ascolto vero (brano sentito per un po'): conta, ricorda la data e tiene al massimo 2000 brani. */
    @Synchronized
    fun recordPlay(item: Yt.Item, now: Long = System.currentTimeMillis()) {
        val old = plays[item.videoId]
        val next = YtPlay(item, (old?.count ?: 0) + 1, old?.firstAt ?: now, now)
        var m = plays + (item.videoId to next)
        if (m.size > 2000) m = m.values.sortedByDescending { it.lastAt }.take(2000).associateBy { it.item.videoId }
        plays = m
        save()
    }

    fun clearHistory() {
        history = emptyList()
        plays = emptyMap()
        save()
    }

    fun addSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        searches = (listOf(t) + searches.filter { !it.equals(t, true) }).take(15)
        save()
    }

    private fun items(a: JSONArray?): List<Yt.Item> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Yt.Item.fromJson(o) } }

    private fun playsOf(a: JSONArray?): Map<String, YtPlay> {
        if (a == null) return emptyMap()
        val m = LinkedHashMap<String, YtPlay>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val item = Yt.Item.fromJson(o) ?: continue
            m[item.videoId] = YtPlay(item, o.optInt("n", 1).coerceAtLeast(1), o.optLong("first"), o.optLong("last"))
        }
        return m
    }

    private fun entries(a: JSONArray?): List<Yt.Entry> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Yt.Entry.fromJson(o) } }

    fun exportJson(): String = JSONObject()
        .put("favorites", JSONArray().apply { favorites.forEach { put(it.toJson()) } })
        .put("history", JSONArray().apply { history.forEach { put(it.toJson()) } })
        .put("searches", JSONArray(searches))
        .put("saved", JSONArray().apply { saved.forEach { put(it.toJson()) } })
        .put("plays", JSONArray().apply {
            plays.values.forEach { put(it.item.toJson().put("n", it.count).put("first", it.firstAt).put("last", it.lastAt)) }
        })
        .toString()

    /** Unisce una copia di riserva a preferiti, salvati e cronologia attuali; restituisce quanti elementi nuovi. */
    fun mergeFrom(text: String): Int {
        val o = JSONObject(text)
        val fav = items(o.optJSONArray("favorites")).filter { n -> favorites.none { it.videoId == n.videoId } }
        val his = items(o.optJSONArray("history")).filter { n -> history.none { it.videoId == n.videoId } }
        val sav = entries(o.optJSONArray("saved")).filter { n -> saved.none { it.browseId == n.browseId } }
        val inPlays = playsOf(o.optJSONArray("plays"))
        val mergedPlays = HashMap(plays)
        var newPlays = 0
        for ((k, p) in inPlays) {
            val cur = mergedPlays[k]
            if (cur == null) { mergedPlays[k] = p; newPlays++ }
            else mergedPlays[k] = YtPlay(cur.item, maxOf(cur.count, p.count), minOf(cur.firstAt, p.firstAt).takeIf { it > 0 } ?: maxOf(cur.firstAt, p.firstAt), maxOf(cur.lastAt, p.lastAt))
        }
        if (inPlays.isNotEmpty()) plays = mergedPlays
        if (fav.isEmpty() && his.isEmpty() && sav.isEmpty()) { if (newPlays > 0) save(); return newPlays }
        favorites = favorites + fav
        history = (history + his).take(300)
        saved = saved + sav
        save()
        return fav.size + his.size + sav.size + newPlays
    }

    /** Scrive il file in coda su un thread dedicato: l'ordine è quello delle modifiche e il file non resta mai a metà. */
    private fun save() {
        writer.write(exportJson()) { AppLog.e("YouTube", "Salvataggio non riuscito", it) }
        YtBackup.schedule()
    }
}
