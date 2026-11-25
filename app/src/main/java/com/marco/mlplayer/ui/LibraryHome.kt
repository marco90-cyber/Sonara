package com.marco.mlplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.marco.mlplayer.yt.MyPlaylist
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtDownloads
import com.marco.mlplayer.yt.YtTaste

/**
 * Pagina iniziale della Libreria nello stile di LastWave (GPL-3.0): intestazione con conteggi, ricerca e Generator,
 * scheda grande dei Preferiti, scheda dei Scaricati e le playlist con play e menu.
 */
@Composable
internal fun LibraryHome(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val st = env.app.ytStore
    var sortMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    val downloads = YtDownloads.entries.map { it.item.videoId }.distinct().size
    val totalTracks = st.favorites.size + st.myPlaylists.sumOf { it.items.size }
    val q = YtTaste.norm(LibUi.homeQuery)
    val lists = st.myPlaylists
        .filter { q.isEmpty() || YtTaste.norm(it.name).contains(q) }
        .let { l ->
            when (LibUi.homeSort) {
                1 -> l.sortedBy { YtTaste.norm(it.name) }
                2 -> l.sortedByDescending { it.items.size }
                else -> l
            }
        }

    fun play(items: List<Yt.Item>, shuffle: Boolean = false) {
        if (items.isEmpty()) return
        env.feedback.tapScreen()
        val tracks = items.map { Yt.toTrack(it) }
        if (shuffle) { env.engine.playQueue(tracks.shuffled(), 0); env.openNowPlaying() } else env.playFrom(tracks, 0)
    }

    Column(Modifier.fillMaxSize()) {
        // intestazione
        Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Libreria", style = MaterialTheme.typography.headlineLarge)
                    Text("${st.myPlaylists.size} playlist • $totalTracks brani", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = { env.feedback.tapScreen(); name = ""; creating = true }) { Icon(Icons.Filled.Add, "Nuova playlist") }
                Box {
                    FilledTonalButton(onClick = { env.feedback.tapScreen(); sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null); Spacer(Modifier.width(6.dp)); Text("Ordina")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf("Recenti", "A-Z", "Più brani").forEachIndexed { i, l ->
                            DropdownMenuItem(text = { Text(if (LibUi.homeSort == i) "✓ $l" else l) }, onClick = { LibUi.homeSort = i; sortMenu = false })
                        }
                    }
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ricerca e Generator
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(
                        value = LibUi.homeQuery, onValueChange = { LibUi.homeQuery = it }, singleLine = true,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("Cerca playlist…") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = cs.surfaceContainer, unfocusedContainerColor = cs.surfaceContainer,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    Button(
                        onClick = { env.feedback.tapScreen(); LibUi.page = 7 },
                        shape = RoundedCornerShape(24.dp), modifier = Modifier.height(56.dp),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Generator")
                    }
                }
            }
            // Preferiti: la scheda grande
            item {
                Surface(
                    shape = RoundedCornerShape(32.dp), color = cs.primaryContainer,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).clickable { env.feedback.tapScreen(); LibUi.page = 0 },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(st.favorites.firstOrNull()?.cover, 84.dp, 18.dp) { Icon(Icons.Filled.Favorite, null, tint = cs.onPrimaryContainer) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Brani che ti piacciono", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
                            Text("Preferiti", style = MaterialTheme.typography.headlineMedium, color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)
                            Text("${st.favorites.size} brani", style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
                        }
                        PlayDot(56.dp) { play(st.favorites) }
                    }
                }
            }
            // Scaricati
            item {
                Surface(
                    shape = RoundedCornerShape(32.dp), color = cs.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).clickable { env.feedback.tapScreen(); LibUi.page = 4 },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(cs.onTertiaryContainer.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Download, null, tint = cs.onTertiaryContainer, modifier = Modifier.size(36.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Salvati offline", style = MaterialTheme.typography.labelLarge, color = cs.onTertiaryContainer.copy(alpha = 0.8f))
                            Text("Scaricati", style = MaterialTheme.typography.headlineMedium, color = cs.onTertiaryContainer, fontWeight = FontWeight.Bold)
                            Text(
                                if (downloads == 0) "Ancora nessun download" else "$downloads brani",
                                style = MaterialTheme.typography.bodyLarge, color = cs.onTertiaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onTertiaryContainer)
                    }
                }
            }
            // le playlist
            items(lists, key = { it.id }) { p -> PlaylistCard(env, p, onPlay = { play(p.items) }, onShuffle = { play(p.items, shuffle = true) }) }
            if (st.myPlaylists.isEmpty()) item {
                Text(
                    "Nessuna playlist tua: creane una con + o aggiungi i brani dal menu ⋮",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp),
                )
            }
            // il resto della raccolta
            item { Text("Altro", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
            item { MoreRow(Icons.Filled.Bookmark, "Salvati", "${st.saved.size} tra artisti, album e playlist") { LibUi.page = 3 } }
            item { MoreRow(Icons.Filled.History, "Cronologia", "${st.history.size} brani ascoltati") { LibUi.page = 1 } }
            item { MoreRow(Icons.Filled.BarChart, "Riepilogo", "Ascolti, artisti e generi preferiti") { LibUi.page = 6 } }
        }
    }

    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("Nuova playlist") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nome") }) },
        confirmButton = {
            TextButton(onClick = {
                val p = st.createPlaylist(name)
                LibUi.myOpen = p.id; LibUi.page = 5; creating = false
            }) { Text("Crea") }
        },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Annulla") } },
    )
}

@Composable
private fun Cover(url: String?, size: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp, placeholder: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        placeholder()
        if (!url.isNullOrBlank()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PlayDot(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size).clip(CircleShape).background(cs.primary).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PlayArrow, "Riproduci", tint = cs.onPrimary, modifier = Modifier.size(size / 2))
    }
}

@Composable
private fun PlaylistCard(env: AppEnv, p: MyPlaylist, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { env.feedback.tapScreen(); LibUi.myOpen = p.id; LibUi.page = 5 },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(p.items.firstOrNull()?.cover, 64.dp, 16.dp) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = cs.onSurfaceVariant) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.items.size} brani", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            PlayDot(48.dp, onPlay)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Apri") }, onClick = { menu = false; LibUi.myOpen = p.id; LibUi.page = 5 })
                    DropdownMenuItem(text = { Text("Riproduci in casuale") }, onClick = { menu = false; onShuffle() })
                }
            }
        }
    }
}

@Composable
private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = cs.onSecondaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}
