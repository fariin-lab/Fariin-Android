package com.fariin.app.call

import com.fariin.app.data.Conversation
import com.fariin.app.ui.calls.NewCallRules
import com.fariin.app.ui.chats.ChatListRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLinkTest {
    // Bytes 01..10; the expected strings were worked out separately in Python (hmac + int base 62).
    private val key = CallLinkKey.ofBytes(ByteArray(16) { (it + 1).toByte() })!!

    @Test fun textAndCompactForms() {
        assertEquals("bcbd-bfbg-bhbk-bmbn-bpbq-brbs-btbx-bzcb", key.text)
        assertEquals("01tuWckR0Qgud2DqqiTysq", key.compact)
        assertEquals(key, CallLinkKey.ofText(key.text))
        assertEquals(key, CallLinkKey.ofText("BCBDBFBG bhbk-bmbn-bpbq-brbs-btbx-bzcb"))
        assertEquals(key, CallLinkKey.ofCompact(key.compact))
        assertNull(CallLinkKey.ofCompact("01tuWckR0Qgud2DqqiTys"))      // 21
        assertNull(CallLinkKey.ofCompact("zzzzzzzzzzzzzzzzzzzzzz"))     // 2^128 or more
        assertNull(CallLinkKey.ofText("bcbd-bfbg-bhbk-bmbn-bpbq-brbs-btbx-bzca"))  // 'a' is not a letter of the key
    }

    @Test fun roomIdIsHkdfHex() {
        assertEquals("1c1a623e2f794ad3683cf8b8ae82d281f76e5f0754cfd5fbd8011d77c4520dad", key.roomId)
    }

    @Test fun repeatedNibbleGroupsAreRefused() {
        val raw = ByteArray(16) { (it + 1).toByte() }
        raw[4] = 0x77; raw[5] = 0x77
        assertNull(CallLinkKey.ofBytes(raw))
        repeat(50) { assertNotNull(CallLinkKey.ofText(CallLinkKey.generate().text)) }
    }

    @Test fun nameSealsAndOpens() {
        val enc = key.encryptName("Team call", ByteArray(12))
        assertEquals(12 + 9 + 16, java.util.Base64.getDecoder().decode(enc).size)
        assertEquals("Team call", key.decryptName(enc))
        assertEquals("", key.encryptName(""))
        assertEquals("", key.decryptName(""))
        val other = CallLinkKey.generate()
        assertNull(other.decryptName(enc))
    }

    @Test fun urlsAndTappedLinks() {
        assertEquals("https://call.fariin.com/video/01tuWckR0Qgud2DqqiTysq", key.url(true))
        assertEquals("https://call.fariin.com/voice/01tuWckR0Qgud2DqqiTysq", key.url(false))
        for (u in listOf(
            "https://call.fariin.com/video/01tuWckR0Qgud2DqqiTysq",
            "https://call.fariin.com/voice/01tuWckR0Qgud2DqqiTysq",
            "https://fariin.com/video/01tuWckR0Qgud2DqqiTysq",
            "https://fariin.com/call/#key=bcbd-bfbg-bhbk-bmbn-bpbq-brbs-btbx-bzcb",
            "https://www.fariin.com/call#key=bcbdbfbgbhbkbmbnbpbqbrbsbtbxbzcb",
            "kulan://video/01tuWckR0Qgud2DqqiTysq",
            "kulan://call/bcbd-bfbg-bhbk-bmbn-bpbq-brbs-btbx-bzcb",
        )) assertEquals(u, key, CallLinkKey.fromLink(u))
        assertNull(CallLinkKey.fromLink("https://evil.com/video/01tuWckR0Qgud2DqqiTysq"))
        assertNull(CallLinkKey.fromLink("https://call.fariin.com/audio/01tuWckR0Qgud2DqqiTysq"))
        assertNull(CallLinkKey.fromLink("https://fariin.com/call/"))
        assertNull(CallLinkKey.fromLink("http://call.fariin.com/video/01tuWckR0Qgud2DqqiTysq"))
        assertTrue(CallLinkKey.linkSaysVideo("https://call.fariin.com/video/x"))
        assertFalse(CallLinkKey.linkSaysVideo("https://call.fariin.com/voice/x"))
    }

    @Test fun wireShapes() {
        assertEquals(mapOf("roomId" to "r", "encName" to "", "restrictions" to "adminApproval", "video" to true), CallLinkWire.createBody("r"))
        assertEquals(mapOf("roomId" to "r", "link" to true, "servers" to listOf("own")), CallLinkWire.tokenRequest("r"))
        val d = CallLinkDraft("r", key.text, "Hi")
        assertEquals(setOf("key", "name", "admin", "createdAt"), CallLinkWire.listFields(d, true, "ts").keys)
        assertEquals(setOf("key", "name", "admin"), CallLinkWire.listFields(d, false, "ts").keys)
        assertNull(CallLinkWire.parseSaved("r", mapOf("key" to "nope")))
        assertEquals("Fariin Call", CallLinkWire.parseSaved("r", mapOf("key" to key.text))!!.title)
        val p = CallLinkWire.parsePeek(key, mapOf("encName" to key.encryptName("Family"), "count" to 4L, "names" to listOf("Amina", "Bobby"), "video" to false))
        assertEquals("Family", p.title)
        assertFalse(p.video)
        assertEquals("Amina, Bobby and 2 more are in the call", CallLinkWire.peopleLine(p))
        assertEquals("No one is in the call yet", CallLinkWire.peopleLine(p.copy(count = 0)))
        assertEquals("This call link is no longer valid", CallLinkWire.peopleLine(p.copy(gone = true)))
    }

    @Test fun nameClamp() {
        assertEquals(32, CallLinkDefaults.clamp("x".repeat(40)).length)
        val emoji = "👨‍👩‍👧"   // 5 code points
        assertTrue(CallLinkDefaults.clamp(emoji.repeat(20)).let { it.codePointCount(0, it.length) } <= 64)
    }

    private fun conv(id: String, users: List<String>, type: String = "", updated: Double = 0.0, extra: Map<String, Any?> = emptyMap()) =
        Conversation.fromFirestore(id, mapOf("users" to users, "type" to type, "names" to users.associateWith { it.uppercase() },
            "updatedAt" to updated, "title" to "G $id") + extra)

    @Test fun newCallTargets() {
        val chats = listOf(
            conv("me_a", listOf("me", "a"), updated = 1.0),
            conv("me_b", listOf("me", "b"), updated = 5.0),
            conv("me_c", listOf("me", "c"), updated = 3.0),
            conv("g1", listOf("me", "a"), "group", 4.0),
            conv("g2", listOf("me", "a"), "group", 6.0, mapOf("onlyAdminsCall" to true, "admins" to listOf("a"))),
            conv("ch", listOf("me"), "group", 7.0, mapOf("channel" to true)),
        )
        val (people, groups) = NewCallRules.targets(chats, "me", setOf("c"))
        assertEquals(listOf("b", "a"), people.map { it.uid })
        assertEquals(listOf("g1"), groups.map { it.groupCid })
        assertEquals(listOf("a"), NewCallRules.search(people, "@A").map { it.uid })
    }

    @Test fun chatListClockOnlyForAQueuedSend() {
        val c = conv("me_b", listOf("me", "b")).copy(lastSender = "me", localPending = true)
        assertTrue(ChatListRules.lastSendPending(c, "me") { true })
        assertFalse(ChatListRules.lastSendPending(c, "me") { false })          // a pending mark-read only
        assertFalse(ChatListRules.lastSendPending(c.copy(localPending = false), "me") { true })
        assertFalse(ChatListRules.lastSendPending(c.copy(lastSender = "b"), "me") { true })
    }
}
