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

/** Una playlist creata dall'utente in Sonara. */
data class MyPlaylist(val id: String, val name: String, val items: List<Yt.Item>)

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

    /** Brani segnati come «non mi piace»: spariscono dai suggerimenti personali. */
    var dislikes by mutableStateOf(emptyList<Yt.Item>())
        private set
    /** Cresce a ogni «mi piace» o «non mi piace»: la Home sa così che deve rifare i suggerimenti. */
    var tasteVersion by mutableStateOf(0)
        private set

    /** Ascolti contati per brano (chiave: id video). */
    var plays by mutableStateOf(emptyMap<String, YtPlay>())
        private set

    /** Salti entro 30 secondi per brano (chiave: id video): pesano -0,5 l'uno, fino a -1. */
    var skips by mutableStateOf(emptyMap<String, Int>())
        private set
    /** Genere (id di [FavGenres]) dei brani dove l'origine è nota, per id video. */
    var trackGenres by mutableStateOf(emptyMap<String, String>())
        private set
    /** Genere per artista (nome normalizzato -> id di [FavGenres], vuoto se sconosciuto), ricavato da Deezer. */
    var artistGenres by mutableStateOf(emptyMap<String, String>())
        private set

    /** Le playlist create da te (il nome e i brani); le più recenti in alto. */
    var myPlaylists by mutableStateOf(emptyList<MyPlaylist>())
        private set

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                favorites = items(o.optJSONArray("favorites"))
                history = items(o.optJSONArray("history"))
                saved = entries(o.optJSONArray("saved"))
                plays = playsOf(o.optJSONArray("plays"))
                dislikes = items(o.optJSONArray("dislikes"))
                myPlaylists = playlistsOf(o.optJSONArray("playlists"))
                skips = o.optJSONObject("skips")?.let { j -> j.keys().asSequence().associateWith { j.optInt(it) }.filterValues { it > 0 } } ?: emptyMap()
                trackGenres = strMap(o.optJSONObject("trackGenres"))
                artistGenres = strMap(o.optJSONObject("artistGenres"))
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

    /** Aggiunge alla libreria (se non c'è già) una playlist, un album o un artista, senza mai toglierlo. */
    fun addSaved(e: Yt.Entry) {
        if (isSaved(e.browseId)) return
        saved = listOf(e) + saved
        save()
    }

    /** Crea una playlist vuota e la restituisce. */
    fun createPlaylist(name: String): MyPlaylist {
        val p = MyPlaylist("p" + System.currentTimeMillis().toString(36) + (0..999).random(), name.trim().ifEmpty { "Nuova playlist" }, emptyList())
        myPlaylists = listOf(p) + myPlaylists
        save()
        return p
    }

    fun renamePlaylist(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        myPlaylists = myPlaylists.map { if (it.id == id) it.copy(name = n) else it }
        save()
    }

    fun deletePlaylist(id: String) {
        myPlaylists = myPlaylists.filter { it.id != id }
        save()
    }

    /** Aggiunge il brano in fondo alla playlist; false se c'era già. */
    fun addToPlaylist(id: String, item: Yt.Item): Boolean {
        val p = myPlaylists.firstOrNull { it.id == id } ?: return false
        if (p.items.any { it.videoId == item.videoId }) return false
        myPlaylists = myPlaylists.map { if (it.id == id) it.copy(items = it.items + item) else it }
        save()
        return true
    }

    fun removeFromPlaylist(id: String, videoId: String) {
        myPlaylists = myPlaylists.map { if (it.id == id) it.copy(items = it.items.filter { i -> i.videoId != videoId }) else it }
        save()
    }

    fun isFavorite(videoId: String) = favorites.any { it.videoId == videoId }

    fun toggleFavorite(item: Yt.Item): Boolean {
        val now = !isFavorite(item.videoId)
        favorites = if (now) listOf(item) + favorites else favorites.filter { it.videoId != item.videoId }
        if (now) dislikes = dislikes.filter { it.videoId != item.videoId }
        noteGenre(item.videoId)
        tasteVersion++
        save()
        return now
    }

    fun isDisliked(videoId: String) = dislikes.any { it.videoId == videoId }

    /** «Non mi piace»: lo toglie dai preferiti e dai suggerimenti; toccando di nuovo si annulla. */
    fun toggleDislike(item: Yt.Item): Boolean {
        val now = !isDisliked(item.videoId)
        dislikes = if (now) listOf(item) + dislikes.filter { it.videoId != item.videoId } else dislikes.filter { it.videoId != item.videoId }
        if (now) favorites = favorites.filter { it.videoId != item.videoId }
        noteGenre(item.videoId)
        tasteVersion++
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
        noteGenre(item.videoId)
        save()
    }

    /** Un salto manuale entro 30 secondi dall'inizio del brano. */
    @Synchronized
    fun recordSkip(item: Yt.Item) {
        skips = skips + (item.videoId to ((skips[item.videoId] ?: 0) + 1).coerceAtMost(50))
        noteGenre(item.videoId)
        tasteVersion++
        save()
    }

    /** Se il brano è arrivato da uno scaffale di un genere (vedi [YtGenres.noteOrigin]) ricorda quel genere. */
    private fun noteGenre(videoId: String) {
        val g = YtGenres.originOf(videoId) ?: return
        if (trackGenres[videoId] == g) return
        var m = trackGenres + (videoId to g)
        if (m.size > 2000) m = m.entries.drop(m.size - 2000).associate { it.key to it.value }
        trackGenres = m
    }

    /** Aggiunge i generi di artisti appena ricavati da Deezer. */
    @Synchronized
    fun addArtistGenres(more: Map<String, String>) {
        if (more.isEmpty()) return
        artistGenres = artistGenres + more
        save()
    }

    /** Cancella preferiti, salvati, cronologia, ricerche, ascolti e «non mi piace». */
    fun clearAll() {
        favorites = emptyList(); saved = emptyList(); history = emptyList()
        searches = emptyList(); plays = emptyMap(); dislikes = emptyList()
        skips = emptyMap(); trackGenres = emptyMap(); myPlaylists = emptyList()
        tasteVersion++
        save()
    }

    fun clearHistory() {
        history = emptyList()
        plays = emptyMap()
        skips = emptyMap()
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

    private fun strMap(o: JSONObject?): Map<String, String> =
        o?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } } ?: emptyMap()

    private fun playlistsOf(a: JSONArray?): List<MyPlaylist> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MyPlaylist(id, o.optString("name", "Playlist"), items(o.optJSONArray("items")))
        }
    }

    private fun entries(a: JSONArray?): List<Yt.Entry> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Yt.Entry.fromJson(o) } }

    fun exportJson(): String = JSONObject()
        .put("favorites", JSONArray().apply { favorites.forEach { put(it.toJson()) } })
        .put("history", JSONArray().apply { history.forEach { put(it.toJson()) } })
        .put("searches", JSONArray(searches))
        .put("saved", JSONArray().apply { saved.forEach { put(it.toJson()) } })
        .put("dislikes", JSONArray().apply { dislikes.forEach { put(it.toJson()) } })
        .put("playlists", JSONArray().apply {
            myPlaylists.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("items", JSONArray().apply { p.items.forEach { put(it.toJson()) } })) }
        })
        .put("skips", JSONObject().apply { skips.forEach { (k, v) -> put(k, v) } })
        .put("trackGenres", JSONObject().apply { trackGenres.forEach { (k, v) -> put(k, v) } })
        .put("artistGenres", JSONObject().apply { artistGenres.forEach { (k, v) -> put(k, v) } })
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
        val dis = items(o.optJSONArray("dislikes")).filter { n -> dislikes.none { it.videoId == n.videoId } }
        if (dis.isNotEmpty()) dislikes = dislikes + dis
        val inPlays = playsOf(o.optJSONArray("plays"))
        val mergedPlays = HashMap(plays)
        var newPlays = 0
        for ((k, p) in inPlays) {
            val cur = mergedPlays[k]
            if (cur == null) { mergedPlays[k] = p; newPlays++ }
            else mergedPlays[k] = YtPlay(cur.item, maxOf(cur.count, p.count), minOf(cur.firstAt, p.firstAt).takeIf { it > 0 } ?: maxOf(cur.firstAt, p.firstAt), maxOf(cur.lastAt, p.lastAt))
        }
        if (inPlays.isNotEmpty()) plays = mergedPlays
        // playlist tue: nuove per id; se c'è già, si uniscono i brani
        val inLists = playlistsOf(o.optJSONArray("playlists"))
        var newLists = 0
        if (inLists.isNotEmpty()) {
            val cur = myPlaylists.associateBy { it.id }.toMutableMap()
            for (n in inLists) {
                val c = cur[n.id]
                if (c == null) { cur[n.id] = n; newLists++ }
                else cur[n.id] = c.copy(items = c.items + n.items.filter { i -> c.items.none { it.videoId == i.videoId } })
            }
            myPlaylists = (inLists.map { it.id } + myPlaylists.map { it.id }).distinct().mapNotNull { cur[it] }
        }
        val inSkips = o.optJSONObject("skips")?.let { j -> j.keys().asSequence().associateWith { j.optInt(it) }.filterValues { it > 0 } }.orEmpty()
        if (inSkips.isNotEmpty()) skips = (skips.keys + inSkips.keys).associateWith { maxOf(skips[it] ?: 0, inSkips[it] ?: 0) }
        trackGenres = strMap(o.optJSONObject("trackGenres")) + trackGenres
        artistGenres = strMap(o.optJSONObject("artistGenres")) + artistGenres
        if (fav.isEmpty() && his.isEmpty() && sav.isEmpty()) { save(); return newPlays + dis.size + inSkips.size + newLists }
        favorites = favorites + fav
        history = (history + his).take(300)
        saved = saved + sav
        save()
        return fav.size + his.size + sav.size + newPlays + newLists
    }

    /** Scrive il file in coda su un thread dedicato: l'ordine è quello delle modifiche e il file non resta mai a metà. */
    private fun save() {
        writer.write(exportJson()) { AppLog.e("YouTube", "Salvataggio non riuscito", it) }
        YtBackup.schedule()
    }
}
