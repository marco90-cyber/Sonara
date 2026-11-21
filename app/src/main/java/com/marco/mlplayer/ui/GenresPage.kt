package com.marco.mlplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marco.mlplayer.yt.FavGenres
import com.marco.mltheme.MLSettingsScaffold

/** I generi a scelta multipla: li usano sia Benvenuto sia Impostazioni. La scelta si salva subito. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenreChips(env: AppEnv) {
    val s = env.settings
    val chosen = FavGenres.parse(s.favGenres).map { it.id }.toSet()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (g in FavGenres.ALL) FilterChip(
            selected = g.id in chosen,
            onClick = { env.feedback.tapScreen(); s.favGenres = FavGenres.toggle(s.favGenres, g.id) },
            label = { Text(g.label) },
        )
    }
}

/** Impostazioni › Generi preferiti. */
@Composable
fun GenresPage(env: AppEnv, onBack: (() -> Unit)?) {
    MLSettingsScaffold("Generi preferiti", onBack) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Scegli quelli che ti piacciono: la Home propone musica di questi generi. All'inizio contano molto, poi pesano di più i brani che ascolti davvero.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            GenreChips(env)
        }
    }
}
