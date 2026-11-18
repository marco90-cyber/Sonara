package com.marco.mlplayer.yt

import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.Net
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Chiamate a YouTube Music (InnerTube, client WEB_REMIX), senza account. */
object YtApi {
    private const val TAG = "YouTube"
    private const val ORIGIN = "https://music.youtube.com"
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20250929.01.00"
    private val THUMB_SIZE = Regex("=w\\d+-h\\d+")

    private val http = Net.withReadTimeout(readTimeoutSec = 25)

    /** POST a un endpoint InnerTube (senza account). */
    fun post(endpoint: String, body: JSONObject, continuation: String? = null): JSONObject {
        body.put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "it").put("gl", "IT")))
        var url = "$ORIGIN/youtubei/v1/$endpoint?key=$KEY&prettyPrint=false"
        if (continuation != null) url += "&ctoken=$continuation&continuation=$continuation&type=next"
        val b = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", Net.BROWSER_UA)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Origin", ORIGIN)
            .header("Accept-Language", "it-IT,it;q=0.9")
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                AppLog.w(TAG, "$endpoint: HTTP ${r.code} ${text.take(160).replace('\n', ' ')}")
                throw IOException("YouTube Music: HTTP ${r.code}")
            }
            return JSONObject(text)
        }
    }

    fun browse(browseId: String?, continuation: String? = null, params: String? = null): JSONObject =
        post("browse", JSONObject().apply {
            if (browseId != null) put("browseId", browseId)
            if (params != null) put("params", params)
        }, continuation)

    // ------------------------------------------------------------------ playlist e artisti

    /** Brani di una playlist (id "VL...", oppure "VLLM" per i brani che ti piacciono), fino a ~1500. */
    fun playlistTracks(browseId: String): List<Yt.Item> {
        val out = ArrayList<Yt.Item>()
        val seen = HashSet<String>()
        var json = browse(browseId)
        for (page in 0 until 15) {
            for (o in collect(json, "musicResponsiveListItemRenderer")) parseTrack(o)?.let { if (seen.add(it.videoId)) out.add(it) }
            val next = nextToken(json) ?: break
            json = browse(null, next)
        }
        AppLog.i(TAG, "Playlist $browseId: ${out.size} brani")
        return out
    }

    /** Contenuto di una pagina aperta: i brani e, per un artista, gli scaffali di album, singoli, ecc. */
    data class Detail(val tracks: List<Yt.Item>, val shelves: List<Shelf>)

    /** Pagina di un artista: tutti i suoi brani ("mostra tutto") e gli scaffali di album e singoli. */
    fun artist(channelId: String): Detail {
        val json = browse(channelId)
        val shelf = collect(json, "musicShelfRenderer").firstOrNull()
        var songs: List<Yt.Item> = shelf?.let { sh -> collect(sh, "musicResponsiveListItemRenderer").mapNotNull { parseTrack(it) } }.orEmpty()
        val moreId = shelf?.let { sh ->
            collect(sh, "bottomEndpoint").firstOrNull()?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?: collect(sh, "title").firstOrNull()?.optJSONArray("runs")?.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
        }?.takeIf { it.isNotBlank() }
        if (moreId != null) {
            runCatching { playlistTracks(moreId) }.onSuccess { if (it.size > songs.size) songs = it }
                .onFailure { AppLog.w(TAG, "Elenco completo dei brani non riuscito: ${it.message}") }
        }
        // titoli doppi farebbero cadere le liste dell'interfaccia (chiavi duplicate): ne tengo uno solo
        val shelves = collect(json, "musicCarouselShelfRenderer").mapNotNull { parseShelf(it) }.distinctBy { it.title }
        AppLog.i(TAG, "Artista $channelId: ${songs.size} brani, ${shelves.size} scaffali")
        return Detail(songs, shelves)
    }

    // ------------------------------------------------------------------ home e radio

    /** Una scheda della home: un brano da suonare oppure un album/playlist/artista da aprire. */
    data class Card(val song: Yt.Item?, val entry: Yt.Entry?) {
        /** Identificativo della scheda (video o browseId): serve a evitare doppioni nelle liste. */
        val id get() = song?.videoId ?: entry?.browseId.orEmpty()
        val title get() = song?.title ?: entry?.title.orEmpty()
        val subtitle get() = song?.artist ?: entry?.subtitle.orEmpty()
        val thumb get() = song?.cover ?: entry?.thumb.orEmpty()
    }

    data class Shelf(val title: String, val cards: List<Card>)

    /** Home di YouTube Music: gli scaffali di suggerimenti (personalizzati se c'è l'account). */
    fun home(): List<Shelf> {
        val out = ArrayList<Shelf>()
        var json = browse("FEmusic_home")
        for (page in 0 until 3) {
            for (sh in collect(json, "musicCarouselShelfRenderer")) parseShelf(sh)?.let { out.add(it) }
            val next = nextToken(json) ?: break
            json = browse(null, next)
        }
        AppLog.i(TAG, "Home: ${out.size} scaffali")
        return out.distinctBy { it.title }
    }

    private fun parseShelf(sh: JSONObject): Shelf? {
        val title = runsText(collect(sh.optJSONObject("header"), "title").firstOrNull())
        val arr = sh.optJSONArray("contents") ?: return null
        val cards = ArrayList<Card>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { cardOf(it) }?.let { cards.add(it) }
        return if (title.isBlank() || cards.isEmpty()) null else Shelf(title, cards.distinctBy { it.id })
    }

    /** Una scheda da un elemento di scaffale: brano (riga o scheda) oppure artista, album, playlist. */
    private fun cardOf(c: JSONObject): Card? {
        c.optJSONObject("musicResponsiveListItemRenderer")?.let { r -> parseTrack(r)?.let { return Card(it, null) } }
        val t = c.optJSONObject("musicTwoRowItemRenderer") ?: return null
        val nav = t.optJSONObject("navigationEndpoint")
        val videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
        val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        val name = runsText(t.optJSONObject("title"))
        val sub = runsText(t.optJSONObject("subtitle"))
        val thumb = thumbOf(t.optJSONObject("thumbnailRenderer"))
        if (name.isBlank()) return null
        if (videoId.isNotBlank()) {
            val parts = sub.split(" • ").map { it.trim() }.filter { it.isNotBlank() }
            val artist = if (parts.size > 1 && parts[0].lowercase() in setOf("brano", "song", "video")) parts[1] else parts.firstOrNull().orEmpty()
            return Card(Yt.Item(videoId, name, artist, 0, thumb), null)
        }
        val kind = when {
            browseId.startsWith("UC") -> Yt.Kind.Artists
            browseId.startsWith("MPRE") -> Yt.Kind.Albums
            browseId.startsWith("VL") -> Yt.Kind.Playlists
            else -> return null
        }
        return Card(null, Yt.Entry(kind, browseId, name, sub, thumb))
    }

    // ------------------------------------------------------------------ esplora

    data class Chip(val title: String, val params: String)

    /** Home con i filtri in alto («Relax», «Energia pura»…): i filtri e gli scaffali della pagina [params] (null = home normale). */
    fun homeFeed(params: String? = null): Pair<List<Chip>, List<Shelf>> {
        val json = browse("FEmusic_home", params = params)
        val chips = ArrayList<Chip>()
        for (c in collect(json, "chipCloudChipRenderer")) {
            val title = runsText(c.optJSONObject("text"))
            val p = c.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("params").orEmpty()
            if (title.isNotBlank() && p.isNotBlank()) chips.add(Chip(title, p))
        }
        val shelves = collect(json, "musicCarouselShelfRenderer").mapNotNull { parseShelf(it) }.distinctBy { it.title }
        AppLog.i(TAG, "Home ${params ?: "base"}: ${chips.size} filtri, ${shelves.size} scaffali")
        return chips.distinctBy { it.title } to shelves
    }

    /** Generi e atmosfere. Le voci hanno id «cat:<browseId>|<params>» e si aprono con [category]. */
    fun moodsAndGenres(): List<Yt.Entry> {
        val json = browse("FEmusic_moods_and_genres")
        val out = ArrayList<Yt.Entry>()
        for (b in collect(json, "musicNavigationButtonRenderer")) {
            val title = runsText(b.optJSONObject("buttonText"))
            val ep = b.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint") ?: continue
            val id = ep.optString("browseId"); val params = ep.optString("params")
            if (title.isBlank() || id.isBlank()) continue
            out.add(Yt.Entry(Yt.Kind.Playlists, "cat:$id|$params", title, "", ""))
        }
        AppLog.i(TAG, "Generi e atmosfere: ${out.size}")
        return out.distinctBy { it.browseId }
    }

    /** Gli scaffali di una categoria (id «cat:<browseId>|<params>»). */
    fun category(entryId: String): Detail {
        val raw = entryId.removePrefix("cat:")
        val browseId = raw.substringBefore('|')
        val params = raw.substringAfter('|', "").ifBlank { null }
        val shelves = collect(browse(browseId, params = params), "musicCarouselShelfRenderer").mapNotNull { parseShelf(it) }.distinctBy { it.title }
        AppLog.i(TAG, "Categoria $browseId: ${shelves.size} scaffali")
        return Detail(emptyList(), shelves)
    }

    /** Nuove uscite (album e singoli): in una griglia o in uno scaffale, a seconda della risposta. */
    fun newReleases(): Shelf? {
        val json = browse("FEmusic_new_releases_albums")
        val cards = collect(json, "musicTwoRowItemRenderer").mapNotNull { cardOf(JSONObject().put("musicTwoRowItemRenderer", it)) }
        AppLog.i(TAG, "Nuove uscite: ${cards.size}")
        return if (cards.isEmpty()) null else Shelf("Nuove uscite", cards.distinctBy { it.id }.take(30))
    }

    /** Classifiche (senza account: quelle del tuo paese o globali), al massimo 3 scaffali. */
    fun charts(): List<Shelf> {
        val shelves = collect(browse("FEmusic_charts"), "musicCarouselShelfRenderer").mapNotNull { parseShelf(it) }.distinctBy { it.title }.take(3)
        AppLog.i(TAG, "Classifiche: ${shelves.size} scaffali")
        return shelves
    }

    /** Radio di un brano: i brani che YouTube Music propone di seguito (il primo è il brano stesso). */
    fun radio(videoId: String): List<Yt.Item> {
        val json = post("next", JSONObject()
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .put("isAudioOnly", true))
        val out = ArrayList<Yt.Item>()
        val seen = HashSet<String>()
        for (o in collect(json, "playlistPanelVideoRenderer")) {
            val id = o.optString("videoId").takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(id)) continue
            val title = runsText(o.optJSONObject("title"))
            val by = o.optJSONObject("longBylineText")?.optJSONArray("runs")
            val artist = by?.optJSONObject(0)?.optString("text").orEmpty()
            val dur = o.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
            out.add(Yt.Item(id, title, artist, parseDuration(dur), thumbOf(o.optJSONObject("thumbnail"))))
        }
        AppLog.i(TAG, "Radio da $videoId: ${out.size} brani")
        return out
    }

    private fun nextToken(json: JSONObject): String? =
        collect(json, "nextContinuationData").firstOrNull()?.optString("continuation")?.takeIf { it.isNotBlank() }
            ?: collect(json, "continuationCommand").firstOrNull()?.optString("token")?.takeIf { it.isNotBlank() }

    private fun parseTrack(o: JSONObject): Yt.Item? {
        val id = o.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: collect(o, "watchEndpoint").firstOrNull()?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: return null
        val flex = o.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = flex.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val title = col(0)?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        if (title.isBlank()) return null
        val runs = col(1)?.optJSONArray("runs")
        val artists = ArrayList<String>()
        if (runs != null) for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            val bid = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
            if (bid.startsWith("UC")) artists.add(r.optString("text"))
        }
        val artist = artists.joinToString(", ").ifBlank { runs?.optJSONObject(0)?.optString("text").orEmpty() }
        val dur = o.optJSONArray("fixedColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        return Yt.Item(id, title, artist, parseDuration(dur), thumbOf(o.optJSONObject("thumbnail")))
    }

    private fun parseDuration(s: String): Long {
        val p = s.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (p.size) { 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> 0 }
    }

    /** La miniatura più grande trovata sotto [root] (YouTube la mette in una lista "thumbnails"). */
    private fun thumbOf(root: JSONObject?): String {
        var found: JSONArray? = null
        fun walk(n: Any?) {
            if (found != null) return
            when (n) {
                is JSONObject -> n.keys().forEach { k ->
                    val v = n.opt(k)
                    if (k == "thumbnails" && v is JSONArray && v.length() > 0) { found = v; return@forEach }
                    walk(v)
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        val a = found ?: return ""
        val url = a.optJSONObject(a.length() - 1)?.optString("url").orEmpty()
        // le miniature di Google hanno la misura nell'indirizzo ("=w60-h60"): chiedo una versione più nitida
        return url.replace(THUMB_SIZE, "=w544-h544")
    }

    private fun runsText(o: JSONObject?): String {
        val a = o?.optJSONArray("runs") ?: return ""
        return (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") }
    }

    /** Tutti gli oggetti sotto una chiave con quel nome, in qualunque punto del JSON (senza scendere dentro i trovati). */
    private fun collect(root: Any?, name: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> n.keys().forEach { k ->
                    val v = n.opt(k)
                    if (k == name && v is JSONObject) out.add(v) else walk(v)
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        return out
    }
}
