package com.marco.mlplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class AsyncFileWriterTest {

    private fun waitFor(file: File, expected: String) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end && !(file.exists() && file.readText() == expected)) Thread.sleep(10)
        assertEquals(expected, file.readText())
    }

    @Test
    fun lastWriteWinsAndNoTempFileLeft() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sonara_writer_" + System.nanoTime()).apply { mkdirs() }
        val file = File(dir, "store.json")
        val w = AsyncFileWriter(file, "test-writer")
        for (i in 1..50) w.write("v$i")
        waitFor(file, "v50")
        assertFalse(File(dir, "store.json.tmp").exists())
        dir.deleteRecursively()
    }
}
