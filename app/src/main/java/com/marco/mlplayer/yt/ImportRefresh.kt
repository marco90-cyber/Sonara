package com.marco.mlplayer.yt

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.marco.mlplayer.MLPlayerApp
import com.marco.mlplayer.audio.ImportNotify
import com.marco.mlplayer.util.AppLog

/** Controllo periodico delle playlist importate: il sistema lo lancia anche con l'app chiusa. */
class ImportRefreshJob : JobService() {
    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext as? MLPlayerApp ?: return false
        thread = Thread({
            try {
                ImportRunner.runSync {
                    val changes = ImportRunner.refreshAll(app)
                    if (app.settings.importNotify) ImportNotify.updated(app, changes)
                }
            } catch (e: Throwable) {
                AppLog.e("Importa", "Controllo periodico non riuscito", e)
            } finally {
                jobFinished(params, false)
            }
        }, "import-refresh").also { it.isDaemon = true; it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        ImportRunner.cancelAll()
        return false
    }
}

/** Accende o spegne il controllo periodico secondo le impostazioni. */
object ImportScheduler {
    private const val JOB_ID = 4301

    fun apply(ctx: Context) {
        val app = ctx.applicationContext as? MLPlayerApp ?: return
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val s = app.settings
        if (!s.importAutoRefresh) { js.cancel(JOB_ID); return }
        val period = s.importRefreshHours.coerceAtLeast(1) * 3_600_000L
        val network = if (s.importWifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        // già programmato con gli stessi valori: non lo riparto, altrimenti ogni avvio dell'app sposterebbe il prossimo controllo
        js.getPendingJob(JOB_ID)?.let { if (it.intervalMillis == period && it.networkType == network) return }
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, ImportRefreshJob::class.java))
            .setPeriodic(period)
            .setRequiredNetworkType(network)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        val ok = runCatching { js.schedule(job) }.getOrDefault(JobScheduler.RESULT_FAILURE)
        AppLog.i("Importa", "Controllo periodico ogni ${s.importRefreshHours} h: ${if (ok == JobScheduler.RESULT_SUCCESS) "programmato" else "non programmato"}")
    }
}
