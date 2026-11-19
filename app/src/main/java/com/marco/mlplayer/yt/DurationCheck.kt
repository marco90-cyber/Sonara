package com.marco.mlplayer.yt

import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Confronto di titoli e durate (nessuna rete, così si prova con i test): serve a capire se un brano YouTube
 * ha la durata giusta e a scegliere, tra i risultati di una ricerca, l'audio che le somiglia di più.
 */
object DurationMatch {
    private val ACCENTS = Regex("\\p{M}+")
    private val BRACKETS = Regex("[(\\[].*?[)\\]]")
    private val SUFFIX = Regex("\\s[-–]\\s(remaster(ed)?|live|radio edit|single version|album version|version|\\d{4}).*$")
    private val FEAT = Regex("\\b(feat|ft|featuring)\\b\\.?.*$")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val SPLIT_ARTISTS = Regex("\\s*(,|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)

    /** Titolo o artista "pulito": minuscolo, senza accenti, senza parentesi, «feat.» e «- Remastered». */
    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(ACCENTS, "")
        t = t.replace(BRACKETS, " ").replace(SUFFIX, "").replace(FEAT, "")
        return t.replace(NON_ALNUM, " ").trim()
    }

    private fun tokens(s: String): Set<String> = norm(s).split(' ').filter { it.isNotBlank() }.toSet()

    /** Somiglianza dei titoli da 0 a 1 (parole in comune su parole totali). */
    fun titleSim(a: String, b: String): Double {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    fun sameTitle(a: String, b: String): Boolean = norm(a).let { it.isNotEmpty() && it == norm(b) }

    /** Hanno almeno una parola in comune (quella di YouTube può avere più artisti o «- Topic»). */
    fun artistMatch(a: String, b: String): Boolean {
        val x = tokens(a)
        return x.isNotEmpty() && x.intersect(tokens(b)).any { it.length > 1 }
    }

    private val UNWANTED = Regex(
        "\\b(bootleg|unofficial|un official|non ufficiale|cover|covers|tribute|fan made|fanmade|mashup|mash up|" +
            "sped up|speed up|slowed|nightcore|8d|dj mix)\\b"
    )
    private val REMIX = Regex("\\b(remix|remixed|rmx)\\b")
    private val OFFICIAL_REMIX = Regex("\\b(official remix|remix official|remix ufficiale|ufficiale remix)\\b")

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(ACCENTS, "").replace(NON_ALNUM, " ")

    /** Le parole da scartare (bootleg, cover, tribute, mashup, sped up, nightcore, 8D, DJ mix, non ufficiale…) che compaiono in [text]. */
    private fun unwantedIn(text: String): Set<String> = UNWANTED.findAll(plain(text)).map { it.value }.toSet()

    /**
     * Il risultato [title] di [artist] è una versione da scartare? Sono scartati bootleg, non ufficiali, cover, tribute, fan made,
     * mashup, sped up, slowed, nightcore, 8D Audio, DJ mix e i remix non ufficiali (un remix passa solo se il titolo dice
     * «official remix» o se lo pubblica l'artista originale [origArtist]).
     * Una parola non conta se compare in uno dei testi di [context] (il titolo del brano cercato o ciò che l'utente ha scritto):
     * chi cerca proprio un remix o una cover lo trova.
     */
    fun isUnwanted(title: String, artist: String, origArtist: String, vararg context: String): Boolean {
        val allowed = context.flatMap { unwantedIn(it) }.toSet()
        if (unwantedIn("$title $artist").any { it !in allowed }) return true
        val t = plain(title)
        if (REMIX.containsMatchIn(t) && context.none { REMIX.containsMatchIn(plain(it)) }) {
            val official = OFFICIAL_REMIX.containsMatchIn(t) || artistMatch(origArtist, artist)
            if (!official) return true
        }
        return false
    }

    /** L'artista principale: «Fedez, Marco Masini» → «Fedez». */
    fun mainArtist(artist: String): String = artist.split(SPLIT_ARTISTS).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    /** Scarto accettato in secondi: 6 s o il 3 % della durata, il maggiore. */
    fun tolerance(refSec: Long): Long = maxOf(6L, refSec * 3 / 100)

    /** La durata [currentSec] è lontana dal riferimento oltre la tolleranza (se una delle due non si sa, no). */
    fun isOff(currentSec: Long, refSec: Long): Boolean =
        refSec > 0 && currentSec > 0 && abs(currentSec - refSec) > tolerance(refSec)

    /** Il candidato scelto; [sure] = durata entro 3 s, stesso titolo, artista in comune e traccia audio (non video). */
    class Pick(val source: Yt.Source, val score: Double, val sure: Boolean)

    /** Tra i risultati di una ricerca sceglie quello che somiglia di più al brano [title] di [artist] lungo [refSec]. */
    fun best(title: String, artist: String, refSec: Long, candidates: List<Yt.Source>, excludeId: String? = null): Pick? {
        val tol = tolerance(refSec)
        return candidates.asSequence()
            .filter { it.item.videoId != excludeId && it.item.durationSec > 0 }
            .filter { !isUnwanted(it.item.title, it.item.artist, artist, title) }
            .filter { abs(it.item.durationSec - refSec) <= tol }
            .filter { titleSim(title, it.item.title) >= 0.6 }
            .map { c ->
                val d = abs(c.item.durationSec - refSec)
                val sure = d <= 3 && sameTitle(title, c.item.title) && artistMatch(artist, c.item.artist) && !c.isVideo
                Pick(c, 100.0 - d * 5 - (if (c.isVideo) 10 else 0) + titleSim(title, c.item.title) * 20, sure)
            }
            .maxByOrNull { it.score }
    }
}

/** Durata di riferimento di un brano da Deezer (senza chiavi) e, in seconda battuta, da iTunes. */
object DurationRef {
    class Ref(val seconds: Long, val source: String)

    private val NONE = Ref(0, "")
    private val cache = ConcurrentHashMap<String, Ref>()

    /** Esito di una ricerca: [reachable] è falso se nessun servizio ha risposto (niente rete), e allora non vale come «non trovato». */
    class Lookup(val ref: Ref?, val reachable: Boolean)

    /** La durata di riferimento di [title] di [artist]; null se non si trova. Bloccante: fuori dal thread principale. */
    fun lookup(title: String, artist: String): Ref? = lookupFull(title, artist).ref

    fun lookupFull(title: String, artist: String): Lookup {
        val key = DurationMatch.norm(title) + "|" + DurationMatch.norm(artist)
        cache[key]?.let { return Lookup(it.takeIf { r -> r !== NONE }, true) }
        val main = DurationMatch.mainArtist(artist)
        var answered = false
        for (url in listOf(
            Http.url("https://api.deezer.com/search", "q" to "track:\"$title\" artist:\"$main\"", "limit" to "8"),
            Http.url("https://api.deezer.com/search", "q" to "$title $main", "limit" to "8"),
        )) {
            val j = json(url) ?: continue
            answered = true
            parse(j.optJSONArray("data"), "title", "duration", title, artist, "Deezer") { it.optJSONObject("artist")?.optString("name").orEmpty() }
                ?.let { cache[key] = it; return Lookup(it, true) }
        }
        json(Http.url("https://itunes.apple.com/search", "term" to "$title $main", "entity" to "song", "limit" to "8", "country" to "IT"))?.let { j ->
            answered = true
            parse(j.optJSONArray("results"), "trackName", "trackTimeMillis", title, artist, "iTunes") { it.optString("artistName") }
                ?.let { cache[key] = it; return Lookup(it, true) }
        }
        // se nessun servizio ha risposto (niente rete) non ricordo il «non trovato»: si potrà riprovare
        if (answered) cache[key] = NONE
        return Lookup(null, answered)
    }

    private fun json(url: String): JSONObject? =
        Http.get(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Primo risultato con titolo e artista giusti. La durata è in secondi per Deezer e in millisecondi per iTunes. */
    private fun parse(
        a: org.json.JSONArray?, titleKey: String, durKey: String, title: String, artist: String, source: String,
        artistOf: (JSONObject) -> String,
    ): Ref? {
        if (a == null) return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val raw = o.optLong(durKey)
            val sec = if (source == "iTunes") raw / 1000 else raw
            if (sec <= 0) continue
            val t = o.optString(titleKey)
            if (DurationMatch.titleSim(title, t) < 0.6) continue
            if (!DurationMatch.artistMatch(artist, artistOf(o))) continue
            return Ref(sec, source)
        }
        return null
    }
}

/** Controllo delle durate di un elenco di brani: dice quali hanno l'audio fuori misura e propone un'alternativa. */
object DurationCheck {
    private const val TAG = "Durate"

    enum class Status { Ok, Off, NoRef }

    /**
     * Esito per un brano: [item] com'è in origine, [currentSec] la durata che suona adesso, [ref] quella di riferimento,
     * [pick] la proposta (solo per i brani fuori misura).
     */
    class Row(val item: Yt.Item, val currentSec: Long, val ref: DurationRef.Ref?, val status: Status, val pick: DurationMatch.Pick?)

    /** Controlla tutti i brani (3 per volta); [onProgress] riceve quanti ne sono stati fatti. */
    suspend fun scan(items: List<Yt.Item>, onProgress: (done: Int, total: Int) -> Unit): List<Row> = withContext(Dispatchers.IO) {
        val list = items.distinctBy { it.videoId }
        val gate = Semaphore(3)
        val done = AtomicInteger(0)
        val rows = coroutineScope {
            list.map { raw ->
                async {
                    gate.withPermit {
                        val r = rowFor(raw)
                        onProgress(done.incrementAndGet(), list.size)
                        r
                    }
                }
            }.awaitAll()
        }
        AppLog.i(TAG, "Controllo di ${rows.size} brani: ${rows.count { it.status == Status.Off }} fuori misura, ${rows.count { it.status == Status.NoRef }} senza riferimento")
        rows
    }

    private fun rowFor(raw: Yt.Item): Row {
        val orig = YtOverrides[raw.videoId]?.orig ?: raw
        val cur = YtOverrides.apply(orig).durationSec
        val ref = runCatching { DurationRef.lookup(orig.title, orig.artist) }.getOrNull()
            ?: return Row(orig, cur, null, Status.NoRef, null)
        if (!DurationMatch.isOff(cur, ref.seconds)) return Row(orig, cur, ref, Status.Ok, null)
        val candidates = runCatching { Yt.searchSources("${orig.title} ${DurationMatch.mainArtist(orig.artist)}") }.getOrDefault(emptyList())
        val pick = DurationMatch.best(orig.title, orig.artist, ref.seconds, candidates, excludeId = YtOverrides.target(orig.videoId))
        return Row(orig, cur, ref, Status.Off, pick)
    }
}
