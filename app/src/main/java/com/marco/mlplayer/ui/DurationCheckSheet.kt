package com.marco.mlplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marco.mlplayer.util.mmss
import com.marco.mlplayer.yt.DurationCheck
import com.marco.mlplayer.yt.Yt

/**
 * «Controlla durate»: confronta ogni brano dell'elenco con la durata di Deezer/iTunes, segnala quelli con l'audio
 * fuori misura (altro brano, videoclip più lungo) e propone un'alternativa da ascoltare in anteprima e confermare.
 * «Correggi i sicuri» cambia da solo solo i casi certi: stesso titolo, durata entro 3 secondi, traccia audio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationCheckSheet(env: AppEnv, items: List<Yt.Item>) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember(items) { mutableStateOf<List<DurationCheck.Row>?>(null) }
    var done by remember(items) { mutableIntStateOf(0) }
    var total by remember(items) { mutableIntStateOf(items.distinctBy { it.videoId }.size) }
    var applied by remember(items) { mutableStateOf(setOf<String>()) }
    val preview = remember { SourcePreview() }
    var pausedByPreview by remember { mutableStateOf(false) }

    LaunchedEffect(items) {
        rows = DurationCheck.scan(items) { d, t -> done = d; total = t }
    }
    DisposableEffect(Unit) {
        onDispose {
            preview.stop()
            if (pausedByPreview && !env.engine.isPlaying) env.engine.togglePlayPause()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { env.durationCheck = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight(0.92f).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Controlla durate", style = MaterialTheme.typography.titleLarge)
            val r = rows
            if (r == null) {
                Text(
                    "Confronto con Deezer e iTunes: $done di $total",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done / total.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                return@Column
            }
            val off = r.filter { it.status == DurationCheck.Status.Off }
            val ok = r.count { it.status == DurationCheck.Status.Ok }
            val noRef = r.count { it.status == DurationCheck.Status.NoRef }
            val sure = off.filter { it.pick?.sure == true && it.item.videoId !in applied }
            Text(
                listOf("$ok a posto", "${off.size} da controllare", if (noRef > 0) "$noRef senza riferimento" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (sure.isNotEmpty()) {
                FilledTonalButton(onClick = {
                    sure.forEach { applySource(env, it.item, it.pick!!.source.item, false, quiet = true) }
                    applied = applied + sure.map { it.item.videoId }
                    env.message = "Corrette ${sure.size} sorgenti: valgono in tutte le playlist"
                }) { Text("Correggi i sicuri (${sure.size})") }
            }
            if (off.isEmpty()) {
                Text("Tutte le durate tornano.", modifier = Modifier.padding(top = 16.dp))
                return@Column
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(off, key = { it.item.videoId }) { row ->
                    OffRow(
                        row = row,
                        applied = row.item.videoId in applied,
                        previewing = preview.playingId == row.pick?.source?.item?.videoId,
                        previewLoading = preview.loadingId == row.pick?.source?.item?.videoId,
                        onPreview = {
                            row.pick?.source?.item?.let { src ->
                                preview.toggle(scope, ctx, src.videoId, src.durationSec,
                                    onError = { env.message = it },
                                    onStart = { if (env.engine.isPlaying) { env.engine.togglePlayPause(); pausedByPreview = true } })
                            }
                        },
                        onApply = {
                            row.pick?.source?.item?.let { src ->
                                preview.stop()
                                applySource(env, row.item, src, false)
                                applied = applied + row.item.videoId
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OffRow(
    row: DurationCheck.Row,
    applied: Boolean,
    previewing: Boolean,
    previewLoading: Boolean,
    onPreview: () -> Unit,
    onApply: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pick = row.pick
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(12.dp),
    ) {
        Text(row.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.item.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "Ora ${mmss(row.currentSec * 1000)} · riferimento ${mmss((row.ref?.seconds ?: 0) * 1000)} (${row.ref?.source.orEmpty()})",
            style = MaterialTheme.typography.labelMedium, color = cs.error, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.size(6.dp))
        if (pick == null) {
            Text("Nessun candidato con la durata giusta: prova «Cambia sorgente» dal lettore.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        } else {
            val c = pick.source
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            if (c.isVideo) "Video" else "Brano",
                            mmss(c.item.durationSec * 1000),
                            if (pick.sure) "sicuro" else "",
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = cs.primary,
                    )
                }
                IconButton(onClick = onPreview) {
                    when {
                        previewLoading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        previewing -> Icon(Icons.Filled.Stop, contentDescription = "Ferma l'anteprima", tint = cs.primary)
                        else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Ascolta l'anteprima")
                    }
                }
                if (applied) {
                    Icon(Icons.Filled.Check, contentDescription = "Fatto", tint = cs.primary, modifier = Modifier.padding(horizontal = 12.dp))
                } else {
                    FilledTonalButton(onClick = onApply) { Text("Usa") }
                }
            }
        }
    }
}
