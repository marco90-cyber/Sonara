package com.marco.mlplayer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.marco.mlplayer.ai.LyricsAi
import com.marco.mlplayer.data.Track

/** «Genera il testo con AI»: scelta del modello Whisper, avvio e avanzamento. */
@UnstableApi
@Composable
fun LyricsAiDialog(env: AppEnv, t: Track, onDismiss: () -> Unit) {
    val s = env.settings
    val cs = MaterialTheme.colorScheme
    val ctx = env.app
    val st = LyricsAi.state
    val mine = when (st) {
        is LyricsAi.State.Working -> st.trackId == t.id
        is LyricsAi.State.Failed -> st.trackId == t.id
        is LyricsAi.State.Done -> st.trackId == t.id
        else -> false
    }
    LaunchedEffect(st) { if (st is LyricsAi.State.Done && st.trackId == t.id) { LyricsAi.dismiss(); onDismiss() } }

    val model = LyricsAi.Model.entries[s.whisperModel.coerceIn(0, LyricsAi.Model.entries.lastIndex)]
    val working = st as? LyricsAi.State.Working

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Genera il testo con AI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!LyricsAi.available) {
                    Text("Serve un telefono a 64 bit (arm64): su questo non è disponibile.", color = cs.error)
                } else if (working != null) {
                    Text(
                        if (mine) working.stage else "Sto già generando il testo di un altro brano…",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (working.percent > 0) LinearProgressIndicator(progress = { working.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Tieni l'app aperta finché non finisce. Per non far spegnere lo schermo usa l'icona del sole nei testi.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Whisper ascolta il brano e scrive le parole con i tempi. Funziona offline, sul telefono: " +
                            "un brano da 4 minuti richiede da 1 a 5 minuti. Col canto può sbagliare qualche parola.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                    )
                    Text("Lingua del testo", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((code, name) in LyricsAi.LANGS) {
                            FilterChip(selected = s.whisperLang == code, onClick = { s.whisperLang = code }, label = { Text(name) })
                        }
                    }
                    Text(
                        "Meglio indicare la lingua se la conosci: con «Automatica» un brano in più lingue può uscire mescolato.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                    for (m in LyricsAi.Model.entries) {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = m == model, role = Role.RadioButton) { s.whisperModel = m.ordinal },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = m == model, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("${m.label} · ${m.note}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (LyricsAi.hasModel(ctx, m)) "Già scaricato" else "Da scaricare, circa ${m.mb} MB",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (st is LyricsAi.State.Failed && mine) {
                        Text(st.message, color = cs.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        },
        confirmButton = {
            if (LyricsAi.available) {
                if (working != null) TextButton(onClick = { LyricsAi.cancel() }) { Text("Annulla la generazione") }
                else TextButton(onClick = { env.feedback.tapScreen(); LyricsAi.start(env, t, model, s.whisperLang) }) {
                    Text(if (LyricsAi.hasModel(ctx, model)) "Genera" else "Scarica e genera")
                }
            }
        },
        dismissButton = {
            Row {
                if (working == null && LyricsAi.hasModel(ctx, model)) {
                    TextButton(onClick = { LyricsAi.deleteModel(ctx, model); env.message = "Modello ${model.label} eliminato" }) { Text("Elimina il modello") }
                }
                TextButton(onClick = onDismiss) { Text(if (working != null) "Chiudi" else "Annulla") }
            }
        },
    )
}
