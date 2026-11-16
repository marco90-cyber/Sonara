package com.marco.mlplayer.yt

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import java.io.IOException

/**
 * I brani YouTube hanno come indirizzo "yt:<idVideo>" (stabile): il link vero, che scade,
 * si ricava solo quando Media3 apre il flusso.
 */
@UnstableApi
object YtDataSource {
    fun factory(context: Context): androidx.media3.datasource.DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(context, http)
        return ResolvingDataSource.Factory(upstream, ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme != "yt") return@Resolver spec
            val id = spec.uri.schemeSpecificPart.orEmpty()
            if (id.isBlank()) throw IOException("Indirizzo YouTube non valido")
            YtDownloads.fileUri(id)?.let { return@Resolver spec.withUri(it) }
            spec.withUri(android.net.Uri.parse(Yt.resolve(id).url))
        })
    }
}
