package com.marco.mlplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marco.mlplayer.yt.Yt

/** Stato della sezione Libreria: resta uguale cambiando sezione. */
private object LibUi { var page by mutableIntStateOf(0) }

/** Libreria di Sonara: preferiti e cronologia (le playlist dell'account arrivano col login). */
@Composable
fun LibraryPage(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val store = env.app.ytStore
    val items = if (LibUi.page == 0) store.favorites else store.history
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Libreria", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = LibUi.page == 0, onClick = { LibUi.page = 0 }, label = { Text("Preferiti · ${store.favorites.size}") })
            FilterChip(selected = LibUi.page == 1, onClick = { LibUi.page = 1 }, label = { Text("Cronologia") })
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (LibUi.page == 0) "Nessun preferito: usa il menu ⋮ di un brano" else "Nessun brano ascoltato",
                    color = cs.onSurfaceVariant,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { env.playFrom(items.map { Yt.toTrack(it) }, 0) }) {
                    Icon(Icons.Filled.PlayArrow, null); Text("Riproduci")
                }
                FilledTonalButton(onClick = {
                    env.engine.playQueue(items.map { Yt.toTrack(it) }.shuffled(), 0); env.openNowPlaying()
                }) { Icon(Icons.Filled.Shuffle, null); Text("Casuale") }
                if (LibUi.page == 1) OutlinedButton(onClick = { store.clearHistory() }) { Text("Svuota") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { i, it -> "${LibUi.page}_${it.videoId}" }) { index, item ->
                    YtRow(env, item) {
                        env.feedback.tapScreen()
                        env.playFrom(items.map { Yt.toTrack(it) }, index)
                    }
                }
            }
        }
    }
}
