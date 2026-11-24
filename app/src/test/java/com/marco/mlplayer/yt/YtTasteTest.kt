package com.marco.mlplayer.yt

import org.junit.Assert.assertEquals
import org.junit.Test

class YtTasteTest {
    private fun mk(id: String, artist: String) = Yt.Item(id, "t$id", artist, 200, "")

    @Test
    fun coldStartUsesHistoryAndFavorites() {
        val a = mk("a", "Mina"); val b = mk("b", "Battisti")
        val w = YtTaste.weights(emptyMap(), listOf(a), listOf(b))
        assertEquals(1.0, w["a"]!!.second, 0.0)
        assertEquals(2.0, w["b"]!!.second, 0.0)
        assertEquals(listOf("Battisti", "Mina"), YtTaste.topArtists(w, 5).map { it.first })
    }

    @Test
    fun playsOutweighHistory() {
        val a = mk("a", "Mina"); val b = mk("b", "Battisti")
        val plays = mapOf("a" to YtPlay(a, 5, 1, 2))
        val w = YtTaste.weights(plays, listOf(b), emptyList())
        assertEquals(a, YtTaste.topTracks(w, 1).first())
    }

    @Test
    fun multiArtistSplitAndNorm() {
        assertEquals(listOf("A", "B", "C"), YtTaste.artistNames("A, B & C"))
        assertEquals(YtTaste.norm("Mahmood"), YtTaste.norm("MAHMOOD"))
        assertEquals(YtTaste.norm("Måneskin"), YtTaste.norm("maneskin"))
    }

    @Test
    fun seedsHaveDifferentArtists() {
        val w = YtTaste.weights(
            mapOf("a" to YtPlay(mk("a", "Mina"), 9, 1, 1), "b" to YtPlay(mk("b", "mina"), 8, 1, 1), "c" to YtPlay(mk("c", "Zero"), 1, 1, 1)),
            emptyList(), emptyList(),
        )
        assertEquals(listOf("a", "c"), YtTaste.seeds(w, 3).map { it.videoId })
    }

    @Test
    fun forgottenFavoritesOldestFirst() {
        val a = mk("a", "x"); val b = mk("b", "y"); val c = mk("c", "z")
        val plays = mapOf("a" to YtPlay(a, 1, 1, 500), "c" to YtPlay(c, 1, 1, 100))
        assertEquals(listOf("b", "c", "a"), YtTaste.forgottenFavorites(listOf(a, b, c), plays, 5).map { it.videoId })
    }

    @Test
    fun scoresFollowTheRules() {
        val a = mk("a", "A"); val b = mk("b", "B"); val c = mk("c", "C"); val d = mk("d", "D")
        val plays = mapOf(
            "a" to YtPlay(a, 9, 1, 1),   // ascolti completi: tetto 5
            "b" to YtPlay(b, 2, 1, 1),
        )
        // b è anche preferito (+2); c solo in cronologia e saltato 3 volte (0 - 1 di tetto); d solo cronologia
        val w = YtTaste.weights(plays, listOf(c, d), listOf(b), mapOf("c" to 3))
        assertEquals(5.0, w["a"]!!.second, 0.0)
        assertEquals(4.0, w["b"]!!.second, 0.0)
        assertEquals(-1.0, w["c"]!!.second, 0.0)
        assertEquals(1.0, w["d"]!!.second, 0.0)
        // un solo salto: -0,5
        assertEquals(-0.5, YtTaste.weights(emptyMap(), listOf(c), emptyList(), mapOf("c" to 1))["c"]!!.second, 0.0)
    }

    @Test
    fun dislikeAlwaysBlocksTheTrackButOnlyNegativeArtistsAreBlocked() {
        val liked = mk("1", "Zero"); val bad1 = mk("2", "Zero")
        // artista con molti ascolti: un «non mi piace» non lo esclude
        val p1 = YtTaste.profile(mapOf("1" to YtPlay(liked, 5, 1, 1)), emptyList(), emptyList(), emptyMap(), listOf(bad1))
        assertEquals(true, p1.bad(bad1))
        assertEquals(false, p1.bad(mk("9", "Zero")))
        // artista senza altri punti: -1 lo esclude
        val p2 = YtTaste.profile(emptyMap(), emptyList(), emptyList(), emptyMap(), listOf(bad1))
        assertEquals(true, p2.bad(mk("9", "zero")))
        // i brani non graditi non entrano nei pesi
        assertEquals(false, p1.weights.containsKey("2"))
    }

    @Test
    fun genreScoresSumTracksAndSubtractDislikes() {
        val a = mk("a", "A"); val b = mk("b", "B"); val bad = mk("x", "C")
        val p = YtTaste.profile(mapOf("a" to YtPlay(a, 3, 1, 1)), listOf(b), listOf(a), emptyMap(), listOf(bad))
        val g = mapOf("a" to "rock", "b" to "rock", "x" to "pop")
        val sc = YtTaste.genreScores(p) { g[it.videoId] }
        assertEquals(6.0, sc["rock"]!!, 0.0)   // a: 3 + 2, b: 1
        assertEquals(-1.0, sc["pop"]!!, 0.0)
    }

    @Test
    fun excluderBlocksTrackAndNegativeArtist() {
        val one = YtTaste.excluder(listOf(mk("1", "Zero")))
        assertEquals(true, one(mk("1", "Zero")))
        assertEquals(true, one(mk("9", "Zero")))   // senza altri punti l'artista è a -1
    }
}
