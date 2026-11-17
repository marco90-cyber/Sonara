package com.marco.mlplayer.yt

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.marco.mlplayer.util.AppLog
import java.io.File

/**
 * Copia su disco dei brani in streaming (la più vecchia esce per prima): riascolti e salti indietro non riscaricano.
 * La dimensione vale dall'avvio dell'app; i brani scaricati hanno la loro cartella e non passano da qui.
 */
@OptIn(UnstableApi::class)
object YtCache {
    private var cache: SimpleCache? = null
    private var dir: File? = null

    /** La cache (creata alla prima richiesta con la dimensione [maxMb]); null se spenta o non creabile. */
    @UnstableApi
    @Synchronized
    fun get(context: Context, maxMb: Int): SimpleCache? {
        if (maxMb <= 0) return null
        cache?.let { return it }
        val d = File(context.cacheDir, "audio").also { dir = it }
        return runCatching {
            SimpleCache(d, LeastRecentlyUsedCacheEvictor(maxMb * 1024L * 1024L), StandaloneDatabaseProvider(context))
        }.onFailure { AppLog.w("Cache", "Cache audio non creata: ${it.message}") }.getOrNull().also { cache = it }
    }

    /** Spazio occupato adesso, in byte. */
    @Synchronized
    fun usedBytes(): Long = cache?.cacheSpace ?: 0L

    /** Toglie tutti i brani dalla cache. */
    @Synchronized
    fun clear() {
        val c = cache ?: return
        runCatching { c.keys.toList().forEach { c.removeResource(it) } }
            .onFailure { AppLog.w("Cache", "Svuotamento non riuscito: ${it.message}") }
    }
}
