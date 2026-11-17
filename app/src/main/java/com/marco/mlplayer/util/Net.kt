package com.marco.mlplayer.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Client OkHttp e User-Agent condivisi: un solo pool di connessioni e un solo dispatcher per tutta l'app. */
object Net {
    const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** Base comune: connessione 15 s, lettura 20 s. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Stesso pool di [client] ma con un timeout di lettura diverso. */
    fun withReadTimeout(readTimeoutSec: Long): OkHttpClient =
        client.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
}
