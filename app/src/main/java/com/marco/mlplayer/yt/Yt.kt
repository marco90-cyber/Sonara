package com.marco.mlplayer.yt

import android.net.Uri
import com.marco.mlplayer.data.Track
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.Net
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Livello YouTube Music di Sonara: ricerca e stream audio tramite NewPipeExtractor
 * (stessa libreria di LastWave). Tutto gira in locale, senza server intermedi.
 */
object Yt {
    private const val TAG = "YouTube"
    private val VIDEO_ID_QUERY = Regex("[?&]v=([A-Za-z0-9_-]{6,})")
    private val VIDEO_ID_SHORT = Regex("youtu\\.be/([A-Za-z0-9_-]{6,})")
    private val PLAYLIST_ID = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val CHANNEL_ID = Regex("/channel/([A-Za-z0-9_-]+)")

    /** Un brano trovato (id del video, titolo, artista, durata, miniatura). */
    data class Item(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSec: Long,
        val thumb: String,
    ) {
        /** Miniatura da mostrare: quella trovata oppure, se manca, quella del video (esiste sempre). */
        val cover: String get() = if (thumb.isBlank()) "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" else bigThumb(thumb)

        fun toJson(): JSONObject =
            JSONObject().put("id", videoId).put("title", title).put("artist", artist).put("dur", durationSec).put("thumb", thumb)

        companion object {
            /** Il brano salvato in JSON da [toJson]; null se manca l'id. */
            fun fromJson(o: JSONObject): Item? {
                val id = o.optString("id")
                return if (id.isBlank()) null else Item(id, o.optString("title"), o.optString("artist"), o.optLong("dur"), o.optString("thumb"))
            }
        }
    }

    private val THUMB_SIZE = Regex("=(w\\d+-h\\d+|s\\d+)")

    /** Le miniature di Google hanno la misura nell'indirizzo («=w226-h226», «=s120»): ne chiedo una nitida da 544 px. */
    fun bigThumb(url: String): String = if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) url.replace(THUMB_SIZE, "=w544-h544") else url

    class Resolved(val url: String, val expiresAtMs: Long, val mime: String, val bitrateKbps: Int)

    private val http = Net.client

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

    /** Scorre le pagine di risultati (fino a [maxPages]) e dopo ognuna chiama [onPage] con tutto ciò che c'è finora. */
    private fun collectPages(ex: SearchExtractor, maxPages: Int, onPage: (List<InfoItem>) -> Unit) {
        ex.fetchPage()
        val all = ArrayList<InfoItem>(ex.initialPage.items)
        onPage(all.toList())
        var next = ex.initialPage.nextPage
        var n = 1
        while (next != null && n < maxPages) {
            val p = try {
                ex.getPage(next)
            } catch (e: Exception) {
                AppLog.w(TAG, "Pagina $n dei risultati non riuscita: ${e.javaClass.simpleName}")
                break
            }
            all.addAll(p.items)
            onPage(all.toList())
            next = p.nextPage
            n++
        }
    }

    /**
     * Cerca brani su YouTube Music (solo canzoni), su più pagine. Da chiamare fuori dal thread principale.
     * [progress] riceve l'elenco parziale dopo ogni pagina, così i primi risultati si vedono subito.
     */
    fun search(query: String, maxPages: Int = 5, progress: (List<Item>) -> Unit = {}): List<Item> {
        init()
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        var last = emptyList<Item>()
        collectPages(ex, maxPages) { items ->
            last = items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toItem() }.distinctBy { it.videoId }
            progress(last)
        }
        AppLog.i(TAG, "Ricerca \"$query\": ${last.size} brani")
        return last
    }

    /** Titolo e copertina di una playlist YouTube; null se non si riesce a leggerli. Bloccante. */
    fun playlistMeta(listId: String): Pair<String, String>? = runCatching {
        init()
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/playlist?list=$listId")
        info.name.orEmpty() to info.thumbnails.maxByOrNull { it.height * it.width }?.url.orEmpty()
    }.getOrNull()?.takeIf { it.first.isNotBlank() }

    enum class Kind(val label: String) { Songs("Brani"), Artists("Artisti"), Albums("Album"), Playlists("Playlist") }

    /** Un risultato che non è un brano: artista, album o playlist (si apre per vederne i brani). */
    data class Entry(val kind: Kind, val browseId: String, val title: String, val subtitle: String, val thumb: String) {
        fun toJson(): JSONObject =
            JSONObject().put("kind", kind.name).put("id", browseId).put("title", title).put("sub", subtitle).put("thumb", thumb)

        companion object {
            /** La voce salvata in JSON da [toJson]; null se il tipo o l'id non sono validi. */
            fun fromJson(o: JSONObject): Entry? {
                val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
                val id = o.optString("id")
                return if (id.isBlank()) null else Entry(kind, id, o.optString("title"), o.optString("sub"), o.optString("thumb"))
            }
        }
    }

    /** Cerca artisti, album o playlist su YouTube Music, su più pagine. Da chiamare fuori dal thread principale. */
    fun searchEntries(query: String, kind: Kind, maxPages: Int = 4, progress: (List<Entry>) -> Unit = {}): List<Entry> {
        init()
        val filter = when (kind) {
            Kind.Artists -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
            Kind.Albums -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
            Kind.Playlists -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
            Kind.Songs -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        }
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        var last = emptyList<Entry>()
        collectPages(ex, maxPages) { items ->
            last = items.mapNotNull { toEntry(it, kind) }.distinctBy { it.browseId }
            progress(last)
        }
        AppLog.i(TAG, "Ricerca ${kind.label} \"$query\": ${last.size} risultati")
        return last
    }

    private fun toEntry(item: InfoItem, kind: Kind): Entry? {
        val thumb = item.thumbnails.maxByOrNull { t -> t.height * t.width }?.url.orEmpty()
        return when (item) {
            is PlaylistInfoItem -> {
                val list = PLAYLIST_ID.find(item.url)?.groupValues?.get(1) ?: return null
                val sub = listOfNotNull(
                    item.uploaderName?.takeIf { u -> u.isNotBlank() },
                    item.streamCount.takeIf { n -> n > 0 }?.let { n -> "$n brani" },
                ).joinToString(" · ")
                Entry(kind, if (list.startsWith("VL")) list else "VL$list", item.name.orEmpty(), sub, thumb)
            }
            is ChannelInfoItem -> {
                val id = CHANNEL_ID.find(item.url)?.groupValues?.get(1) ?: return null
                Entry(kind, id, item.name.orEmpty(), "Artista", thumb)
            }
            else -> null
        }
    }

    private fun StreamInfoItem.toItem(): Item? {
        val id = videoIdOf(url) ?: return null
        val thumb = thumbnails.maxByOrNull { it.height * it.width }?.url.orEmpty()
        return Item(id, name.orEmpty(), uploaderName.orEmpty(), duration.coerceAtLeast(0), thumb)
    }

    /** Bitrate in kbps: alcuni flussi lo dichiarano in bit al secondo. */
    private fun kbpsOf(average: Int, bitrate: Int): Int = maxOf(average, bitrate).let { if (it > 2000) it / 1000 else it }

    fun videoIdOf(url: String): String? =
        VIDEO_ID_QUERY.find(url)?.groupValues?.get(1) ?: VIDEO_ID_SHORT.find(url)?.groupValues?.get(1)

    /** Qualità scelta nelle impostazioni per la rete in uso (0 = massima, 1 = alta, 2 = risparmio): la imposta l'app all'avvio. */
    @Volatile var qualityProvider: () -> Int = { 0 }

    fun currentQuality(): Int = runCatching { qualityProvider() }.getOrDefault(0).coerceIn(0, 2)

    /** Oltre questo bitrate (kbps) un flusso non è più "alto" ma "massimo". */
    private const val HIGH_KBPS = 160

    /**
     * Quale flusso scegliere tra quelli con questi bitrate (kbps): 0 = il più ricco, 1 = il più ricco fino a [HIGH_KBPS]
     * (se non ce n'è, il più leggero), 2 = il più leggero. -1 se non ce ne sono.
     */
    fun pickIndex(kbps: List<Int>, quality: Int): Int {
        if (kbps.isEmpty()) return -1
        val lightest = kbps.indices.minByOrNull { kbps[it] }!!
        return when (quality) {
            2 -> lightest
            1 -> kbps.indices.filter { kbps[it] <= HIGH_KBPS }.maxByOrNull { kbps[it] } ?: lightest
            else -> kbps.indices.maxByOrNull { kbps[it] }!!
        }
    }

    private fun cacheKey(videoId: String, preferMp4: Boolean, quality: Int) = "$videoId#${if (preferMp4) "m4a" else "any"}#q$quality"

    private val prefetchExec = Executors.newSingleThreadExecutor { r -> Thread(r, "yt-prefetch").apply { isDaemon = true } }
    private val prefetching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Prepara in anticipo il link del brano (di solito il successivo della coda), così all'avvio non c'è da aspettare. */
    fun prefetch(videoId: String) {
        val quality = currentQuality()
        if (runCatching { YtDownloads.fileUri(videoId) }.getOrNull() != null) return
        if (cache[cacheKey(videoId, false, quality)]?.let { it.expiresAtMs - 90_000 > System.currentTimeMillis() } == true) return
        if (!prefetching.add(videoId)) return
        prefetchExec.execute {
            try {
                resolve(videoId, quality = quality)
            } catch (e: Exception) {
                AppLog.w(TAG, "Preparazione di $videoId non riuscita: ${e.message}")
            } finally {
                prefetching.remove(videoId)
            }
        }
    }

    /**
     * Link audio valido adesso per il video (con cache fino alla scadenza). Bloccante.
     * [quality]: 0 = il flusso più ricco, 1 = alta, 2 = risparmio; i download usano sempre la massima.
     */
    fun resolve(videoId: String, preferMp4: Boolean = false, quality: Int = 0): Resolved {
        val now = System.currentTimeMillis()
        val key = cacheKey(videoId, preferMp4, quality)
        cache[key]?.takeIf { it.expiresAtMs - 90_000 > now }?.let { return it }
        init()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: Exception) {
            AppLog.e(TAG, "Estrazione non riuscita per $videoId", e)
            throw IOException("YouTube: estrazione non riuscita per $videoId", e)
        }
        val usable = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
            .filter { !it.content.contains("xtags=drc") }
        // per i download preferisco l'AAC (m4a): ci si possono scrivere i tag e lo leggono tutti i lettori
        val pool = if (preferMp4) usable.filter { it.format?.mimeType?.contains("mp4") == true }.ifEmpty { usable } else usable
        val best = pool.getOrNull(pickIndex(pool.map { kbpsOf(it.averageBitrate, it.bitrate) }, quality))
            ?: throw IOException("YouTube: nessun flusso audio per $videoId").also {
                AppLog.w(TAG, "Nessun flusso audio progressivo per $videoId (${info.audioStreams.size} flussi)")
            }
        val expire = Uri.parse(best.content).getQueryParameter("expire")?.toLongOrNull()?.times(1000)
            ?: (now + 5 * 60_000)
        val kbps = kbpsOf(best.averageBitrate, best.bitrate)
        val r = Resolved(best.content, expire, best.format?.mimeType.orEmpty(), kbps)
        if (cache.size >= 64) cache.entries.removeIf { it.value.expiresAtMs <= now }
        cache[key] = r
        AppLog.i(TAG, "Stream pronto per $videoId: ${r.mime} ${r.bitrateKbps} kbps")
        return r
    }

    /** Dimentica il link (scaduto o rifiutato, in entrambe le varianti) e la cache dello script del player. */
    fun invalidate(videoId: String) {
        cache.keys.removeIf { it.startsWith("$videoId#") }
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
            if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", Net.BROWSER_UA)
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
