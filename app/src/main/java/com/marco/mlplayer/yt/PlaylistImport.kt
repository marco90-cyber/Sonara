package com.marco.mlplayer.yt

import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.Net
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer

/**
 * Importa playlist pubbliche da Spotify, Deezer e YouTube.
 * Deezer e Spotify danno solo titolo, artista e durata: ogni brano viene cercato su YouTube Music
 * e scelto confrontando titolo, artista e durata ([score]).
 */
object PlaylistImport {
    private const val TAG = "Importa"

    enum class Source(val label: String, val prefix: String) {
        YouTube("YouTube", "ytl"), Deezer("Deezer", "dz"), Spotify("Spotify", "sp"),
    }

    /** Una playlist di un servizio: [id] è quello del servizio. */
    data class Ref(val source: Source, val id: String) {
        /** Id usato quando la playlist è solo aperta (ricerca o link). */
        val browseId get() = "${source.prefix}:$id"
        /** Id con cui la playlist importata viene salvata. */
        val importId get() = "imp:${source.prefix}:$id"
        fun placeholder() = Yt.Entry(Yt.Kind.Playlists, browseId, "Playlist ${source.label}", source.label, "")
    }

    /** Un brano come lo descrive il servizio di origine. */
    data class Raw(val title: String, val artist: String, val durationSec: Long)

    class Meta(val name: String, val owner: String, val cover: String)

    class Fetched(val meta: Meta, val tracks: List<Raw>)

    private val DEEZER = Regex("deezer\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?playlist/(\\d+)", RegexOption.IGNORE_CASE)
    private val SPOTIFY_URL = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(?:embed/)?playlist/([A-Za-z0-9]{22})")
    private val SPOTIFY_URI = Regex("^spotify:playlist:([A-Za-z0-9]{22})")
    private val YOUTUBE = Regex("(?:music\\.)?youtube\\.com/.*[?&]list=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val SHORT_HOSTS = listOf("link.deezer.com", "deezer.page.link", "spotify.link", "spotify.app.link", "dzr.page.link")
    private val NEXT_DATA = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
    private val MARKS = Regex("\\p{M}+")
    private val BRACKETS = Regex("[(\\[].*?[)\\]]")
    private val AFTER_DASH = Regex("\\s-\\s.*$")
    private val NOT_ALNUM = Regex("[^a-z0-9]+")
    private val ARTIST_SPLIT = Regex("\\s*(?:,|&|;|\\bfeat\\.?\\b|\\bft\\.?\\b|\\be\\b)\\s*", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ link

    /** Il testo sembra un link (o un URI Spotify): conviene provare a riconoscerlo come playlist. */
    fun maybeLink(text: String): Boolean {
        val t = text.trim()
        return !t.contains(' ') && (t.startsWith("http", ignoreCase = true) || t.startsWith("spotify:"))
    }

    /** Riconosce il link di una playlist di Deezer, Spotify o YouTube; null se non lo è. Non usa la rete. */
    fun parseLink(text: String): Ref? {
        val t = text.trim()
        DEEZER.find(t)?.let { return Ref(Source.Deezer, it.groupValues[1]) }
        SPOTIFY_URL.find(t)?.let { return Ref(Source.Spotify, it.groupValues[1]) }
        SPOTIFY_URI.find(t)?.let { return Ref(Source.Spotify, it.groupValues[1]) }
        YOUTUBE.find(t)?.let { return Ref(Source.YouTube, it.groupValues[1]) }
        return null
    }

    /** Come [parseLink], ma segue i link brevi (link.deezer.com, spotify.link...). Bloccante. */
    fun parseResolving(text: String): Ref? {
        parseLink(text)?.let { return it }
        val t = text.trim()
        if (SHORT_HOSTS.none { t.contains(it, ignoreCase = true) }) return null
        val final = runCatching {
            Net.client.newCall(Request.Builder().url(t).header("User-Agent", Net.BROWSER_UA).build()).execute().use { it.request.url.toString() }
        }.getOrNull() ?: return null
        return parseLink(final)
    }

    /** Dal browse id di una voce ("dz:12", "sp:...", "ytl:...", "imp:dz:12"); null per tutti gli altri id. */
    fun refOf(browseId: String): Ref? {
        val parts = browseId.removePrefix("imp:").split(':', limit = 2)
        if (parts.size != 2 || parts[1].isBlank()) return null
        val source = Source.entries.firstOrNull { it.prefix == parts[0] } ?: return null
        return Ref(source, parts[1])
    }

    // ------------------------------------------------------------------ ricerca

    /** Playlist pubbliche di Deezer per [query] (la ricerca di Deezer non chiede chiavi). Vuoto se non raggiungibile. */
    fun searchDeezer(query: String): List<Yt.Entry> = runCatching {
        val url = "https://api.deezer.com/search/playlist".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("limit", "25").build()
        val data = getJson(url.toString()).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { i ->
            val o = data.getJSONObject(i)
            val id = o.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val sub = listOfNotNull(
                "Deezer",
                o.optInt("nb_tracks").takeIf { it > 0 }?.let { "$it brani" },
                o.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() }?.let { "di $it" },
            ).joinToString(" · ")
            Yt.Entry(Yt.Kind.Playlists, Ref(Source.Deezer, id.toString()).browseId, o.optString("title"), sub, o.optString("picture_medium"))
        }
    }.onFailure { AppLog.w(TAG, "Ricerca Deezer non riuscita: ${it.message}") }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ lettura dei servizi

    private fun getJson(url: String): JSONObject = JSONObject(getText(url))

    private fun getText(url: String): String =
        Net.client.newCall(Request.Builder().url(url).header("User-Agent", Net.BROWSER_UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("risposta vuota")
        }

    /** Titolo, autore e brani di una playlist pubblica di Deezer. Bloccante. */
    fun fetchDeezer(id: String): Fetched {
        val head = getJson("https://api.deezer.com/playlist/$id")
        head.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message")}") }
        if (head.has("public") && !head.optBoolean("public", true)) throw IOException("Playlist Deezer non pubblica")
        val out = ArrayList<Raw>()
        var index = 0
        for (page in 0 until 10) {
            val data = getJson("https://api.deezer.com/playlist/$id/tracks?limit=100&index=$index").optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val t = data.getJSONObject(i)
                val title = t.optString("title")
                if (title.isNotBlank()) out.add(Raw(title, t.optJSONObject("artist")?.optString("name").orEmpty(), t.optLong("duration")))
            }
            if (data.length() < 100) break
            index += 100
        }
        val meta = Meta(head.optString("title", "Playlist Deezer"), head.optJSONObject("creator")?.optString("name").orEmpty(), head.optString("picture_big"))
        return Fetched(meta, out)
    }

    /** Titolo e brani di una playlist pubblica di Spotify, dalla pagina "embed" (circa i primi 100 brani). Bloccante. */
    fun fetchSpotify(id: String): Fetched {
        val html = getText("https://open.spotify.com/embed/playlist/$id")
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: throw IOException("Spotify: pagina non riconosciuta")
        val root = JSONObject(json)
        val list = findKey(root, "trackList") as? JSONArray ?: throw IOException("Spotify: nessun elenco di brani (playlist privata?)")
        val out = ArrayList<Raw>()
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val title = t.optString("title")
            if (title.isNotBlank()) out.add(Raw(title, t.optString("subtitle"), t.optLong("duration") / 1000))
        }
        val entity = findEntity(root)
        val name = entity?.optString("name")?.takeIf { it.isNotBlank() } ?: entity?.optString("title")?.takeIf { it.isNotBlank() } ?: "Playlist Spotify"
        val cover = (findKey(root, "coverArt") as? JSONObject)?.optJSONArray("sources")?.optJSONObject(0)?.optString("url").orEmpty()
        return Fetched(Meta(name, "", cover), out)
    }

    private fun findEntity(root: JSONObject): JSONObject? =
        root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")

    /** Il primo valore con chiave [key] in un JSON, a qualunque profondità. */
    private fun findKey(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.get(key)
                for (k in node.keys()) findKey(node.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findKey(node.opt(i), key)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ abbinamento su YouTube Music

    /** Testo confrontabile: minuscolo, senza accenti e simboli; di [dropSuffix] toglie anche " - Remastered 2011" e simili. */
    fun norm(s: String, dropSuffix: Boolean = false): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
        t = t.replace(BRACKETS, " ")
        if (dropSuffix) t = t.replace(AFTER_DASH, "")
        return t.replace(NOT_ALNUM, " ").trim()
    }

    /** Quanto il risultato [c] somiglia al brano [raw]: titolo, artista e durata. Sotto zero = da scartare. */
    fun score(raw: Raw, c: Yt.Item): Int {
        val t = norm(raw.title, dropSuffix = true)
        val ct = norm(c.title)
        if (t.isEmpty() || ct.isEmpty()) return -1
        var s = when {
            ct == t -> 3
            ct.length >= 3 && t.length >= 3 && (ct.contains(t) || t.contains(ct)) -> 2
            else -> return -1
        }
        val first = raw.artist.split(ARTIST_SPLIT).map { norm(it) }.firstOrNull { it.isNotEmpty() }
        if (first != null && "${norm(c.artist)} $ct".contains(first)) s += 2
        if (raw.durationSec > 0 && c.durationSec > 0) {
            val d = kotlin.math.abs(raw.durationSec - c.durationSec)
            s += when { d <= 5 -> 2; d <= 12 -> 1; d > 30 -> -2; else -> 0 }
        }
        return s
    }

    private const val MIN_SCORE = 4

    /** Il risultato migliore tra [candidates] (a parità, il primo, che YouTube ha messo più in alto); null se nessuno è abbastanza simile. */
    fun best(raw: Raw, candidates: List<Yt.Item>): Yt.Item? =
        candidates.map { it to score(raw, it) }.filter { it.second >= MIN_SCORE }.maxByOrNull { it.second }?.first

    /** Chiave stabile di un brano di Spotify o Deezer: serve a riconoscerlo negli aggiornamenti senza cercarlo di nuovo. */
    fun rawKey(raw: Raw): String = norm(raw.title, dropSuffix = true) + "|" + norm(raw.artist)

    /** Cerca [raw] su YouTube Music, con un secondo tentativo; se la rete manca lancia l'eccezione (un brano non trovato non è un errore). Bloccante. */
    private fun matchStrict(raw: Raw): Yt.Item? {
        val q = "${raw.title} ${raw.artist}".trim()
        val found = try {
            Yt.search(q, maxPages = 1)
        } catch (e: Exception) {
            Thread.sleep(1000)
            Yt.search(q, maxPages = 1)
        }
        return best(raw, found)
    }

    /**
     * Una playlist letta e abbinata: [tracks] sono i brani trovati su YouTube Music, [total] quelli dell'origine,
     * [keys] dice per ogni brano dell'origine quale video è stato scelto ("" = non trovato).
     */
    class Imported(val meta: Meta, val tracks: List<Yt.Item>, val total: Int, val keys: Map<String, String>)

    /**
     * Legge la playlist e trova i brani su YouTube Music. I brani già noti ([knownKeys] e [knownItems], da un'importazione precedente)
     * non vengono cercati di nuovo. [progress] riceve il nome (appena noto), i brani trovati finora, quanti ne ha provati e il totale.
     * Bloccante: da chiamare fuori dal thread principale; lancia un'eccezione se la lettura o la rete falliscono.
     */
    fun import(
        ref: Ref,
        knownKeys: Map<String, String> = emptyMap(),
        knownItems: Map<String, Yt.Item> = emptyMap(),
        progress: (Meta, List<Yt.Item>, Int, Int) -> Unit = { _, _, _, _ -> },
    ): Imported {
        if (ref.source == Source.YouTube) {
            val tracks = YtApi.playlistTracks("VL${ref.id}")
            if (tracks.isEmpty()) throw IOException("YouTube: playlist vuota o non leggibile")
            val m = Yt.playlistMeta(ref.id)
            val meta = Meta(m?.first ?: "Playlist YouTube", "", m?.second.orEmpty().ifBlank { tracks.first().cover })
            progress(meta, tracks, tracks.size, tracks.size)
            return Imported(meta, tracks, tracks.size, emptyMap())
        }
        val f = if (ref.source == Source.Deezer) fetchDeezer(ref.id) else fetchSpotify(ref.id)
        if (f.tracks.isEmpty()) throw IOException("${ref.source.label}: nessun brano letto")
        val found = ArrayList<Yt.Item>()
        val seen = HashSet<String>()
        val keys = LinkedHashMap<String, String>()
        progress(f.meta, found, 0, f.tracks.size)
        f.tracks.forEachIndexed { i, raw ->
            val key = rawKey(raw)
            val known = knownKeys[key]
            val item = when {
                known == "" -> null
                known != null && knownItems[known] != null -> knownItems[known]
                else -> matchStrict(raw).also { Thread.sleep(120) }
            }
            keys[key] = item?.videoId.orEmpty()
            if (item != null && seen.add(item.videoId)) found.add(item)
            progress(f.meta, found.toList(), i + 1, f.tracks.size)
        }
        AppLog.i(TAG, "${ref.source.label} ${ref.id}: ${found.size} su ${f.tracks.size} brani trovati")
        return Imported(f.meta, found, f.tracks.size, keys)
    }

    /** Il sottotitolo di una playlist importata, per la Home e la sua pagina. */
    fun subtitle(source: Source, found: Int, total: Int): String =
        if (found == total) "Importata da ${source.label} · $total brani" else "Importata da ${source.label} · $found di $total brani"
}
