package com.marco.mlplayer.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stato della ricerca YouTube Music: resta uguale cambiando sezione. */
object YtUi {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Yt.Item>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

private fun fmt(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

/** Sezione "Cerca": cerca brani su YouTube Music e li suona con il player di Sonara. */
@Composable
fun SearchPage(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current

    fun run() {
        val q = YtUi.query.trim()
        if (q.isEmpty()) return
        focus.clearFocus()
        env.app.ytStore.addSearch(q)
        YtUi.loading = true
        YtUi.error = null
        env.app.appScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Yt.search(q) } }
            YtUi.loading = false
            r.onSuccess {
                YtUi.results = it
                if (it.isEmpty()) YtUi.error = "Nessun risultato"
            }.onFailure {
                AppLog.e("YouTube", "Ricerca non riuscita", it)
                YtUi.error = "Ricerca non riuscita. Controlla la connessione (dettagli nel log)."
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = YtUi.query,
            onValueChange = { YtUi.query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            singleLine = true,
            placeholder = { Text("Cerca brani su YouTube Music") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (YtUi.query.isNotEmpty()) {
                    IconButton(onClick = { YtUi.query = ""; YtUi.results = emptyList(); YtUi.error = null }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Cancella")
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
        )
        when {
            YtUi.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            YtUi.error != null && YtUi.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(YtUi.error!!, color = cs.onSurfaceVariant)
            }
            YtUi.results.isEmpty() -> {
                val past = env.app.ytStore.searches
                LazyColumn(Modifier.fillMaxSize()) {
                    if (past.isNotEmpty()) {
                        item {
                            Text("Ricerche recenti", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(past) { q ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .clickable { YtUi.query = q; run() }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.History, null, tint = cs.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(YtUi.results, key = { _, it -> it.videoId }) { index, item ->
                    YtRow(env, item) {
                        env.feedback.tapScreen()
                        env.playFrom(YtUi.results.map { Yt.toTrack(it) }, index)
                    }
                }
            }
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
            if (item.thumb.isNotBlank()) {
                AsyncImage(item.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
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
