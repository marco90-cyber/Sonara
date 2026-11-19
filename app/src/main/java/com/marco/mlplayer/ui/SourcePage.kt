package com.marco.mlplayer.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.Http
import com.marco.mlplayer.util.Net
import com.marco.mlplayer.util.mmss
import com.marco.mlplayer.yt.DurationMatch
import com.marco.mlplayer.yt.DurationRef
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtOverrides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ---------------------------------------------------------------- azioni (usate anche dal menu del brano)

/** Fa suonare il brano [orig] dal video scelto: aggiorna subito la coda, riparte da capo e sistema la copertina. */
fun applySource(env: AppEnv, orig: Yt.Item, source: Yt.Item, useTargetMeta: Boolean, quiet: Boolean = false) {
    YtOverrides.set(orig, source, useTargetMeta)
    refreshQueue(env, YtOverrides[orig.videoId]?.orig ?: orig)
    if (!quiet) env.message = "Sorgente cambiata: vale in tutte le playlist"
}

/** Toglie il cambio di sorgente: torna l'audio, il titolo e la copertina di prima. */
fun restoreSource(env: AppEnv, videoId: String) {
    val orig = YtOverrides.clear(videoId) ?: return
    refreshQueue(env, orig)
    env.message = "Sorgente originale ripristinata"
}

/** Aggiorna i brani già in coda (titolo, copertina, sorgente) a partire da [base], il brano com'è senza cambi. */
private fun refreshQueue(env: AppEnv, base: Yt.Item) {
    val uri = "yt:${base.videoId}"
    val track = Yt.toTrack(base)
    env.engine.replaceTracks(mapOf(uri to track), setOf(uri), restart = true)
    // la copertina del brano è tenuta su disco: la riscarico se è cambiata la miniatura
    env.app.appScope.launch {
        val bytes = withContext(Dispatchers.IO) { Http.bytes(track.coverUrl) }
        if (bytes != null) env.library.setTrackCover(track, bytes)
    }
}

// ---------------------------------------------------------------- anteprima

/** Anteprima di un risultato: parte da circa 30 secondi (o dall'inizio nei brani corti) e si ferma toccando di nuovo. */
internal class SourcePreview {
    var playingId by mutableStateOf<String?>(null)
        private set
    var loadingId by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null
    private var token = 0

    fun toggle(scope: CoroutineScope, ctx: android.content.Context, id: String, durationSec: Long, onError: (String) -> Unit, onStart: () -> Unit) {
        if (playingId == id || loadingId == id) { stop(); return }
        stop()
        val my = ++token
        loadingId = id
        onStart()
        scope.launch {
            var p: MediaPlayer? = null
            try {
                // l'anteprima segue sempre il video mostrato, anche se quel brano ha già un cambio di sorgente
                val r = withContext(Dispatchers.IO) { Yt.resolve(id, quality = 1, followOverride = false) }
                if (my != token) return@launch
                p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                val mp = p
                withContext(Dispatchers.IO) { mp.setDataSource(ctx, Uri.parse(r.url), mapOf("User-Agent" to Net.BROWSER_UA)) }
                if (my != token) { mp.release(); return@launch }
                mp.setOnPreparedListener {
                    if (my != token) { it.release(); return@setOnPreparedListener }
                    if (durationSec > 75) it.seekTo(30_000)
                    it.start()
                    loadingId = null
                    playingId = id
                }
                mp.setOnCompletionListener { if (my == token) stop() }
                mp.setOnErrorListener { _, _, _ ->
                    if (my == token) { stop(); onError("Anteprima non riuscita") }
                    true
                }
                player = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                AppLog.w("Sorgente", "Anteprima di $id non riuscita: ${e.message}")
                runCatching { p?.release() }
                if (my == token) { stop(); onError("Anteprima non riuscita") }
            }
        }
    }

    fun stop() {
        token++
        runCatching { player?.release() }
        player = null
        playingId = null
        loadingId = null
    }
}

// ---------------------------------------------------------------- pagina

/** Pagina «Cambia sorgente»: cerca su YouTube un altro audio per il brano, lo ascolta in anteprima e lo sostituisce. */
@Composable
fun SourcePage(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = env.editTrack ?: env.engine.currentTrack
    val shown = t?.let { Yt.itemOf(it) }
    if (t == null || shown == null) {
        Column(Modifier.fillMaxSize()) { PageBar(env, "Cambia sorgente") }
        return
    }
    // il brano com'era in origine (se ha già un cambio, quello di partenza) e il video che suona adesso
    val orig = YtOverrides[shown.videoId]?.orig ?: shown
    val activeId = YtOverrides.target(shown.videoId)

    var query by remember(t.id) { mutableStateOf(listOf(orig.title, orig.artist).filter { it.isNotBlank() }.joinToString(" ")) }
    var results by remember(t.id) { mutableStateOf<List<Yt.Source>?>(null) }
    var loading by remember(t.id) { mutableStateOf(false) }
    var failed by remember(t.id) { mutableStateOf(false) }
    var confirm by remember(t.id) { mutableStateOf<Yt.Source?>(null) }
    var useMeta by remember(t.id) { mutableStateOf(false) }
    val preview = remember { SourcePreview() }
    var pausedByPreview by remember { mutableStateOf(false) }
    // durata di riferimento (Deezer/iTunes): serve a mostrare di quanto ogni risultato si discosta
    var ref by remember(t.id) { mutableStateOf<DurationRef.Ref?>(null) }
    LaunchedEffect(t.id) {
        ref = withContext(Dispatchers.IO) { runCatching { DurationRef.lookup(orig.title, orig.artist) }.getOrNull() }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
        loading = true
        failed = false
        preview.stop()
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Yt.searchSources(q) }.getOrNull() }
            if (r == null) failed = true else results = r
            loading = false
        }
    }

    LaunchedEffect(t.id) { if (results == null) search() }

    // chiudendo la pagina l'anteprima si ferma e la musica che suonava riprende
    DisposableEffect(Unit) {
        onDispose {
            preview.stop()
            if (pausedByPreview && !env.engine.isPlaying) env.engine.togglePlayPause()
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar(env, "Cambia sorgente")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                orig.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (YtOverrides.has(shown.videoId)) "Ora suona un'altra sorgente. Scegline una diversa o ripristina dal menu del brano."
                else "Scegli il video giusto: sostituisce l'audio di questo brano in tutte le playlist.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            ref?.let { r ->
                Text(
                    "Durata di riferimento: ${mmss(r.seconds * 1000)} (${r.source}). I risultati più vicini sono in alto.",
                    style = MaterialTheme.typography.bodySmall, color = cs.primary, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Cerca su YouTube Music") },
                trailingIcon = { IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, contentDescription = "Cerca") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.size(8.dp))
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            failed -> Text(
                "Ricerca non riuscita. Controlla la connessione e riprova.",
                color = cs.error, modifier = Modifier.padding(16.dp),
            )
            results?.isEmpty() == true -> Text("Nessun risultato: prova a cambiare le parole.", modifier = Modifier.padding(16.dp), color = cs.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val shownResults = ref?.let { r ->
                    results.orEmpty().sortedBy { if (it.item.durationSec > 0) abs(it.item.durationSec - r.seconds) else Long.MAX_VALUE }
                } ?: results.orEmpty()
                items(shownResults, key = { it.item.videoId }) { src ->
                    SourceRow(
                        src = src,
                        refSec = ref?.seconds,
                        inUse = src.item.videoId == activeId,
                        previewing = preview.playingId == src.item.videoId,
                        previewLoading = preview.loadingId == src.item.videoId,
                        onPreview = {
                            preview.toggle(scope, ctx, src.item.videoId, src.item.durationSec,
                                onError = { env.message = it },
                                onStart = {
                                    // mentre si ascolta l'anteprima la musica del lettore si mette in pausa
                                    if (env.engine.isPlaying) { env.engine.togglePlayPause(); pausedByPreview = true }
                                })
                        },
                        onChoose = { preview.stop(); useMeta = false; confirm = src },
                    )
                }
            }
        }
    }

    confirm?.let { src ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Usare questo audio?") },
            text = {
                Column {
                    Text(src.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(src.item.artist, if (src.item.durationSec > 0) mmss(src.item.durationSec * 1000) else "").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    MetaChoice(!useMeta, "Tieni titolo e copertina attuali") { useMeta = false }
                    MetaChoice(useMeta, "Usa titolo e copertina di questo risultato") { useMeta = true }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Vale per questo brano in tutte le playlist e resta salvato. Dal menu del brano si può ripristinare.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview.stop()
                    applySource(env, orig, src.item, useMeta)
                    confirm = null
                    env.feedback.tapScreen()
                    env.overlay = Overlay.None
                }) { Text("Sostituisci") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun MetaChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SourceRow(
    src: Yt.Source,
    refSec: Long?,
    inUse: Boolean,
    previewing: Boolean,
    previewLoading: Boolean,
    onPreview: () -> Unit,
    onChoose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val item = src.item
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onChoose).padding(vertical = 8.dp),
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
            Text(item.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (src.isVideo) "Video" else "Brano",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (src.isVideo) cs.tertiary else cs.primary,
                )
                if (item.durationSec > 0) {
                    Text(" · " + mmss(item.durationSec * 1000), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    if (refSec != null && refSec > 0) {
                        val d = item.durationSec - refSec
                        Text(
                            " (" + (if (d >= 0) "+" else "-") + mmss(abs(d) * 1000) + ")",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (abs(d) <= DurationMatch.tolerance(refSec)) cs.primary else cs.error,
                        )
                    }
                }
                if (inUse) Text(" · In uso", style = MaterialTheme.typography.labelSmall, color = cs.primary)
            }
        }
        IconButton(onClick = onPreview) {
            when {
                previewLoading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                previewing -> Icon(Icons.Filled.Stop, contentDescription = "Ferma l'anteprima", tint = cs.primary)
                else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Ascolta l'anteprima")
            }
        }
    }
}
