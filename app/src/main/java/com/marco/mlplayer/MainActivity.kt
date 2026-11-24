package com.marco.mlplayer

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.marco.mlplayer.ui.Actions
import com.marco.mlplayer.ui.PlayerApp
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.YtBackup
import com.marco.mlplayer.yt.YtDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), Actions {

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            takeAccess(uri)
            val app = application as MLPlayerApp
            app.settings.addFolder(uri.toString())
            AppLog.i("Libreria", "Cartella aggiunta: $uri")
            app.env.startScan(false)
        }

    private val dataPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val app = application as MLPlayerApp
            app.appScope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                }
                val msg = text?.let { withContext(Dispatchers.IO) { runCatching { com.marco.mlplayer.yt.DataTransfer.import(app, it) }.getOrNull() } }
                if (msg == null) {
                    app.env.message = "Non è un file di backup di Sonara"
                } else {
                    app.env.message = "$msg: riavvio l'app"
                    kotlinx.coroutines.delay(1500)
                    restartApp()
                }
            }
        }

    private val downloadPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val app = application as MLPlayerApp
            if (uri == null) {
                YtDownloads.resumePending(false)
                return@registerForActivityResult
            }
            if (!takeAccess(uri)) {
                app.env.message = "Questa cartella non si può usare per i download"
                YtDownloads.resumePending(false)
                return@registerForActivityResult
            }
            AppLog.i("Download", "Cartella download: $uri")
            app.appScope.launch {
                // prima ritrovo i download e la libreria di una installazione precedente, poi attivo la copia di riserva
                val restored = runCatching { YtBackup.restore(uri.toString()) }.getOrNull()
                app.settings.downloadDir = uri.toString()
                app.env.message = restored ?: "Cartella dei download impostata"
                YtDownloads.resumePending(true)
                YtBackup.schedule()
            }
        }

    override fun pickDownloadFolder() {
        try {
            downloadPicker.launch(null)
        } catch (e: Exception) {
            AppLog.e("Download", "Selettore cartelle non disponibile", e)
        }
    }

    /** Permesso persistente di lettura e, se il sistema lo concede, anche di scrittura (tag, copertine, testi). */
    private fun takeAccess(uri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            contentResolver.takePersistableUriPermission(uri, rw)
            true
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: Exception) {
                AppLog.w("Libreria", "Permesso persistente non ottenuto", e2)
            }
            false
        }
    }

    /** Cartella richiesta di nuovo per avere il permesso di scrittura. */
    private val writePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val app = application as MLPlayerApp
            if (uri == null) {
                app.env.message = "Permesso non concesso: i file non sono stati modificati"
                return@registerForActivityResult
            }
            if (!takeAccess(uri)) {
                app.env.message = "Questa cartella non si può modificare"
                return@registerForActivityResult
            }
            AppLog.i("Libreria", "Permesso di scrittura concesso: $uri")
            app.env.onWriteGranted()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("App", "Permesso notifiche: ${if (granted) "concesso" else "negato"}")
            (application as MLPlayerApp).env.permissionTick++
        }

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    override fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }.onFailure { AppLog.w("App", "Impostazioni notifiche non disponibili: ${it.message}") }
    }

    /** Con il volume digitale (DVC) acceso i tasti del volume regolano il volume dell'app. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (up || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if ((application as MLPlayerApp).engine.stepDvc(up)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val app = application as MLPlayerApp
        val env = app.env
        env.actions = this

        // controllo aggiornamenti all'apertura dell'app (non a ogni rotazione dello schermo)
        if (savedInstanceState == null && app.settings.updateCheckOnStart) {
            AppUpdate.addListener(object : AppUpdate.Listener {
                override fun onChanged() {
                    when (val st = AppUpdate.state) {
                        is AppUpdate.State.Available -> {
                            env.message = "Aggiornamento disponibile: ${st.release.name}"
                            AppUpdate.removeListener(this)
                        }
                        is AppUpdate.State.UpToDate, is AppUpdate.State.Failed, is AppUpdate.State.Ready -> AppUpdate.removeListener(this)
                        else -> {}
                    }
                }
            })
            AppUpdate.check(app)
        }

        // al primo avvio il permesso si chiede nella schermata di benvenuto, spiegando a cosa serve
        if (app.settings.onboardingDone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            PlayerApp(env)
        }
    }

    override fun onResume() {
        super.onResume()
        // uscita USB esclusiva: serve il permesso per il DAC collegato
        val app = application as MLPlayerApp
        if (app.settings.usbExclusive) runCatching { app.engine.outputs.ensureUsbPermission() }
        app.env.permissionTick++
    }

    override fun onDestroy() {
        val env = (application as MLPlayerApp).env
        if (env.actions === this) env.actions = null
        super.onDestroy()
    }

    // ---- Actions

    override fun restartApp() {
        val app = application as MLPlayerApp
        // prima si scrive tutto su disco (fuori dal thread principale), poi si riparte
        Thread {
            runCatching { app.settings.flush() }
            runCatching { com.marco.mlplayer.util.AsyncFileWriter.flushAll() }
            runCatching { YtBackup.flush() }
            runOnUiThread {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (launch != null) startActivity(launch)
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }.start()
    }

    override fun pickFolder() {
        try {
            folderPicker.launch(null)
        } catch (e: Exception) {
            AppLog.e("Libreria", "Selettore cartelle non disponibile", e)
        }
    }

    override fun requestWriteAccess(tree: String) {
        try {
            // il selettore si apre già sulla cartella della musica
            val start = runCatching {
                val t = Uri.parse(tree)
                android.provider.DocumentsContract.buildDocumentUriUsingTree(t, android.provider.DocumentsContract.getTreeDocumentId(t))
            }.getOrNull()
            writePicker.launch(start)
        } catch (e: Exception) {
            AppLog.e("Libreria", "Selettore cartelle non disponibile", e)
        }
    }

    override fun shareText(text: String, subject: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            startActivity(Intent.createChooser(send, subject))
        } catch (e: Exception) {
            AppLog.w("App", "Condivisione non riuscita", e)
        }
    }

    override fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            AppLog.w("App", "Impossibile aprire $url", e)
        }
    }

    override fun exportData() {
        val app = application as MLPlayerApp
        app.appScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    java.io.File(cacheDir, com.marco.mlplayer.yt.DataTransfer.FILE).also { it.writeText(com.marco.mlplayer.yt.DataTransfer.export(app)) }
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Backup di Sonara")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Esporta i dati di Sonara"))
            } catch (e: Exception) {
                AppLog.e("App", "Esportazione non riuscita", e)
                app.env.message = "Esportazione non riuscita (dettagli nel log)"
            }
        }
    }

    override fun importData() {
        try {
            dataPicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
        } catch (e: Exception) {
            AppLog.e("App", "Selettore file non disponibile", e)
        }
    }

    override fun shareLog() {
        try {
            val file = AppLog.exportFile(this)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Log di Sonara")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Condividi log"))
        } catch (e: Exception) {
            AppLog.e("App", "Condivisione del log non riuscita", e)
        }
    }
}
