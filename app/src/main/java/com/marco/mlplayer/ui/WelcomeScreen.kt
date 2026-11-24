package com.marco.mlplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.marco.mlplayer.data.Region

private const val PAGES = 9

/**
 * Benvenuto: poche pagine per sistemare le cose importanti (notifiche, cartella dei download, correzione dei brani,
 * paese dei consigliati) e la legenda dei simboli. Si vede al primo avvio e si riapre dalle Impostazioni.
 */
@Composable
fun WelcomeScreen(env: AppEnv) {
    val cs = MaterialTheme.colorScheme
    var page by remember { mutableIntStateOf(0) }
    fun finish() {
        env.settings.onboardingDone = true
        env.showWelcome = false
    }
    BackHandler(enabled = page > 0) { page-- }

    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                if (page < PAGES - 1) TextButton(onClick = { finish() }) { Text("Salta") } else Spacer(Modifier.size(48.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(targetState = page, label = "benvenuto") { p ->
                    when (p) {
                        0 -> IntroPage()
                        1 -> ImportWelcomePage(env)
                        2 -> NotificationsPage(env)
                        3 -> FolderPage(env)
                        4 -> AutoFixPage(env)
                        5 -> CountryPage(env)
                        6 -> GenresWelcomePage(env)
                        7 -> ArtistsWelcomePage(env)
                        else -> LegendPage()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(PAGES) { i ->
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(if (i == page) 10.dp else 8.dp).clip(CircleShape)
                            .background(if (i == page) cs.primary else cs.outlineVariant)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) TextButton(onClick = { page-- }) { Text("Indietro") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (page == PAGES - 1) finish() else page++ }) { Text(if (page == PAGES - 1) "Inizia" else "Avanti") }
            }
        }
    }
}

@Composable
private fun WelcomePage(icon: ImageVector, title: String, body: String, content: @Composable ColumnScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.size(16.dp))
        Box(Modifier.size(88.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IntroPage() = WelcomePage(
    Icons.Filled.MusicNote, "Benvenuto in Sonara",
    "La tua musica di YouTube Music, solo audio: con equalizzatore, testi sincronizzati e brani da scaricare per ascoltarli senza internet.\n\n" +
        "Prima di iniziare ti spiego in poche pagine le cose importanti. Tutto si può cambiare dopo, dalle Impostazioni.",
)

@Composable
private fun ImportWelcomePage(env: AppEnv) = WelcomePage(
    Icons.Filled.SwapHoriz, "Vieni da un altro telefono?",
    "Se hai già usato Sonara, importa qui il file di backup (Impostazioni → Esporta e importa, sul vecchio telefono): ritrovi libreria, preferiti, gusti, playlist importate e impostazioni.\n\n" +
        "I brani scaricati si ritrovano scegliendo la stessa cartella dei download, nella pagina dopo. Se è la tua prima volta, vai avanti.",
) {
    Button(onClick = { env.actions?.importData() }) { Text("Importa un backup") }
}

@Composable
private fun NotificationsPage(env: AppEnv) {
    val ctx = LocalContext.current
    val enabled = remember(env.permissionTick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    var asked by remember { mutableStateOf(false) }
    WelcomePage(
        Icons.Filled.Notifications, "Notifiche",
        "Le notifiche servono a tre cose:",
    ) {
        Bullet("i controlli del lettore (pausa, avanti, indietro) nella tendina e sulla schermata di blocco;")
        Bullet("l'avanzamento dei brani che stai scaricando;")
        Bullet("un avviso quando una playlist importata cambia.")
        Spacer(Modifier.size(8.dp))
        Text(
            "Se preferisci non attivarle la musica suona lo stesso, ma senza i controlli fuori dall'app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        if (enabled) {
            FilledTonalButton(onClick = {}, enabled = false) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Notifiche attive")
            }
        } else if (asked) {
            FilledTonalButton(onClick = { env.actions?.openNotificationSettings() }) { Text("Aprile dalle impostazioni di sistema") }
        } else {
            Button(onClick = { asked = true; env.actions?.requestNotifications() }) { Text("Consenti le notifiche") }
        }
    }
}

@Composable
private fun FolderPage(env: AppEnv) {
    val dir = env.settings.downloadDir
    val name = dir.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it).lastPathSegment?.substringAfterLast(':') }
    WelcomePage(
        Icons.Filled.Folder, "Cartella dei download",
        "Scegli dove salvare i brani scaricati: si ascoltano anche senza internet.\n\n" +
            "Nella stessa cartella tengo una copia di riserva di preferiti, gusti, playlist e sorgenti cambiate, così se reinstalli l'app o cambi telefono ritrovi tutto: basta scegliere la stessa cartella.",
    ) {
        if (name != null) {
            Text("Cartella scelta: $name", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            FilledTonalButton(onClick = { env.actions?.pickDownloadFolder() }) { Text("Cambia cartella") }
        } else {
            Button(onClick = { env.actions?.pickDownloadFolder() }) { Text("Scegli la cartella") }
            Spacer(Modifier.size(8.dp))
            Text(
                "Puoi anche saltare: la sceglierai quando scaricherai il primo brano, o dalle Impostazioni.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutoFixPage(env: AppEnv) {
    val s = env.settings
    WelcomePage(
        Icons.Filled.AutoFixHigh, "Correzione brani",
        "A volte un brano ha il nome giusto ma l'audio di un'altra canzone, oppure è il videoclip con le parti più lunghe.\n\n" +
            "Se la accendi, controllo la durata con Deezer e iTunes e cambio la sorgente con quella giusta, solo quando sono sicuro. " +
            "Scarto remix non ufficiali, cover, bootleg e simili.",
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Correggi i brani da soli", style = MaterialTheme.typography.titleSmall)
                Text(if (s.autoFix) "Attiva" else "Disattivata", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.autoFix, onCheckedChange = { s.autoFix = it })
        }
        Spacer(Modifier.size(8.dp))
        Text(
            "Di base è spenta. Ogni correzione si annulla dal menu del brano.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CountryPage(env: AppEnv) {
    val ctx = LocalContext.current
    val s = env.settings
    val code = Region.effective(s.region, ctx)
    var picker by remember { mutableStateOf(false) }
    WelcomePage(
        Icons.Filled.Public, "Paese dei consigliati",
        "I consigliati della Home cambiano da paese a paese. Di base uso il paese in cui ti trovi adesso.",
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        ) {
            Text(Region.name(code), style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.region.isBlank()) "Scelto in base a dove ti trovi" else "Scelto da te",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        FilledTonalButton(onClick = { picker = true }) { Text("Cambia paese") }
    }
    if (picker) CountryPickerSheet(env) { picker = false }
}

@Composable
private fun GenresWelcomePage(env: AppEnv) = WelcomePage(
    Icons.Filled.Category, "I tuoi generi preferiti",
    "Scegli i generi che ti piacciono: la Home ti propone musica più adatta fin da subito. Puoi saltare e cambiarli dopo dalle Impostazioni.",
) { GenreChips(env) }

@Composable
private fun ArtistsWelcomePage(env: AppEnv) = WelcomePage(
    Icons.Filled.Person, "I tuoi artisti preferiti",
    "Tocca gli artisti che ami (quelli proposti seguono i generi scelti) o cercane altri. Non è obbligatorio: vai avanti quando vuoi.",
) { ArtistPicker(env) }

private class Sym(val icon: ImageVector, val title: String, val text: String)

private val SYMBOLS = listOf(
    Sym(Icons.Filled.ThumbUp, "Mi piace e non mi piace", "Insegnano all'app che musica proporti nei consigliati."),
    Sym(Icons.Filled.Favorite, "Preferiti", "Il cuore salva il brano tra i tuoi preferiti."),
    Sym(Icons.Filled.Download, "Scarica", "Salva il brano o la playlist nella cartella dei download."),
    Sym(Icons.Filled.DownloadDone, "Scaricato", "Il brano è sul telefono e suona anche senza internet."),
    Sym(Icons.Filled.MoreVert, "Altre azioni", "Il menu del brano: testo, radio, cambia sorgente, tag e altro."),
    Sym(Icons.Filled.Lyrics, "Testo", "Il testo sincronizzato, con fonti e traduzione."),
    Sym(Icons.AutoMirrored.Filled.QueueMusic, "Coda", "I brani che suoneranno dopo."),
    Sym(Icons.Filled.Timeline, "Analizzatore", "Spettro e livelli dell'audio in tempo reale."),
    Sym(Icons.Filled.Bookmark, "Salva nella libreria", "Tiene a portata di mano playlist e album."),
    Sym(Icons.Filled.Refresh, "Aggiorna", "Riscarica una playlist importata dall'originale."),
    Sym(Icons.Filled.Timer, "Controlla durate", "Trova i brani con l'audio sbagliato (un'altra canzone, il videoclip più lungo) e propone quello giusto."),
    Sym(Icons.Filled.SwapHoriz, "Cambia sorgente", "Sostituisce l'audio di un brano con un altro da YouTube, con anteprima."),
)

@Composable
private fun LegendPage() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Cosa significano i simboli", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.size(12.dp))
        SYMBOLS.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(s.icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall)
                    Text(s.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
