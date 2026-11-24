package com.marco.mlplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.FavArtists
import com.marco.mlplayer.yt.FavGenres
import com.marco.mlplayer.yt.Yt
import com.marco.mltheme.MLSettingsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Scelta degli artisti preferiti: quelli proposti seguono i generi scelti; se non ce ne sono (o non bastano) si cerca per nome.
 * La scelta si salva subito. Si usa in Benvenuto e in Impostazioni.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistPicker(env: AppEnv) {
    val s = env.settings
    val genreIds = s.favGenres
    var suggested by remember { mutableStateOf<List<Yt.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Yt.Entry>?>(null) }
    var searching by remember { mutableStateOf(false) }

    // proposte dai generi scelti: qualche artista per genere, a turno, senza doppioni
    LaunchedEffect(genreIds) {
        val genres = FavGenres.parse(genreIds).take(6)
        if (genres.isEmpty()) { suggested = emptyList(); return@LaunchedEffect }
        loading = true
        val lists = withContext(Dispatchers.IO) {
            genres.map { g ->
                runCatching { Yt.searchEntries(g.label, Yt.Kind.Artists, 1).take(20) }
                    .onFailure { AppLog.w("Artisti", "Proposte per «${g.label}» non riuscite: ${it.message}") }
                    .getOrDefault(emptyList())
            }
        }
        val mixed = ArrayList<Yt.Entry>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) l.getOrNull(i)?.let { mixed.add(it) }
        suggested = mixed.distinctBy { it.browseId }.take(60)   // tanti: quando ne scegli uno, ne compaiono altri al suo posto
        loading = false
    }
    // artisti simili a quelli scelti: appena ne scegli uno compaiono i suoi simili (anche senza generi)
    val chosenIds = FavArtists.parse(s.favArtists).map { it.browseId }
    var similar by remember { mutableStateOf<Map<String, List<Yt.Entry>>>(emptyMap()) }
    LaunchedEffect(chosenIds) {
        val todo = chosenIds.filter { it !in similar }
        if (todo.isEmpty()) return@LaunchedEffect
        val got = withContext(Dispatchers.IO) {
            todo.associateWith { id ->
                runCatching { com.marco.mlplayer.yt.YtApi.similarArtists(id) }
                    .onFailure { AppLog.w("Artisti", "Simili non riusciti: ${it.message}") }
                    .getOrDefault(emptyList())
            }
        }
        similar = similar + got
    }
    suspend fun search() {
        val q = query.trim()
        if (q.isEmpty()) { found = null; return }
        searching = true
        found = withContext(Dispatchers.IO) { runCatching { Yt.searchEntries(q, Yt.Kind.Artists, 1).take(12) }.getOrDefault(emptyList()) }
        searching = false
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val chosen = FavArtists.parse(s.favArtists)
    OutlinedTextField(
        value = query, onValueChange = { query = it; if (it.isBlank()) found = null },
        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp),
        placeholder = { Text("Cerca un artista") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { scope.launch { search() } }),
    )
    Column(Modifier.padding(top = 12.dp)) {
        if (chosen.isNotEmpty()) {
            Text("Scelti", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            ArtistGrid(env, chosen)
        }
        // prima i simili agli ultimi scelti (a turno), poi quelli dei generi
        val fromChosen = ArrayList<Yt.Entry>().also { out ->
            val lists = chosenIds.reversed().mapNotNull { similar[it] }
            for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) l.getOrNull(i)?.let { out.add(it) }
        }
        val shown = (found ?: (fromChosen + suggested)).filter { e -> chosen.none { it.browseId == e.browseId } }
            .distinctBy { it.browseId }.take(30)
        if (searching || (found == null && loading)) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (shown.isNotEmpty()) {
            Text(
                if (found != null) "Risultati" else if (fromChosen.isNotEmpty()) "Simili ai tuoi artisti e proposti per te" else "Proposti per te",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            ArtistGrid(env, shown)
        } else if (found != null) {
            Text("Nessun artista trovato", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        } else if (chosen.isEmpty()) {
            Text(
                if (genreIds.isBlank()) "Scrivi il nome di un artista per aggiungerlo. Se scegli dei generi, qui compaiono artisti da cui partire."
                else "Non riesco a proporre artisti adesso: cerca per nome.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistGrid(env: AppEnv, list: List<Yt.Entry>) {
    val s = env.settings
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (e in list) {
            val on = FavArtists.has(s.favArtists, e.browseId)
            Column(
                Modifier.width(88.dp).clip(RoundedCornerShape(12.dp))
                    .clickable { env.feedback.tapScreen(); s.favArtists = FavArtists.toggle(s.favArtists, e) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, null, tint = cs.onPrimaryContainer)
                    if (e.thumb.isNotBlank()) AsyncImage(e.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (on) Box(Modifier.fillMaxSize().background(cs.primary.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(32.dp))
                    }
                }
                Text(
                    e.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Impostazioni › Artisti preferiti. */
@Composable
fun ArtistsPage(env: AppEnv, onBack: (() -> Unit)?) {
    MLSettingsScaffold("Artisti preferiti", onBack) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Tocca gli artisti che ami: la Home li mette in primo piano e propone artisti simili. Man mano che scegli compaiono artisti simili a quelli scelti, oltre a quelli dei tuoi generi preferiti.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            ArtistPicker(env)
        }
    }
}
