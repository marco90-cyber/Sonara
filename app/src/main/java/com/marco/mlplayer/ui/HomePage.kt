package com.marco.mlplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.Featured
import com.marco.mlplayer.yt.FavGenres
import com.marco.mlplayer.yt.FavArtists
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtApi
import com.marco.mlplayer.yt.YtFeed
import com.marco.mlplayer.yt.YtGenres
import com.marco.mlplayer.yt.YtTaste
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stato della Home: resta uguale cambiando sezione. */
private object HomeUi {
    var shelves by mutableStateOf<List<YtApi.Shelf>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** Scaffali personali (dai tuoi ascolti) e relative radio; si rifanno al massimo ogni 6 ore. */
    var personal by mutableStateOf<List<YtApi.Shelf>>(emptyList())
    var radios by mutableStateOf<List<List<Yt.Item>>>(emptyList())
    /** Genere scelto nelle chip sotto la ricerca (null = Home) e i suoi scaffali. */
    var activeGenre by mutableStateOf<FavGenres.Genre?>(null)
    var genreChipShelves by mutableStateOf<List<YtApi.Shelf>?>(null)
    /** Mix quotidiano: uguale per tutta la giornata; si rifà se cambiano generi, artisti o gusti. */
    var mix by mutableStateOf<List<Yt.Item>>(emptyList())
    /** Mix 1, 2, 3 (ognuno con artisti e generi diversi) e «Scopri settimanale». */
    var mixes by mutableStateOf<List<ForYouMix>>(emptyList())
    var weekly by mutableStateOf<ForYouMix?>(null)
    var weeklyKey = ""
    var weeklyLoading = false
    /** «Novità per te»: uscite recenti dei tuoi artisti e di quelli simili. */
    var releases by mutableStateOf<List<YtApi.Card>>(emptyList())
    var releasesAt = 0L
    var mixKey = ""
    var mixLoading by mutableStateOf(false)
    var genres by mutableStateOf<List<Yt.Entry>>(emptyList())
    var featured by mutableStateOf<List<Yt.Entry>>(emptyList())
    /** Scaffali dei generi preferiti (scelti in Benvenuto/Impostazioni). */
    var genreShelves by mutableStateOf<List<YtApi.Shelf>>(emptyList())
    var artistShelves by mutableStateOf<List<YtApi.Shelf>>(emptyList())
    var artistKey = ""
    var genreKey = ""
    var genreAt = 0L
    var explore by mutableStateOf<List<YtApi.Shelf>>(emptyList())
    var exploreAt = 0L
    var personalAt = 0L
    var version = 0
    var personalLoading = false
}

/** Dopo un cambio di paese la Home si rifà da capo: i consigliati sono quelli del nuovo paese. */
fun resetHomeFeed() {
    HomeUi.shelves = null
    HomeUi.activeGenre = null
    HomeUi.genreChipShelves = null
    HomeUi.mixKey = ""
    HomeUi.genres = emptyList()
    HomeUi.explore = emptyList()
    HomeUi.exploreAt = 0L
    HomeUi.personalAt = 0L
    HomeUi.genreKey = ""
    HomeUi.artistKey = ""
}

/** Home e ricerca nella stessa pagina: barra di ricerca in alto, sotto i suggerimenti di YouTube Music. */
@Composable
fun HomePage(env: AppEnv) {
    Box(Modifier.fillMaxSize().softBackground(MaterialTheme.colorScheme)) {
        SearchPage(env) { open, search -> HomeContent(env, open, search) }
    }
}

/** Ricerche recenti, ascoltati di recente e suggerimenti (personalizzati se hai fatto l'accesso). */
@Composable
private fun HomeContent(env: AppEnv, open: (Yt.Entry) -> Unit, search: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme

    fun load() {
        HomeUi.loading = true
        HomeUi.error = null
        env.app.appScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { YtApi.home() }
            }
            HomeUi.loading = false
            r.onSuccess {
                HomeUi.shelves = it
                if (it.isEmpty()) HomeUi.error = "YouTube Music non ha proposto nulla"
            }.onFailure {
                AppLog.e("YouTube", "Caricamento home non riuscito", it)
                HomeUi.error = "Non riesco a caricare i suggerimenti. Controlla la connessione (dettagli nel log)."
            }
        }
    }
    fun loadGenres() {
        val st = env.app.ytStore
        val prof = YtTaste.profile(st.plays, st.history, st.favorites, st.skips, st.dislikes)
        val scores = YtTaste.genreScores(prof) { YtGenres.genreOf(it, st) }
        // i generi scelti da te, tranne quelli col punteggio negativo; in più i 2 con più punti che non hai scelto (almeno 3)
        val picked = FavGenres.parse(env.settings.favGenres).filter { (scores[it.id] ?: 0.0) >= 0.0 }
        val extra = FavGenres.ALL.filter { g -> picked.none { it.id == g.id } && (scores[g.id] ?: 0.0) >= 3.0 }
            .sortedByDescending { scores[it.id] }.take(2)
        val all = picked + extra
        if (all.isEmpty()) { HomeUi.genreShelves = emptyList(); HomeUi.genreKey = ""; return }
        // pochi ascolti: i generi guidano la Home (fino a 6 scaffali); con più ascolti ne bastano 2. In cima quelli con più punti, a pari punti a rotazione ogni giorno
        val n = if (st.plays.size < 15) 6 else 2
        val day = System.currentTimeMillis() / 86_400_000L
        val use = all.shuffled(java.util.Random(day)).sortedByDescending { scores[it.id] ?: 0.0 }.take(n)
        val key = use.joinToString(",") { it.id }
        if (key == HomeUi.genreKey && System.currentTimeMillis() - HomeUi.genreAt < 6 * 3600_000L) return
        HomeUi.genreKey = key
        HomeUi.genreAt = System.currentTimeMillis()
        HomeUi.genreShelves = emptyList()
        env.app.appScope.launch {
            withContext(Dispatchers.IO) {
                for (g in use) {
                    runCatching { Yt.searchEntries(g.query, Yt.Kind.Playlists, 1).take(12) }
                        .onSuccess { l ->
                            if (key == HomeUi.genreKey && l.isNotEmpty())
                                HomeUi.genreShelves = HomeUi.genreShelves + YtApi.Shelf("Per te · ${g.label}", l.map { YtApi.Card(null, it) })
                        }
                        .onFailure { AppLog.w("HomeFeed", "Genere «${g.label}» non riuscito: ${it.message}") }
                }
                if (key == HomeUi.genreKey && HomeUi.genreShelves.isEmpty()) HomeUi.genreKey = ""   // riprovo alla prossima apertura
            }
        }
    }
    fun loadPersonal(force: Boolean) {
        if (HomeUi.personalLoading) return
        if (!force && System.currentTimeMillis() - HomeUi.personalAt < 6 * 3600_000L) return
        val st = env.app.ytStore
        val prof = YtTaste.profile(st.plays, st.history, st.favorites, st.skips, st.dislikes)
        val bad = prof.bad
        val w = prof.weights.filterValues { !bad(it.first) }
        if (w.isEmpty()) return
        HomeUi.personalLoading = true
        HomeUi.version = st.tasteVersion
        if (force) HomeUi.personal = emptyList()
        env.app.appScope.launch {
            // il genere degli artisti che ascolti di più (da Deezer, ricordato): serve ai punteggi dei generi
            withContext(Dispatchers.IO) { runCatching { YtGenres.fetchMissing(st, YtTaste.topArtists(w, 15).map { it.first }) } }
            loadGenres()
            val r = withContext(Dispatchers.IO) {
                runCatching { YtFeed.build(w, bad) { sh -> HomeUi.personal = (HomeUi.personal.filter { it.title != sh.title } + sh) } }
            }
            HomeUi.personalLoading = false
            r.onSuccess { HomeUi.radios = it.radios; HomeUi.personalAt = System.currentTimeMillis() }
                .onFailure { AppLog.e("YouTube", "Suggerimenti personali non riusciti", it) }
        }
    }
    fun loadFavArtists() {
        val mine = FavArtists.parse(env.settings.favArtists)
        if (mine.isEmpty()) { HomeUi.artistShelves = emptyList(); HomeUi.artistKey = ""; return }
        val key = mine.joinToString(",") { it.browseId }
        if (key == HomeUi.artistKey) return
        HomeUi.artistKey = key
        HomeUi.artistShelves = listOf(YtApi.Shelf("I tuoi artisti", mine.map { YtApi.Card(null, it) }))
        env.app.appScope.launch {
            val sim = withContext(Dispatchers.IO) {
                val have = mine.map { it.browseId }.toSet()
                val out = LinkedHashMap<String, Yt.Entry>()
                for (a in mine.shuffled().take(4)) runCatching { YtApi.similarArtists(a.browseId) }
                    .onSuccess { l -> l.forEach { if (it.browseId !in have) out.putIfAbsent(it.browseId, it) } }
                    .onFailure { AppLog.w("HomeFeed", "Simili a «${a.title}» non riusciti: ${it.message}") }
                out.values.take(20).toList()
            }
            if (key == HomeUi.artistKey && sim.isNotEmpty())
                HomeUi.artistShelves = HomeUi.artistShelves + YtApi.Shelf("Simili ai tuoi artisti", sim.map { YtApi.Card(null, it) })
        }
    }
    fun loadExplore() {
        if (System.currentTimeMillis() - HomeUi.exploreAt < 6 * 3600_000L) return
        HomeUi.exploreAt = System.currentTimeMillis()
        // ogni sezione è indipendente: se una non risponde, le altre restano
        env.app.appScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { YtApi.moodsAndGenres() }.onSuccess { HomeUi.genres = it }.onFailure { AppLog.w("HomeFeed", "Generi non riusciti: ${it.message}") }
                val extra = ArrayList<YtApi.Shelf>()
                runCatching { YtApi.newReleases() }.onSuccess { s -> s?.let { extra.add(it) } }.onFailure { AppLog.w("HomeFeed", "Nuove uscite non riuscite: ${it.message}") }
                HomeUi.explore = extra.distinctBy { it.title }
                runCatching { YtApi.charts() }.onSuccess { extra.addAll(it) }.onFailure { AppLog.w("HomeFeed", "Classifiche non riuscite: ${it.message}") }
                HomeUi.explore = extra.distinctBy { it.title }
                if (HomeUi.genres.isEmpty() && extra.isEmpty()) HomeUi.exploreAt = 0L   // riprovo alla prossima apertura
            }
        }
    }
    fun pickGenre(g: FavGenres.Genre?) {
        HomeUi.activeGenre = g
        HomeUi.genreChipShelves = null
        if (g == null) return
        env.app.appScope.launch {
            val out = withContext(Dispatchers.IO) {
                val l = ArrayList<YtApi.Shelf>()
                runCatching { Yt.search(g.query, 1).take(15) }.onSuccess { YtGenres.noteOrigin(it, g.id); if (it.isNotEmpty()) l.add(YtApi.Shelf("Brani · ${g.label}", it.map { i -> YtApi.Card(i, null) })) }
                    .onFailure { AppLog.w("HomeFeed", "Brani di «${g.label}» non riusciti: ${it.message}") }
                runCatching { Yt.searchEntries(g.query, Yt.Kind.Playlists, 1).take(12) }.onSuccess { if (it.isNotEmpty()) l.add(YtApi.Shelf("Playlist · ${g.label}", it.map { e -> YtApi.Card(null, e) })) }
                    .onFailure { AppLog.w("HomeFeed", "Playlist di «${g.label}» non riuscite: ${it.message}") }
                runCatching { Yt.searchEntries(g.label, Yt.Kind.Artists, 1).take(12) }.onSuccess { if (it.isNotEmpty()) l.add(YtApi.Shelf("Artisti · ${g.label}", it.map { e -> YtApi.Card(null, e) })) }
                    .onFailure { AppLog.w("HomeFeed", "Artisti di «${g.label}» non riusciti: ${it.message}") }
                l.toList()
            }
            if (HomeUi.activeGenre == g) HomeUi.genreChipShelves = out
        }
    }
    /** Il mix: radio dai tuoi ascolti, brani degli artisti preferiti e dei generi scelti, a turno e senza doppioni. */
    fun loadMix() {
        val st = env.app.ytStore
        val day = System.currentTimeMillis() / 86_400_000L
        val key = "$day|${env.settings.favGenres}|${env.settings.favArtists}|${HomeUi.radios.sumOf { it.size }}|${st.tasteVersion}"
        if (key == HomeUi.mixKey || HomeUi.mixLoading) return
        HomeUi.mixLoading = true
        val bad = YtTaste.profile(st.plays, st.history, st.favorites, st.skips, st.dislikes).bad
        val genres = FavGenres.parse(env.settings.favGenres).shuffled(java.util.Random(day)).take(3)
        val artists = FavArtists.parse(env.settings.favArtists).shuffled(java.util.Random(day)).take(4)
        val radios = HomeUi.radios
        env.app.appScope.launch {
            // (nome, brani): il nome serve a descrivere ogni mix
            val pools = withContext(Dispatchers.IO) {
                val l = ArrayList<Pair<String, List<Yt.Item>>>()
                for (r in radios) {
                    val label = r.firstOrNull()?.artist?.let { YtTaste.artistNames(it).firstOrNull() ?: it }.orEmpty()
                    l.add(label to r.shuffled(java.util.Random(day)).take(20))
                }
                for (a in artists) runCatching { YtApi.artist(a.browseId).tracks.take(15) }.onSuccess { l.add(a.title to it) }
                    .onFailure { AppLog.w("HomeFeed", "Mix: «${a.title}» non riuscito: ${it.message}") }
                for (g in genres) runCatching { Yt.search(g.query, 1).take(15) }.onSuccess { YtGenres.noteOrigin(it, g.id); l.add(g.label to it) }
                    .onFailure { AppLog.w("HomeFeed", "Mix: «${g.label}» non riuscito: ${it.message}") }
                l.filter { it.second.isNotEmpty() }
            }
            fun blend(ps: List<Pair<String, List<Yt.Item>>>, max: Int): List<Yt.Item> {
                val seen = HashSet<String>()
                val out = ArrayList<Yt.Item>()
                for (i in 0 until (ps.maxOfOrNull { it.second.size } ?: 0)) for (p in ps) p.second.getOrNull(i)?.let { if (!bad(it) && seen.add(it.videoId)) out.add(it) }
                return out.take(max)
            }
            val out = blend(pools, 40)
            HomeUi.mix = out
            // più mix al giorno: i gruppi di brani vanno a turno nei mix, così ognuno ha artisti e generi suoi
            val k = if (pools.size >= 6) 3 else if (pools.size >= 4) 2 else 1
            HomeUi.mixes = if (k < 2) emptyList() else (0 until k).mapNotNull { j ->
                val ps = pools.filterIndexed { idx, _ -> idx % k == j }
                val tracks = blend(ps, 30)
                if (tracks.isEmpty()) null
                else {
                    val names = ps.map { it.first }.filter { it.isNotBlank() }.distinct()
                    ForYouMix("Mix ${j + 1}", names.take(3).joinToString(", ") + if (names.size > 3) " e altri" else "", tracks)
                }
            }
            HomeUi.mixLoading = false
            // senza nulla in mano riprovo alla prossima apertura
            if (out.isNotEmpty()) HomeUi.mixKey = key
        }
    }

    /** «Scopri settimanale»: brani nuovi per te (radio e artisti simili) che non hai ancora ascoltato; cambia ogni settimana. */
    fun loadWeekly() {
        val radios = HomeUi.radios
        if (radios.isEmpty() || HomeUi.weeklyLoading) return
        val week = System.currentTimeMillis() / (7 * 86_400_000L)
        val key = "$week|${radios.size}"
        if (key == HomeUi.weeklyKey) return
        HomeUi.weeklyLoading = true
        val st = env.app.ytStore
        val prof = YtTaste.profile(st.plays, st.history, st.favorites, st.skips, st.dislikes)
        val known = prof.weights.keys + st.history.map { it.videoId } + st.dislikes.map { it.videoId }
        val similar = HomeUi.personal.firstOrNull { it.title == "Artisti simili" }?.cards?.mapNotNull { it.entry }.orEmpty().take(4)
        env.app.appScope.launch {
            val pools = withContext(Dispatchers.IO) {
                val l = ArrayList<List<Yt.Item>>()
                l.addAll(radios.map { r -> r.filter { it.videoId !in known } })
                for (a in similar) runCatching { YtApi.artist(a.browseId).tracks.take(8).filter { it.videoId !in known } }
                    .onSuccess { l.add(it) }.onFailure { AppLog.w("HomeFeed", "Settimanale: «${a.title}» non riuscito: ${it.message}") }
                l
            }
            val rnd = java.util.Random(week)
            val seen = HashSet<String>()
            val out = ArrayList<Yt.Item>()
            val shuffled = pools.map { it.shuffled(rnd) }
            for (i in 0 until (shuffled.maxOfOrNull { it.size } ?: 0)) for (p in shuffled) p.getOrNull(i)?.let { if (!prof.bad(it) && seen.add(it.videoId)) out.add(it) }
            val tracks = out.take(30)
            HomeUi.weekly = if (tracks.isEmpty()) null else ForYouMix("Scopri settimanale", "${tracks.size} brani nuovi, cambia ogni settimana", tracks)
            HomeUi.weeklyLoading = false
            if (tracks.isNotEmpty()) HomeUi.weeklyKey = key
        }
    }

    /** «Novità per te»: tra le nuove uscite di YouTube Music, quelle dei tuoi artisti e degli artisti simili. */
    fun loadReleases() {
        if (System.currentTimeMillis() - HomeUi.releasesAt < 6 * 3600_000L) return
        val st = env.app.ytStore
        val prof = YtTaste.profile(st.plays, st.history, st.favorites, st.skips, st.dislikes)
        val mine = YtTaste.topArtists(prof.weights, 15).map { it.first } +
            FavArtists.parse(env.settings.favArtists).map { it.title } +
            HomeUi.personal.flatMap { sh -> if (sh.title == "Artisti simili") sh.cards.mapNotNull { it.entry?.title } else emptyList() }
        val names = mine.map { YtTaste.norm(it) }.filter { it.length > 1 }.toSet()
        if (names.isEmpty()) return
        HomeUi.releasesAt = System.currentTimeMillis()
        env.app.appScope.launch {
            val shelf = withContext(Dispatchers.IO) { runCatching { YtApi.newReleases() }.getOrNull() }
            val cards = shelf?.cards.orEmpty().filter { c ->
                val sub = YtTaste.norm(c.subtitle)
                names.any { n -> sub.contains(n) }
            }
            HomeUi.releases = cards
            if (shelf == null) HomeUi.releasesAt = 0L   // riprovo alla prossima apertura
        }
    }
    // classifiche di Spotify e Deezer del paese scelto (in cache per 6 ore)
    LaunchedEffect(YtApi.country) {
        val c = YtApi.country
        withContext(Dispatchers.IO) { Featured.load(c) { part -> HomeUi.featured = part } }
            .let { HomeUi.featured = it }
    }
    LaunchedEffect(env.settings.favGenres, env.app.ytStore.tasteVersion) { loadGenres() }
    LaunchedEffect(env.settings.favArtists) { loadFavArtists() }
    LaunchedEffect(env.settings.favGenres, env.settings.favArtists, HomeUi.radios, env.app.ytStore.tasteVersion) { loadMix(); loadWeekly(); loadReleases() }
    LaunchedEffect(Unit) {
        loadExplore()
        if (!HomeUi.loading && HomeUi.shelves == null) load()
        // dopo un «mi piace» o «non mi piace» rifaccio i suggerimenti personali
        loadPersonal(env.app.ytStore.tasteVersion != HomeUi.version)
    }

    var removing by remember { mutableStateOf<Yt.Entry?>(null) }
    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Rimuovere la playlist?") },
            text = { Text("«${r.title}» non sarà più tra le importate. I brani scaricati restano.") },
            confirmButton = {
                TextButton(onClick = {
                    env.app.ytImports.remove(r.browseId)
                    YtUi.details.remove(r.browseId)
                    removing = null
                }) { Text("Rimuovi") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Annulla") } },
        )
    }

    val shelves = HomeUi.shelves
    val imported = env.app.ytImports.all
    val recent = env.app.ytStore.history.take(15)
    val past = env.app.ytStore.searches.take(8)
    val forgotten = remember(env.app.ytStore.favorites, env.app.ytStore.plays) {
        YtTaste.forgottenFavorites(env.app.ytStore.favorites, env.app.ytStore.plays, 15)
    }
    when {
        shelves == null && HomeUi.loading && recent.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        shelves == null && HomeUi.error != null && recent.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(HomeUi.error!!, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            Button(onClick = { load() }) { Text("Riprova") }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            if (past.isNotEmpty()) item(key = "ricerche") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    items(past) { q -> Pill(q, false, Icons.Filled.History) { search(q) } }
                }
            }
            item(key = "chips") {
                val picked = FavGenres.parse(env.settings.favGenres)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    item(key = "home-pill") { Pill("Home", HomeUi.activeGenre == null, Icons.Filled.Home) { pickGenre(null) } }
                    items(picked, key = { it.id }) { g ->
                        Pill(g.label, HomeUi.activeGenre == g, null) { pickGenre(if (HomeUi.activeGenre == g) null else g) }
                    }
                    if (picked.isEmpty()) item(key = "pick-genres") {
                        Pill("Scegli i generi", false, Icons.Filled.Add) { env.settingsNav.push(GenresScreen); env.tab = Tab.Settings }
                    }
                }
            }
            if (HomeUi.activeGenre != null) {
                val cs2 = HomeUi.genreChipShelves
                if (cs2 == null) item(key = "chip-loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                else if (cs2.isEmpty()) item(key = "chip-empty") { Text("Nessun suggerimento per questo genere", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                else items(cs2, key = { "c:" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
                return@LazyColumn
            }
            item(key = "mix") {
                MixBanner(env, HomeUi.mix, HomeUi.mixLoading) { env.settingsNav.push(GenresScreen); env.tab = Tab.Settings }
            }
            val forYou = HomeUi.mixes + listOfNotNull(HomeUi.weekly)
            if (forYou.isNotEmpty()) item(key = "foryou") { ForYouRow(env, forYou) }
            if (HomeUi.releases.isNotEmpty()) item(key = "releases") { ShelfRow("Novità per te", HomeUi.releases, env, open) }
            if (recent.isNotEmpty()) item(key = "recent") {
                ShelfRow("Ascoltati di recente", recent.map { YtApi.Card(it, null) }, env, open)
            }
            val wide = (HomeUi.genreShelves.mapNotNull { sh -> sh.cards.firstOrNull { it.entry?.kind == Yt.Kind.Playlists }?.entry } + HomeUi.featured.take(3))
                .distinctBy { it.browseId }.take(4)
            if (wide.isNotEmpty()) item(key = "recommended") { RecommendedCards(env, wide, open) }
            items(HomeUi.personal, key = { "p:" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
            items(HomeUi.artistShelves, key = { "a:" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
            items(HomeUi.genreShelves, key = { "g:" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
            if (forgotten.isNotEmpty()) item(key = "forgotten") {
                ShelfRow("Preferiti da riascoltare", forgotten.map { YtApi.Card(it, null) }, env, open)
            }
            if (HomeUi.featured.isNotEmpty()) item(key = "in-evidenza") {
                ShelfRow("In evidenza", HomeUi.featured.map { YtApi.Card(null, it) }, env, open)
            }
            if (imported.isNotEmpty()) item(key = "imported") {
                ShelfRow("Importate", imported.map { YtApi.Card(null, it.entry) }, env, open, onLongPress = { removing = it })
            }
            if (HomeUi.genres.isNotEmpty()) item(key = "generi") {
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Generi e atmosfere", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                        items(HomeUi.genres, key = { it.browseId }) { g -> Pill(g.title, false, null) { open(g) } }
                    }
                }
            }
            items(HomeUi.explore, key = { "x:" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
            items(shelves.orEmpty(), key = { "y:" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
            if (HomeUi.loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (shelves != null && HomeUi.error != null) item { Text(HomeUi.error!!, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            item {
                OutlinedButton(onClick = { load(); loadPersonal(true) }, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Filled.Refresh, null); Text("Aggiorna suggerimenti")
                }
            }
        }
    }
}

/** Etichetta tonda senza bordo: sfondo tenue, o colorato se selezionata. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(text: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) cs.primary else cs.surfaceVariant.copy(alpha = 0.6f))
            .combinedClickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = if (selected) cs.onPrimary else cs.onSurfaceVariant); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) cs.onPrimary else cs.onSurface)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfRow(title: String, cards: List<YtApi.Card>, env: AppEnv, onOpen: (Yt.Entry) -> Unit, onLongPress: ((Yt.Entry) -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { (it.song?.videoId ?: it.entry?.browseId.orEmpty()) + it.title }) { c ->
                val artist = c.entry?.kind == Yt.Kind.Artists
                Column(
                    Modifier.width(140.dp).combinedClickable(
                        onLongClick = onLongPress?.let { f -> { c.entry?.let(f) } },
                        onClick = {
                            env.feedback.tapScreen()
                            val s = c.song
                            if (s != null) env.playWithRadio(s) else c.entry?.let(onOpen)
                        },
                    ),
                ) {
                    Box(
                        Modifier.size(140.dp).clip(if (artist) CircleShape else RoundedCornerShape(18.dp)).background(cs.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when { c.song != null -> Icons.Filled.MusicNote; artist -> Icons.Filled.Person; else -> Icons.Filled.QueueMusic },
                            null, tint = cs.onPrimaryContainer,
                        )
                        if (c.thumb.isNotBlank()) AsyncImage(c.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (!artist) Box(
                            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(32.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(c.title, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (c.subtitle.isNotBlank()) Text(c.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
