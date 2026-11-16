package com.marco.mlplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/** Stato della Home: resta uguale cambiando sezione. */
private object HomeUi {
    var shelves by mutableStateOf<List<YtApi.Shelf>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadedWithAccount by mutableStateOf(false)
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
            val r = withContext(Dispatchers.IO) { runCatching { YtApi.home() } }
            HomeUi.loading = false
            r.onSuccess {
                HomeUi.shelves = it
                HomeUi.loadedWithAccount = YtAuth.connected
                if (it.isEmpty()) HomeUi.error = "YouTube Music non ha proposto nulla"
            }.onFailure {
                AppLog.e("YouTube", "Caricamento home non riuscito", it)
                HomeUi.error = "Non riesco a caricare i suggerimenti. Controlla la connessione (dettagli nel log)."
            }
        }
    }
    // al primo avvio, e di nuovo quando l'account cambia, per avere i suggerimenti giusti
    LaunchedEffect(YtAuth.connected) {
        if (!HomeUi.loading && (HomeUi.shelves == null || HomeUi.loadedWithAccount != YtAuth.connected)) load()
    }

    val shelves = HomeUi.shelves
    val recent = env.app.ytStore.history.take(15)
    val past = env.app.ytStore.searches.take(8)
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
            if (recent.isNotEmpty()) item {
                ShelfRow("Ascoltati di recente", recent.map { YtApi.Card(it, null) }, env, open)
            }
            items(shelves.orEmpty(), key = { it.title }) { sh -> ShelfRow(sh.title, sh.cards, env, open) }
            if (HomeUi.loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (shelves != null && HomeUi.error != null) item { Text(HomeUi.error!!, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            item {
                OutlinedButton(onClick = { load() }, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Filled.Refresh, null); Text("Aggiorna suggerimenti")
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(title: String, cards: List<YtApi.Card>, env: AppEnv, onOpen: (Yt.Entry) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { (it.song?.videoId ?: it.entry?.browseId.orEmpty()) + it.title }) { c ->
                val artist = c.entry?.kind == Yt.Kind.Artists
                Column(
                    Modifier.width(140.dp).clip(RoundedCornerShape(14.dp)).clickable {
                        env.feedback.tapScreen()
                        val s = c.song
                        if (s != null) env.playWithRadio(s) else c.entry?.let(onOpen)
                    },
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
