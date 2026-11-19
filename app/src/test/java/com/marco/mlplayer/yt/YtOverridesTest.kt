package com.marco.mlplayer.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** «Cambia sorgente»: l'id del brano resta quello originale, cambia solo il video da cui si prende l'audio. */
class YtOverridesTest {
    private val orig = Yt.Item("orig0001", "Titolo giusto", "Artista", 200, "https://x/o.jpg")
    private val target = Yt.Item("targ0002", "Titolo del video", "Altro canale", 305, "https://x/t.jpg")

    @Before
    fun clean() = YtOverrides.resetForTest()

    private fun json(meta: Boolean) =
        """{"overrides":[{"orig":${orig.toJson()},"target":${target.toJson()},"meta":$meta}]}"""

    @Test
    fun targetFollowsOverrideAndOtherIdsAreUntouched() {
        YtOverrides.mergeFrom(json(false))
        assertEquals("targ0002", YtOverrides.target("orig0001"))
        assertEquals("zzz99999", YtOverrides.target("zzz99999"))
        assertTrue(YtOverrides.has("orig0001"))
        assertFalse(YtOverrides.has("targ0002"))
    }

    @Test
    fun keepsOriginalNamesButUsesTheNewDuration() {
        YtOverrides.mergeFrom(json(false))
        val shown = YtOverrides.apply(orig)
        assertEquals("orig0001", shown.videoId)
        assertEquals("Titolo giusto", shown.title)
        assertEquals("https://x/o.jpg", shown.thumb)
        assertEquals(305L, shown.durationSec)
    }

    @Test
    fun canShowTheNamesOfTheNewResult() {
        YtOverrides.mergeFrom(json(true))
        val shown = YtOverrides.apply(orig)
        assertEquals("orig0001", shown.videoId)
        assertEquals("Titolo del video", shown.title)
        assertEquals("Altro canale", shown.artist)
        assertEquals("https://x/t.jpg", shown.thumb)
    }

    @Test
    fun exportKeepsOriginalAndTarget() {
        YtOverrides.mergeFrom(json(true))
        val text = YtOverrides.exportJson()
        assertTrue(text.contains("orig0001") && text.contains("targ0002"))
    }
}
