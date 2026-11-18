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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
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
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtApi
import com.marco.mlplayer.yt.YtFeed
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
    var chips by mutableStateOf<List<YtApi.Chip>>(emptyList())
    var activeChip by mutableStateOf<YtApi.Chip?>(null)
    var chipShelves by mutableStateOf<List<YtApi.Shelf>?>(null)
    var genres by mutableStateOf<List<Yt.Entry>>(emptyList())
    var explore by mutableStateOf<List<YtApi.Shelf>>(emptyList())
    var exploreAt = 0L
    var personalAt = 0L
    var personalLoading = false
}

/** Home e ricerca nella stessa pagina: barra di ricerca in alto, sotto i suggerimenti di YouTube Music. */
@Composable
fun HomePage(env: AppEnv) {
    SearchPage(env) { open, search -> HomeContent(env, open, search) }
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
                runCatching { YtApi.homeFeed().first }.onSuccess { if (it.isNotEmpty()) HomeUi.chips = it }
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
    fun loadPersonal(force: Boolean) {
        if (HomeUi.personalLoading) return
        if (!force && System.currentTimeMillis() - HomeUi.personalAt < 6 * 3600_000L) return
        val st = env.app.ytStore
        val w = YtTaste.weights(st.plays, st.history, st.favorites)
        if (w.isEmpty()) return
        HomeUi.personalLoading = true
        env.app.appScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { YtFeed.build(w) { sh -> HomeUi.personal = (HomeUi.personal.filter { it.title != sh.title } + sh) } }
            }
            HomeUi.personalLoading = false
            r.onSuccess { HomeUi.radios = it.radios; HomeUi.personalAt = System.currentTimeMillis() }
                .onFailure { AppLog.e("YouTube", "Suggerimenti personali non riusciti", it) }
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
    fun pickChip(c: YtApi.Chip?) {
        HomeUi.activeChip = c
        HomeUi.chipShelves = null
        if (c == null) return
        env.app.appScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YtApi.homeFeed(c.params).second } }
            if (HomeUi.activeChip == c) {
                r.onFailure { AppLog.w("HomeFeed", "Filtro «${c.title}» non riuscito: ${it.message}") }
                HomeUi.chipShelves = r.getOrDefault(emptyList())
            }
        }
    }
    LaunchedEffect(Unit) {
        loadExplore()
        if (!HomeUi.loading && HomeUi.shelves == null) load()
        loadPersonal(false)
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
            if (past.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(past) { q ->
                        AssistChip(onClick = { search(q) }, label = { Text(q, maxLines = 1) }, leadingIcon = { Icon(Icons.Filled.History, null) })
                    }
                }
            }
            if (HomeUi.chips.isNotEmpty()) item(key = "chips") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                    items(HomeUi.chips, key = { it.title }) { c ->
                        FilterChip(selected = HomeUi.activeChip == c, onClick = { pickChip(if (HomeUi.activeChip == c) null else c) }, label = { Text(c.title, maxLines = 1) })
                    }
                }
            }
            if (HomeUi.activeChip != null) {
                val cs2 = HomeUi.chipShelves
                if (cs2 == null) item(key = "chip-loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                else if (cs2.isEmpty()) item(key = "chip-empty") { Text("Nessun suggerimento per questo filtro", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                else items(cs2, key = { "c:" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
                return@LazyColumn
            }
            if (HomeUi.radios.isNotEmpty()) item(key = "radio-per-te") {
                Button(onClick = {
                    env.feedback.tapScreen()
                    // un brano a turno da ciascuna radio, senza doppioni
                    val lists = HomeUi.radios
                    val mixed = ArrayList<Yt.Item>()
                    val seen = HashSet<String>()
                    for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) l.getOrNull(i)?.let { if (seen.add(it.videoId)) mixed.add(it) }
                    if (mixed.isNotEmpty()) env.playFrom(mixed.map { Yt.toTrack(it) }, 0)
                }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Radio per te") }
            }
            if (recent.isNotEmpty()) item(key = "recent") {
                ShelfRow("Ascoltati di recente", recent.map { YtApi.Card(it, null) }, env, open)
            }
            items(HomeUi.personal, key = { "p:" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
            if (forgotten.isNotEmpty()) item(key = "forgotten") {
                ShelfRow("Preferiti da riascoltare", forgotten.map { YtApi.Card(it, null) }, env, open)
            }
            if (imported.isNotEmpty()) item(key = "imported") {
                ShelfRow("Importate", imported.map { YtApi.Card(null, it.entry) }, env, open, onLongPress = { removing = it })
            }
            if (HomeUi.genres.isNotEmpty()) item(key = "generi") {
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Generi e atmosfere", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                        items(HomeUi.genres, key = { it.browseId }) { g -> AssistChip(onClick = { open(g) }, label = { Text(g.title, maxLines = 1) }) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfRow(title: String, cards: List<YtApi.Card>, env: AppEnv, onOpen: (Yt.Entry) -> Unit, onLongPress: ((Yt.Entry) -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { (it.song?.videoId ?: it.entry?.browseId.orEmpty()) + it.title }) { c ->
                val artist = c.entry?.kind == Yt.Kind.Artists
                Column(
                    Modifier.width(140.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(
                        onLongClick = onLongPress?.let { f -> { c.entry?.let(f) } },
                        onClick = {
                            env.feedback.tapScreen()
                            val s = c.song
                            if (s != null) env.playWithRadio(s) else c.entry?.let(onOpen)
                        },
                    ),
                ) {
                    Box(
                        Modifier.size(140.dp).clip(if (artist) CircleShape else RoundedCornerShape(14.dp)).background(cs.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when { c.song != null -> Icons.Filled.MusicNote; artist -> Icons.Filled.Person; else -> Icons.Filled.QueueMusic },
                            null, tint = cs.onPrimaryContainer,
                        )
                        if (c.thumb.isNotBlank()) AsyncImage(c.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.subtitle.isNotBlank()) Text(c.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
