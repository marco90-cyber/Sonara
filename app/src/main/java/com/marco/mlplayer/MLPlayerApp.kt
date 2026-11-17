package com.marco.mlplayer

import android.app.Application
import com.marco.mlplayer.audio.AudioEngine
import com.marco.mlplayer.data.Library
import com.marco.mlplayer.data.Settings
import com.marco.mlplayer.feedback.Feedback
import com.marco.mlplayer.ui.AppEnv
import com.marco.mlplayer.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MLPlayerApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var settings: Settings
        private set
    lateinit var library: Library
        private set
    lateinit var engine: AudioEngine
        private set
    lateinit var feedback: Feedback
        private set
    lateinit var ytStore: com.marco.mlplayer.yt.YtStore
        private set
    lateinit var ytImports: com.marco.mlplayer.yt.YtImports
        private set

    /** Stato dell'interfaccia: sopravvive alla ricreazione dell'Activity. */
    val env: AppEnv by lazy { AppEnv(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        settings = Settings(this)
        AppLog.verbose = settings.verboseLog
        library = Library(this)
        ytStore = com.marco.mlplayer.yt.YtStore(this)
        ytImports = com.marco.mlplayer.yt.YtImports(this)
        com.marco.mlplayer.yt.YtDownloads.init(this)
        com.marco.mlplayer.yt.YtBackup.init(this)
        // l'app è visibile se almeno un'activity è avviata: a importazione finita in background si avvisa con una notifica
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; com.marco.mlplayer.yt.ImportRunner.visible = true }
            override fun onActivityStopped(activity: android.app.Activity) { started--; com.marco.mlplayer.yt.ImportRunner.visible = started > 0 }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        com.marco.mlplayer.yt.ImportScheduler.apply(this)
        feedback = Feedback(this, settings)
        engine = AudioEngine(this, settings, library)
        engine.connectService()

        appScope.launch {
            library.load()
            if ((settings.scanOnStart || library.needsCoverFix) && settings.folders.isNotEmpty()) {
                library.rescan(settings.folders, rebuild = false)
            }
        }
    }
}
