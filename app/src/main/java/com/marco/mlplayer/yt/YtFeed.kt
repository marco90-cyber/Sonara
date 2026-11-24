package com.marco.mlplayer.yt

import com.marco.mlplayer.util.AppLog

/** Scaffali personali della Home, costruiti dai gusti locali. Ogni sezione è indipendente: se una fallisce, le altre restano. */
object YtFeed {
    private const val TAG = "HomeFeed"
    private val artistCache = HashMap<String, Yt.Entry?>()

    /** Radio dei brani di partenza, per «Radio per te» (id brano -> brani). */
    class Result(val shelves: List<YtApi.Shelf>, val radios: List<List<Yt.Item>>)

    /** Da chiamare fuori dal thread principale. [onShelf] riceve gli scaffali man mano che arrivano. */
    fun build(w: Map<String, Pair<Yt.Item, Double>>, bad: (Yt.Item) -> Boolean, onShelf: (YtApi.Shelf) -> Unit): Result {
        val shelves = ArrayList<YtApi.Shelf>()
        val radios = ArrayList<List<Yt.Item>>()
        fun add(s: YtApi.Shelf) { shelves.add(s); onShelf(s) }
        if (w.isEmpty()) return Result(shelves, radios)
        val known = w.keys

        // 1) «Perché hai ascoltato X»: la radio dei brani di partenza (uno per artista)
        val seeds = YtTaste.seeds(w, 3)
        for (seed in seeds) {
            runCatching {
                val r = YtApi.radio(seed.videoId).filter { !bad(it) }
                radios.add(r)
                val cards = r.filter { it.videoId != seed.videoId && it.videoId !in known }.take(20).map { YtApi.Card(it, null) }
                AppLog.i(TAG, "Radio da «${seed.title}»: ${r.size} brani, ${cards.size} nuovi")
                if (cards.isNotEmpty()) add(YtApi.Shelf("Perché hai ascoltato «${seed.title}»", cards))
            }.onFailure { AppLog.w(TAG, "Radio da «${seed.title}» non riuscita: ${it.message}") }
        }

        // 2) «Artisti per te»: i più ascoltati, ritrovati su YouTube Music (accetto solo un nome uguale)
        runCatching {
            val cards = ArrayList<YtApi.Card>()
            val mine = ArrayList<Yt.Entry>()
            for ((name, _) in YtTaste.topArtists(w, 8)) {
                val hit = artistEntry(name) ?: continue
                mine.add(hit)
                cards.add(YtApi.Card(null, hit))
            }
            AppLog.i(TAG, "Artisti per te: ${cards.size}")
            if (cards.isNotEmpty()) add(YtApi.Shelf("Artisti per te", cards.distinctBy { it.id }))
            // «Artisti simili»: quelli consigliati nelle pagine dei tuoi 3 artisti principali, senza i tuoi
            val have = mine.map { it.browseId }.toSet()
            val sim = LinkedHashMap<String, Yt.Entry>()
            for (a in mine.take(3)) runCatching { YtApi.similarArtists(a.browseId) }
                .onSuccess { l -> l.forEach { if (it.browseId !in have) sim.putIfAbsent(it.browseId, it) } }
                .onFailure { AppLog.w(TAG, "Simili a «${a.title}» non riusciti: ${it.message}") }
            if (sim.isNotEmpty()) add(YtApi.Shelf("Artisti simili", sim.values.take(20).map { YtApi.Card(null, it) }))
        }.onFailure { AppLog.w(TAG, "Artisti per te non riuscito: ${it.message}") }
        return Result(shelves, radios)
    }

    /** L'artista con quel nome (o null): prende il primo risultato solo se il nome combacia. */
    private fun artistEntry(name: String): Yt.Entry? {
        val key = YtTaste.norm(name)
        synchronized(artistCache) { if (artistCache.containsKey(key)) return artistCache[key] }
        val hit = runCatching { Yt.searchEntries(name, Yt.Kind.Artists, 1) }.getOrNull()
            ?.firstOrNull { YtTaste.norm(it.title) == key }
        synchronized(artistCache) { artistCache[key] = hit }
        return hit
    }
}
