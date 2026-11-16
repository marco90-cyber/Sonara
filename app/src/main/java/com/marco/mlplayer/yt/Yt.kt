package com.marco.mlplayer.yt

import android.net.Uri
import com.marco.mlplayer.data.Track
import com.marco.mlplayer.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Livello YouTube Music di Sonara: ricerca e stream audio tramite NewPipeExtractor
 * (stessa libreria di LastWave). Tutto gira in locale, senza server intermedi.
 */
object Yt {
    private const val TAG = "YouTube"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** Un brano trovato (id del video, titolo, artista, durata, miniatura). */
    data class Item(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSec: Long,
        val thumb: String,
    ) {
        /** Miniatura da mostrare: quella trovata oppure, se manca, quella del video (esiste sempre). */
        val cover: String get() = thumb.ifBlank { "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" }
    }

    class Resolved(val url: String, val expiresAtMs: Long, val mime: String, val bitrateKbps: Int)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ready = false
    private val cache = ConcurrentHashMap<String, Resolved>()

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            NewPipe.init(OkDownloader(http), Localization("it", "IT"), ContentCountry("IT"))
            ready = true
        }
    }

    /** Cerca brani su YouTube Music (solo canzoni). Da chiamare fuori dal thread principale. */
    fun search(query: String): List<Item> {
        init()
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        ex.fetchPage()
        val out = ex.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toItem() }
        AppLog.i(TAG, "Ricerca \"$query\": ${out.size} brani")
        return out
    }

    enum class Kind(val label: String) { Songs("Brani"), Artists("Artisti"), Albums("Album"), Playlists("Playlist") }

    /** Un risultato che non è un brano: artista, album o playlist (si apre per vederne i brani). */
    data class Entry(val kind: Kind, val browseId: String, val title: String, val subtitle: String, val thumb: String)

    /** Cerca artisti, album o playlist su YouTube Music. Da chiamare fuori dal thread principale. */
    fun searchEntries(query: String, kind: Kind): List<Entry> {
        init()
        val filter = when (kind) {
            Kind.Artists -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
            Kind.Albums -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
            Kind.Playlists -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
            Kind.Songs -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        }
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        ex.fetchPage()
        val out = ArrayList<Entry>()
        for (it in ex.initialPage.items) {
            val thumb = it.thumbnails.maxByOrNull { t -> t.height * t.width }?.url.orEmpty()
            when (it) {
                is PlaylistInfoItem -> {
                    val list = Regex("[?&]list=([A-Za-z0-9_-]+)").find(it.url)?.groupValues?.get(1) ?: continue
                    val sub = listOfNotNull(
                        it.uploaderName?.takeIf { u -> u.isNotBlank() },
                        it.streamCount.takeIf { n -> n > 0 }?.let { n -> "$n brani" },
                    ).joinToString(" · ")
                    out.add(Entry(kind, if (list.startsWith("VL")) list else "VL$list", it.name.orEmpty(), sub, thumb))
                }
                is ChannelInfoItem -> {
                    val id = Regex("/channel/([A-Za-z0-9_-]+)").find(it.url)?.groupValues?.get(1) ?: continue
                    out.add(Entry(kind, id, it.name.orEmpty(), "Artista", thumb))
                }
                else -> {}
            }
        }
        AppLog.i(TAG, "Ricerca ${kind.label} \"$query\": ${out.size} risultati")
        return out
    }

    private fun StreamInfoItem.toItem(): Item? {
        val id = videoIdOf(url) ?: return null
        val thumb = thumbnails.maxByOrNull { it.height * it.width }?.url.orEmpty()
        return Item(id, name.orEmpty(), uploaderName.orEmpty(), duration.coerceAtLeast(0), thumb)
    }

    fun videoIdOf(url: String): String? =
        Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
            ?: Regex("youtu\\.be/([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)

    /** Link audio valido adesso per il video (con cache fino alla scadenza). Bloccante. */
    fun resolve(videoId: String): Resolved {
        val now = System.currentTimeMillis()
        cache[videoId]?.takeIf { it.expiresAtMs - 90_000 > now }?.let { return it }
        init()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: Exception) {
            AppLog.e(TAG, "Estrazione non riuscita per $videoId", e)
            throw IOException("YouTube: estrazione non riuscita per $videoId", e)
        }
        val best = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
            .filter { !it.content.contains("xtags=drc") }
            .maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
            ?: throw IOException("YouTube: nessun flusso audio per $videoId").also {
                AppLog.w(TAG, "Nessun flusso audio progressivo per $videoId (${info.audioStreams.size} flussi)")
            }
        val expire = Uri.parse(best.content).getQueryParameter("expire")?.toLongOrNull()?.times(1000)
            ?: (now + 5 * 60_000)
        val kbps = maxOf(best.averageBitrate, best.bitrate).let { if (it > 2000) it / 1000 else it }
        val r = Resolved(best.content, expire, best.format?.mimeType.orEmpty(), kbps)
        cache[videoId] = r
        AppLog.i(TAG, "Stream pronto per $videoId: ${r.mime} ${r.bitrateKbps} kbps")
        return r
    }

    /** Dimentica il link (scaduto o rifiutato) e la cache dello script del player. */
    fun invalidate(videoId: String) {
        cache.remove(videoId)
        runCatching { YoutubeJavaScriptPlayerManager.clearAllCaches() }
    }

    /** L'elemento YouTube di un brano in coda (solo per i brani "yt:<id>"). */
    fun itemOf(t: Track): Item? {
        if (!t.uri.startsWith("yt:")) return null
        return Item(t.uri.removePrefix("yt:"), t.title, t.artist, t.durationMs / 1000, t.coverUrl)
    }

    fun toTrack(i: Item) = Track(
        id = "yt_${i.videoId}",
        uri = "yt:${i.videoId}",
        title = i.title,
        artist = i.artist,
        album = "YouTube Music",
        albumArtist = i.artist,
        genre = "",
        durationMs = i.durationSec * 1000,
        trackNo = 0, discNo = 0, year = 0,
        sampleRate = 0, bits = 0, bitrateKbps = 0,
        ext = "", size = 0, modified = 0,
        albumKey = "yt",
        coverUrl = i.cover,
    )

    private class OkDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val b = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            val headers = request.headers()
            headers.forEach { (n, vs) -> vs.forEach { v -> b.addHeader(n, v) } }
            if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", UA)
            if (headers.keys.none { it.equals("Accept-Language", true) }) b.header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
            return client.newCall(b.build()).execute().use { r ->
                Response(
                    r.code, r.message,
                    r.headers.names().associateWith(r.headers::values),
                    r.body?.string().orEmpty(),
                    r.request.url.toString(),
                )
            }
        }
    }
}
