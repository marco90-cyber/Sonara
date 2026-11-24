package com.marco.mlplayer.yt

/** Quanti ascolti (di almeno una trentina di secondi) ha un brano, e quando l'ultima volta. */
data class YtPlay(val item: Yt.Item, val count: Int, val firstAt: Long, val lastAt: Long)

/** Gusti ricavati in locale da ascolti, cronologia e preferiti (nessun account). Funzioni pure, provate nei test. */
object YtTaste {
    /** Punti di ogni gesto: «mi piace» (il preferito) +2, ascolto completo +1 (al massimo 5 per brano), salto entro 30 s -0,5 (al massimo -1 per brano), «non mi piace» -1. */
    const val LIKE = 2.0
    const val COMPLETE = 1.0
    const val MAX_COMPLETE = 5
    const val SKIP = -0.5
    const val MAX_SKIP = -1.0
    const val DISLIKE = -1.0

    /**
     * Peso di un brano. Ascolti contati (tetto [MAX_COMPLETE]); un brano solo in cronologia vale 1, ma 0 se l'hai saltato;
     * il preferito +2; i salti tolgono 0,5 l'uno fino a -1. Il «non mi piace» non conta qui: esclude il brano e pesa sull'artista e sul genere.
     */
    fun weights(
        plays: Map<String, YtPlay>, history: List<Yt.Item>, favorites: List<Yt.Item>, skips: Map<String, Int> = emptyMap(),
    ): Map<String, Pair<Yt.Item, Double>> {
        val items = LinkedHashMap<String, Yt.Item>()
        val base = HashMap<String, Double>()
        for (p in plays.values) { items[p.item.videoId] = p.item; base[p.item.videoId] = minOf(p.count, MAX_COMPLETE) * COMPLETE }
        for (h in history) if (h.videoId !in items) { items[h.videoId] = h; base[h.videoId] = if ((skips[h.videoId] ?: 0) > 0) 0.0 else COMPLETE }
        for (f in favorites) { if (f.videoId !in items) items[f.videoId] = f; base.merge(f.videoId, LIKE, Double::plus) }
        for ((id, n) in skips) if (id in items) base.merge(id, maxOf(n * SKIP, MAX_SKIP), Double::plus)
        return items.mapValues { (id, it) -> it to base.getValue(id) }
    }

    /** I gusti già pronti: pesi dei brani (senza quelli non graditi) e filtro per escludere brani e artisti con punteggio negativo. */
    class Profile(val weights: Map<String, Pair<Yt.Item, Double>>, val bad: (Yt.Item) -> Boolean, val artistScore: Map<String, Double>, val dislikes: List<Yt.Item>)

    /**
     * Un artista è escluso quando il suo totale è sotto zero: i suoi brani (ascolti, preferiti, salti) più -1 per ogni «non mi piace».
     * Il brano non gradito è sempre escluso, qualunque sia il punteggio.
     */
    fun profile(
        plays: Map<String, YtPlay>, history: List<Yt.Item>, favorites: List<Yt.Item>, skips: Map<String, Int>, dislikes: List<Yt.Item>,
    ): Profile {
        val ids = dislikes.map { it.videoId }.toSet()
        val w = weights(plays, history, favorites, skips).filterKeys { it !in ids }
        val artist = HashMap<String, Double>()
        for ((item, wt) in w.values) for (a in artistNames(item.artist)) artist.merge(norm(a), wt, Double::plus)
        for (d in dislikes) for (a in artistNames(d.artist)) artist.merge(norm(a), DISLIKE, Double::plus)
        val bad = { i: Yt.Item ->
            i.videoId in ids || artistNames(i.artist).firstOrNull()?.let { (artist[norm(it)] ?: 0.0) < 0.0 } == true
        }
        return Profile(w, bad, artist, dislikes)
    }

    /** Punteggio dei generi: somma dei pesi dei brani di ogni genere, -1 per ogni «non mi piace». [genreOf] dà l'id del genere di un brano (o null). */
    fun genreScores(p: Profile, genreOf: (Yt.Item) -> String?): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((item, wt) in p.weights.values) genreOf(item)?.let { out.merge(it, wt, Double::plus) }
        for (d in p.dislikes) genreOf(d)?.let { out.merge(it, DISLIKE, Double::plus) }
        return out
    }

    /** Esclude i brani non graditi e gli artisti con punteggio negativo. */
    fun excluder(dislikes: List<Yt.Item>, w: Map<String, Pair<Yt.Item, Double>> = emptyMap()): (Yt.Item) -> Boolean {
        val ids = dislikes.map { it.videoId }.toSet()
        val artist = HashMap<String, Double>()
        for ((item, wt) in w.values) for (a in artistNames(item.artist)) artist.merge(norm(a), wt, Double::plus)
        for (d in dislikes) for (a in artistNames(d.artist)) artist.merge(norm(a), DISLIKE, Double::plus)
        return { i -> i.videoId in ids || artistNames(i.artist).firstOrNull()?.let { (artist[norm(it)] ?: 0.0) < 0.0 } == true }
    }

    /** I brani più ascoltati, dal più al meno. */
    fun topTracks(w: Map<String, Pair<Yt.Item, Double>>, n: Int): List<Yt.Item> =
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
    fun topArtists(w: Map<String, Pair<Yt.Item, Double>>, n: Int): List<Pair<String, Double>> {
        val names = HashMap<String, String>()
        val sum = HashMap<String, Double>()
        for ((item, wt) in w.values) for (a in artistNames(item.artist)) {
            val k = norm(a)
            if (k.isEmpty()) continue
            names.putIfAbsent(k, a)
            sum[k] = (sum[k] ?: 0.0) + wt
        }
        return sum.entries.filter { it.value > 0.0 }.sortedByDescending { it.value }.take(n).map { names.getValue(it.key) to it.value }
    }

    /** Un brano di partenza per ogni artista tra i primi: il suo brano più ascoltato (artisti diversi tra loro). */
    fun seeds(w: Map<String, Pair<Yt.Item, Double>>, n: Int): List<Yt.Item> {
        val used = HashSet<String>()
        val out = ArrayList<Yt.Item>()
        for (e in w.values.filter { it.second > 0.0 }.sortedByDescending { it.second }) {
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
