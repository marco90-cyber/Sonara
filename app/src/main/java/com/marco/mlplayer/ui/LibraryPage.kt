package com.marco.mlplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtApi
import com.marco.mlplayer.yt.YtDownloads

/** Stato della sezione Libreria: resta uguale cambiando sezione. */
private object LibUi {
    var page by mutableIntStateOf(0)
    /** Pagine aperte dai Salvati (artista -> album ...). */
    val trail = androidx.compose.runtime.mutableStateListOf<Yt.Entry>()
    /** Gruppo di download aperto in Scaricati. */
    var dlOpen by mutableStateOf<String?>(null)
    /** Salvati: filtro (0 tutti, 1 artisti, 2 playlist, 3 album) e ordine (0 recenti, 1 A-Z). */
    var savedKind by mutableIntStateOf(0)
    var savedSort by mutableIntStateOf(0)
    var savedQuery by mutableStateOf("")
    /** Playlist tue aperta, e ricerca/ordine dei Preferiti (0 recenti, 1 A-Z, 2 artista). */
    var myOpen by mutableStateOf<String?>(null)
    var favSort by mutableIntStateOf(0)
    var favQuery by mutableStateOf("")
}

/** Raccolta di Sonara: preferiti, playlist tue, salvati, scaricati, cronologia e riepilogo degli ascolti. */
@Composable
fun LibraryPage(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val store = env.app.ytStore
    BackHandler(enabled = LibUi.page == 4 && LibUi.dlOpen != null) { LibUi.dlOpen = null }
    BackHandler(enabled = LibUi.page == 5 && LibUi.myOpen != null) { LibUi.myOpen = null }
    BackHandler(enabled = LibUi.page == 3 && LibUi.trail.isNotEmpty()) { LibUi.trail.removeAt(LibUi.trail.lastIndex) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Raccolta", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = LibUi.page == 0, onClick = { LibUi.page = 0 }, label = { Text("Preferiti · ${store.favorites.size}") })
            FilterChip(selected = LibUi.page == 5, onClick = { LibUi.page = 5 }, label = { Text("Playlist · ${store.myPlaylists.size}") })
            FilterChip(selected = LibUi.page == 3, onClick = { LibUi.page = 3 }, label = { Text("Salvati · ${store.saved.size}") })
            FilterChip(selected = LibUi.page == 4, onClick = { LibUi.page = 4 }, label = { Text("Scaricati · ${YtDownloads.entries.map { it.item.videoId }.distinct().size}") })
            FilterChip(selected = LibUi.page == 1, onClick = { LibUi.page = 1 }, label = { Text("Cronologia") })
            FilterChip(selected = LibUi.page == 6, onClick = { LibUi.page = 6 }, label = { Text("Riepilogo") })
        }
        if (LibUi.page == 5) {
            PlaylistsSection(env)
            return@Column
        }
        if (LibUi.page == 6) {
            SummarySection(env)
            return@Column
        }
        if (LibUi.page == 3) {
            SavedSection(env)
            return@Column
        }
        if (LibUi.page == 4) {
            DownloadsSection(env)
            return@Column
        }
        val items = when (LibUi.page) {
            0 -> store.favorites
            4 -> YtDownloads.entries.map { it.item }
            else -> store.history
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (LibUi.page) {
                        0 -> "Nessun preferito: usa il menu ⋮ di un brano"
                        4 -> "Nessun brano scaricato: usa Scarica nel menu ⋮ di un brano"
                        else -> "Nessun brano ascoltato"
                    },
                    color = cs.onSurfaceVariant,
                )
            }
        } else {
            var list = items
            if (LibUi.page == 0) {
                // Preferiti: ricerca e ordine
                val norm = com.marco.mlplayer.yt.YtTaste::norm
                androidx.compose.material3.OutlinedTextField(
                    value = LibUi.favQuery, onValueChange = { LibUi.favQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Cerca nei preferiti") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Recenti", "A-Z", "Artista").forEachIndexed { i, l ->
                        FilterChip(selected = LibUi.favSort == i, onClick = { LibUi.favSort = i }, label = { Text(l) })
                    }
                }
                val q = norm(LibUi.favQuery)
                if (q.isNotEmpty()) list = list.filter { norm(it.title).contains(q) || norm(it.artist).contains(q) }
                list = when (LibUi.favSort) {
                    1 -> list.sortedBy { norm(it.title) }
                    2 -> list.sortedWith(compareBy({ norm(it.artist) }, { norm(it.title) }))
                    else -> list
                }
            }
            TrackList(env, list, showClear = LibUi.page == 1)
        }
    }
}

/** Pulsanti Riproduci/Casuale e l'elenco di brani. */
@Composable
internal fun TrackList(env: AppEnv, items: List<Yt.Item>, showClear: Boolean = false, onRemove: ((Yt.Item) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { env.playFrom(items.map { Yt.toTrack(it) }, 0) }) {
            Icon(Icons.Filled.PlayArrow, null); Text("Riproduci")
        }
        FilledTonalButton(onClick = {
            env.engine.playQueue(items.map { Yt.toTrack(it) }.shuffled(), 0); env.openNowPlaying()
        }) { Icon(Icons.Filled.Shuffle, null); Text("Casuale") }
        FilledTonalIconButton(onClick = { env.durationCheck = items }) {
            Icon(Icons.Filled.Timer, contentDescription = "Controlla durate")
        }
        if (showClear) OutlinedButton(onClick = { env.app.ytStore.clearHistory() }) { Text("Svuota") }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { i, it -> "${i}_${it.videoId}" }) { index, item ->
            YtRow(env, item, onRemove = onRemove?.let { r -> { r(item) } }) {
                env.feedback.tapScreen()
                env.playFrom(items.map { Yt.toTrack(it) }, index)
            }
        }
    }
}

/** Pagina di una raccolta (playlist, album, artista): indietro, copertina, titolo, pulsanti e lista dei brani. */
@Composable
internal fun CollectionState(
    env: AppEnv,
    title: String,
    subtitle: String,
    thumb: String,
    tracks: List<Yt.Item>?,
    error: String?,
    onBack: () -> Unit,
    shelves: List<YtApi.Shelf> = emptyList(),
    onOpen: (Yt.Entry) -> Unit = {},
    circle: Boolean = false,
    bookmark: Yt.Entry? = null,
    onRefresh: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    when {
        error != null -> Column(Modifier.fillMaxSize()) {
            BackRow(title, onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error, color = cs.onSurfaceVariant) }
        }
        tracks == null -> Column(Modifier.fillMaxSize()) {
            BackRow(title, onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        tracks.isEmpty() && shelves.isEmpty() -> Column(Modifier.fillMaxSize()) {
            BackRow(title, onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nessun brano", color = cs.onSurfaceVariant) }
        }
        else -> CollectionView(env, title, subtitle, thumb, tracks, onBack, shelves, onOpen, circle, bookmark, onRefresh)
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionView(
    env: AppEnv, title: String, subtitle: String, thumb: String, tracks: List<Yt.Item>, onBack: () -> Unit,
    shelves: List<YtApi.Shelf>, onOpen: (Yt.Entry) -> Unit, circle: Boolean, bookmark: Yt.Entry?, onRefresh: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val cover = thumb.ifBlank { tracks.firstOrNull()?.cover.orEmpty() }
    val totalSec = tracks.sumOf { it.durationSec }
    // pagina artista: in cima i brani più popolari, il resto a richiesta
    var allSongs by androidx.compose.runtime.remember(title) { androidx.compose.runtime.mutableStateOf(false) }
    val shownTracks = if (circle && !allSongs) tracks.take(5) else tracks
    val info = buildString {
        append("${tracks.size} brani")
        if (totalSec > 0) {
            val m = totalSec / 60
            append(" · ")
            append(if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min")
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(200.dp).clip(if (circle) CircleShape else RoundedCornerShape(20.dp)).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.QueueMusic, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(64.dp))
                    if (cover.isNotBlank()) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(info, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                // con il pulsante in più i tasti possono non starci in una riga: vanno a capo
                FlowRow(
                    Modifier.padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { env.playFrom(tracks.map { Yt.toTrack(it) }, 0) }) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Riproduci")
                    }
                    FilledTonalButton(onClick = { env.engine.playQueue(tracks.map { Yt.toTrack(it) }.shuffled(), 0); env.openNowPlaying() }) {
                        Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Casuale")
                    }
                    FilledTonalButton(onClick = { env.playRadioFrom(tracks, title) }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Filled.Radio, null); Spacer(Modifier.width(6.dp)); Text("Radio")
                    }
                if (bookmark != null) {
                    val gid = bookmark.browseId
                    val busy = YtDownloads.groupBusy(gid)
                    val all = tracks.isNotEmpty() && tracks.all { YtDownloads.isDownloaded(it.videoId) }
                    FilledTonalIconButton(onClick = {
                        if (busy) YtDownloads.cancelGroup(gid)
                        else YtDownloads.download(tracks, YtDownloads.Group(gid, bookmark.kind.name, title, bookmark.thumb.ifBlank { thumb }))
                    }) {
                        Icon(
                            if (busy) Icons.Filled.Stop else if (all) Icons.Filled.DownloadDone else Icons.Filled.Download,
                            contentDescription = if (busy) "Ferma il download" else "Scarica tutto",
                            tint = if (busy || all) cs.primary else cs.onSurface,
                        )
                    }
                }
                FilledTonalIconButton(onClick = { env.durationCheck = tracks }) {
                    Icon(Icons.Filled.Timer, contentDescription = "Controlla durate")
                }
                if (onRefresh != null) {
                    FilledTonalIconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna dalla playlist originale")
                    }
                }
                if (bookmark != null && circle) {
                    val following = env.app.ytStore.isSaved(bookmark.browseId)
                    if (following) FilledTonalButton(onClick = { env.app.ytStore.toggleSaved(bookmark); env.message = "Non segui più ${bookmark.title}" }) {
                        Icon(Icons.Filled.Bookmark, null); Spacer(Modifier.width(6.dp)); Text("Segui già")
                    } else OutlinedButton(onClick = { env.app.ytStore.toggleSaved(bookmark); env.message = "Segui ${bookmark.title}" }) {
                        Icon(Icons.Filled.BookmarkBorder, null); Spacer(Modifier.width(6.dp)); Text("Segui")
                    }
                } else if (bookmark != null) {
                    val saved = env.app.ytStore.isSaved(bookmark.browseId)
                    FilledTonalIconButton(onClick = {
                        val now = env.app.ytStore.toggleSaved(bookmark)
                        env.message = if (now) "Salvato nella libreria" else "Tolto dalla libreria"
                    }) {
                        Icon(
                            if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (saved) "Togli dalla libreria" else "Salva nella libreria",
                            tint = if (saved) cs.primary else cs.onSurface,
                        )
                    }
                }
                }
            }
        }
        if (circle && tracks.isNotEmpty()) item(key = "popular-title") {
            Text("Brani popolari", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        }
        itemsIndexed(shownTracks, key = { i, it -> "${i}_${it.videoId}" }) { index, item ->
            Box(Modifier.padding(horizontal = 16.dp)) {
                YtRow(env, item) {
                    env.feedback.tapScreen()
                    env.playFrom(tracks.map { Yt.toTrack(it) }, index)
                }
            }
        }
        if (circle && tracks.size > 5) item(key = "popular-more") {
            TextButton(onClick = { allSongs = !allSongs }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (allSongs) "Mostra meno" else "Mostra tutti i ${tracks.size} brani")
            }
        }
        // album, singoli e altro dell'artista
        items(shelves, key = { "shelf_" + it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, onOpen) }
    }
}

/** Playlist, album e artisti salvati con il segnalibro. */
@Composable
private fun SavedSection(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val saved = env.app.ytStore.saved
    val top = LibUi.trail.lastOrNull()
    if (top != null) {
        val d = YtUi.details[top.browseId]
        CollectionState(
            env, top.title, top.subtitle, top.thumb, d?.tracks, YtUi.errors[top.browseId],
            onBack = { LibUi.trail.removeAt(LibUi.trail.lastIndex) },
            shelves = d?.shelves.orEmpty(),
            onOpen = { openYtEntry(env, it, LibUi.trail) },
            circle = top.kind == Yt.Kind.Artists,
            bookmark = top,
            onRefresh = if (top.browseId.startsWith("imp:")) ({ refreshImported(env, top, LibUi.trail) }) else null,
        )
        return
    }
    if (saved.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Niente di salvato: usa il segnalibro nella pagina di una playlist, un album o un artista", color = cs.onSurfaceVariant,
                modifier = Modifier.padding(24.dp))
        }
        return
    }
    val kinds = listOf<Pair<Yt.Kind?, String>>(null to "Tutti", Yt.Kind.Artists to "Artisti", Yt.Kind.Playlists to "Playlist", Yt.Kind.Albums to "Album")
    val kind = kinds[LibUi.savedKind.coerceIn(0, 3)].first
    val q = com.marco.mlplayer.yt.YtTaste.norm(LibUi.savedQuery)
    fun norm(t: String) = com.marco.mlplayer.yt.YtTaste.norm(t)
    val shown = saved.filter { e ->
        (kind == null || e.kind == kind) && (q.isEmpty() || norm(e.title).contains(q) || norm(e.subtitle).contains(q))
    }.let { l -> if (LibUi.savedSort == 1) l.sortedBy { norm(it.title) } else l }
    androidx.compose.material3.OutlinedTextField(
        value = LibUi.savedQuery, onValueChange = { LibUi.savedQuery = it },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, shape = RoundedCornerShape(28.dp),
        placeholder = { Text("Cerca nei salvati") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEachIndexed { i, (k, label) ->
            val n = if (k == null) saved.size else saved.count { it.kind == k }
            FilterChip(selected = LibUi.savedKind == i, onClick = { LibUi.savedKind = i }, label = { Text("$label · $n") })
        }
        FilterChip(
            selected = false, onClick = { LibUi.savedSort = 1 - LibUi.savedSort },
            label = { Text(if (LibUi.savedSort == 0) "Recenti" else "A-Z") },
        )
    }
    @Composable
    fun Row1(e: Yt.Entry) {
        EntryRow(
            e.copy(subtitle = listOf(e.kind.label, e.subtitle).filter { it.isNotBlank() }.distinct().joinToString(" · ")),
            trailing = {
                // segnalibro pieno: un tocco toglie dai Salvati (con «Annulla»)
                androidx.compose.material3.IconButton(onClick = {
                    env.feedback.tapScreen()
                    env.app.ytStore.toggleSaved(e)
                    env.messageAction = "Annulla" to { env.app.ytStore.addSaved(e) }
                    env.message = "«${e.title}» tolto dai Salvati"
                }) { Icon(Icons.Filled.Bookmark, contentDescription = "Togli dai Salvati", tint = cs.primary) }
            },
        ) { env.feedback.tapScreen(); openYtEntry(env, e, LibUi.trail) }
    }
    if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nessun risultato", color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (kind == null) {
            // in «Tutti» le voci sono divise in sezioni
            for (k in listOf(Yt.Kind.Artists, Yt.Kind.Playlists, Yt.Kind.Albums)) {
                val part = shown.filter { it.kind == k }
                if (part.isEmpty()) continue
                item(key = "h_" + k.name) {
                    Text("${k.label} · ${part.size}", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(part, key = { "e_" + it.browseId }) { e -> Row1(e) }
            }
        } else {
            items(shown, key = { "e_" + it.browseId }) { e -> Row1(e) }
        }
    }
}

private fun kindName(kind: String) = when (kind) {
    "Playlists" -> "Playlist"
    "Albums" -> "Album"
    "Artists" -> "Artista"
    else -> "Brani singoli"
}

/** Playlist, album e artisti scaricati: tocca per aprire, tieni premuto o usa ⋮ per il menu. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DownloadsSection(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val open = LibUi.dlOpen?.let { YtDownloads.groupOf(it) }
    if (open != null) {
        CollectionState(
            env, open.title, kindName(open.kind), open.thumb, YtDownloads.itemsOf(open.id), null,
            onBack = { LibUi.dlOpen = null },
            circle = open.kind == "Artists",
        )
        return
    }
    var menuFor by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var confirm by androidx.compose.runtime.remember { mutableStateOf<YtDownloads.Group?>(null) }
    val list = YtDownloads.groups.filter { YtDownloads.itemsOf(it.id).isNotEmpty() || YtDownloads.groupBusy(it.id) }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Niente scaricato: usa il pulsante Scarica in una playlist, un album o un artista", color = cs.onSurfaceVariant,
                modifier = Modifier.padding(24.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(list, key = { it.id }) { g ->
            val count = YtDownloads.itemsOf(g.id).size
            val busy = YtDownloads.groupBusy(g.id)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { env.feedback.tapScreen(); LibUi.dlOpen = g.id }, onLongClick = { menuFor = g.id })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).clip(if (g.kind == "Artists") CircleShape else RoundedCornerShape(10.dp)).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.QueueMusic, null, tint = cs.onPrimaryContainer)
                    if (g.thumb.isNotBlank()) AsyncImage(g.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        kindName(g.kind) + " · $count brani" + if (busy) " · in download" else "",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuFor = g.id }) { Icon(Icons.Filled.MoreVert, contentDescription = "Altre azioni") }
                    DropdownMenu(expanded = menuFor == g.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Apri") }, onClick = { menuFor = null; LibUi.dlOpen = g.id })
                        if (count > 0) DropdownMenuItem(text = { Text("Riproduci") }, onClick = {
                            menuFor = null
                            env.playFrom(YtDownloads.itemsOf(g.id).map { Yt.toTrack(it) }, 0)
                        })
                        if (busy) DropdownMenuItem(text = { Text("Ferma il download") }, onClick = { menuFor = null; YtDownloads.cancelGroup(g.id) })
                        DropdownMenuItem(text = { Text("Elimina") }, onClick = { menuFor = null; confirm = g })
                    }
                }
            }
        }
    }
    confirm?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Eliminare «${g.title}»?") },
            text = { Text("I brani scaricati e la cartella vengono cancellati dal telefono.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { YtDownloads.deleteGroup(g.id); confirm = null; env.message = "Download eliminati" }) { Text("Elimina") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null }) { Text("Annulla") } },
        )
    }
}


/** Le tue playlist: crea, apri, rinomina, elimina; i brani si aggiungono dal menu ⋮ di un brano. */
@Composable
private fun PlaylistsSection(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val st = env.app.ytStore
    val open = LibUi.myOpen?.let { id -> st.myPlaylists.firstOrNull { it.id == id } }
    var creating by androidx.compose.runtime.remember { mutableStateOf(false) }
    var renaming by androidx.compose.runtime.remember { mutableStateOf(false) }
    var deleting by androidx.compose.runtime.remember { mutableStateOf(false) }
    var name by androidx.compose.runtime.remember { mutableStateOf("") }
    if (open != null) {
        Column(Modifier.fillMaxSize()) {
            BackRow(open.name) { LibUi.myOpen = null }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { name = open.name; renaming = true }) { Text("Rinomina") }
                OutlinedButton(onClick = { deleting = true }) { Text("Elimina") }
            }
            if (open.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Playlist vuota: usa «Aggiungi a una playlist» nel menu ⋮ di un brano", color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
                }
            } else {
                TrackList(env, open.items, onRemove = { st.removeFromPlaylist(open.id, it.videoId) })
            }
        }
        if (renaming) androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rinomina la playlist") },
            text = { androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { st.renamePlaylist(open.id, name); renaming = false }) { Text("Salva") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = false }) { Text("Annulla") } },
        )
        if (deleting) androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Eliminare la playlist?") },
            text = { Text("«${open.name}» e il suo elenco di brani verranno cancellati. I brani scaricati restano.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { st.deletePlaylist(open.id); LibUi.myOpen = null; deleting = false }) { Text("Elimina") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text("Annulla") } },
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        FilledTonalButton(onClick = { name = ""; creating = true }, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Nuova playlist")
        }
        if (st.myPlaylists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nessuna playlist tua: creane una e aggiungi i brani dal menu ⋮", color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(st.myPlaylists, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { env.feedback.tapScreen(); LibUi.myOpen = p.id }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.QueueMusic, null, tint = cs.onPrimaryContainer)
                        p.items.firstOrNull()?.let { AsyncImage(it.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Playlist tua · ${p.items.size} brani", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (creating) androidx.compose.material3.AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("Nuova playlist") },
        text = { androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nome") }) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { val p = st.createPlaylist(name); LibUi.myOpen = p.id; creating = false }) { Text("Crea") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { creating = false }) { Text("Annulla") } },
    )
}

/** Riepilogo degli ascolti: totali, artisti e brani più ascoltati, generi preferiti (dai punteggi). */
@Composable
private fun SummarySection(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val st = env.app.ytStore
    val plays = st.plays.values.toList()
    val total = plays.sumOf { it.count }
    if (total == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Ascolta qualche brano (almeno 30 secondi): qui vedrai artisti, brani e generi più ascoltati", color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
        }
        return
    }
    val byArtist = LinkedHashMap<String, Pair<String, Int>>()
    for (p in plays) {
        val a = com.marco.mlplayer.yt.YtTaste.artistNames(p.item.artist).firstOrNull() ?: continue
        val k = com.marco.mlplayer.yt.YtTaste.norm(a)
        byArtist[k] = a to ((byArtist[k]?.second ?: 0) + p.count)
    }
    val topArtists = byArtist.values.sortedByDescending { it.second }.take(5)
    val topTracks = plays.sortedByDescending { it.count }.take(5)
    val prof = com.marco.mlplayer.yt.YtTaste.profile(st.plays, st.history, st.favorites, st.skips, st.dislikes)
    val scores = com.marco.mlplayer.yt.YtTaste.genreScores(prof) { com.marco.mlplayer.yt.YtGenres.genreOf(it, st) }
    val topGenres = scores.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(4)
        .mapNotNull { e -> com.marco.mlplayer.yt.FavGenres.ALL.firstOrNull { it.id == e.key }?.label }
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((n, l) in listOf(total to "ascolti", plays.size to "brani", byArtist.size to "artisti")) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("$n", style = MaterialTheme.typography.headlineSmall, color = cs.primary)
                        Text(l, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        item { Text("Artisti più ascoltati", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
        itemsIndexed(topArtists) { i, a ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, modifier = Modifier.width(28.dp))
                    Text(a.first, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.second} ascolti", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { a.second.toFloat() / topArtists.first().second },
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp),
                )
            }
        }
        item { Text("Brani più ascoltati", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
        itemsIndexed(topTracks) { i, p ->
            YtRow(env, p.item) { env.feedback.tapScreen(); env.playFrom(topTracks.map { Yt.toTrack(it.item) }, i) }
        }
        if (topGenres.isNotEmpty()) {
            item { Text("Generi che ascolti di più", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            item { Text(topGenres.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}
