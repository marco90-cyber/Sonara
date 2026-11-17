package com.marco.mlplayer.yt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** I file salvati (preferiti, download, copia di riserva) devono restare leggibili: chiavi e formato non cambiano. */
class YtModelTest {

    @Test
    fun itemRoundTrip() {
        val item = Yt.Item("abc12345", "Titolo", "Artista", 215, "https://i.ytimg.com/x.jpg")
        assertEquals(item, Yt.Item.fromJson(JSONObject(item.toJson().toString())))
    }

    @Test
    fun itemKeysAreStable() {
        val o = Yt.Item("abc12345", "T", "A", 7, "u").toJson()
        for (k in listOf("id", "title", "artist", "dur", "thumb")) assertEquals("manca $k", true, o.has(k))
    }

    @Test
    fun itemWithoutIdIsSkipped() {
        assertNull(Yt.Item.fromJson(JSONObject("""{"title":"x"}""")))
        assertNull(Yt.Item.fromJson(JSONObject("""{"id":"  ","title":"x"}""")))
    }

    @Test
    fun entryRoundTrip() {
        val e = Yt.Entry(Yt.Kind.Albums, "MPREb_xyz", "Album", "Artista · 2020", "t")
        assertEquals(e, Yt.Entry.fromJson(JSONObject(e.toJson().toString())))
    }

    @Test
    fun entryWithUnknownKindOrNoIdIsSkipped() {
        assertNull(Yt.Entry.fromJson(JSONObject("""{"kind":"Podcast","id":"x"}""")))
        assertNull(Yt.Entry.fromJson(JSONObject("""{"kind":"Artists","id":""}""")))
        assertNotNull(Yt.Entry.fromJson(JSONObject("""{"kind":"Artists","id":"UC123"}""")))
    }
}
