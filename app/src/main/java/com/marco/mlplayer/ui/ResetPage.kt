package com.marco.mlplayer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.ImportRunner
import com.marco.mlplayer.yt.ImportScheduler
import com.marco.mlplayer.yt.YtDownloads
import com.marco.mlplayer.yt.YtOverrides
import com.marco.mltheme.MLGroup
import com.marco.mltheme.MLNavRow
import com.marco.mltheme.MLSettingsScaffold

/** Cosa si può azzerare in Impostazioni › Dati e ripristino. */
private enum class ResetKind(val title: String, val sub: String, val warning: String, val done: String) {
    Settings(
        "Reimposta le impostazioni", "Interruttori e scelte tornano ai valori di partenza",
        "Tutti gli interruttori e le scelte tornano ai valori di partenza.\n\nRestano: cartelle (musica e download), paese, equalizzatore e preset, e i tuoi dati.",
        "Impostazioni reimpostate",
    ),
    Library(
        "Preferiti, salvati e cronologia", "File .sonara-library.json",
        "Cancella preferiti, playlist/album/artisti salvati, cronologia, ricerche recenti, ascolti e «non mi piace». La Home riparte da zero.",
        "Preferiti, salvati e cronologia cancellati",
    ),
    Imports(
        "Playlist importate", "File .sonara-imports.json",
        "Dimentica tutte le playlist importate da Spotify, Deezer e YouTube. Si possono importare di nuovo.",
        "Playlist importate cancellate",
    ),
    Sources(
        "Sorgenti cambiate", "File .sonara-sources.json",
        "Toglie tutte le sostituzioni fatte con «Cambia sorgente»: i brani tornano alla sorgente originale. Con «Correzione brani» attiva potrebbero essere corretti di nuovo.",
        "Sorgenti cambiate cancellate",
    ),
    Downloads(
        "Elenco dei download", "File .sonara-downloads.json",
        "L'app dimentica i brani scaricati e i loro gruppi. I file audio restano nella cartella, ma non si vedono più in Raccolta.",
        "Elenco dei download cancellato",
    ),
    All(
        "Resetta tutto", "Impostazioni e tutti i dati qui sopra",
        "Reimposta le impostazioni e cancella preferiti, salvati, cronologia, playlist importate, sorgenti cambiate ed elenco dei download.\n\nI file audio scaricati e la musica sul telefono non vengono toccati.",
        "Tutto reimpostato",
    ),
}

private fun resetSettings(env: AppEnv) {
    env.settings.resetPreferences()
    // alcune scelte si applicano una volta sola all'avvio: le riapplico
    AppLog.verbose = env.settings.verboseLog
    env.engine.outputs.usb.setWanted(env.settings.usbExclusive)
    env.engine.outputs.usbBitPerfect.setEnabled(env.settings.bitPerfect)
    ImportScheduler.apply(env.app)
}

/** Esegue l'azione; restituisce il messaggio da mostrare, o null se non si può fare ora. */
private fun runReset(env: AppEnv, kind: ResetKind): String? {
    val dataKind = kind != ResetKind.Settings
    // lavori in corso rimetterebbero a posto quello che si sta cancellando
    if (dataKind && (YtDownloads.active || ImportRunner.active)) {
        env.message = "Ci sono download o importazioni in corso: fermali prima"
        return null
    }
    val app = env.app
    when (kind) {
        ResetKind.Settings -> resetSettings(env)
        ResetKind.Library -> app.ytStore.clearAll()
        ResetKind.Imports -> app.ytImports.clearAll()
        ResetKind.Sources -> YtOverrides.clearAll()
        ResetKind.Downloads -> YtDownloads.forgetAll()
        ResetKind.All -> {
            app.ytStore.clearAll()
            app.ytImports.clearAll()
            YtOverrides.clearAll()
            YtDownloads.forgetAll()
            resetSettings(env)
        }
    }
    AppLog.i("Ripristino", kind.done)
    return kind.done
}

@Composable
private fun ResetRow(env: AppEnv, k: ResetKind, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onAsk: (ResetKind) -> Unit) {
    MLNavRow(k.title, k.sub, icon = icon) { env.feedback.tapScreen(); onAsk(k) }
}

/** Impostazioni › Dati e ripristino: ogni voce chiede conferma prima di cancellare. */
@Composable
fun ResetPage(env: AppEnv, onBack: (() -> Unit)?) {
    var ask by remember { mutableStateOf<ResetKind?>(null) }
    MLSettingsScaffold("Dati e ripristino", onBack) {
        MLGroup("Impostazioni") {
            ResetRow(env, ResetKind.Settings, Icons.Filled.RestartAlt) { ask = it }
        }
        MLGroup("Dati salvati") {
            ResetRow(env, ResetKind.Library) { ask = it }
            ResetRow(env, ResetKind.Imports) { ask = it }
            ResetRow(env, ResetKind.Sources) { ask = it }
            ResetRow(env, ResetKind.Downloads) { ask = it }
        }
        MLGroup("Tutto") {
            ResetRow(env, ResetKind.All, Icons.Filled.DeleteForever) { ask = it }
        }
    }
    ask?.let { k ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(if (k == ResetKind.Settings || k == ResetKind.All) "${k.title}?" else "Cancellare «${k.title}»?") },
            text = { Text(k.warning + "\n\nNon si può annullare. L'app si riavvia subito.") },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    // ogni cancellazione ha effetto solo dopo un riavvio: l'app si riavvia da sola, dopo aver salvato tutto
                    runReset(env, k)?.let { done -> env.message = "$done · riavvio dell'app…"; env.actions?.restartApp() }
                }) { Text(if (k == ResetKind.Settings) "Reimposta" else "Cancella") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Annulla") } },
        )
    }
}
