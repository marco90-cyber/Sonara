package com.marco.mlplayer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Timer di spegnimento: ferma la musica dopo un tempo scelto o alla fine del brano in corso. Gira sul thread principale. */
object SleepTimer {
    /** Quando scatta (ms, orologio); 0 = spento o «fine del brano». */
    var endAt by mutableLongStateOf(0L)
        private set
    var endOfTrack by mutableStateOf(false)
        private set
    val active get() = endAt > 0L || endOfTrack
    private var job: Job? = null

    fun cancel(env: AppEnv) {
        job?.cancel(); job = null
        endAt = 0L
        if (endOfTrack) env.engine.player.pauseAtEndOfMediaItems = false
        endOfTrack = false
    }

    fun startMinutes(env: AppEnv, minutes: Int) {
        cancel(env)
        endAt = System.currentTimeMillis() + minutes * 60_000L
        job = env.app.appScope.launch {
            while (System.currentTimeMillis() < endAt) delay(1000)
            env.engine.player.pause()
            endAt = 0L
            env.message = "Timer: musica fermata"
        }
    }

    /** Si ferma quando finisce il brano che sta suonando (poi la coda resta pronta). */
    fun startEndOfTrack(env: AppEnv) {
        cancel(env)
        val p = env.engine.player
        p.pauseAtEndOfMediaItems = true
        endOfTrack = true
        var id = p.currentMediaItem?.mediaId
        job = env.app.appScope.launch {
            while (true) {
                delay(500)
                val now = p.currentMediaItem?.mediaId
                if (p.playWhenReady) { id = now; continue }   // sta suonando: il brano da finire è quello attuale
                if (now != id) break                          // è passato al successivo e si è fermato
            }
            p.pauseAtEndOfMediaItems = false
            endOfTrack = false
            env.message = "Timer: musica fermata"
        }
    }
}

@Composable
fun SleepTimerDialog(env: AppEnv, onDismiss: () -> Unit) {
    val left = ((SleepTimer.endAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L) + 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timer di spegnimento") },
        text = {
            Column {
                if (SleepTimer.active) Text(
                    if (SleepTimer.endOfTrack) "Si ferma alla fine di questo brano" else "Si ferma tra circa $left min",
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp),
                )
                for ((label, action) in listOf<Pair<String, () -> Unit>>(
                    "15 minuti" to { SleepTimer.startMinutes(env, 15) },
                    "30 minuti" to { SleepTimer.startMinutes(env, 30) },
                    "45 minuti" to { SleepTimer.startMinutes(env, 45) },
                    "1 ora" to { SleepTimer.startMinutes(env, 60) },
                    "Fine del brano" to { SleepTimer.startEndOfTrack(env) },
                )) Text(
                    label, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { env.feedback.tapScreen(); action(); onDismiss() }.padding(vertical = 12.dp),
                )
            }
        },
        confirmButton = {
            if (SleepTimer.active) TextButton(onClick = { SleepTimer.cancel(env); onDismiss() }) { Text("Annulla il timer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}
