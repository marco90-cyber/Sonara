package com.marco.mlplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.PlaylistImport
import com.marco.mlplayer.yt.Yt
import com.marco.mlplayer.yt.YtApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sfondo morbido di Home e lettore: un velo dei colori del tema in alto che sfuma nella superficie. */
fun Modifier.softBackground(cs: ColorScheme): Modifier =
    background(Brush.verticalGradient(0f to cs.primaryContainer.copy(alpha = 0.55f), 0.55f to cs.surface, 1f to cs.surface))

/** Colore scuro di contrasto derivato dal tema (miniplayer e banner): primario scurito col tema chiaro, contenitore col tema scuro. */
internal fun deepAccent(cs: ColorScheme): Color =
    if (cs.surface.luminance() > 0.5f) lerp(cs.primary, Color.Black, 0.55f) else cs.primaryContainer

internal fun onDeepAccent(cs: ColorScheme): Color =
    if (cs.surface.luminance() > 0.5f) Color.White else cs.onPrimaryContainer

/** «Il tuo mix quotidiano»: una selezione dai tuoi ascolti, generi e artisti preferiti, uguale per tutta la giornata. */
@Composable
internal fun MixBanner(env: AppEnv, mix: List<Yt.Item>, loading: Boolean, onChoose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val base = deepAccent(cs)
    val fg = onDeepAccent(cs)
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(base),
    ) {
        // le copertine del mix, sfumate, sul lato destro
        val covers = mix.take(3).map { it.cover }
        if (covers.isNotEmpty()) Row(Modifier.matchParentSize()) {
            Spacer(Modifier.weight(0.4f))
            Row(Modifier.weight(0.6f).fillMaxHeight()) {
                for (c in covers) AsyncImage(c, null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight().alpha(0.6f))
            }
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to base, 0.5f to base.copy(alpha = 0.9f), 1f to base.copy(alpha = 0.3f))))
        Column(Modifier.padding(22.dp)) {
            Text("IL TUO MIX QUOTIDIANO", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = fg.copy(alpha = 0.8f))
            Text(
                "La tua musica, oggi.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = fg, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.7f),
            )
            Text(
                when {
                    mix.isNotEmpty() -> "Una selezione creata per accompagnare la tua giornata."
                    loading -> "Sto preparando il tuo mix…"
                    else -> "Scegli i tuoi generi o artisti preferiti: il mix nasce da lì."
                },
                style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.7f),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    env.feedback.tapScreen()
                    if (mix.isNotEmpty()) env.playFrom(mix.map { Yt.toTrack(it) }, 0) else onChoose()
                },
                enabled = mix.isNotEmpty() || !loading,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = base, disabledContainerColor = Color.White.copy(alpha = 0.5f), disabledContentColor = base),
            ) {
                if (mix.isNotEmpty()) Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (mix.isNotEmpty()) "Ascolta ora" else if (loading) "Un momento…" else "Scegli i generi", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Un mix pronto da ascoltare (Mix 1, 2, 3, Scopri settimanale). */
class ForYouMix(val title: String, val subtitle: String, val tracks: List<Yt.Item>)

/** «I tuoi mix»: schede in fila con la copertina del primo brano; il tocco avvia il mix. */
@Composable
internal fun ForYouRow(env: AppEnv, mixes: List<ForYouMix>) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            "I tuoi mix", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(mixes.size) { i ->
                val m = mixes[i]
                Column(Modifier.width(150.dp).clickable { env.feedback.tapScreen(); env.playFrom(m.tracks.map { Yt.toTrack(it) }, 0) }) {
                    Box(Modifier.size(150.dp).clip(RoundedCornerShape(20.dp)).background(cs.primaryContainer)) {
                        val covers = m.tracks.take(4).map { it.cover }
                        if (covers.isNotEmpty()) AsyncImage(covers.first(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(cs.surface.copy(alpha = 0.9f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Ascolta ${m.title}", tint = cs.primary) }
                    }
                    Text(m.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(m.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Fa partire una playlist di YouTube subito; quelle di Spotify e Deezer si aprono (e si importano). */
internal fun playPlaylistNow(env: AppEnv, e: Yt.Entry, onOpen: (Yt.Entry) -> Unit) {
    if (PlaylistImport.refOf(e.browseId) != null) { onOpen(e); return }
    env.message = "Carico «${e.title}»…"
    env.app.appScope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { YtApi.playlistTracks(e.browseId) } }
        r.onFailure { AppLog.w("HomeFeed", "Playlist «${e.title}» non caricata: ${it.message}") }
        val list = r.getOrNull().orEmpty()
        if (list.isEmpty()) { env.message = "Non riesco a caricare questa playlist"; return@launch }
        env.playFrom(list.map { Yt.toTrack(if (it.thumb.isBlank() && e.thumb.isNotBlank()) it.copy(thumb = e.thumb) else it) }, 0)
    }
}

/** «Consigliati per te»: playlist a schede larghe, con il tasto per ascoltarle subito. */
@Composable
internal fun RecommendedCards(env: AppEnv, entries: List<Yt.Entry>, onOpen: (Yt.Entry) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            "Consigliati per te", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        for (e in entries) Row(
            Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().height(92.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(listOf(cs.primaryContainer.copy(alpha = 0.7f), cs.surfaceContainerLow)))
                .clickable { env.feedback.tapScreen(); onOpen(e) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(112.dp).fillMaxHeight().background(cs.primaryContainer)) {
                if (e.thumb.isNotBlank()) AsyncImage(e.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxHeight().width(112.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    e.subtitle.ifBlank { "Brani che potrebbero piacerti" }, style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp), tint = cs.primary)
                    Text("Ascolta la playlist", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                }
            }
            Box(
                Modifier.padding(end = 14.dp).size(46.dp).clip(CircleShape).background(cs.surface)
                    .clickable { env.feedback.tapScreen(); playPlaylistNow(env, e, onOpen) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Ascolta ${e.title}", tint = cs.primary) }
        }
    }
}
