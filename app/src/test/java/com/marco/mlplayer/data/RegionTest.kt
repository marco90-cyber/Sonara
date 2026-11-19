package com.marco.mlplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il paese dei consigliati: elenco coerente e nomi in italiano. */
class RegionTest {
    @Test
    fun codesAreUniqueTwoLetterUppercase() {
        assertEquals(Region.CODES.size, Region.CODES.toSet().size)
        assertTrue(Region.CODES.all { it.length == 2 && it == it.uppercase() })
        assertTrue(Region.DEFAULT in Region.CODES)
    }

    @Test
    fun namesAreInItalian() {
        assertEquals("Italia", Region.name("IT"))
        assertEquals("Germania", Region.name("DE"))
        assertEquals("Stati Uniti", Region.name("US"))
    }
}
