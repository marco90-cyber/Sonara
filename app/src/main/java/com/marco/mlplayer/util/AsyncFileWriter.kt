package com.marco.mlplayer.util

import java.io.File
import java.util.concurrent.Executors

/**
 * Scrive un file di testo fuori dal thread principale, sempre nell'ordine delle richieste
 * (l'ultima vince) e in modo atomico: prima un file temporaneo, poi lo scambio. Un'interruzione
 * a metà scrittura non lascia mai un file troncato.
 */
class AsyncFileWriter(private val file: File, threadName: String) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, threadName).apply { isDaemon = true } }

    fun write(text: String, onError: (Throwable) -> Unit = {}) {
        exec.execute {
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }
}
