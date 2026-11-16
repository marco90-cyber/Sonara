package com.marco.mlplayer.yt

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mlplayer.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Preferiti, cronologia e ultime ricerche di Sonara (file JSON locale, nessun account richiesto). */
class YtStore(context: Context) {
    private val file = File(context.filesDir, "yt_store.json")
    private val lock = Any()

    var favorites by mutableStateOf(emptyList<Yt.Item>())
        private set
    var history by mutableStateOf(emptyList<Yt.Item>())
        private set
    var searches by mutableStateOf(emptyList<String>())
        private set
    /** Playlist, album e artisti salvati con il segnalibro (i più recenti in alto). */
    var saved by mutableStateOf(emptyList<Yt.Entry>())
        private set

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                favorites = items(o.optJSONArray("favorites"))
                history = items(o.optJSONArray("history"))
                saved = o.optJSONArray("saved")?.let { a ->
                    (0 until a.length()).mapNotNull {
                        val e = a.optJSONObject(it) ?: return@mapNotNull null
                        val kind = runCatching { Yt.Kind.valueOf(e.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                        val id = e.optString("id")
                        if (id.isBlank()) null else Yt.Entry(kind, id, e.optString("title"), e.optString("sub"), e.optString("thumb"))
                    }
                } ?: emptyList()
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

    fun clearHistory() {
        history = emptyList()
        save()
    }

    fun addSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        searches = (listOf(t) + searches.filter { !it.equals(t, true) }).take(15)
        save()
    }

    fun clearSearches() {
        searches = emptyList()
        save()
    }

    private fun items(a: JSONArray?): List<Yt.Item> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull {
            val o = a.optJSONObject(it) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else Yt.Item(id, o.optString("title"), o.optString("artist"), o.optLong("dur"), o.optString("thumb"))
        }
    }

    private fun arr(l: List<Yt.Item>) = JSONArray().apply {
        l.forEach {
            put(JSONObject().put("id", it.videoId).put("title", it.title).put("artist", it.artist).put("dur", it.durationSec).put("thumb", it.thumb))
        }
    }

    private fun save() {
        val json = JSONObject()
            .put("favorites", arr(favorites))
            .put("history", arr(history))
            .put("searches", JSONArray(searches))
            .put("saved", JSONArray().apply {
                saved.forEach {
                    put(JSONObject().put("kind", it.kind.name).put("id", it.browseId).put("title", it.title).put("sub", it.subtitle).put("thumb", it.thumb))
                }
            })
            .toString()
        Thread {
            synchronized(lock) { runCatching { file.writeText(json) }.onFailure { AppLog.e("YouTube", "Salvataggio non riuscito", it) } }
        }.start()
    }
}
