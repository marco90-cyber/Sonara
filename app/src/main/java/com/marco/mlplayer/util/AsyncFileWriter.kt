package com.marco.mlplayer.util

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Scrive un file di testo fuori dal thread principale, sempre nell'ordine delle richieste
 * (l'ultima vince) e in modo atomico: prima un file temporaneo, poi lo scambio. Un'interruzione
 * a metà scrittura non lascia mai un file troncato.
 */
class AsyncFileWriter(private val file: File, threadName: String) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, threadName).apply { isDaemon = true } }

    init { all.add(this) }

    /** Aspetta che le scritture già richieste finiscano (max 3 s). */
    private fun flush() {
        runCatching { exec.submit {}.get(3, TimeUnit.SECONDS) }
    }

    companion object {
        private val all = CopyOnWriteArrayList<AsyncFileWriter>()

        /** Aspetta tutte le scritture in coda: serve prima di riavviare l'app. */
        fun flushAll() = all.forEach { it.flush() }
    }

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
