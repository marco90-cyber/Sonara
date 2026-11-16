package com.marco.mlplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
}

private val LIKED = YtApi.Playlist("VLLM", "Brani che ti piacciono", "Account YouTube Music", "")

/** Libreria di Sonara: preferiti, cronologia e playlist dell'account. */
@Composable
fun LibraryPage(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val store = env.app.ytStore
    BackHandler(enabled = LibUi.page == 2 && LibUi.open != null) { LibUi.open = null; LibUi.openTracks = null }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Libreria", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = LibUi.page == 0, onClick = { LibUi.page = 0 }, label = { Text("Preferiti · ${store.favorites.size}") })
            FilterChip(selected = LibUi.page == 1, onClick = { LibUi.page = 1 }, label = { Text("Cronologia") })
            FilterChip(selected = LibUi.page == 2, onClick = { LibUi.page = 2 }, label = { Text("Playlist") })
        }
        if (LibUi.page == 2) {
            AccountSection(env)
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
private fun TrackList(env: AppEnv, items: List<Yt.Item>, showClear: Boolean = false) {
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
        Text(open.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val tracks = LibUi.openTracks
        when {
            LibUi.openError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(LibUi.openError!!, color = cs.onSurfaceVariant) }
            tracks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            tracks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Playlist vuota", color = cs.onSurfaceVariant) }
            else -> TrackList(env, tracks)
        }
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
