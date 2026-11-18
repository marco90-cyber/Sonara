package com.marco.mlplayer.yt

import org.junit.Assert.assertEquals
import org.junit.Test

class YtTasteTest {
    private fun mk(id: String, artist: String) = Yt.Item(id, "t$id", artist, 200, "")

    @Test
    fun coldStartUsesHistoryAndFavorites() {
        val a = mk("a", "Mina"); val b = mk("b", "Battisti")
        val w = YtTaste.weights(emptyMap(), listOf(a), listOf(b))
        assertEquals(1, w["a"]!!.second)
        assertEquals(2, w["b"]!!.second)
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
}
