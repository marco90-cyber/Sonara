package com.marco.mlplayer.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.marco.mlplayer.MainActivity
import com.marco.mlplayer.yt.YtDownloads

/** Tiene vivo l'app durante i download e mostra in una notifica quanto manca. */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            YtDownloads.cancelAll()
            return START_NOT_STICKY
        }
        ensureChannel()
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        YtDownloads.onChange = { refresh() }
        // se i download sono già finiti prima che il servizio partisse, la notifica non deve restare appesa
        if (!YtDownloads.active) refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (!YtDownloads.active) {
            YtDownloads.onChange = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun build(): Notification {
        val s = YtDownloads.snapshot()
        val left = (s.total - s.done).coerceAtLeast(0)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (left == 1) "Manca 1 brano" else "Mancano $left brani")
            .setContentText(if (s.title.isBlank()) "Download in corso" else "${s.title} · ${s.pct}%")
            .setSubText("${s.done}/${s.total}")
            .setProgress((s.total * 100).coerceAtLeast(1), s.done * 100 + s.pct, s.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ferma tutto", stop)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Download", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    override fun onDestroy() {
        YtDownloads.onChange = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 4201
        private const val ACTION_STOP = "com.marco.sonara.STOP_DOWNLOADS"
    }
}
