package com.marco.mlplayer.yt

import com.marco.mlplayer.data.Region
import com.marco.mlplayer.util.AppLog
import java.text.Normalizer

/**
 * Playlist «In evidenza» della Home: le classifiche di Spotify e Deezer per il paese scelto.
 * Spotify: elenco fisso di playlist pubbliche (verificate), lette dalla pagina "embed" come ogni import.
 * Deezer: la classifica del paese (cercata per nome) e le playlist più ascoltate del momento.
 */
object Featured {
    private const val TAG = "InEvidenza"
    private const val TTL_MS = 6 * 3_600_000L

    private class Spot(val id: String, val title: String)

    private val GLOBAL_TOP = Spot("37i9dQZEVXbMDoHDwVN2tF", "Top 50 · Mondo")
    /** Top 50 di Spotify per paese: finora verificata solo l'Italia, gli altri paesi usano quella mondiale. */
    private val COUNTRY_TOP = mapOf("IT" to Spot("37i9dQZEVXbIQnj7RRhdSX", "Top 50 · Italia"))
    private val FIXED = listOf(
        Spot("37i9dQZF1DXcBWIGoYBM5M", "Today's Top Hits"),
        Spot("37i9dQZF1DX4JAvHpjipBk", "New Music Friday"),
    )

    private var cacheCountry = ""
    private var cacheAt = 0L
    private var cache = emptyList<Yt.Entry>()

    private fun spotSpots(country: String) = listOf(COUNTRY_TOP[country] ?: GLOBAL_TOP) + FIXED

    private fun entryOf(s: Spot, cover: String = "") =
        Yt.Entry(Yt.Kind.Playlists, PlaylistImport.Ref(PlaylistImport.Source.Spotify, s.id).browseId, s.title, "Spotify", cover)

    /** Subito, senza rete: le playlist di Spotify (senza copertina). */
    fun spotifyNow(country: String): List<Yt.Entry> = spotSpots(country).map { entryOf(it) }

    private val MARKS = Regex("\\p{M}+")
    private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    /** Da chiamare fuori dal thread principale. [onPartial] riceve l'elenco man mano che si completa. */
    fun load(country: String, onPartial: (List<Yt.Entry>) -> Unit): List<Yt.Entry> {
        val now = System.currentTimeMillis()
        if (cacheCountry == country && cache.isNotEmpty() && now - cacheAt < TTL_MS) return cache
        val base = spotifyNow(country)
        onPartial(base)
        // Deezer: classifica del paese, poi le playlist più ascoltate
        val names = listOf(
            plain(Region.name(country)),
            plain(java.util.Locale.Builder().setRegion(country).build().getDisplayCountry(java.util.Locale.ENGLISH)),
        ).filter { it.isNotBlank() }
        val deezerTop = PlaylistImport.searchDeezer("Top ${Region.name(country)}")
            .filter { e -> plain(e.title).startsWith("top") && names.any { plain(e.title).contains(it) } }
            .take(2)
        val charts = PlaylistImport.deezerCharts(6).filter { c -> deezerTop.none { it.browseId == c.browseId } }
        val deezer = deezerTop + charts
        val withDeezer = base + deezer
        onPartial(withDeezer)
        // copertine di Spotify (la pagina "embed" le ha); se una non si legge resta senza
        val covered = spotSpots(country).mapIndexed { i, s ->
            val cover = runCatching { PlaylistImport.fetchSpotify(s.id).meta.cover }
                .onFailure { AppLog.w(TAG, "Copertina di ${s.title} non letta: ${it.message}") }.getOrDefault("")
            entryOf(s, cover.ifBlank { base[i].thumb })
        }
        val all = covered + deezer
        if (all.isNotEmpty()) { cacheCountry = country; cacheAt = now; cache = all }
        return all
    }
}
