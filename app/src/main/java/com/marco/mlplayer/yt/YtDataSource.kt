package com.marco.mlplayer.yt

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.marco.mlplayer.util.Net
import java.io.IOException

/**
 * I brani YouTube hanno come indirizzo "yt:<idVideo>" (stabile): il link vero, che scade,
 * si ricava solo quando Media3 apre il flusso.
 */
@UnstableApi
object YtDataSource {
    fun factory(context: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.BROWSER_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(context, http)
        return ResolvingDataSource.Factory(upstream, ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme != "yt") return@Resolver spec
            val id = spec.uri.schemeSpecificPart.orEmpty()
            if (id.isBlank()) throw IOException("Indirizzo YouTube non valido")
            YtDownloads.fileUri(id)?.let { return@Resolver spec.withUri(it) }
            spec.withUri(Uri.parse(Yt.resolve(id).url))
        })
    }
}
