package com.marco.mlplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stato della ricerca YouTube Music: resta uguale cambiando sezione. */
object YtUi {
    var query by mutableStateOf("")
    var kind by mutableStateOf(Yt.Kind.Songs)
    /** Ultima ricerca eseguita (i risultati dei filtri si riferiscono a questa). */
    var searched by mutableStateOf("")
    var songs by mutableStateOf<List<Yt.Item>?>(null)
    var entries by mutableStateOf<Map<Yt.Kind, List<Yt.Entry>>>(emptyMap())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** Pagine aperte una dentro l'altra (artista -> album ...): l'ultima è quella visibile. */
    val trail = mutableStateListOf<Yt.Entry>()
    val details = mutableStateMapOf<String, YtApi.Detail>()
    val errors = mutableStateMapOf<String, String>()
    val open: Yt.Entry? get() = trail.lastOrNull()

    fun back() { if (trail.isNotEmpty()) trail.removeAt(trail.lastIndex) }

    fun reset() {
        query = ""; searched = ""; songs = null; entries = emptyMap(); error = null; trail.clear()
    }
}

/** Apre un artista, album o playlist sopra la pagina attuale (con il suo elenco di brani). */
fun openYtEntry(env: AppEnv, e: Yt.Entry) {
    YtUi.trail.add(e)
    if (YtUi.details.containsKey(e.browseId)) return
    YtUi.errors.remove(e.browseId)
    env.app.appScope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                if (e.kind == Yt.Kind.Artists) YtApi.artist(e.browseId)
                else YtApi.Detail(
                    // i brani degli album spesso non hanno la miniatura: uso quella dell'album
                    YtApi.playlistTracks(e.browseId).map { if (it.thumb.isBlank() && e.thumb.isNotBlank()) it.copy(thumb = e.thumb) else it },
                    emptyList(),
                )
            }
        }
        r.onSuccess { YtUi.details[e.browseId] = it }.onFailure {
            AppLog.e("YouTube", "Apertura di ${e.title} non riuscita", it)
            YtUi.errors[e.browseId] = "Non riesco ad aprire questo elemento (dettagli nel log)."
        }
    }
}

private fun fmt(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

/** Sezione "Cerca": brani, artisti, album e playlist di YouTube Music, suonati dal player di Sonara. */
@Composable
fun SearchPage(env: AppEnv, idle: @Composable (open: (Yt.Entry) -> Unit, search: (String) -> Unit) -> Unit = { _, _ -> }) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current

    BackHandler(enabled = YtUi.trail.isNotEmpty()) { YtUi.back() }

    /** Carica i risultati del filtro attuale per [q] (se non ci sono già); i primi si vedono subito, il resto arriva dopo. */
    fun load(q: String, kind: Yt.Kind) {
        val have = if (kind == Yt.Kind.Songs) YtUi.songs != null else YtUi.entries[kind] != null
        if (have && YtUi.searched == q) return
        YtUi.loading = true
        YtUi.error = null
        val scope = env.app.appScope
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (kind == Yt.Kind.Songs) {
                        Yt.search(q) { part -> scope.launch { if (YtUi.searched == q) { YtUi.songs = part; YtUi.loading = false } } }.size
                    } else {
                        Yt.searchEntries(q, kind) { part ->
                            scope.launch { if (YtUi.searched == q) { YtUi.entries = YtUi.entries + (kind to part); YtUi.loading = false } }
                        }.size
                    }
                }
            }
            YtUi.loading = false
            r.onSuccess { n -> if (n == 0 && YtUi.searched == q) YtUi.error = "Nessun risultato" }
                .onFailure {
                    AppLog.e("YouTube", "Ricerca non riuscita", it)
                    YtUi.error = "Ricerca non riuscita. Controlla la connessione (dettagli nel log)."
                }
        }
    }

    fun run() {
        val q = YtUi.query.trim()
        if (q.isEmpty()) return
        focus.clearFocus()
        env.app.ytStore.addSearch(q)
        if (YtUi.searched != q) {
            YtUi.searched = q; YtUi.songs = null; YtUi.entries = emptyMap(); YtUi.trail.clear()
        }
        load(q, YtUi.kind)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = YtUi.query,
            onValueChange = { YtUi.query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            singleLine = true,
            placeholder = { Text("Cerca su YouTube Music") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (YtUi.query.isNotEmpty()) {
                    IconButton(onClick = { YtUi.reset() }) { Icon(Icons.Filled.Clear, contentDescription = "Cancella") }
                }
            },
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
        )
        if (YtUi.searched.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in Yt.Kind.entries) {
                FilterChip(
                    selected = YtUi.kind == k,
                    onClick = {
                        YtUi.kind = k
                        YtUi.trail.clear()
                        if (YtUi.searched.isNotEmpty()) load(YtUi.searched, k)
                    },
                    label = { Text(k.label) },
                )
            }
        }

        val open = YtUi.open
        val songs = YtUi.songs
        val ents = YtUi.entries[YtUi.kind]
        when {
            open != null -> {
                val d = YtUi.details[open.browseId]
                CollectionState(
                    env, open.title, open.subtitle, open.thumb, d?.tracks, YtUi.errors[open.browseId],
                    onBack = { YtUi.back() },
                    shelves = d?.shelves.orEmpty(),
                    onOpen = { openYtEntry(env, it) },
                    circle = open.kind == Yt.Kind.Artists,
                )
            }
            YtUi.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            YtUi.searched.isEmpty() -> idle({ openYtEntry(env, it) }, { YtUi.query = it; run() })
            YtUi.kind == Yt.Kind.Songs && songs != null && songs.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                // il primo risultato in evidenza, come nella ricerca di YouTube Music
                item { TopSong(env, songs) }
                itemsIndexed(songs.drop(1), key = { _, it -> it.videoId }) { index, item ->
                    YtRow(env, item) {
                        env.feedback.tapScreen()
                        env.playFrom(songs.map { Yt.toTrack(it) }, index + 1)
                    }
                }
            }
            YtUi.kind != Yt.Kind.Songs && !ents.isNullOrEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                items(ents, key = { it.browseId }) { e -> EntryRow(e) { env.feedback.tapScreen(); openYtEntry(env, e) } }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(YtUi.error ?: "Nessun risultato", color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Scheda del primo brano trovato, con il pulsante per farlo partire. */
@Composable
private fun TopSong(env: AppEnv, songs: List<Yt.Item>) {
    val cs = MaterialTheme.colorScheme
    val top = songs.first()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHigh)
            .clickable { env.feedback.tapScreen(); env.playFrom(songs.map { Yt.toTrack(it) }, 0) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MusicNote, null, tint = cs.onPrimaryContainer)
            AsyncImage(top.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "TOP SONG", style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(cs.primaryContainer.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(top.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            Text(top.artist, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledIconButton(onClick = { env.feedback.tapScreen(); env.playFrom(songs.map { Yt.toTrack(it) }, 0) }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Riproduci")
        }
    }
}

/** Riga di un artista, album o playlist: tocca per aprirlo. */
@Composable
private fun EntryRow(e: Yt.Entry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = if (e.kind == Yt.Kind.Artists) CircleShape else RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(shape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(if (e.kind == Yt.Kind.Artists) Icons.Filled.Person else Icons.Filled.QueueMusic, null, tint = cs.onPrimaryContainer)
            if (e.thumb.isNotBlank()) AsyncImage(e.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.subtitle.isNotBlank()) Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Riga di un brano YouTube: tocca per suonare; il menu ⋮ ha coda e preferiti. */
@Composable
fun YtRow(env: AppEnv, item: Yt.Item, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val fav = env.app.ytStore.isFavorite(item.videoId)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = cs.onPrimaryContainer)
            AsyncImage(item.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (fav) Icon(Icons.Filled.Favorite, null, tint = cs.primary, modifier = Modifier.size(18.dp))
        if (item.durationSec > 0) {
            Spacer(Modifier.width(8.dp))
            Text(fmt(item.durationSec), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Altre azioni") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Avvia radio") }, onClick = { menu = false; env.playWithRadio(item) })
                DropdownMenuItem(text = { Text("Riproduci dopo") }, onClick = {
                    menu = false; env.engine.playNext(Yt.toTrack(item)); env.message = "Aggiunto: riproduci dopo"
                })
                DropdownMenuItem(text = { Text("Aggiungi alla coda") }, onClick = {
                    menu = false; env.engine.addToQueue(Yt.toTrack(item)); env.message = "Aggiunto alla coda"
                })
                DropdownMenuItem(
                    text = { Text(if (fav) "Togli dai preferiti" else "Aggiungi ai preferiti") },
                    leadingIcon = { Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
                    onClick = { menu = false; env.app.ytStore.toggleFavorite(item) },
                )
            }
        }
    }
}
