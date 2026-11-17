package com.marco.mlplayer.yt

import com.marco.mlplayer.yt.PlaylistImport.Raw
import com.marco.mlplayer.yt.PlaylistImport.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportTest {
    private fun item(title: String, artist: String, dur: Long, id: String = "vid$title") = Yt.Item(id, title, artist, dur, "")

    @Test
    fun parsesLinks() {
        assertEquals(PlaylistImport.Ref(Source.Deezer, "908622995"), PlaylistImport.parseLink("https://www.deezer.com/it/playlist/908622995?utm=x"))
        assertEquals(PlaylistImport.Ref(Source.Deezer, "1"), PlaylistImport.parseLink("https://deezer.com/playlist/1"))
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(PlaylistImport.Ref(Source.Spotify, id), PlaylistImport.parseLink("https://open.spotify.com/playlist/$id?si=abc"))
        assertEquals(PlaylistImport.Ref(Source.Spotify, id), PlaylistImport.parseLink("https://open.spotify.com/intl-it/playlist/$id"))
        assertEquals(PlaylistImport.Ref(Source.Spotify, id), PlaylistImport.parseLink("spotify:playlist:$id"))
        assertEquals(PlaylistImport.Ref(Source.YouTube, "PLabc_12-x"), PlaylistImport.parseLink("https://music.youtube.com/playlist?list=PLabc_12-x"))
        assertEquals(PlaylistImport.Ref(Source.YouTube, "PLabc"), PlaylistImport.parseLink("https://www.youtube.com/watch?v=abcdefghijk&list=PLabc"))
    }

    @Test
    fun otherTextIsNotAPlaylist() {
        assertNull(PlaylistImport.parseLink("queen bohemian rhapsody"))
        assertNull(PlaylistImport.parseLink("https://www.youtube.com/watch?v=abcdefghijk"))
        assertNull(PlaylistImport.parseLink("https://open.spotify.com/track/37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(PlaylistImport.maybeLink("queen bohemian rhapsody"))
        assertTrue(PlaylistImport.maybeLink("https://deezer.page.link/abc"))
        assertTrue(PlaylistImport.maybeLink("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun browseIdsRoundTrip() {
        for (s in Source.entries) {
            val ref = PlaylistImport.Ref(s, "abc123")
            assertEquals(ref, PlaylistImport.refOf(ref.browseId))
            assertEquals(ref, PlaylistImport.refOf(ref.importId))
        }
        assertNull(PlaylistImport.refOf("VLPLabc"))
        assertNull(PlaylistImport.refOf("UCabc"))
        assertNull(PlaylistImport.refOf("dz:"))
    }

    @Test
    fun normalizesTitles() {
        assertEquals("bohemian rhapsody", PlaylistImport.norm("Bohemian Rhapsody - Remastered 2011", dropSuffix = true))
        assertEquals("perche ti amo", PlaylistImport.norm("Perché ti amo (Live)"))
        assertEquals("queen bohemian rhapsody", PlaylistImport.norm("Queen - Bohemian Rhapsody"))
    }

    @Test
    fun picksTheRightVersion() {
        val raw = Raw("Bohemian Rhapsody - Remastered 2011", "Queen", 355)
        val live = item("Bohemian Rhapsody (Live)", "Queen", 420, "live")
        val studio = item("Bohemian Rhapsody", "Queen", 355, "studio")
        val cover = item("Bohemian Rhapsody", "Piano Covers", 300, "cover")
        assertEquals("studio", PlaylistImport.best(raw, listOf(live, cover, studio))?.videoId)
    }

    @Test
    fun rejectsUnrelatedResults() {
        val raw = Raw("Imagine", "John Lennon", 183)
        assertNull(PlaylistImport.best(raw, listOf(item("Another Song", "John Lennon", 183), item("Imagine Dragons Mix", "Other", 600))))
    }

    @Test
    fun artistInTitleStillMatches() {
        val raw = Raw("Hurt", "Johnny Cash", 218)
        assertEquals("a", PlaylistImport.best(raw, listOf(item("Johnny Cash - Hurt", "Topic", 217, "a")))?.videoId)
    }
}
