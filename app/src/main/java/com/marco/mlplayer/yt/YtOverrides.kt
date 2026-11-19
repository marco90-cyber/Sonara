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

/**
 * «Cambia sorgente»: un brano con il nome giusto ma l'audio sbagliato (o con il videoclip al posto della traccia)
 * si può far suonare da un altro video di YouTube. La scelta vale in tutta l'app, resta salvata e si può annullare.
 *
 * Il brano mantiene il suo id originale (preferiti, cronologia, playlist non cambiano): si sostituisce solo
 * il video da cui si prende l'audio e, a scelta, titolo, artista e copertina mostrati.
 */
object YtOverrides {
    private const val TAG = "Sorgente"

    /**
     * [orig] è il brano com'era prima del cambio (serve per ripristinarlo), [target] il video scelto.
     * [useTargetMeta]: mostrare titolo, artista e copertina del video scelto invece di quelli originali.
     */
    class Override(val orig: Yt.Item, val target: Yt.Item, val useTargetMeta: Boolean)

    private var writer: AsyncFileWriter? = null

    /** Brani con la sorgente cambiata (chiave: id video originale). Stato osservabile dall'interfaccia. */
    var map by mutableStateOf(emptyMap<String, Override>())
        private set

    fun init(context: Context) {
        val file = File(context.filesDir, "yt_overrides.json")
        writer = AsyncFileWriter(file, "yt-overrides")
        runCatching {
            if (file.exists()) map = parse(JSONObject(file.readText()).optJSONArray("overrides"))
        }.onFailure { AppLog.e(TAG, "Lettura delle sorgenti cambiate non riuscita", it) }
    }

    fun has(videoId: String) = map.containsKey(videoId)

    operator fun get(videoId: String): Override? = map[videoId]

    /** L'id del video da cui prendere l'audio per [videoId]. */
    fun target(videoId: String): String = map[videoId]?.target?.videoId ?: videoId

    /** Il brano com'è da mostrare: titolo, artista e copertina secondo la scelta, durata del video scelto. */
    fun apply(i: Yt.Item): Yt.Item {
        val o = map[i.videoId] ?: return i
        val t = o.target
        return if (o.useTargetMeta) Yt.Item(i.videoId, t.title, t.artist, t.durationSec, t.thumb)
        else Yt.Item(i.videoId, i.title, i.artist, t.durationSec, i.thumb)
    }

    /**
     * Usa il video [target] come sorgente di [orig]. Se il brano era già stato cambiato si tiene
     * l'originale della prima volta, così «Ripristina» torna sempre al brano di partenza.
     */
    fun set(orig: Yt.Item, target: Yt.Item, useTargetMeta: Boolean) {
        val first = map[orig.videoId]?.orig ?: orig
        if (target.videoId == orig.videoId) { clear(orig.videoId); return }
        map = map + (orig.videoId to Override(first, target, useTargetMeta))
        AppLog.i(TAG, "${first.title}: audio da ${target.videoId} (${target.title})")
        save()
    }

    /** Toglie il cambio e restituisce il brano originale (null se non era stato cambiato). */
    fun clear(videoId: String): Yt.Item? {
        val o = map[videoId] ?: return null
        map = map - videoId
        AppLog.i(TAG, "${o.orig.title}: sorgente ripristinata")
        save()
        return o.orig
    }

    private fun parse(a: JSONArray?): Map<String, Override> {
        if (a == null) return emptyMap()
        val m = LinkedHashMap<String, Override>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val orig = o.optJSONObject("orig")?.let { Yt.Item.fromJson(it) } ?: continue
            val target = o.optJSONObject("target")?.let { Yt.Item.fromJson(it) } ?: continue
            m[orig.videoId] = Override(orig, target, o.optBoolean("meta", false))
        }
        return m
    }

    fun exportJson(): String = JSONObject().put("overrides", JSONArray().apply {
        map.values.forEach {
            put(JSONObject().put("orig", it.orig.toJson()).put("target", it.target.toJson()).put("meta", it.useTargetMeta))
        }
    }).toString()

    /** Unisce una copia di riserva: i cambi già presenti restano, quelli nuovi si aggiungono. Restituisce quanti. */
    fun mergeFrom(text: String): Int {
        val incoming = parse(JSONObject(text).optJSONArray("overrides")).filterKeys { it !in map }
        if (incoming.isEmpty()) return 0
        map = map + incoming
        save()
        return incoming.size
    }

    /** Solo per i test: svuota l'elenco (l'oggetto è condiviso tra un test e l'altro). */
    internal fun resetForTest() { map = emptyMap() }

    private fun save() {
        writer?.write(exportJson()) { AppLog.e(TAG, "Salvataggio non riuscito", it) }
        YtBackup.schedule()
    }
}
