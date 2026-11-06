package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEmojiUploadTest {
    @Test fun onlyTheChooserMayAdd() {
        assertTrue(CustomEmojiUpload.canUpload("SVf0zxDVZzO8oLbGvM150QWgIWd2"))
        assertFalse(CustomEmojiUpload.canUpload("someoneElse"))
        assertFalse(CustomEmojiUpload.canUpload(null))
    }

    @Test fun twentyPerCall() {
        assertEquals(emptyList<List<Int>>(), CustomEmojiUpload.batches(emptyList<Int>()))
        assertEquals(listOf(20), CustomEmojiUpload.batches((1..20).toList()).map { it.size })
        assertEquals(listOf(20, 20, 5), CustomEmojiUpload.batches((1..45).toList()).map { it.size })
        assertEquals(21, CustomEmojiUpload.batches((1..45).toList())[1].first())
    }

    @Test fun payloadIsNameAndBase64() {
        val p = CustomEmojiUpload.payload(listOf(CustomEmojiUpload.Picked("a.tgs", byteArrayOf(1, 2, 3))))
        assertEquals(listOf(mapOf("name" to "a.tgs", "data" to "AQID")), p["files"])
    }

    @Test fun countsWhatTheServerAdded() {
        assertEquals(2, CustomEmojiUpload.addedCount(mapOf("added" to listOf(mapOf("id" to "a"), mapOf("id" to "b"))), 3))
        assertEquals(3, CustomEmojiUpload.addedCount(null, 3))
    }

    @Test fun resultWords() {
        assertEquals("1 emoji added", CustomEmojiUpload.resultText(1, null, emptyList()))
        assertEquals("25 emoji added", CustomEmojiUpload.resultText(25, null, emptyList()))
        // The server's refusal is shown as it is, after what already went in.
        assertEquals("bad.tgs is not a valid emoji", CustomEmojiUpload.resultText(0, "bad.tgs is not a valid emoji", emptyList()))
        assertEquals("20 emoji added. bad.tgs is not a valid emoji", CustomEmojiUpload.resultText(20, "bad.tgs is not a valid emoji", emptyList()))
        assertEquals("2 emoji added. big.tgs is too big (64 KB at most)", CustomEmojiUpload.resultText(2, null, listOf("big.tgs")))
        assertEquals("3 files are too big (64 KB at most)", CustomEmojiUpload.resultText(0, null, listOf("a", "b", "c")))
    }
}
