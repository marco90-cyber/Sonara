package com.marco.mlplayer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.marco.mlplayer.data.Region
import com.marco.mlplayer.AppUpdate
import com.marco.mlplayer.AppUpdateStatus
import com.marco.mlplayer.yt.ImportRunner
import com.marco.mlplayer.yt.YtCache
import com.marco.mlplayer.yt.ImportScheduler
import com.marco.mltheme.MLChoiceRow
import com.marco.mltheme.MLDependent
import com.marco.mltheme.MLGroup
import com.marco.mltheme.MLNavRow
import com.marco.mltheme.MLSettingsScaffold
import com.marco.mltheme.MLSwitchRow
import com.marco.mltheme.MLThemeSettingsGroup

/** Pagina principale delle impostazioni, a gruppi come nelle altre MLapps. */
@Composable
fun SettingsHome(env: AppEnv) {
    val s = env.settings
    val nav = env.settingsNav
    fun go(screen: Screen) {
        env.feedback.tapScreen()
        nav.push(screen)
    }
    MLSettingsScaffold("Impostazioni", onBack = null) {
        MLGroup {
            MLNavRow("Aspetto", "Tema, colori, VU meter", icon = Icons.Filled.Palette) { go(AppearanceScreen) }
        }
        MLGroup("Audio") {
            MLNavRow("Equalizzatore", "Parametrico, AutoEQ, grafico e moduli DSP", icon = Icons.Filled.Equalizer) {
                env.feedback.tapScreen(); env.select(Tab.Eq)
            }
            MLNavRow("Riproduzione", "Mescola, ripeti, uscita adattiva", icon = Icons.Filled.PlayCircle) { go(env.playbackScreen()) }
            MLNavRow("Analizzatore", "Spettro, livelli, fase e PPM", icon = Icons.Filled.Timeline) {
                env.feedback.tapScreen(); env.overlay = Overlay.Analyzer
            }
        }
        MLGroup("Tatto") {
            MLSwitchRow("Vibrazione al tocco", null, s.hapticScreen, icon = Icons.Filled.Vibration) {
                s.hapticScreen = it
                env.feedback.tapScreen()
            }
            MLDependent(s.hapticScreen) {
                MLChoiceRow(
                    "Intensità",
                    listOf("0" to "Leggera", "1" to "Media", "2" to "Forte"),
                    s.hapticStrength.coerceIn(0, 2).toString()
                ) { s.hapticStrength = it.toInt(); env.feedback.test() }
            }
        }
        MLGroup("Streaming") {
            val q = listOf("0" to "Massima", "1" to "Alta (fino a 160 kbps)", "2" to "Risparmio (circa 50 kbps)")
            MLChoiceRow("Qualità con il Wi-Fi", q, s.qualityWifi.coerceIn(0, 2).toString()) { s.qualityWifi = it.toInt() }
            MLChoiceRow("Qualità con i dati mobili", q, s.qualityMobile.coerceIn(0, 2).toString()) { s.qualityMobile = it.toInt() }
            MLChoiceRow(
                "Cache audio",
                listOf("0" to "Spenta", "200" to "200 MB", "500" to "500 MB", "1000" to "1 GB", "2000" to "2 GB"),
                s.cacheMb.toString(),
            ) { s.cacheMb = it.toInt(); env.message = "La nuova dimensione vale dal prossimo avvio" }
            MLNavRow(
                "Svuota la cache audio",
                "Occupa ${YtCache.usedBytes() / (1024 * 1024)} MB · riascolti e salti indietro senza riscaricare",
                icon = Icons.Filled.Delete,
            ) { env.feedback.tapScreen(); YtCache.clear(); env.message = "Cache audio svuotata" }
        }
        MLGroup("Consigliati") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var picker by remember { mutableStateOf(false) }
            MLNavRow(
                "Paese dei consigliati",
                if (s.region.isBlank()) "Automatico: ${Region.name(Region.detect(ctx))}" else Region.name(s.region),
                icon = Icons.Filled.Public,
            ) { picker = true }
            if (picker) CountryPickerSheet(env) { picker = false }
        }
        MLGroup("Correzione brani") {
            MLSwitchRow(
                "Correggi i brani da soli",
                "Se la durata di un brano non torna con quella ufficiale (audio di un'altra canzone, videoclip più lungo) cambia la sorgente con quella giusta. Vale per playlist, Home e consigliati; si annulla dal menu del brano.",
                s.autoFix, icon = Icons.Filled.AutoFixHigh,
            ) { s.autoFix = it }
        }
        MLGroup("Download") {
            MLNavRow(
                "Cartella dei download",
                s.downloadDir.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it).lastPathSegment?.substringAfterLast(':') }
                    ?: "Non impostata: scegli dove salvare i brani offline",
                icon = Icons.Filled.Download,
            ) { env.actions?.pickDownloadFolder() }
        }
        MLGroup("Playlist importate") {
            MLSwitchRow(
                "Aggiorna da sole", "Controlla se le playlist di Spotify, Deezer e YouTube sono cambiate, anche con l'app chiusa",
                s.importAutoRefresh, icon = Icons.Filled.Sync,
            ) { s.importAutoRefresh = it; ImportScheduler.apply(env.app) }
            MLDependent(s.importAutoRefresh) {
                MLChoiceRow(
                    "Ogni quanto",
                    listOf("6" to "6 ore", "12" to "12 ore", "24" to "1 giorno", "48" to "2 giorni", "168" to "1 settimana"),
                    s.importRefreshHours.toString(),
                ) { s.importRefreshHours = it.toInt(); ImportScheduler.apply(env.app) }
                MLSwitchRow("Solo con il Wi-Fi", "Niente traffico dati mobili", s.importWifiOnly) {
                    s.importWifiOnly = it; ImportScheduler.apply(env.app)
                }
                MLSwitchRow("Avvisa dei cambiamenti", "Una notifica quando una playlist cambia", s.importNotify) { s.importNotify = it }
            }
            MLNavRow(
                "Aggiorna ora",
                env.app.ytImports.lastCheck.takeIf { it > 0 }?.let { "Ultimo controllo: " + android.text.format.DateUtils.getRelativeTimeSpanString(it) }
                    ?: "Nessuna playlist ancora controllata",
                icon = Icons.Filled.Refresh,
            ) {
                env.feedback.tapScreen()
                if (env.app.ytImports.all.isEmpty()) env.message = "Non ci sono playlist importate"
                else {
                    env.message = "Aggiorno le playlist importate…"
                    ImportRunner.launch(env.app) {
                        val c = ImportRunner.refreshAll(env.app).filter { it.any }
                        env.message = if (c.isEmpty()) "Le playlist sono già aggiornate" else "${c.size} playlist aggiornate"
                    }
                }
            }
        }
        MLGroup("App") {
            MLNavRow(
                "Aggiornamenti",
                if (AppUpdateStatus.release != null) "Nuova versione disponibile" else "Cerca la nuova versione su GitHub",
                icon = Icons.Filled.SystemUpdate
            ) { go(env.updateScreen()); AppUpdate.check(env.app) }
            MLNavRow("Dati e ripristino", "Reimposta le impostazioni o cancella i dati salvati", icon = Icons.Filled.RestartAlt) { go(ResetScreen) }
            MLNavRow("Debug", "Log e informazioni tecniche", icon = Icons.Filled.BugReport) { go(env.debugScreen()) }
            MLNavRow("Informazioni", "Versione, licenze, componenti", icon = Icons.Filled.Info) { go(env.aboutScreen()) }
        }
    }
}

/** Impostazioni › Aspetto: tema di MLTheme, colori dalla copertina, VU meter. */
@Composable
fun AppearancePage(env: AppEnv, onBack: (() -> Unit)?) {
    val s = env.settings
    MLSettingsScaffold("Aspetto", onBack) {
        MLThemeSettingsGroup("Tema")
        MLGroup("Colori") {
            MLSwitchRow(
                "Colori dalla copertina",
                "Mentre suona un brano, l'app prende i colori della sua copertina",
                s.coverColors
            ) { s.coverColors = it }
        }
        MLGroup("VU meter") {
            MLSwitchRow("VU meter nel lettore", "Livello dei canali sinistro e destro sotto i comandi", s.showVu) { s.showVu = it }
            MLDependent(s.showVu) {
                MLChoiceRow(
                    "Riferimento",
                    VU_REFS.map { it.toString() to vuRefLabel(it) },
                    s.vuRef.toString()
                ) { s.vuRef = it.toInt() }
                MLChoiceRow(
                    "Velocità",
                    listOf("0" to "Morbida (300 ms)", "1" to "Rapida (120 ms)"),
                    s.vuSpeed.coerceIn(0, 1).toString()
                ) { s.vuSpeed = it.toInt() }
            }
        }
        MLGroup("Info nel lettore") {
            MLSwitchRow("Bitrate e canali", "Es. 128 kbps · Stereo", s.infoBitrate) { s.infoBitrate = it }
            MLSwitchRow("Uscita attiva", "Dove va il suono, con formato e frequenza mandati ad Android", s.infoOutput) { s.infoOutput = it }
            MLSwitchRow("Livello del brano", "ReplayGain e guadagno applicato", s.infoLevel) { s.infoLevel = it }
            MLSwitchRow("Stato dell'audio", "In streaming, scaricato o file locale", s.infoSource) { s.infoSource = it }
            MLSwitchRow("Percorso del segnale", "Una riga: sorgente, DSP e uscita", s.infoPath) { s.infoPath = it }
            MLSwitchRow("Altro sul brano", "Album e anno", s.infoMore) { s.infoMore = it }
        }
        MLGroup("Altro") {
            MLSwitchRow("Copertine negli elenchi", null, s.coverInMenu) { s.coverInMenu = it }
            MLSwitchRow("Schermo sempre acceso", "Mentre l'app è aperta", s.keepScreenOn) { s.keepScreenOn = it }
            MLNavRow("Benvenuto e guida", "Notifiche, cartella, correzione brani, paese e significato dei simboli", icon = Icons.Filled.Info) {
                env.feedback.tapScreen(); env.showWelcome = true
            }
        }
    }
}
