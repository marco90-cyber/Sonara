package com.marco.mlplayer.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.marco.mlplayer.MainActivity
import com.marco.mlplayer.yt.ImportRunner

/** Tiene vivo l'app mentre una playlist si importa o si aggiorna e mostra l'avanzamento in una notifica. */
class ImportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ImportRunner.cancelAll()
            return START_NOT_STICKY
        }
        ImportNotify.ensureChannels(this)
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        ImportRunner.onChange = { refresh() }
        // se il lavoro è già finito prima che il servizio partisse, la notifica non deve restare appesa
        if (!ImportRunner.active) refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (!ImportRunner.active) {
            ImportRunner.onChange = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun build(): Notification {
        val s = ImportRunner.snapshot()
        val stop = PendingIntent.getService(
            this, 3, Intent(this, ImportService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val more = (s.queued - 1).coerceAtLeast(0)
        return NotificationCompat.Builder(this, ImportNotify.CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (s.name.isBlank()) "Importazione in corso" else "Importo «${s.name}»")
            .setContentText(if (s.total > 0) "${s.done}/${s.total} brani" else "Leggo la playlist…")
            .setSubText(if (more > 0) "+$more in coda" else null)
            .setProgress(s.total.coerceAtLeast(1), s.done, s.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Interrompi", stop)
            .build()
    }

    override fun onDestroy() {
        ImportRunner.onChange = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 4202
        private const val ACTION_STOP = "com.marco.sonara.STOP_IMPORT"
    }
}

/** Notifiche a lavoro finito (importazione completata, playlist aggiornate). */
object ImportNotify {
    const val CHANNEL_PROGRESS = "imports"
    private const val CHANNEL_DONE = "imports_done"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Importazione playlist", NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Playlist importate", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun post(ctx: Context, id: Int, title: String, text: String) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    /** Una playlist è stata importata mentre l'app non era visibile. */
    fun imported(ctx: Context, title: String, sub: String) = post(ctx, 4203, "Playlist importata", "«$title» · $sub")

    /** Le playlist cambiate al controllo periodico. */
    fun updated(ctx: Context, changes: List<ImportRunner.Change>) {
        val c = changes.filter { it.any }
        if (c.isEmpty()) return
        val text = c.joinToString("\n") { x ->
            val parts = listOfNotNull(x.added.takeIf { it > 0 }?.let { "+$it" }, x.removed.takeIf { it > 0 }?.let { "−$it" }, "riordinata".takeIf { x.reordered })
            "«${x.title}» ${parts.joinToString(" ")}"
        }
        post(ctx, 4204, if (c.size == 1) "Playlist aggiornata" else "${c.size} playlist aggiornate", text)
    }
}
