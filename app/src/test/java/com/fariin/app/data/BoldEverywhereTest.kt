package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS bold-everywhere contract: a 1:1 reads `fmt` only when it and the text are both sealed. */
class BoldEverywhereTest {
    // The "open" stands in for decryption: drops the enc1: prefix.
    private fun read(cid: String, text: String, fmt: String?): Message {
        val d = mutableMapOf<String, Any?>("authorId" to "a", "text" to text)
        if (fmt != null) d["fmt"] = fmt
        return Message.fromFirestore("m", d, cid) { r, _ -> r.removePrefix("enc1:") }
    }

    private val runs = """[[0,5,"b"]]"""

    @Test fun oneToOneSealedBoldIsRead() {
        assertEquals(listOf(TextFormat.Span(0, 5)), read("a_b", "enc1:hello world", "enc1:$runs").bold)
    }

    @Test fun oneToOneBoldInTheClearIsIgnored() {
        assertTrue(read("a_b", "enc1:hello world", runs).bold.isEmpty())
    }

    @Test fun groupBoldUnchanged() {
        assertEquals(listOf(TextFormat.Span(0, 5)), read("grp1", "hello world", runs).bold)
    }
}
