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
import androidx.media3.common.util.UnstableApi
import com.marco.mlplayer.MainActivity
import com.marco.mlplayer.ai.LyricsAi

/** Tiene vivo l'app mentre l'AI genera un testo e mostra l'avanzamento in una notifica. */
@UnstableApi
class LyricsAiService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LyricsAi.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels()
        val n = build(LyricsAi.state as? LyricsAi.State.Working)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        LyricsAi.onChange = { refresh() }
        if (LyricsAi.state !is LyricsAi.State.Working) refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        val st = LyricsAi.state
        val nm = getSystemService(NotificationManager::class.java)
        if (st is LyricsAi.State.Working) {
            nm.notify(ID, build(st))
            return
        }
        LyricsAi.onChange = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // esito: una notifica normale, così lo sai anche con lo schermo spento
        val text = when (st) {
            is LyricsAi.State.Done -> "Testo pronto: ${LyricsAi.title}"
            is LyricsAi.State.Failed -> "Testo non generato: ${st.message}"
            else -> return
        }
        nm.notify(ID_DONE, NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Testo con AI")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open())
            .build())
    }

    private fun open() = PendingIntent.getActivity(
        this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(st: LyricsAi.State.Working?): Notification {
        val stop = PendingIntent.getService(
            this, 4, Intent(this, LyricsAiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pct = st?.percent ?: 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Genero il testo: ${LyricsAi.title}")
            .setContentText(if (st == null) "Preparo" else "${st.stage}${if (pct > 0) " · $pct%" else ""}")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annulla", stop)
            .build()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Testi con AI", NotificationManager.IMPORTANCE_LOW))
            if (nm.getNotificationChannel(CHANNEL_DONE) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Testi con AI: esito", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    override fun onDestroy() {
        LyricsAi.onChange = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "lyrics_ai"
        private const val CHANNEL_DONE = "lyrics_ai_done"
        private const val ID = 4202
        private const val ID_DONE = 4203
        private const val ACTION_STOP = "com.marco.sonara.STOP_LYRICS_AI"
    }
}
