package com.marco.mlplayer.yt

import com.marco.mlplayer.util.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Chiamate a YouTube Music (InnerTube, client WEB_REMIX), con o senza account. */
object YtApi {
    private const val TAG = "YouTube"
    private const val ORIGIN = "https://music.youtube.com"
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20250929.01.00"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class Playlist(val browseId: String, val title: String, val subtitle: String, val thumb: String)

    /** POST a un endpoint InnerTube; usa i cookie dell'account se collegato. */
    fun post(endpoint: String, body: JSONObject, continuation: String? = null): JSONObject {
        body.put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "it").put("gl", "IT")))
        var url = "$ORIGIN/youtubei/v1/$endpoint?key=$KEY&prettyPrint=false"
        if (continuation != null) url += "&ctoken=$continuation&continuation=$continuation&type=next"
        val b = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Origin", ORIGIN)
            .header("Accept-Language", "it-IT,it;q=0.9")
        YtAuth.authorization()?.let { b.header("Authorization", it) }
        YtAuth.cookieHeader()?.let { b.header("Cookie", it); b.header("X-Goog-AuthUser", "0") }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                AppLog.w(TAG, "$endpoint: HTTP ${r.code} ${text.take(160).replace('\n', ' ')}")
                throw IOException("YouTube Music: HTTP ${r.code}")
            }
            return JSONObject(text)
        }
    }

    fun browse(browseId: String?, continuation: String? = null): JSONObject =
        post("browse", JSONObject().apply { if (browseId != null) put("browseId", browseId) }, continuation)

    // ------------------------------------------------------------------ account

    /** Playlist della libreria dell'account (serve il login). */
    fun libraryPlaylists(): List<Playlist> {
        val out = ArrayList<Playlist>()
        val json = browse("FEmusic_liked_playlists")
        for (o in collect(json, "musicTwoRowItemRenderer")) {
            val id = o.opt("navigationEndpoint").obj("browseEndpoint")?.optString("browseId").orEmpty()
            if (!id.startsWith("VL")) continue
            out.add(Playlist(id, runsText(o.optJSONObject("title")), runsText(o.optJSONObject("subtitle")), thumbOf(o.optJSONObject("thumbnailRenderer"))))
        }
        AppLog.i(TAG, "Playlist dell'account: ${out.size}")
        return out
    }

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

    // ------------------------------------------------------------------ home e radio

    /** Una scheda della home: un brano da suonare oppure un album/playlist/artista da aprire. */
    data class Card(val song: Yt.Item?, val entry: Yt.Entry?) {
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
        return out
    }

    private fun parseShelf(sh: JSONObject): Shelf? {
        val title = runsText(collect(sh.optJSONObject("header"), "title").firstOrNull())
        val arr = sh.optJSONArray("contents") ?: return null
        val cards = ArrayList<Card>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            c.optJSONObject("musicResponsiveListItemRenderer")?.let { r -> parseTrack(r)?.let { cards.add(Card(it, null)) } }
            val t = c.optJSONObject("musicTwoRowItemRenderer") ?: continue
            val nav = t.optJSONObject("navigationEndpoint")
            val videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
            val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
            val name = runsText(t.optJSONObject("title"))
            val sub = runsText(t.optJSONObject("subtitle"))
            val thumb = thumbOf(t.optJSONObject("thumbnailRenderer"))
            if (name.isBlank()) continue
            if (videoId.isNotBlank()) {
                val parts = sub.split(" • ").map { it.trim() }.filter { it.isNotBlank() }
                val artist = if (parts.size > 1 && parts[0].lowercase() in setOf("brano", "song", "video")) parts[1] else parts.firstOrNull().orEmpty()
                cards.add(Card(Yt.Item(videoId, name, artist, 0, thumb), null))
            } else {
                val kind = when {
                    browseId.startsWith("UC") -> Yt.Kind.Artists
                    browseId.startsWith("MPRE") -> Yt.Kind.Albums
                    browseId.startsWith("VL") -> Yt.Kind.Playlists
                    else -> continue
                }
                cards.add(Card(null, Yt.Entry(kind, browseId, name, sub, thumb)))
            }
        }
        return if (title.isBlank() || cards.isEmpty()) null else Shelf(title, cards)
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
        return url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
    }

    private fun runsText(o: JSONObject?): String {
        val a = o?.optJSONArray("runs") ?: return ""
        return (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") }
    }

    private fun Any?.obj(key: String): JSONObject? = (this as? JSONObject)?.optJSONObject(key)

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
