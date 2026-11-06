package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportTest {
    private fun msg(id: String, author: String, text: String = "", type: String? = null, at: Double = 1000.0) =
        Message(id = id, authorId = author, text = text, type = type, createdAtMillis = at)

    private val fmt: (Double) -> String = { "t${it.toLong()}" }

    @Test fun fileNamesKeepLettersDigitsSpaces() {
        assertEquals("Fariin-Chat-Amina 2.txt", ChatExport.chatFileName("Amina/ 2!"))
        assertEquals("Fariin-Group-Export.txt", ChatExport.groupFileName("***"))
        assertEquals("Fariin-Group-Family.txt", ChatExport.groupFileName("Family"))
    }

    @Test fun mediaIsATagWithItsCaption() {
        assertEquals("[Photo]", ChatExport.describe(msg("1", "a", type = "image")))
        assertEquals("[Photo] at the beach", ChatExport.describe(msg("1", "a", "at the beach", "image")))
        assertEquals("[Photo]", ChatExport.describe(msg("1", "a", type = "album").copy(
            album = listOf(Message.AlbumItem("u", null, null, null)))))
        assertEquals("[Video] look", ChatExport.describe(msg("1", "a", "look", "video")))
        assertEquals("[Voice message]", ChatExport.describe(msg("1", "a", type = "audio")))
        assertEquals("[File: report.pdf]", ChatExport.describe(msg("1", "a", type = "file").copy(fileName = "report.pdf")))
        assertEquals("[File: document]", ChatExport.describe(msg("1", "a", type = "file")))
        assertEquals("[GIF]", ChatExport.describe(msg("1", "a", type = "gif")))
        assertEquals("[😂 Sticker]", ChatExport.describe(msg("1", "a", type = "sticker").copy(stickerEmoji = "😂")))
        assertEquals("[Video call]", ChatExport.describe(msg("1", "a", type = "call").copy(callVideo = true)))
        assertEquals("[Voice call]", ChatExport.describe(msg("1", "a", type = "call")))
        assertEquals("hello", ChatExport.describe(msg("1", "a", "hello", "text")))
    }

    @Test fun cardMarkerIsNeverWrittenRaw() {
        val card = CardRules.contactText("u1", "Amina", null)
        assertEquals("[Contact]", ChatExport.describe(msg("1", "a", card, "text")))
    }

    @Test fun keepsDropsClearedDeletedHiddenExpired() {
        val hidden = setOf("h")
        val keep = { m: Message -> ChatExport.keeps(m, 500.0, { it in hidden }, 10_000.0) }
        assertTrue(keep(msg("1", "a", "hi", at = 600.0)))
        assertFalse(keep(msg("1", "a", "hi", at = 500.0)))
        assertFalse(keep(msg("1", "a", "hi", at = 600.0).copy(deleted = true)))
        assertFalse(keep(msg("h", "a", "hi", at = 600.0)))
        assertFalse(keep(msg("1", "a", "hi", at = 600.0).copy(expiresAtMillis = 9_000.0)))
    }

    @Test fun chatLinesNameYouAndThem() {
        val text = ChatExport.chatText("Amina", "me", listOf(msg("1", "me", "hi", at = 1.0), msg("2", "amina", "hey", at = 2.0)), "NOW", fmt)
        assertEquals("Fariin, chat with Amina\nExported NOW\n\n[t1] You: hi\n[t2] Amina: hey\n", text)
    }

    @Test fun groupLinesNameMembersAndNotices() {
        val ms = listOf(msg("1", "", "Amina created the group", "system", 1.0), msg("2", "u1", "salaam", at = 2.0),
            msg("3", "ghost", "hi", at = 3.0), msg("4", "me", "", "audio", 4.0))
        val text = ChatExport.groupText("Family", "me", mapOf("u1" to "Amina"), ms, cut = false, now = "NOW", fmt = fmt)
        assertEquals("Fariin, group Family\nExported NOW\n\n[t1] Amina created the group\n[t2] Amina: salaam\n" +
            "[t3] Member: hi\n[t4] You: [Voice message]\n", text)
        val cut = ChatExport.groupText("Family", "me", emptyMap(), emptyList(), cut = true, now = "NOW", fmt = fmt)
        assertEquals("Fariin, group Family\nExported NOW\nThe newest 10000 messages\n\n", cut)
    }
}
