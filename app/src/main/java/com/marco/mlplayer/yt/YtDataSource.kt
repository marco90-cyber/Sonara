package com.marco.mlplayer.yt

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import com.marco.mlplayer.util.Net
import java.io.IOException

/**
 * I brani YouTube hanno come indirizzo "yt:<idVideo>" (stabile): il link vero, che scade,
 * si ricava solo quando Media3 apre il flusso. Il flusso passa dalla cache su disco ([YtCache]),
 * con una chiave che non dipende dal link (cambia a ogni richiesta) ma dal brano e dal formato scelto.
 */
@UnstableApi
object YtDataSource {
    fun factory(context: Context, cacheMb: Int): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.BROWSER_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(context, http)
        val cache = YtCache.get(context, cacheMb)
        val source: DataSource.Factory = if (cache == null) upstream else {
            val cached = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            DataSource.Factory { WebCachedDataSource(cached.createDataSource(), upstream.createDataSource()) }
        }
        return ResolvingDataSource.Factory(source, ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme != "yt") return@Resolver spec
            val id = spec.uri.schemeSpecificPart.orEmpty()
            if (id.isBlank()) throw IOException("Indirizzo YouTube non valido")
            // con la sorgente cambiata il file scaricato (dell'audio sbagliato) non si usa e la cache segue il video scelto
            val overridden = YtOverrides.has(id)
            if (!overridden) YtDownloads.fileUri(id)?.let { return@Resolver spec.withUri(it) }
            val r = Yt.resolve(id, quality = Yt.currentQuality())
            spec.buildUpon().setUri(Uri.parse(r.url)).setKey("yt:${YtOverrides.target(id)}:${r.mime}:${r.bitrateKbps}").build()
        })
    }
}

/** Solo gli indirizzi web passano dalla cache; i file (scaricati o della libreria) si leggono direttamente. */
@UnstableApi
private class WebCachedDataSource(private val web: DataSource, private val direct: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        web.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val source = if (scheme == "http" || scheme == "https") web else direct
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
