package com.marco.mlplayer.yt

/** Quanti ascolti (di almeno una trentina di secondi) ha un brano, e quando l'ultima volta. */
data class YtPlay(val item: Yt.Item, val count: Int, val firstAt: Long, val lastAt: Long)

/** Gusti ricavati in locale da ascolti, cronologia e preferiti (nessun account). Funzioni pure, provate nei test. */
object YtTaste {
    /** Peso di un brano: ascolti contati; i brani della cronologia senza conteggio valgono 1, i preferiti +2. */
    fun weights(plays: Map<String, YtPlay>, history: List<Yt.Item>, favorites: List<Yt.Item>): Map<String, Pair<Yt.Item, Int>> {
        val out = LinkedHashMap<String, Pair<Yt.Item, Int>>()
        for (p in plays.values) out[p.item.videoId] = p.item to p.count
        for (h in history) if (h.videoId !in out) out[h.videoId] = h to 1
        for (f in favorites) {
            val cur = out[f.videoId]
            out[f.videoId] = (cur?.first ?: f) to ((cur?.second ?: 0) + 2)
        }
        return out
    }

    /** Esclude i brani non graditi e gli artisti con almeno 3 brani non graditi (uno solo non basta a bandire un artista). */
    fun excluder(dislikes: List<Yt.Item>): (Yt.Item) -> Boolean {
        val ids = dislikes.map { it.videoId }.toSet()
        val perArtist = HashMap<String, Int>()
        for (d in dislikes) for (a in artistNames(d.artist).take(1)) perArtist.merge(norm(a), 1, Int::plus)
        val blocked = perArtist.filterValues { it >= 3 }.keys
        return { i -> i.videoId in ids || artistNames(i.artist).firstOrNull()?.let { norm(it) in blocked } == true }
    }

    /** I brani più ascoltati, dal più al meno. */
    fun topTracks(w: Map<String, Pair<Yt.Item, Int>>, n: Int): List<Yt.Item> =
        w.values.sortedByDescending { it.second }.take(n).map { it.first }

    /** "A, B & C" -> [A, B, C]. */
    fun artistNames(artist: String): List<String> =
        artist.split(",", "&", " e ", " x ", " feat. ", " ft. ").map { it.trim() }.filter { it.isNotBlank() }

    /** Nome senza maiuscole, accenti e punteggiatura: per confrontare lo stesso artista scritto in modo diverso. */
    fun norm(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(MARKS, "").replace(NOT_ALNUM, " ").trim()

    private val MARKS = Regex("\\p{M}+")
    private val NOT_ALNUM = Regex("[^a-z0-9]+")

    /** Gli artisti più ascoltati (nome, peso), sommando i pesi dei loro brani. */
    fun topArtists(w: Map<String, Pair<Yt.Item, Int>>, n: Int): List<Pair<String, Int>> {
        val names = HashMap<String, String>()
        val sum = HashMap<String, Int>()
        for ((item, wt) in w.values) for (a in artistNames(item.artist)) {
            val k = norm(a)
            if (k.isEmpty()) continue
            names.putIfAbsent(k, a)
            sum[k] = (sum[k] ?: 0) + wt
        }
        return sum.entries.sortedByDescending { it.value }.take(n).map { names.getValue(it.key) to it.value }
    }

    /** Un brano di partenza per ogni artista tra i primi: il suo brano più ascoltato (artisti diversi tra loro). */
    fun seeds(w: Map<String, Pair<Yt.Item, Int>>, n: Int): List<Yt.Item> {
        val used = HashSet<String>()
        val out = ArrayList<Yt.Item>()
        for (e in w.values.sortedByDescending { it.second }) {
            val first = artistNames(e.first.artist).firstOrNull()?.let { a -> norm(a) } ?: continue
            if (used.add(first)) out.add(e.first)
            if (out.size >= n) break
        }
        return out
    }

    /** Preferiti che non ascolti da più tempo (mai ascoltati prima), fino a [n]. */
    fun forgottenFavorites(favorites: List<Yt.Item>, plays: Map<String, YtPlay>, n: Int): List<Yt.Item> =
        favorites.sortedBy { plays[it.videoId]?.lastAt ?: 0L }.take(n)
}
