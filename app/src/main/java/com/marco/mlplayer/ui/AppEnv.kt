package com.marco.mlplayer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mlplayer.MLPlayerApp
import com.marco.mlplayer.data.Track
import com.marco.mlplayer.util.AppLog
import kotlinx.coroutines.launch

/** Pagine a tutto schermo sopra alle sezioni (si chiudono con la freccia o il tasto indietro). */
enum class Overlay { None, Log, Analyzer, Lyrics, Cover, Tags, Source }

/** Sezioni della barra di navigazione in basso. */
enum class Tab(val label: String) {
    Home("Home"),
    Library("Libreria"),
    NowPlaying("Lettore"),
    Eq("EQ"),
    Settings("Impostazioni"),
}

/** Azioni che richiedono l'Activity (selettore cartelle, browser, condivisione). */
interface Actions {
    fun pickFolder()
    /** Salva tutto e riavvia l'app (dopo un ripristino). */
    fun restartApp()
    fun pickDownloadFolder()
    fun openUrl(url: String)
    fun shareLog()
    /** Chiede di nuovo la cartella [tree] con il permesso di scrittura (per tag, copertine e testi). */
    fun requestWriteAccess(tree: String)
    fun shareText(text: String, subject: String)
    /** Chiede il permesso per le notifiche (Android 13 e successivi). */
    fun requestNotifications()
    /** Apre le impostazioni di sistema delle notifiche dell'app. */
    fun openNotificationSettings()
}

class MenuItem(
    val label: String,
    val value: String? = null,
    val chevron: Boolean = false,
    val sub: String? = null,
    val track: Track? = null,
    val albumKey: String? = null,
    val dimmed: Boolean = false,
    /** Non null = la voce è un interruttore acceso/spento. */
    val toggle: Boolean? = null,
    val onClick: () -> Unit = {},
)

sealed class Screen

class MenuScreen(
    val title: String,
    val cache: Boolean = false,
    val build: AppEnv.() -> List<MenuItem>,
) : Screen()

/** Impostazioni › Aspetto: tema, colori, VU meter. */
object AppearanceScreen : Screen()

/** Impostazioni › Generi preferiti. */
object GenresScreen : Screen()

/** Impostazioni › Dati e ripristino. */
object ResetScreen : Screen()

/** Impostazioni (pagina principale, con i gruppi di MLTheme). */
object SettingsHomeScreen : Screen()


class Entry(val screen: Screen) {
    val listState = LazyListState()
}

class Nav(root: Screen) {
    val stack = mutableStateListOf(Entry(root))

    val top: Entry get() = stack.last()

    fun push(screen: Screen) {
        if (stack.last().screen === screen) return
        stack.add(Entry(screen))
    }

    fun pop(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** Stato globale dell'interfaccia: sezione attiva, navigazione dei menu e pagine sopra. */
class AppEnv(val app: MLPlayerApp) {
    val settings get() = app.settings
    val library get() = app.library
    val engine get() = app.engine
    val feedback get() = app.feedback

    /** Curva calcolata dal motore nativo per l'equalizzatore. */
    val preview by lazy { com.marco.mlplayer.audio.DspPreview() }

    var actions: Actions? = null

    /** La schermata di benvenuto è aperta (al primo avvio, o dalle impostazioni). */
    var showWelcome by mutableStateOf(!app.settings.onboardingDone)

    /** Sale a ogni risposta ai permessi o ritorno nell'app, così le schermate ricontrollano lo stato. */
    var permissionTick by androidx.compose.runtime.mutableIntStateOf(0)

    /** Cambia il paese dei consigliati ("" = automatico) e rifà la Home. */
    fun setRegion(code: String) {
        settings.region = code
        app.applyRegion()
        resetHomeFeed()
        message = "Consigliati per: " + com.marco.mlplayer.data.Region.name(com.marco.mlplayer.data.Region.effective(code, app))
    }

    var tab by mutableStateOf(Tab.Home)
    var overlay by mutableStateOf(Overlay.None)


    /** Coda di riproduzione aperta (foglio dal basso nel lettore). */
    var showQueue by mutableStateOf(false)

    /** Brani di cui si sta controllando la durata («Controlla durate»); null = foglio chiuso. */
    var durationCheck by mutableStateOf<List<com.marco.mlplayer.yt.Yt.Item>?>(null)

    /** Menu ⋮ del brano (Testo, Copertina, Modifica tag). */
    var showTrackMenu by mutableStateOf(false)

    /** Brano su cui lavorano le pagine Copertina e Modifica tag. */
    var editTrack by mutableStateOf<Track?>(null)

    /** Brani e nome della cartella per "Cerca le copertine". */

    /** Finestra con l'avanzamento della scansione (dopo aver scelto una cartella). */

    /** Messaggio breve in basso (Snackbar). */
    var message by mutableStateOf<String?>(null)

    /** Testi dei brani (cache, file, fonti online). */
    val lyrics by lazy { com.marco.mlplayer.lyrics.LyricsStore(app, app.settings) }

    /** Cosa rifare quando l'utente concede il permesso di scrittura. */
    private var afterWriteGrant: (() -> Unit)? = null

    fun requestWrite(tree: String, then: () -> Unit) {
        afterWriteGrant = then
        message = "Scegli la cartella «${folderName(tree)}» (o una che la contiene) e tocca «Usa questa cartella»"
        actions?.requestWriteAccess(tree)
    }

    fun onWriteGranted() {
        val f = afterWriteGrant
        afterWriteGrant = null
        lyrics.needsAccess = null
        f?.invoke()
    }

    fun openPage(o: Overlay, t: Track? = engine.currentTrack) {
        showTrackMenu = false
        editTrack = t
        overlay = o
    }

    /**
     * Scrive nei file dei [tracks] con [op], poi rilegge la libreria e aggiorna la coda.
     * Se manca il permesso chiede la cartella e, una volta concesso, ricomincia da capo.
     */
    fun writeFiles(
        tracks: List<Track>, what: String, op: (Track) -> com.marco.mlplayer.tags.WriteResult,
        done: () -> Unit = {}, askAccess: Boolean = true,
    ) {
        app.appScope.launch {
            val ok = ArrayList<String>()
            var failed = 0
            var stop = false
            for (t in tracks) {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { op(t) }
                when (r) {
                    is com.marco.mlplayer.tags.WriteResult.Ok -> ok += t.uri
                    is com.marco.mlplayer.tags.WriteResult.NoAccess -> {
                        if (askAccess) {
                            // dopo il permesso si riprova una volta sola: niente selettore all'infinito
                            requestWrite(r.tree) { writeFiles(tracks.filter { it.uri !in ok }, what, op, done, askAccess = false) }
                        } else {
                            message = "La cartella scelta non contiene questo brano: serve «${folderName(r.tree)}» o una cartella che la contiene"
                            done()
                        }
                        stop = true
                    }
                    is com.marco.mlplayer.tags.WriteResult.Unsupported -> failed++
                    is com.marco.mlplayer.tags.WriteResult.Failed -> failed++
                }
                if (stop) break
            }
            if (ok.isNotEmpty()) {
                com.marco.mlplayer.yt.YtDownloads.refreshMeta(ok)
                library.refreshTracks(ok)
                val byUri = library.tracks.filter { it.uri in ok }.associateBy { it.uri }
                engine.replaceTracks(byUri, ok.toSet())
                editTrack = editTrack?.let { byUri[it.uri] ?: it }
            }
            if (!stop) {
                message = when {
                    failed == 0 -> "$what: salvato"
                    ok.isEmpty() -> "$what: non salvato (formato non supportato o file non scrivibile)"
                    else -> "$what: salvato in ${ok.size} brani, ${failed} non riusciti"
                }
                done()
            }
        }
    }

    val libraryNav = Nav(MenuScreen("Libreria") { musicItems() })
    val settingsNav = Nav(SettingsHomeScreen)

    /** Banda dell'analizzatore selezionata (0..30, 17 = 1 kHz) e richiesta di azzeramento picchi. */
    var analyzerBand by mutableStateOf(17)
    var analyzerResetToken by mutableStateOf(0)

    /** Navigazione della sezione attiva (null se la sezione non ha menu). */
    val currentNav: Nav?
        get() = when (tab) {
            Tab.Library -> libraryNav
            Tab.Settings -> settingsNav
            else -> null
        }

    /** Sezione da cui si è arrivati al lettore: il tasto indietro ci riporta lì. */
    private var tabBeforePlayer = Tab.Home

    fun select(t: Tab) {
        if (tab == t) currentNav?.popToRoot()
        // toccare Home mentre sei già in Home: torna alla schermata principale (chiude ricerca e pagine aperte)
        if (t == Tab.Home && tab == Tab.Home) YtUi.reset()
        if (t == Tab.NowPlaying && tab != Tab.NowPlaying) tabBeforePlayer = tab
        tab = t
    }

    /** Tasto indietro: chiude coda e pagine sopra, poi risale nei menu, poi torna alla Home; dal lettore torna a dove eri. */
    fun back(): Boolean {
        if (showQueue) {
            showQueue = false
            return true
        }
        if (showTrackMenu) {
            showTrackMenu = false
            return true
        }
        if (overlay != Overlay.None) {
            overlay = Overlay.None
            return true
        }
        if (currentNav?.pop() == true) return true
        if (tab == Tab.NowPlaying) {
            // dal lettore si torna a dove si era (Home di solito), non si esce dall'app
            tab = tabBeforePlayer.takeIf { it != Tab.NowPlaying } ?: Tab.Home
            return true
        }
        if (tab != Tab.Home) {
            tab = Tab.Home
            return true
        }
        return false
    }

    fun startScan(rebuild: Boolean = false) {
        app.appScope.launch {
            library.rescan(settings.folders, rebuild)
        }
    }

    /** Suona il brano subito e, appena pronta, accoda la radio di YouTube Music che segue. */
    fun playWithRadio(item: com.marco.mlplayer.yt.Yt.Item) {
        val seed = com.marco.mlplayer.yt.Yt.toTrack(item)
        engine.playQueue(listOf(seed), 0)
        openNowPlaying()
        app.appScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.marco.mlplayer.yt.YtApi.radio(item.videoId) }
            }
            r.onSuccess { list ->
                if (engine.currentTrack?.id != seed.id) return@onSuccess   // nel frattempo hai cambiato brano
                list.filter { it.videoId != item.videoId }.forEach { engine.addToQueue(com.marco.mlplayer.yt.Yt.toTrack(it)) }
                message = "Radio avviata: ${list.size - 1} brani in coda"
            }.onFailure {
                AppLog.e("YouTube", "Radio non riuscita", it)
                message = "Radio non riuscita (dettagli nel log)"
            }
        }
    }

    fun openNowPlaying() {
        overlay = Overlay.None
        if (tab != Tab.NowPlaying) tabBeforePlayer = tab
        tab = Tab.NowPlaying
    }

    fun playFrom(list: List<Track>, index: Int) {
        engine.playQueue(list, index, shuffle = false)
        openNowPlaying()
    }

    fun shuffleAll() {
        val all = library.tracks
        if (all.isEmpty()) return
        engine.playQueue(all, (all.indices).random(), shuffle = true)
        openNowPlaying()
    }

    fun shuffleAlbums() {
        val albums = library.albums
        if (albums.isEmpty()) return
        val queue = albums.shuffled().flatMap { it.tracks }
        engine.playQueue(queue, 0, shuffle = false)
        openNowPlaying()
    }

    fun playPause() {
        val e = engine
        if (e.currentTrack == null && library.tracks.isNotEmpty()) shuffleAll() else e.togglePlayPause()
    }

    fun log(msg: String) = AppLog.d("UI", msg)
}
