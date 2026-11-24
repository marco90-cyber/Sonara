package com.marco.mlplayer.yt

import com.marco.mlplayer.util.AppLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Il genere di un brano, in id di [FavGenres]. Due strade:
 * 1) l'origine: il brano è arrivato da uno scaffale di un genere ([noteOrigin]);
 * 2) il genere dell'artista, chiesto a Deezer (genere più frequente tra i suoi album) e ricordato in [YtStore.artistGenres].
 */
object YtGenres {
    private const val TAG = "Generi"
    private val origin = ConcurrentHashMap<String, String>()

    /** Da chiamare quando si mostrano brani presi da uno scaffale di genere: se poi li ascolti, il genere è quello. */
    fun noteOrigin(items: List<Yt.Item>, genreId: String) { items.forEach { origin.putIfAbsent(it.videoId, genreId) } }
    fun originOf(videoId: String): String? = origin[videoId]

    /** Nomi dei generi di Deezer -> id di [FavGenres]. I generi senza corrispondenza non contano. */
    private val DEEZER = mapOf(
        "pop" to "pop", "rap/hip hop" to "hiphop", "rock" to "rock", "dance" to "dance", "r&b" to "rnb",
        "alternative" to "indie", "electro" to "electronic", "folk" to "folk", "reggae" to "reggae", "jazz" to "jazz",
        "classical" to "classica", "films/games" to "colonne", "metal" to "metal", "soul & funk" to "soul",
        "latin music" to "reggaeton", "blues" to "blues", "country" to "country", "asian music" to "kpop",
    )

    /** Il genere di un brano: prima quello d'origine ricordato, poi quello dell'artista; null se non si sa. */
    fun genreOf(item: Yt.Item, st: YtStore): String? =
        st.trackGenres[item.videoId]
            ?: YtTaste.artistNames(item.artist).firstOrNull()?.let { st.artistGenres[YtTaste.norm(it)] }?.takeIf { it.isNotEmpty() }

    /**
     * Ricava da Deezer il genere degli [artists] che non si conoscono ancora (al massimo [max] per volta) e lo salva nello store.
     * Bloccante: da chiamare fuori dal thread principale. Un artista non trovato resta «sconosciuto» (vuoto) e non si richiede più.
     */
    fun fetchMissing(st: YtStore, artists: List<String>, max: Int = 12) {
        val todo = artists.filter { YtTaste.norm(it) !in st.artistGenres }.distinctBy { YtTaste.norm(it) }.take(max)
        if (todo.isEmpty()) return
        val found = HashMap<String, String>()
        for (name in todo) {
            val key = YtTaste.norm(name)
            runCatching { found[key] = artistGenre(name) ?: "" }
                .onFailure { AppLog.w(TAG, "Genere di «$name» non ricavato: ${it.message}") }
        }
        AppLog.i(TAG, "Generi ricavati: ${found.count { it.value.isNotEmpty() }}/${todo.size}")
        st.addArtistGenres(found)
    }

    private fun artistGenre(name: String): String? {
        val hit = PlaylistImport.deezerArtistGenres(name) ?: return null
        val best = hit.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        return DEEZER[best.lowercase()]
    }
}
