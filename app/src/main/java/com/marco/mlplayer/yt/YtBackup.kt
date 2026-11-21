package com.marco.mlplayer.yt

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.marco.mlplayer.MLPlayerApp
import com.marco.mlplayer.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Copia di riserva nella cartella dei download, per ritrovare tutto dopo una reinstallazione:
 * - .sonara-downloads.json: i brani scaricati e i loro gruppi (playlist, album, artisti);
 * - .sonara-library.json: preferiti, salvati e cronologia;
 * - .sonara-imports.json: le playlist importate da Spotify, Deezer e YouTube;
 * - .sonara-sources.json: i brani con la sorgente audio cambiata.
 */
object YtBackup {
    private const val TAG = "Backup"
    const val DOWNLOADS = ".sonara-downloads.json"
    const val LIBRARY = ".sonara-library.json"
    const val IMPORTS = ".sonara-imports.json"
    const val OVERRIDES = ".sonara-sources.json"

    private lateinit var app: MLPlayerApp
    private val io = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null

    fun init(app: MLPlayerApp) { this.app = app }

    private fun root(): DocumentFile? =
        app.settings.downloadDir.takeIf { it.isNotBlank() }
            ?.let { runCatching { DocumentFile.fromTreeUri(app, Uri.parse(it)) }.getOrNull() }
            ?.takeIf { it.canWrite() }

    /** Riscrive i file di riserva poco dopo l'ultima modifica (così molte modifiche fanno una sola scrittura). */
    fun schedule() {
        if (!::app.isInitialized || app.settings.downloadDir.isBlank()) return
        synchronized(this) {
            pending?.cancel(false)
            pending = io.schedule({ write() }, 3, TimeUnit.SECONDS)
        }
    }

    /** Scrive subito i file di riserva e aspetta che finisca (max 8 s): serve prima di riavviare l'app. */
    fun flush() {
        if (!::app.isInitialized || app.settings.downloadDir.isBlank()) return
        synchronized(this) { pending?.cancel(false); pending = null }
        runCatching { io.submit { write() }.get(8, TimeUnit.SECONDS) }
    }

    private fun writeFile(root: DocumentFile, name: String, text: String) {
        val f = root.findFile(name) ?: root.createFile("application/octet-stream", name) ?: return
        app.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(text.toByteArray()) }
    }

    private fun write() {
        val r = root() ?: return
        runCatching {
            writeFile(r, DOWNLOADS, YtDownloads.exportJson())
            writeFile(r, LIBRARY, app.ytStore.exportJson())
            writeFile(r, IMPORTS, app.ytImports.exportJson())
            writeFile(r, OVERRIDES, YtOverrides.exportJson())
        }.onFailure { AppLog.w(TAG, "Copia di riserva non scritta", it) }
    }

    private fun read(root: DocumentFile, name: String): String? =
        root.findFile(name)?.let { f ->
            runCatching { app.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }

    /** Legge i file di riserva dalla cartella [treeUri] e li unisce a quanto c'è già. Restituisce un messaggio. */
    suspend fun restore(treeUri: String): String? = withContext(Dispatchers.IO) {
        val root = runCatching { DocumentFile.fromTreeUri(app, Uri.parse(treeUri)) }.getOrNull() ?: return@withContext null
        var songs = 0
        var lib = 0
        runCatching { read(root, DOWNLOADS)?.let { songs = YtDownloads.restoreFrom(root, it) } }
            .onFailure { AppLog.w(TAG, "Download non ripristinati", it) }
        runCatching { read(root, LIBRARY)?.let { lib = app.ytStore.mergeFrom(it) } }
            .onFailure { AppLog.w(TAG, "Libreria non ripristinata", it) }
        runCatching { read(root, IMPORTS)?.let { lib += app.ytImports.mergeFrom(it) } }
            .onFailure { AppLog.w(TAG, "Playlist importate non ripristinate", it) }
        runCatching { read(root, OVERRIDES)?.let { lib += YtOverrides.mergeFrom(it) } }
            .onFailure { AppLog.w(TAG, "Sorgenti cambiate non ripristinate", it) }
        AppLog.i(TAG, "Ripristino: $songs brani scaricati, $lib elementi di libreria")
        when {
            songs > 0 && lib > 0 -> "Ripristinati $songs brani scaricati e $lib elementi della libreria"
            songs > 0 -> "Ripristinati $songs brani scaricati"
            lib > 0 -> "Ripristinati $lib elementi della libreria"
            else -> null
        }
    }
}
