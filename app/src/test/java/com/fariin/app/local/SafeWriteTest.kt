package com.fariin.app.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafeWriteTest {
    private fun dir(): File = Files.createTempDirectory("safewrite").toFile()

    @Test fun replaceWritesTheNewBytesAndLeavesNoTemp() {
        val d = dir()
        val f = File(d, "q.bin")
        f.writeBytes("old".toByteArray())
        assertTrue(SafeWrite.replace(f, "new".toByteArray()))
        assertArrayEquals("new".toByteArray(), f.readBytes())
        assertFalse(File(d, "q.bin.tmp").exists())
    }

    @Test fun replaceMakesTheFolder() {
        val f = File(File(dir(), "a/b"), "q.bin")
        assertTrue(SafeWrite.replace(f, "x".toByteArray()))
        assertArrayEquals("x".toByteArray(), f.readBytes())
    }

    @Test fun anUnreadableFileIsKeptAside() {
        val d = dir()
        val f = File(d, "q.bin")
        f.writeBytes("torn".toByteArray())
        SafeWrite.setAside(f)
        assertFalse(f.exists())
        assertArrayEquals("torn".toByteArray(), File(d, "q.bin.unread").readBytes())
    }
}
