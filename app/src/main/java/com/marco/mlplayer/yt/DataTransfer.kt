package com.marco.mlplayer.yt

import com.marco.mlplayer.MLPlayerApp
import org.json.JSONObject

/**
 * Tutto in un solo file per cambiare telefono: libreria e gusti, playlist importate, sorgenti cambiate e impostazioni.
 * I brani scaricati restano fuori: si ritrovano scegliendo la stessa cartella dei download.
 */
object DataTransfer {
    const val FILE = "sonara-backup.json"
    private const val KIND = "sonara-backup"

    fun export(app: MLPlayerApp): String = JSONObject()
        .put("kind", KIND)
        .put("version", 1)
        .put("library", app.ytStore.exportJson())
        .put("imports", app.ytImports.exportJson())
        .put("sources", YtOverrides.exportJson())
        .put("settings", app.settings.exportPrefs())
        .toString()

    /** Unisce il file a quanto c'è già. Restituisce il messaggio da mostrare, o null se il file non è un backup di Sonara. */
    fun import(app: MLPlayerApp, text: String): String? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.optString("kind") != KIND) return null
        var n = 0
        o.optString("library").takeIf { it.isNotBlank() }?.let { n += app.ytStore.mergeFrom(it) }
        o.optString("imports").takeIf { it.isNotBlank() }?.let { n += app.ytImports.mergeFrom(it) }
        o.optString("sources").takeIf { it.isNotBlank() }?.let { n += YtOverrides.mergeFrom(it) }
        o.optJSONObject("settings")?.let { app.settings.importPrefs(it) }
        return if (n > 0) "Importati $n elementi e le impostazioni" else "Impostazioni importate (il resto c'era già)"
    }
}
