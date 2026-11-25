package com.marco.mlplayer.yt

import com.marco.mlplayer.util.AppLog

/**
 * Generatore di playlist (dall'idea di LastWave, GPL-3.0): crea un elenco di brani da una modalità,
 * partendo dai gusti locali di Sonara (ascolti, cronologia, preferiti, salti, «non mi piace») e da YouTube Music.
 * Funzioni da chiamare fuori dal thread principale.
 */
object YtGenerator {
    private const val TAG = "Generator"

    enum class Mode { Top, Recent, Radio, SimilarArtists, Genre, MyMix, Suggestions, NeverHeard, Library }

    enum class Period(val label: String, val days: Int) {
        All("Sempre", 0), Y1("12 mesi", 365), M6("6 mesi", 182), M3("3 mesi", 91), M1("1 mese", 30), W1("7 giorni", 7)
    }

    /** Tutto quello che serve a generare: i gusti e le scelte fatte nella pagina. */
    class Input(
        val plays: Map<String, YtPlay>,
        val history: List<Yt.Item>,
        val favorites: List<Yt.Item>,
        val skips: Map<String, Int>,
        val dislikes: List<Yt.Item>,
        val seed: Yt.Item? = null,
        val artist: String? = null,
        val genre: FavGenres.Genre? = null,
        val period: Period = Period.All,
        val count: Int = 26,
    )

    fun generate(mode: Mode, input: Input, onProgress: (String) -> Unit = {}): List<Yt.Item> {
        val profile = YtTaste.profile(input.plays, input.history, input.favorites, input.skips, input.dislikes)
        val w = profile.weights
        val bad = profile.bad
        val n = input.count.coerceIn(5, 100)
        val now = System.currentTimeMillis()
        val cutoff = if (input.period.days > 0) now - input.period.days * 86_400_000L else 0L
        // tutto quello che hai già ascoltato o salvato: per le scoperte
        val known = HashSet<String>().apply {
            addAll(w.keys); addAll(input.plays.keys); input.history.forEach { add(it.videoId) }
            input.favorites.forEach { add(it.videoId) }; input.dislikes.forEach { add(it.videoId) }
        }

        fun clean(list: List<Yt.Item>) = list.filter { !bad(it) }.distinctBy { it.videoId }

        val out: List<Yt.Item> = when (mode) {
            Mode.Top -> {
                val pool = if (cutoff > 0L) w.filter { (id, _) -> (input.plays[id]?.lastAt ?: 0L) >= cutoff } else w
                clean(YtTaste.topTracks(pool, n * 2))
            }
            Mode.Recent -> {
                val recent = input.history.filter { cutoff == 0L || (input.plays[it.videoId]?.lastAt ?: Long.MAX_VALUE) >= cutoff }
                clean(recent)
            }
            Mode.Radio -> {
                val seed = input.seed ?: return emptyList()
                onProgress("Radio da «${seed.title}»…")
                listOf(seed) + clean(YtApi.radio(seed.videoId).filter { it.videoId != seed.videoId })
            }
            Mode.SimilarArtists -> similarArtistsTracks(input.artist ?: return emptyList(), n, bad, onProgress)
            Mode.Genre -> {
                val g = input.genre ?: return emptyList()
                onProgress("Cerco «${g.label}»…")
                clean(Yt.search(g.query, 2)).shuffled()
            }
            Mode.MyMix -> {
                onProgress("Mescolo i tuoi gusti…")
                val lists = ArrayList<List<Yt.Item>>()
                lists += YtTaste.topTracks(w, n)
                lists += YtTaste.forgottenFavorites(input.favorites, input.plays, n)
                for (seed in YtTaste.seeds(w, 4)) runCatching { YtApi.radio(seed.videoId) }.onSuccess { lists += it }
                    .onFailure { AppLog.w(TAG, "Radio non riuscita: ${it.message}") }
                clean(interleave(lists.map { it.shuffled() }))
            }
            Mode.Suggestions -> {
                onProgress("Cerco scoperte per te…")
                clean(radiosFrom(w, 5).flatten().filter { it.videoId !in known })
            }
            Mode.NeverHeard -> {
                onProgress("Cerco brani nuovi…")
                val fresh = clean(interleave(radiosFrom(w, 6)).filter { it.videoId !in known })
                // al massimo due brani per artista, per non ripetersi
                val perArtist = HashMap<String, Int>()
                fresh.filter { perArtist.merge(YtTaste.norm(it.artist), 1, Int::plus)!! <= 2 }
            }
            Mode.Library -> {
                // «Riscopri il tuo passato»: brani che hai amato (più ascolti) e non senti da un po', nel periodo scelto
                val loved = input.plays.values.filter { it.count >= 2 && it.lastAt >= cutoff }.sortedBy { it.lastAt }.map { it.item }
                clean(loved + YtTaste.forgottenFavorites(input.favorites, input.plays, n) + input.history.reversed())
            }
        }
        AppLog.i(TAG, "$mode: ${out.size} brani")
        return out.take(n)
    }

    /** Le radio dei brani di partenza (un brano per artista). */
    private fun radiosFrom(w: Map<String, Pair<Yt.Item, Double>>, seeds: Int): List<List<Yt.Item>> =
        YtTaste.seeds(w, seeds).mapNotNull { s -> runCatching { YtApi.radio(s.videoId) }.getOrNull() }

    private fun similarArtistsTracks(name: String, n: Int, bad: (Yt.Item) -> Boolean, onProgress: (String) -> Unit): List<Yt.Item> {
        onProgress("Cerco artisti simili a $name…")
        val me = YtFeed.artistEntry(name) ?: return emptyList()
        val sims = runCatching { YtApi.similarArtists(me.browseId) }.getOrDefault(emptyList()).take(6)
        val lists = sims.map { a ->
            onProgress("Brani di ${a.title}…")
            runCatching { YtApi.artist(a.browseId).tracks.take(n / 3 + 3) }.getOrDefault(emptyList())
        }
        return interleave(lists).filter { !bad(it) }.distinctBy { it.videoId }
    }

    /** Un brano per volta da ogni elenco, a turno. */
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        var i = 0
        while (lists.any { i < it.size }) {
            for (l in lists) if (i < l.size) out.add(l[i])
            i++
        }
        return out
    }
}
