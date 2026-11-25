@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.marco.mlplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marco.mlplayer.yt.FavGenres
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtGenerator
import com.marco.mlplayer.yt.YtGenerator.Mode
import com.marco.mlplayer.yt.YtGenerator.Period
import com.marco.mlplayer.yt.YtTaste
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nome, descrizione e icona di ogni modalità (come nel Generator di LastWave). */
private class ModeInfo(val label: String, val sub: String, val icon: ImageVector)

private fun info(m: Mode): ModeInfo = when (m) {
    Mode.Top -> ModeInfo("Brani top", "I brani che hai ascoltato di più", Icons.Filled.ThumbUp)
    Mode.Recent -> ModeInfo("Brani recenti", "Quello che hai ascoltato di recente", Icons.Filled.History)
    Mode.Radio -> ModeInfo("Radio brano", "Radio di YouTube Music a partire da un brano", Icons.Filled.MusicNote)
    Mode.SimilarArtists -> ModeInfo("Artisti simili", "Scopri artisti, prima YouTube", Icons.Filled.People)
    Mode.Genre -> ModeInfo("Per tag / genere", "Scelte di genere, prima YouTube", Icons.Filled.Sell)
    Mode.MyMix -> ModeInfo("Il mio mix", "I tuoi gusti, i mix e i preferiti", Icons.Filled.Shuffle)
    Mode.Suggestions -> ModeInfo("I miei consigli", "35 scoperte, prima YouTube", Icons.Filled.AutoAwesome)
    Mode.NeverHeard -> ModeInfo("Mai sentiti", "Scoperte nuove, brani che non hai mai ascoltato", Icons.Filled.Explore)
    Mode.Library -> ModeInfo("La mia libreria", "Riscopri i suoni del tuo passato", Icons.Filled.LibraryMusic)
}

private const val SUGGESTIONS_COUNT = 35

/** Generatore di playlist: scegli una modalità, le sue opzioni (diverse per ogni modalità) e genera. */
@Composable
fun GeneratorPage(env: AppEnv, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val st = env.app.ytStore
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf<Mode?>(null) }
    var period by remember { mutableStateOf(Period.All) }
    var count by remember { mutableFloatStateOf(26f) }
    var seed by remember { mutableStateOf<Yt.Item?>(null) }
    var seedQuery by remember { mutableStateOf("") }
    var seedHits by remember { mutableStateOf(emptyList<Yt.Item>()) }
    var searching by remember { mutableStateOf(false) }
    var artist by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf<FavGenres.Genre?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<Mode, List<Yt.Item>>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    BackHandler(enabled = result != null) { result = null }
    BackHandler(enabled = result == null) { onBack() }

    val weights = remember(st.plays, st.history, st.favorites, st.skips) { YtTaste.weights(st.plays, st.history, st.favorites, st.skips) }

    val res = result
    if (res != null) {
        ResultView(env, res.first, res.second, onBack = { result = null }) {
            saveName = "${info(res.first).label} · ${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date())}"
            saving = true
        }
        if (saving) AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Salva come playlist") },
            text = { OutlinedTextField(value = saveName, onValueChange = { saveName = it }, singleLine = true, label = { Text("Nome") }) },
            confirmButton = {
                TextButton(onClick = {
                    val p = st.createPlaylist(saveName.ifBlank { info(res.first).label })
                    res.second.forEach { st.addToPlaylist(p.id, it) }
                    env.message = "Playlist «${p.name}» creata: ${res.second.size} brani"
                    saving = false
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Annulla") } },
        )
        return
    }

    fun canGenerate(): Boolean = !busy && when (mode) {
        null -> false
        Mode.Radio -> seed != null
        Mode.SimilarArtists -> artist.isNotBlank()
        Mode.Genre -> genre != null || tag.isNotBlank()
        else -> true
    }

    fun generate() {
        val m = mode ?: return
        busy = true; error = null; progress = ""
        val input = YtGenerator.Input(
            plays = st.plays, history = st.history, favorites = st.favorites, skips = st.skips, dislikes = st.dislikes,
            seed = seed, artist = artist.trim().takeIf { it.isNotBlank() },
            genre = genre ?: tag.trim().takeIf { it.isNotBlank() }?.let { FavGenres.Genre("custom", it, it) },
            period = period, count = if (m == Mode.Suggestions) SUGGESTIONS_COUNT else count.toInt(),
        )
        scope.launch {
            val out = withContext(Dispatchers.IO) { runCatching { YtGenerator.generate(m, input) { progress = it } }.getOrNull() }
            busy = false
            if (out.isNullOrEmpty()) error = "Non ho trovato brani per questa modalità. Riprova o cambia opzioni."
            else result = m to out
        }
    }

    // scelta una modalità, la lista scende da sola fino alle sue opzioni
    val list = rememberLazyListState()
    LaunchedEffect(mode) {
        if (mode != null) {
            delay(80)
            runCatching { list.animateScrollToItem(Mode.entries.size) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // intestazione: indietro, titolo e sottotitolo
        Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { env.feedback.tapScreen(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Generator", style = MaterialTheme.typography.headlineLarge)
                    Text("Scegli una modalità per generare una playlist", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Mode.entries.toList()) { m ->
                val i = info(m)
                val sel = mode == m
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (sel) cs.primaryContainer else cs.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(enabled = !busy) {
                        env.feedback.tapScreen()
                        mode = m
                        error = null
                        if (m == Mode.Radio && seed == null) seed = env.engine.currentTrack?.let { com.marco.mlplayer.yt.Yt.itemOf(it) }
                    },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (m == Mode.NeverHeard) cs.tertiaryContainer else if (m == Mode.Radio || m == Mode.SimilarArtists || m == Mode.Suggestions) cs.primary else cs.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                i.icon, null,
                                tint = if (m == Mode.NeverHeard) cs.onTertiaryContainer else if (m == Mode.Radio || m == Mode.SimilarArtists || m == Mode.Suggestions) cs.onPrimary else cs.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(i.label, style = MaterialTheme.typography.titleMedium)
                            Text(i.sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            // opzioni della modalità scelta
            mode?.let { m ->
                item {
                    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Tune, null, tint = cs.onSecondaryContainer)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text("Opzioni", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(16.dp))
                            when (m) {
                                Mode.Top, Mode.Library -> {
                                    Label("Periodo")
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (p in Period.entries) FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
                                    }
                                }
                                Mode.Recent -> Hint("Gli ultimi brani che hai ascoltato, dal più recente.")
                                Mode.Radio -> {
                                    Label("Brano di partenza")
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = seedQuery, onValueChange = { seedQuery = it }, singleLine = true,
                                            modifier = Modifier.weight(1f), placeholder = { Text("Titolo e artista") },
                                        )
                                        TextButton(
                                            enabled = seedQuery.isNotBlank() && !searching,
                                            onClick = {
                                                searching = true
                                                scope.launch {
                                                    seedHits = withContext(Dispatchers.IO) { runCatching { Yt.search(seedQuery, 1).take(6) }.getOrDefault(emptyList()) }
                                                    searching = false
                                                }
                                            },
                                        ) { Text(if (searching) "…" else "Cerca") }
                                    }
                                    val mine = buildList {
                                        env.engine.currentTrack?.let { t -> Yt.itemOf(t)?.let { add(it) } }
                                        addAll(YtTaste.topTracks(weights, 8))
                                    }
                                    val choices = (seedHits + mine).distinctBy { it.videoId }
                                    if (choices.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            for (t in choices) FilterChip(
                                                selected = seed?.videoId == t.videoId, onClick = { seed = t },
                                                label = { Text("${t.title} – ${t.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                                            )
                                        }
                                    }
                                }
                                Mode.SimilarArtists -> {
                                    Label("Artista di partenza")
                                    OutlinedTextField(
                                        value = artist, onValueChange = { artist = it }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("Nome dell'artista") },
                                    )
                                    val top = remember(weights) { YtTaste.topArtists(weights, 10).map { it.first } }
                                    if (top.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("I tuoi artisti più ascoltati", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            for (a in top) FilterChip(selected = artist == a, onClick = { artist = a }, label = { Text(a) })
                                        }
                                    }
                                }
                                Mode.Genre -> {
                                    Label("Tag o genere")
                                    OutlinedTextField(
                                        value = tag, onValueChange = { tag = it; genre = null }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("es. indie, lofi, anni 90") },
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (g in FavGenres.ALL) FilterChip(selected = genre?.id == g.id, onClick = { genre = g; tag = g.label }, label = { Text(g.label) })
                                    }
                                }
                                Mode.MyMix -> Hint("Un mix dei tuoi brani più ascoltati, dei preferiti che non senti da tempo e delle radio dei tuoi artisti.")
                                Mode.Suggestions -> Hint("$SUGGESTIONS_COUNT scoperte dalle radio dei tuoi brani, senza quelli che conosci già.")
                                Mode.NeverHeard -> Hint("Solo brani che non hai mai ascoltato, al massimo due per artista.")
                            }
                            if (m == Mode.Suggestions) {
                                Spacer(Modifier.height(16.dp))
                                Text("Numero di brani: $SUGGESTIONS_COUNT (fisso)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                            } else {
                                Spacer(Modifier.height(16.dp))
                                Text("Numero di brani: ${count.toInt()}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                                Slider(value = count, onValueChange = { count = it }, valueRange = 10f..60f, steps = 49)
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { env.feedback.tapScreen(); generate() },
                                enabled = canGenerate(),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(progress.ifBlank { "Genero…" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    Icon(Icons.Filled.AutoAwesome, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (m == Mode.Radio) "Crea radio" else "Genera playlist")
                                }
                            }
                            error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** I brani generati: riproduci, casuale, salva come playlist. */
@Composable
private fun ResultView(env: AppEnv, mode: Mode, tracks: List<Yt.Item>, onBack: () -> Unit, onSave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { env.feedback.tapScreen(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(info(mode).label, style = MaterialTheme.typography.headlineSmall)
                Text("${tracks.size} brani", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        FilledTonalButton(onClick = { env.feedback.tapScreen(); onSave() }, modifier = Modifier.padding(top = 12.dp)) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Salva come playlist")
        }
        TrackList(env, tracks)
    }
}
