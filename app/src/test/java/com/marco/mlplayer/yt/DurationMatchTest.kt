package com.marco.mlplayer.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il confronto di titoli e durate deve trovare i brani con l'audio fuori misura senza segnalare quelli giusti. */
class DurationMatchTest {
    private fun src(id: String, title: String, artist: String, dur: Long, video: Boolean = false) =
        Yt.Source(Yt.Item(id, title, artist, dur, ""), video)

    @Test
    fun normRemovesNoise() {
        assertEquals("male necessario", DurationMatch.norm("MALE NECESSARIO"))
        assertEquals("perche", DurationMatch.norm("Perché (Live)"))
        assertEquals("song", DurationMatch.norm("Song - Remastered 2011"))
        assertEquals("song", DurationMatch.norm("Song feat. Someone"))
        assertEquals("con te partiro", DurationMatch.norm("Con te partirò"))
    }

    @Test
    fun mainArtistTakesTheFirstName() {
        assertEquals("Fedez", DurationMatch.mainArtist("Fedez, Marco Masini"))
        assertEquals("Mina", DurationMatch.mainArtist("Mina feat. Celentano"))
        assertEquals("Elisa", DurationMatch.mainArtist("Elisa"))
    }

    @Test
    fun artistMatchNeedsACommonWord() {
        assertTrue(DurationMatch.artistMatch("Fedez, Marco Masini", "Fedez"))
        assertFalse(DurationMatch.artistMatch("Fedez", "Elisa"))
    }

    @Test
    fun toleranceAndOff() {
        assertEquals(9L, DurationMatch.tolerance(300))
        assertEquals(6L, DurationMatch.tolerance(100))
        assertFalse(DurationMatch.isOff(305, 304))
        assertTrue(DurationMatch.isOff(405, 304))
        assertFalse(DurationMatch.isOff(0, 304))
        assertFalse(DurationMatch.isOff(300, 0))
    }

    @Test
    fun picksTheClosestAudioAndNotTheLongVideo() {
        val list = listOf(
            src("video", "Male necessario (Official Video)", "Fedez", 365, video = true),
            src("close", "MALE NECESSARIO", "Fedez, Marco Masini", 304),
            src("far", "Male necessario", "Altro", 290),
        )
        val p = DurationMatch.best("Male necessario", "Fedez, Marco Masini", 304, list)
        assertNotNull(p)
        assertEquals("close", p!!.source.item.videoId)
        assertTrue(p.sure)
    }

    @Test
    fun notSureWhenItIsAVideoOrFarEnough() {
        val v = DurationMatch.best("Titolo", "Artista", 200, listOf(src("v", "Titolo", "Artista", 200, video = true)))
        assertNotNull(v)
        assertFalse(v!!.sure)
        val d = DurationMatch.best("Titolo", "Artista", 200, listOf(src("a", "Titolo", "Artista", 206)))
        assertNotNull(d)
        assertFalse(d!!.sure)
    }

    @Test
    fun noCandidateWhenDurationOrTitleDoNotFit() {
        assertNull(DurationMatch.best("Titolo", "Artista", 200, listOf(src("a", "Titolo", "Artista", 260))))
        assertNull(DurationMatch.best("Titolo", "Artista", 200, listOf(src("b", "Tutt'altra canzone", "Artista", 200))))
    }

    @Test
    fun skipsTheSourceAlreadyInUse() {
        val list = listOf(src("same", "Titolo", "Artista", 200))
        assertNull(DurationMatch.best("Titolo", "Artista", 200, list, excludeId = "same"))
    }

    @Test
    fun unwantedVersionsAreRecognised() {
        for (t in listOf("Titolo (Remix)", "Titolo - Instrumental", "Titolo [Bootleg]", "Titolo (Cover)", "Titolo (Unofficial)", "Titolo non ufficiale", "Titolo Strumentale", "Titolo (Karaoke Version)")) {
            assertTrue(t, DurationMatch.isUnwanted(t, "Artista", "Titolo"))
        }
        assertTrue(DurationMatch.isUnwanted("Titolo", "Artista Covers", "Titolo"))
        assertFalse(DurationMatch.isUnwanted("Titolo (Official Audio)", "Artista", "Titolo"))
        assertFalse(DurationMatch.isUnwanted("Recoverable", "Artista", "Titolo"))
    }

    @Test
    fun theWordIsAllowedWhenTheOriginalOrTheSearchHasIt() {
        assertFalse(DurationMatch.isUnwanted("Titolo (Remix)", "Artista", "Titolo (Remix)"))
        assertFalse(DurationMatch.isUnwanted("Titolo (Remix)", "Artista", "Titolo", "titolo artista remix"))
        assertTrue(DurationMatch.isUnwanted("Titolo (Remix)", "Artista", "Titolo", "titolo artista"))
    }

    @Test
    fun bestIgnoresRemixesEvenWhenTheDurationIsRight() {
        val list = listOf(
            src("rmx", "Titolo (Remix)", "Artista", 200),
            src("inst", "Titolo (Instrumental)", "Artista", 200),
            src("ok", "Titolo", "Artista", 203),
        )
        assertEquals("ok", DurationMatch.best("Titolo", "Artista", 200, list)!!.source.item.videoId)
        assertNull(DurationMatch.best("Titolo", "Artista", 200, list.take(2)))
    }
}
