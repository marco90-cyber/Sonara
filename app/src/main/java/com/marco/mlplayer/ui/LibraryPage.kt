package com.marco.mlplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtApi
import com.marco.mlplayer.yt.YtAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stato della sezione Libreria: resta uguale cambiando sezione. */
private object LibUi {
    var page by mutableIntStateOf(0)
    var playlists by mutableStateOf<List<YtApi.Playlist>?>(null)
    var loadingPlaylists by mutableStateOf(false)
    var playlistError by mutableStateOf<String?>(null)
    var open by mutableStateOf<YtApi.Playlist?>(null)
    var openTracks by mutableStateOf<List<Yt.Item>?>(null)
    var openError by mutableStateOf<String?>(null)
    /** Pagine aperte dai Salvati (artista -> album ...). */
    val trail = androidx.compose.runtime.mutableStateListOf<Yt.Entry>()
}

private val LIKED = YtApi.Playlist("VLLM", "Brani che ti piacciono", "Account YouTube Music", "")

/** Libreria di Sonara: preferiti, cronologia e playlist dell'account. */
@Composable
fun LibraryPage(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val store = env.app.ytStore
    BackHandler(enabled = LibUi.page == 2 && LibUi.open != null) { LibUi.open = null; LibUi.openTracks = null }
    BackHandler(enabled = LibUi.page == 3 && LibUi.trail.isNotEmpty()) { LibUi.trail.removeAt(LibUi.trail.lastIndex) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Libreria", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = LibUi.page == 0, onClick = { LibUi.page = 0 }, label = { Text("Preferiti · ${store.favorites.size}") })
            FilterChip(selected = LibUi.page == 3, onClick = { LibUi.page = 3 }, label = { Text("Salvati · ${store.saved.size}") })
            FilterChip(selected = LibUi.page == 1, onClick = { LibUi.page = 1 }, label = { Text("Cronologia") })
            FilterChip(selected = LibUi.page == 2, onClick = { LibUi.page = 2 }, label = { Text("Playlist") })
        }
        if (LibUi.page == 2) {
            AccountSection(env)
            return@Column
        }
        if (LibUi.page == 3) {
            SavedSection(env)
            return@Column
        }
        val items = if (LibUi.page == 0) store.favorites else store.history
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (LibUi.page == 0) "Nessun preferito: usa il menu ⋮ di un brano" else "Nessun brano ascoltato",
                    color = cs.onSurfaceVariant,
                )
            }
        } else {
            TrackList(env, items, showClear = LibUi.page == 1)
        }
    }
}

/** Pulsanti Riproduci/Casuale e l'elenco di brani. */
@Composable
internal fun TrackList(env: AppEnv, items: List<Yt.Item>, showClear: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { env.playFrom(items.map { Yt.toTrack(it) }, 0) }) {
            Icon(Icons.Filled.PlayArrow, null); Text("Riproduci")
        }
        FilledTonalButton(onClick = {
            env.engine.playQueue(items.map { Yt.toTrack(it) }.shuffled(), 0); env.openNowPlaying()
        }) { Icon(Icons.Filled.Shuffle, null); Text("Casuale") }
        if (showClear) OutlinedButton(onClick = { env.app.ytStore.clearHistory() }) { Text("Svuota") }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { i, it -> "${i}_${it.videoId}" }) { index, item ->
            YtRow(env, item) {
                env.feedback.tapScreen()
                env.playFrom(items.map { Yt.toTrack(it) }, index)
            }
        }
    }
}

@Composable
private fun AccountSection(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    if (!YtAuth.connected) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Accedi con Google per vedere le tue playlist e i brani che ti piacciono su YouTube Music.",
                color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            Button(onClick = { env.openPage(Overlay.Login) }) { Text("Accedi con Google") }
        }
        return
    }

    fun loadPlaylists() {
        LibUi.loadingPlaylists = true
        LibUi.playlistError = null
        env.app.appScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YtApi.libraryPlaylists() } }
            LibUi.loadingPlaylists = false
            r.onSuccess { LibUi.playlists = it }.onFailure {
                AppLog.e("YouTube", "Caricamento playlist non riuscito", it)
                LibUi.playlistError = "Non riesco a leggere le playlist. Riprova o esci e accedi di nuovo (dettagli nel log)."
            }
        }
    }
    fun openPlaylist(p: YtApi.Playlist) {
        LibUi.open = p
        LibUi.openTracks = null
        LibUi.openError = null
        env.app.appScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YtApi.playlistTracks(p.browseId) } }
            r.onSuccess { LibUi.openTracks = it }.onFailure {
                AppLog.e("YouTube", "Caricamento di ${p.title} non riuscito", it)
                LibUi.openError = "Non riesco a leggere questa playlist (dettagli nel log)."
            }
        }
    }
    LaunchedEffect(YtAuth.connected) { if (LibUi.playlists == null && !LibUi.loadingPlaylists) loadPlaylists() }

    val open = LibUi.open
    if (open != null) {
        CollectionState(env, open.title, open.subtitle, open.thumb, LibUi.openTracks, LibUi.openError, onBack = { LibUi.open = null; LibUi.openTracks = null })
        return
    }

    val list = LibUi.playlists
    when {
        LibUi.loadingPlaylists && list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        LibUi.playlistError != null && list == null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(LibUi.playlistError!!, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            Button(onClick = { loadPlaylists() }) { Text("Riprova") }
        }
        else -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            item { PlaylistRow(LIKED) { openPlaylist(LIKED) } }
            items(list.orEmpty(), key = { it.browseId }) { p -> PlaylistRow(p) { openPlaylist(p) } }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { LibUi.playlists = null; loadPlaylists() }) { Text("Aggiorna") }
                    OutlinedButton(onClick = {
                        YtAuth.signOut(); LibUi.playlists = null; LibUi.open = null; env.message = "Account scollegato"
                    }) { Text("Esci dall'account") }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(p: YtApi.Playlist, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(if (p.browseId == "VLLM") Icons.Filled.Favorite else Icons.Filled.QueueMusic, null, tint = cs.onPrimaryContainer)
            if (p.thumb.isNotBlank()) AsyncImage(p.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.subtitle.isNotBlank()) Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        else -> CollectionView(env, title, subtitle, thumb, tracks, onBack, shelves, onOpen, circle, bookmark)
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CollectionView(
    env: AppEnv, title: String, subtitle: String, thumb: String, tracks: List<Yt.Item>, onBack: () -> Unit,
    shelves: List<YtApi.Shelf>, onOpen: (Yt.Entry) -> Unit, circle: Boolean, bookmark: Yt.Entry?,
) {
    val cs = MaterialTheme.colorScheme
    val cover = thumb.ifBlank { tracks.firstOrNull()?.cover.orEmpty() }
    val totalSec = tracks.sumOf { it.durationSec }
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
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { env.playFrom(tracks.map { Yt.toTrack(it) }, 0) }) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Riproduci")
                    }
                    FilledTonalButton(onClick = { env.engine.playQueue(tracks.map { Yt.toTrack(it) }.shuffled(), 0); env.openNowPlaying() }) {
                        Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Casuale")
                    }
                if (bookmark != null) {
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
        itemsIndexed(tracks, key = { i, it -> "${i}_${it.videoId}" }) { index, item ->
            Box(Modifier.padding(horizontal = 16.dp)) {
                YtRow(env, item) {
                    env.feedback.tapScreen()
                    env.playFrom(tracks.map { Yt.toTrack(it) }, index)
                }
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
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(saved, key = { it.browseId }) { e ->
            EntryRow(e.copy(subtitle = listOf(e.kind.label, e.subtitle).filter { it.isNotBlank() }.distinct().joinToString(" · "))) {
                env.feedback.tapScreen(); openYtEntry(env, e, LibUi.trail)
            }
        }
    }
}
